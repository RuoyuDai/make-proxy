package com.ruoyudai.makeproxy

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/** In-memory diagnostic log shown on the main screen. */
object DiagLog {
    private const val MAX_ENTRIES = 50
    private val entries = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var listener: (() -> Unit)? = null

    fun add(msg: String) {
        entries.addFirst("${timeFormat.format(Date())} $msg")
        while (entries.size > MAX_ENTRIES) {
            entries.removeLast()
        }
        listener?.invoke()
    }

    fun snapshot(): List<String> = entries.toList()
}
