package com.ruoyudai.makeproxy

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS tunnel to the make-proxy server.
 *
 * The server uses a self-signed certificate, so the TLS peer is not
 * verified - TLS here is traffic camouflage. Confidentiality and peer
 * authentication come from the application-layer AES-GCM encryption and
 * the username/password handshake.
 *
 * Framing inside TLS: 4-byte big-endian length + GCM message,
 * mirroring Erlang's {packet, 4}.
 */
class Tunnel(
    serverAddr: String,
    serverPort: Int,
    username: String,
    password: String,
    targetHost: String,
    targetPort: Int
) {
    private val key: ByteArray = MpCrypto.deriveKey(password)
    private val socket: SSLSocket
    private val input: InputStream
    private val output: OutputStream

    init {
        socket = trustAllFactory().createSocket() as SSLSocket
        socket.keepAlive = true
        socket.connect(InetSocketAddress(serverAddr, serverPort), 10000)
        socket.startHandshake()
        input = socket.inputStream
        output = socket.outputStream

        writeFrame(output, MpCrypto.encrypt(key, Etf.authTerm(username, password, targetHost, targetPort)))
        val reply = readFrame(input)
        val plain = try {
            MpCrypto.decrypt(key, reply)
        } catch (e: Exception) {
            throw IOException("cannot decrypt auth reply (wrong password?)", e)
        }
        if (!Etf.isOkReply(plain)) {
            throw IOException("server rejected the request (auth failed or target unreachable)")
        }
    }

    /** Encrypt and send one message to the proxy server. */
    @Synchronized
    fun send(plain: ByteArray) {
        writeFrame(output, MpCrypto.encrypt(key, plain))
    }

    /** Receive and decrypt one message from the proxy server. */
    fun recv(): ByteArray {
        return MpCrypto.decrypt(key, readFrame(input))
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private fun trustAllFactory(): SSLSocketFactory {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, trustAll, SecureRandom())
            return ctx.socketFactory
        }

        private fun writeFrame(out: OutputStream, payload: ByteArray) {
            out.write((payload.size ushr 24) and 0xff)
            out.write((payload.size ushr 16) and 0xff)
            out.write((payload.size ushr 8) and 0xff)
            out.write(payload.size and 0xff)
            out.write(payload)
            out.flush()
        }

        private fun readFrame(input: InputStream): ByteArray {
            val lenBytes = readFully(input, 4)
            val len = ((lenBytes[0].toInt() and 0xff) shl 24) or
                ((lenBytes[1].toInt() and 0xff) shl 16) or
                ((lenBytes[2].toInt() and 0xff) shl 8) or
                (lenBytes[3].toInt() and 0xff)
            if (len <= 0 || len > 16 * 1024 * 1024) throw IOException("bad frame length: $len")
            return readFully(input, len)
        }

        fun readFully(input: InputStream, len: Int): ByteArray {
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) throw EOFException()
                off += n
            }
            return buf
        }
    }
}
