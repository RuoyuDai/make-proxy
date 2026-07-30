# MakeProxy Android Client

Android client for the make-proxy server. It runs a local proxy on
`127.0.0.1:<local port>` (default 7070) that auto-detects SOCKS5 and
HTTP (CONNECT + plain HTTP), and forwards traffic to the Erlang server
over the TLS + AES-128-GCM tunnel with username/password authentication.

## Build

Requirements: JDK 17, Android SDK (platform 34, build-tools 34).

```
cd android
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install on a phone (USB debugging on):

```
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Open the app, fill in server address/port, username, password
   (same values as the server's `app.config`), and the local port.
2. Tap **Start**. The proxy runs as a foreground service.
3. Set the phone's Wi-Fi proxy (Wi-Fi settings -> network -> advanced
   -> proxy -> manual) to host `127.0.0.1`, port `7070`.
4. HTTPS traffic (HTTP CONNECT) and plain HTTP are proxied. SOCKS5
   clients can also use `127.0.0.1:7070` directly.

Limitations:

- Apps that ignore the Android system proxy setting will not be
  tunneled (this is not a VpnService-based app).
- The TLS certificate is self-signed and not verified by the client;
  traffic confidentiality and server authentication are provided by the
  application-layer AES-GCM + password handshake. Use a long random
  ASCII password.

## Protocol interop test (JVM)

`app/src/testInterop/TestClient.kt` exercises the exact protocol code
used by the app against a live Erlang server. Compile it together with
the app's `MpCrypto` / `Etf` / `Tunnel` sources and run it against a
make_proxy server with a TCP echo server as target.
