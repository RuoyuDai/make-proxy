package com.ruoyudai.makeproxy

import java.net.InetSocketAddress
import java.net.Socket

/** Common interface for an upstream tunnel (TLS via proxy server, or direct). */
interface ProxyTunnel {
    /** Send one chunk of plaintext upstream. */
    fun send(plain: ByteArray)

    /** Receive one chunk of plaintext from upstream. Throws on EOF/error. */
    fun recv(): ByteArray

    fun close()
}

/** Direct TCP connection, used for targets reachable from the phone itself. */
class DirectTunnel(host: String, port: Int) : ProxyTunnel {
    private val socket = Socket()
    private val input get() = socket.getInputStream()
    private val output get() = socket.getOutputStream()

    init {
        socket.connect(InetSocketAddress(host, port), 10000)
    }

    @Synchronized
    override fun send(plain: ByteArray) {
        output.write(plain)
        output.flush()
    }

    override fun recv(): ByteArray {
        val buf = ByteArray(16 * 1024)
        val n = input.read(buf)
        if (n < 0) throw java.io.EOFException()
        return buf.copyOf(n)
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
