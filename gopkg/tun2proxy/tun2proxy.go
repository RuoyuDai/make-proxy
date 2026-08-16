// Package tun2proxy forwards TCP connections from a VpnService TUN fd to a
// local SOCKS5 proxy (the MakeProxy LocalProxyServer), and answers DNS
// queries with DNS-over-TCP relayed through the tunnel.
package tun2proxy

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"sync"
	"time"

	"golang.org/x/net/proxy"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const nicID = 1
const tunMTU = 1500

var (
	gStack *stack.Stack
	gTun   *os.File
	gLink  *channel.Endpoint
	gStop  chan struct{}
	gLock  sync.Mutex
)

// Start brings up the userspace TCP/IP stack on the given TUN fd and starts
// forwarding. socksAddr is the local SOCKS5 endpoint (e.g. "127.0.0.1:7070").
func Start(tunFd int, socksAddr string) error {
	gLock.Lock()
	defer gLock.Unlock()
	if gStack != nil {
		return fmt.Errorf("already running")
	}

	link := channel.New(512, tunMTU, "")
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	if err := s.CreateNIC(nicID, link); err != nil {
		return fmt.Errorf("create nic: %v", err)
	}
	if err := s.SetPromiscuousMode(nicID, true); err != nil {
		return fmt.Errorf("promiscuous: %v", err)
	}
	s.SetSpoofing(nicID, true)

	// Give the NIC an address and a default route so the stack can emit
	// forwarded packets back to the TUN fd.
	addr := tcpip.ProtocolAddress{
		Protocol: ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddressWithPrefix{
			Address:   tcpip.AddrFrom4([4]byte{26, 26, 26, 1}),
			PrefixLen: 30,
		},
	}
	if err := s.AddProtocolAddress(nicID, addr, stack.AddressProperties{}); err != nil {
		return fmt.Errorf("add address: %v", err)
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: nicID}})

	s.SetTransportProtocolHandler(tcp.ProtocolNumber, newTCPForwarder(s, socksAddr).HandlePacket)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, newUDPForwarder(s, socksAddr).HandlePacket)

	gStack = s
	gLink = link
	gTun = os.NewFile(uintptr(tunFd), "tun")
	gStop = make(chan struct{})

	go tunToStack(gTun, link, gStop)
	go stackToTun(gTun, link, gStop)
	return nil
}

// Stop tears down the stack and forwarding loops.
func Stop() {
	gLock.Lock()
	defer gLock.Unlock()
	if gStack == nil {
		return
	}
	close(gStop)
	gStack.Close()
	gLink.Close()
	gStack = nil
	gLink = nil
	// Do not close gTun here: ownership stays with the caller.
	gTun = nil
}

// tunToStack pumps raw IPv4 packets from the TUN fd into the stack.
// IPv6 packets are dropped: the VPN is IPv4-only, apps fall back via
// Happy Eyeballs.
func tunToStack(f *os.File, link *channel.Endpoint, stop chan struct{}) {
	buf := make([]byte, tunMTU)
	for {
		n, err := f.Read(buf)
		if err != nil {
			log.Printf("tun2proxy: tun read error: %v", err)
			return
		}
		select {
		case <-stop:
			return
		default:
		}
		if n < 20 || buf[0]>>4 != 4 {
			continue
		}
		data := make([]byte, n)
		copy(data, buf[:n])
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(data),
		})
		link.InjectInbound(ipv4.ProtocolNumber, pkt)
	}
}

// stackToTun pumps outbound packets from the stack back to the TUN fd.
func stackToTun(f *os.File, link *channel.Endpoint, stop chan struct{}) {
	ctx := context.Background()
	for {
		select {
		case <-stop:
			return
		default:
		}
		pkt := link.ReadContext(ctx)
		if pkt == nil {
			return
		}
		var out []byte
		for _, slice := range pkt.AsSlices() {
			out = append(out, slice...)
		}
		pkt.DecRef()
		if _, err := f.Write(out); err != nil {
			log.Printf("tun2proxy: tun write error: %v", err)
			return
		}
	}
}

// ---------------- TCP forwarding ----------------

func newTCPForwarder(s *stack.Stack, socksAddr string) *tcp.Forwarder {
	return tcp.NewForwarder(s, 0, 1024, func(r *tcp.ForwarderRequest) {
		id := r.ID()
		wq := &waiter.Queue{}
		ep, err := r.CreateEndpoint(wq)
		if err != nil {
			log.Printf("tun2proxy: tcp create endpoint failed: %v", err)
			r.Complete(true)
			return
		}
		r.Complete(false)
		local := gonet.NewTCPConn(wq, ep)
		go handleTCP(local, id, socksAddr)
	})
}

func handleTCP(local *gonet.TCPConn, id stack.TransportEndpointID, socksAddr string) {
	defer local.Close()
	target := fmt.Sprintf("%s:%d", id.LocalAddress.String(), id.LocalPort)

	dialer, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
	if err != nil {
		log.Printf("tun2proxy: socks5 init error: %v", err)
		return
	}
	remote, err := dialer.Dial("tcp", target)
	if err != nil {
		log.Printf("tun2proxy: dial %s error: %v", target, err)
		return
	}
	defer remote.Close()

	done := make(chan struct{}, 2)
	go func() {
		io.Copy(remote, local)
		if tc, ok := remote.(*net.TCPConn); ok {
			tc.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		io.Copy(local, remote)
		done <- struct{}{}
	}()
	<-done
}

// ---------------- UDP forwarding (DNS only) ----------------

func newUDPForwarder(s *stack.Stack, socksAddr string) *udp.Forwarder {
	return udp.NewForwarder(s, func(r *udp.ForwarderRequest) bool {
		id := r.ID()
		if id.LocalPort != 53 {
			// Not DNS: leave unhandled, the stack sends ICMP port
			// unreachable. This also kills QUIC and forces TCP.
			return false
		}
		wq := &waiter.Queue{}
		ep, err := r.CreateEndpoint(wq)
		if err != nil {
			return true
		}
		conn := gonet.NewUDPConn(wq, ep)
		go handleDNS(conn, socksAddr)
		return true
	})
}

// handleDNS answers one DNS query via DNS-over-TCP through the tunnel.
func handleDNS(conn *gonet.UDPConn, socksAddr string) {
	defer conn.Close()
	buf := make([]byte, 2048)
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	n, err := conn.Read(buf)
	if err != nil || n == 0 {
		return
	}
	resp, err := dnsOverTCP(socksAddr, buf[:n])
	if err != nil {
		return
	}
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	conn.Write(resp)
}

// dnsOverTCP relays one DNS message over TCP (RFC 7766 framing) to 8.8.8.8
// through the SOCKS5 tunnel, so resolution happens on the VPS side.
func dnsOverTCP(socksAddr string, query []byte) ([]byte, error) {
	dialer, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
	if err != nil {
		return nil, err
	}
	c, err := dialer.Dial("tcp", "8.8.8.8:53")
	if err != nil {
		return nil, err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(8 * time.Second))

	frame := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(frame, uint16(len(query)))
	copy(frame[2:], query)
	if _, err := c.Write(frame); err != nil {
		return nil, err
	}

	var lb [2]byte
	if _, err := io.ReadFull(c, lb[:]); err != nil {
		return nil, err
	}
	resp := make([]byte, binary.BigEndian.Uint16(lb[:]))
	if _, err := io.ReadFull(c, resp); err != nil {
		return nil, err
	}
	return resp, nil
}
