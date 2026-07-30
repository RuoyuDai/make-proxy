import com.ruoyudai.makeproxy.Etf
import com.ruoyudai.makeproxy.MpCrypto
import com.ruoyudai.makeproxy.Tunnel

/**
 * JVM-side interop test for the Android client protocol code.
 * Run against a live Erlang make_proxy server.
 *
 * usage: TestClient <serverAddr> <serverPort> <username> <password> <targetHost> <targetPort>
 */
fun main(args: Array<String>) {
    val addr = args[0]
    val port = args[1]
    val user = args[2]
    val pass = args[3]
    val targetHost = args[4]
    val targetPort = args[5]

    // 1. crypto round-trip
    val key = MpCrypto.deriveKey(pass)
    val msg = "hello make-proxy".toByteArray()
    val enc = MpCrypto.encrypt(key, msg)
    check(MpCrypto.decrypt(key, enc).contentEquals(msg)) { "crypto round-trip failed" }
    println("crypto round-trip OK")

    // 2. ETF authTerm sanity
    val term = Etf.authTerm(user, pass, targetHost, targetPort.toInt())
    check(term[0].toInt() and 0xff == 131) { "bad ETF version" }
    println("authTerm bytes: ${term.size}")

    // 3. live tunnel: auth + echo through the proxy server.
    // target is expected to be a TCP echo server.
    val tunnel = Tunnel(addr, port.toInt(), user, pass, targetHost, targetPort.toInt())
    println("tunnel auth OK")
    val payload = "ping-over-tls-gcm".toByteArray()
    tunnel.send(payload)
    val echoed = tunnel.recv()
    check(echoed.contentEquals(payload)) { "echo mismatch: ${String(echoed)}" }
    println("tunnel echo OK")
    tunnel.close()
    println("ALL TESTS PASSED")
}
