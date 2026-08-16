# MakeProxy Android Client

Android client for the make-proxy server. Two layers:

- **VpnService (global mode)**: captures all device traffic on a TUN
  interface. TCP goes through the tunnel, DNS is answered with
  DNS-over-TCP relayed through the tunnel, other UDP (QUIC) is dropped
  so apps fall back to TCP. Works on Wi-Fi **and cellular**, for every
  app (browser, YouTube, Telegram, ...).
- **Local SOCKS5/HTTP proxy** (`LocalProxyServer`, on `127.0.0.1:<local
  port>`): the tunnel endpoint used by the VPN engine; can also be used
  directly by apps that support proxy settings.

The tunnel itself is unchanged: TLS + AES-128-GCM + username/password
authentication to the Erlang make-proxy server.

## Architecture

```
apps -> VpnService TUN -> tun2proxy (Go/gvisor, tun2proxy.aar)
      -> SOCKS5 127.0.0.1:7070 (LocalProxyServer)
      -> TLS + AES-GCM + auth -> make-proxy server -> target
```

`gopkg/tun2proxy/` contains the Go engine: a gVisor userspace TCP/IP
stack that forwards TCP via SOCKS5 and answers DNS over TCP through the
tunnel. It is packaged with gomobile into
`android/app/libs/tun2proxy.aar`.

## Build

Requirements: JDK 17, Android SDK (platform 34, build-tools 34).

```
cd android
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Rebuilding the Go engine (only needed after changing
`gopkg/tun2proxy/`):

```
cd gopkg/tun2proxy
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
gomobile bind -target=android -androidapi 21 -o tun2proxy.aar .
cp tun2proxy.aar ../../android/app/libs/
```

## Usage

1. Open the app, fill in server address/port, username, password
   (same values as the server's `app.config`).
2. Tap **Start** and confirm the system VPN prompt (once).
3. All traffic now goes through the tunnel. Tap **Stop** to disconnect.

The main screen shows live connection diagnostics (OPEN/CLOSE with byte
counts and close reasons).

## Notes

- IPv6 is dropped by the engine; apps fall back to IPv4 (Happy
  Eyeballs).
- Android "Private DNS" (DoT, port 853) works: it is forwarded through
  the tunnel to the real resolver, so certificate validation is
  unaffected.
- Local/LAN addresses (10.x, 172.16-31.x, 192.168.x, 169.254.x) bypass
  the tunnel and connect directly.
- The TLS certificate of the make-proxy server is self-signed and not
  verified; confidentiality and peer authentication come from the
  application-layer AES-GCM + password handshake. Use a long random
  ASCII password.
