package io.github.zeperus.openpad.domain

/**
 * The app's error log: a trace of what went wrong, for the developer looking at logcat. There is no telemetry - nothing is ever
 * sent anywhere - and **note text and titles are never logged**: callers pass a fixed message, and of the throwable only what
 * [describe] keeps (its class and where it was thrown) is written. An exception's own message is left out on purpose: messages of
 * storage and provider failures can contain a document's name or path.
 *
 * Plain Kotlin, so the unit tests (on the JVM, where `android.util.Log` does not exist) run the same code: the app points [sink]
 * at `android.util.Log.e` when it starts (see `OpenPadApplication`), and until then - and in tests - nothing is written.
 */
object AppLog {
    /** Where the entries go. Set once when the app starts; tests may replace it to see what is logged. */
    @Volatile var sink: (tag: String, message: String, error: Throwable?) -> Unit = { _, _, _ -> }

    /**
     * [error] without anything a user wrote: the class names of the throwable and its causes and the first stack frames of each -
     * never a message.
     */
    fun describe(error: Throwable?): String {
        if (error == null) return ""
        val out = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (depth > 0) out.append("caused by ")
            out.append(current.javaClass.name).append('\n')
            for (frame in current.stackTrace.take(MAX_FRAMES)) out.append("    at ").append(frame.toString()).append('\n')
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return out.toString().trimEnd()
    }

    private const val MAX_CAUSES = 4
    private const val MAX_FRAMES = 12

    /** Logs an error. Never throws: a log that fails must not turn a handled failure into a crash. */
    fun e(tag: String, message: String, error: Throwable? = null) {
        try {
            sink(tag, message, error)
        } catch (_: Exception) {
            // nothing sensible to do: the log itself is unavailable
        }
    }
}
