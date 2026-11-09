package io.github.zeperus.openpad.domain

/**
 * The app's error log: a trace of what went wrong, for the developer looking at logcat. There is no telemetry - nothing is ever
 * sent anywhere - and **note text and titles are never logged**: callers pass a fixed message and the throwable only.
 *
 * Plain Kotlin, so the unit tests (on the JVM, where `android.util.Log` does not exist) run the same code: the app points [sink]
 * at `android.util.Log.e` when it starts (see `OpenPadApplication`), and until then - and in tests - nothing is written.
 */
object AppLog {
    /** Where the entries go. Set once when the app starts; tests may replace it to see what is logged. */
    @Volatile var sink: (tag: String, message: String, error: Throwable?) -> Unit = { _, _, _ -> }

    /** Logs an error. Never throws: a log that fails must not turn a handled failure into a crash. */
    fun e(tag: String, message: String, error: Throwable? = null) {
        try {
            sink(tag, message, error)
        } catch (_: Exception) {
            // nothing sensible to do: the log itself is unavailable
        }
    }
}
