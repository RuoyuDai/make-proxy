package com.ruoyudai.makeproxy

import java.io.ByteArrayOutputStream

/**
 * Minimal Erlang External Term Format codec, just enough for the
 * authentication handshake: the client sends
 * {auth, Username::binary, Password::binary, {Host::binary, Port::int}}
 * and the server replies with the atom `ok` or {error, Reason}.
 */
object Etf {
    private const val VERSION = 131
    private const val SMALL_TUPLE_EXT = 104
    private const val ATOM_EXT = 100
    private const val ATOM_UTF8_EXT = 118
    private const val SMALL_ATOM_EXT = 115
    private const val SMALL_ATOM_UTF8_EXT = 119
    private const val BINARY_EXT = 109
    private const val INTEGER_EXT = 98

    fun authTerm(username: String, password: String, host: String, port: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        out.write(SMALL_TUPLE_EXT)
        out.write(4)
        writeAtom(out, "auth")
        writeBinary(out, username.toByteArray(Charsets.UTF_8))
        writeBinary(out, password.toByteArray(Charsets.UTF_8))
        out.write(SMALL_TUPLE_EXT)
        out.write(2)
        writeBinary(out, host.toByteArray(Charsets.UTF_8))
        out.write(INTEGER_EXT)
        out.write((port ushr 24) and 0xff)
        out.write((port ushr 16) and 0xff)
        out.write((port ushr 8) and 0xff)
        out.write(port and 0xff)
        return out.toByteArray()
    }

    /** Returns true if the term is the atom `ok`. */
    fun isOkReply(data: ByteArray): Boolean {
        if (data.size < 2 || data[0].toInt() and 0xff != VERSION) return false
        val tag = data[1].toInt() and 0xff
        val nameBytes = when (tag) {
            ATOM_EXT, ATOM_UTF8_EXT -> {
                if (data.size < 4) return false
                val len = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
                if (data.size < 4 + len) return false
                data.copyOfRange(4, 4 + len)
            }
            SMALL_ATOM_EXT, SMALL_ATOM_UTF8_EXT -> {
                if (data.size < 3) return false
                val len = data[2].toInt() and 0xff
                if (data.size < 3 + len) return false
                data.copyOfRange(3, 3 + len)
            }
            else -> return false
        }
        return String(nameBytes, Charsets.UTF_8) == "ok"
    }

    private fun writeAtom(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.ISO_8859_1)
        out.write(ATOM_EXT)
        out.write((bytes.size ushr 8) and 0xff)
        out.write(bytes.size and 0xff)
        out.write(bytes, 0, bytes.size)
    }

    private fun writeBinary(out: ByteArrayOutputStream, bytes: ByteArray) {
        out.write(BINARY_EXT)
        out.write((bytes.size ushr 24) and 0xff)
        out.write((bytes.size ushr 16) and 0xff)
        out.write((bytes.size ushr 8) and 0xff)
        out.write(bytes.size and 0xff)
        out.write(bytes, 0, bytes.size)
    }
}
