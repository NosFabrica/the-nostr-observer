package com.nosfabrica.observer.service.testing

import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.LogLevel
import com.vitorpamplona.quartz.utils.LogSink
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Everything logged while [block] runs, at every level, through Quartz's sink and on
 * stdout/stderr, with each throwable's whole chain: what the "never logged" tests search.
 */
suspend fun <T> capturingLogs(block: suspend () -> T): Pair<T, String> {
    val lines = StringBuilder()
    val oldSink = Log.sink
    val oldLevel = Log.minLevel
    val oldOut = System.out
    val oldErr = System.err
    val stream = ByteArrayOutputStream()
    Log.minLevel = LogLevel.DEBUG
    Log.sink =
        LogSink { level, tag, message, throwable ->
            synchronized(lines) {
                lines.append("$level [$tag] $message")
                var t = throwable
                while (t != null) {
                    lines.append(" | ").append(t.toString())
                    t = t.cause
                }
                lines.append('\n')
            }
        }
    System.setOut(PrintStream(stream, true))
    System.setErr(PrintStream(stream, true))
    try {
        val result = block()
        return result to (synchronized(lines) { lines.toString() } + stream.toString())
    } finally {
        Log.sink = oldSink
        Log.minLevel = oldLevel
        System.setOut(oldOut)
        System.setErr(oldErr)
    }
}
