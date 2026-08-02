package com.ruoyudai.makeproxy

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Local proxy entry point. Listens on 127.0.0.1 and auto-detects the
 * protocol of each incoming connection:
 *  - first byte 0x05 -> SOCKS5 (no-auth)
 *  - otherwise       -> HTTP proxy (CONNECT tunnels and plain HTTP)
 */
class LocalProxyServer(
    private val localPort: Int,
    private val serverAddr: String,
    private val serverPort: Int,
    private val username: String,
    private val password: String,
    private val onError: (String) -> Unit,
    private val onConnectionChange: (Int) -> Unit = {}
) {
    @Volatile
    private var running = true
    private var serverSocket: ServerSocket? = null
    private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)

    fun start() {
        thread(name = "proxy-accept") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", localPort))
                serverSocket = ss
                while (running) {
                    val client = ss.accept()
                    thread(name = "proxy-conn") {
                        val count = activeConnections.incrementAndGet()
                        onConnectionChange(count)
                        try {
                            handle(client)
                        } finally {
                            onConnectionChange(activeConnections.decrementAndGet())
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) onError("listener error: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    private fun openTunnel(host: String, port: Int): ProxyTunnel {
        // Private/LAN addresses are reachable directly from the phone,
        // sending them to the remote server would always fail.
        if (isPrivateAddress(host)) {
            return DirectTunnel(host, port)
        }
        return Tunnel(serverAddr, serverPort, username, password, host, port)
    }

    private fun isPrivateAddress(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        val a = nums[0]
        val b = nums[1]
        return a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            val first = client.getInputStream().read()
            if (first < 0) {
                client.close()
                return
            }
            if (first == 0x05) {
                handleSocks5(client)
            } else {
                handleHttp(client, first)
            }
        } catch (e: Exception) {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- SOCKS5 (RFC 1928, no-auth) ----------------

    private fun handleSocks5(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()

        // greeting: version byte (0x05) already consumed
        val nMethods = input.read()
        if (nMethods <= 0) throw EOFException()
        Tunnel.readFully(input, nMethods) // methods, ignored - we always pick no-auth
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        // request: VER CMD RSV ATYP
        val head = Tunnel.readFully(input, 4)
        if (head[0].toInt() != 0x05 || head[1].toInt() != 0x01) {
            // only CONNECT is supported
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }
        val host: String
        when (head[3].toInt()) {
            0x01 -> { // IPv4
                val addr = Tunnel.readFully(input, 4)
                host = addr.joinToString(".") { (it.toInt() and 0xff).toString() }
            }
            0x03 -> { // domain
                val len = input.read()
                if (len <= 0) throw EOFException()
                host = String(Tunnel.readFully(input, len), Charsets.UTF_8)
            }
            0x04 -> { // IPv6
                val addr = Tunnel.readFully(input, 16)
                host = java.net.InetAddress.getByAddress(addr).hostAddress
                    ?: throw java.io.IOException("invalid IPv6 address")
            }
            else -> {
                output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                return
            }
        }
        val portBytes = Tunnel.readFully(input, 2)
        val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)

        val tunnel = try {
            openTunnel(host, port)
        } catch (e: Exception) {
            DiagLog.add("FAIL $host:$port ${e.message}")
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            throw e
        }
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()

        relay(client, tunnel, "$host:$port")
    }

    // ---------------- HTTP proxy ----------------

    private fun handleHttp(client: Socket, firstByte: Int) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val headerBytes = readHttpHeaders(input, firstByte)
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val requestLine = headerText.substringBefore("\r\n")
        val parts = requestLine.split(" ")
        if (parts.size < 3) throw java.io.IOException("bad request line")

        val method = parts[0].uppercase()
        if (method == "CONNECT") {
            val authority = parts[1]
            val host = authority.substringBefore(":")
            val port = authority.substringAfter(":", "443").toInt()
            val tunnel = try {
                openTunnel(host, port)
            } catch (e: Exception) {
                DiagLog.add("FAIL $host:$port ${e.message}")
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
                throw e
            }
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            relay(client, tunnel, authority)
        } else {
            // plain HTTP: rewrite absolute URI to origin-form and forward
            val uri = parts[1]
            val withoutScheme = uri.substringAfter("://")
            val hostPort = withoutScheme.substringBefore("/")
            val path = "/" + withoutScheme.substringAfter("/", "")
            val host = hostPort.substringBefore(":")
            val port = hostPort.substringAfter(":", "80").toInt()
            val tunnel = try {
                openTunnel(host, port)
            } catch (e: Exception) {
                DiagLog.add("FAIL $host:$port ${e.message}")
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
                throw e
            }
            val rewritten = headerText.replaceFirst(requestLine, "${parts[0]} $path ${parts[2]}")
            tunnel.send(rewritten.toByteArray(Charsets.ISO_8859_1))
            relay(client, tunnel, hostPort)
        }
    }

    /** Read from the stream until "\r\n\r\n" (inclusive), first byte already consumed. */
    private fun readHttpHeaders(input: InputStream, firstByte: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(firstByte)
        val window = IntArray(4) { -1 }
        while (out.size() < 64 * 1024) {
            val b = input.read()
            if (b < 0) throw EOFException()
            out.write(b)
            window[0] = window[1]; window[1] = window[2]; window[2] = window[3]; window[3] = b
            if (window[0] == '\r'.code && window[1] == '\n'.code &&
                window[2] == '\r'.code && window[3] == '\n'.code
            ) {
                return out.toByteArray()
            }
        }
        throw java.io.IOException("headers too large")
    }

    // ---------------- relay ----------------

    /** Pipe local socket <-> tunnel in both directions until either side closes. */
    private fun relay(client: Socket, tunnel: ProxyTunnel, tag: String) {
        DiagLog.add("OPEN $tag")
        val upBytes = java.util.concurrent.atomic.AtomicLong(0)
        val downBytes = java.util.concurrent.atomic.AtomicLong(0)
        val upError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val toRemote = thread(name = "relay-up") {
            try {
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = client.getInputStream().read(buf)
                    if (n < 0) break
                    tunnel.send(buf.copyOf(n))
                    upBytes.addAndGet(n.toLong())
                }
            } catch (e: Exception) {
                upError.set(e.message ?: e.javaClass.simpleName)
            }
            // Do NOT close the tunnel here: the remote may still be sending
            // the response after the local side half-closes.
        }
        var downReason = "target closed"
        try {
            while (true) {
                val data = tunnel.recv()
                client.getOutputStream().write(data)
                client.getOutputStream().flush()
                downBytes.addAndGet(data.size.toLong())
            }
        } catch (e: Exception) {
            downReason = e.message ?: e.javaClass.simpleName
        } finally {
            tunnel.close()
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
        toRemote.join(5000)
        val upInfo = upError.get()?.let { ", up-err=$it" } ?: ""
        DiagLog.add("CLOSE $tag up=${upBytes.get()} down=${downBytes.get()} ($downReason$upInfo)")
    }
}
