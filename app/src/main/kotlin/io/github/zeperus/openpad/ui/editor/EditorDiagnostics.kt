package io.github.zeperus.openpad.ui.editor

import java.util.concurrent.atomic.AtomicInteger

/** Measured durations in milliseconds (shown in Settings of debuggable builds). */
data class EditorTiming(
    val edits: Int,
    val lastEditMs: Double,
    val avgEditMs: Double,
    val maxEditMs: Double,
    val opens: Int,
    val lastOpenMs: Double,
    val maxOpenMs: Double,
)

/** Frame statistics in milliseconds: [frames] counted, [slow] of them took longer than 32 ms. */
data class FrameTiming(
    val frames: Int,
    val avgMs: Double,
    val maxMs: Double,
    val slow: Int,
)

/**
 * Counts how many row text fields were created and disposed. A structural edit (list <-> paragraph, Enter, Backspace, ticking
 * a checkbox) must not dispose the field the user is typing in - that is what used to close and reopen the keyboard - so the
 * instrumented tests watch [disposed]. The cost is two integer increments per field lifecycle.
 */
object EditorDiagnostics {
    private val created = AtomicInteger()
    private val disposed = AtomicInteger()

    val fieldsCreated: Int get() = created.get()
    val fieldsDisposed: Int get() = disposed.get()

    internal fun fieldCreated() { created.incrementAndGet() }
    internal fun fieldDisposed() { disposed.incrementAndGet() }

    // timing: guarded by `this`; calls come from the main thread, so the lock is never contended
    private var edits = 0
    private var totalEditNanos = 0L
    private var lastEditNanos = 0L
    private var maxEditNanos = 0L
    private var opens = 0
    private var lastOpenNanos = 0L
    private var maxOpenNanos = 0L
    private var frameCount = 0
    private var totalFrameNanos = 0L
    private var maxFrameNanos = 0L
    private var slowFrameCount = 0

    /** One editing operation (model + Markdown + publish) took [nanos]. */
    fun recordEdit(nanos: Long) {
        synchronized(this) {
            edits++
            totalEditNanos += nanos
            lastEditNanos = nanos
            maxEditNanos = maxOf(maxEditNanos, nanos)
        }
    }

    /** Opening/switching to a note (load + parse + publish) took [nanos]. */
    fun recordOpen(nanos: Long) {
        synchronized(this) {
            opens++
            lastOpenNanos = nanos
            maxOpenNanos = maxOf(maxOpenNanos, nanos)
        }
    }

    /** One rendered frame (total duration from the frame metrics) took [nanos]. Non-positive values are ignored. */
    fun recordFrame(nanos: Long) {
        if (nanos <= 0L) return
        synchronized(this) {
            frameCount++
            totalFrameNanos += nanos
            maxFrameNanos = maxOf(maxFrameNanos, nanos)
            if (nanos > SLOW_FRAME_NANOS) slowFrameCount++
        }
    }

    val frames: FrameTiming
        get() = synchronized(this) {
            FrameTiming(
                frames = frameCount,
                avgMs = if (frameCount == 0) 0.0 else totalFrameNanos / NANOS_PER_MS / frameCount,
                maxMs = maxFrameNanos / NANOS_PER_MS,
                slow = slowFrameCount,
            )
        }

    val timing: EditorTiming
        get() = synchronized(this) {
            EditorTiming(
                edits = edits,
                lastEditMs = lastEditNanos / NANOS_PER_MS,
                avgEditMs = if (edits == 0) 0.0 else totalEditNanos / NANOS_PER_MS / edits,
                maxEditMs = maxEditNanos / NANOS_PER_MS,
                opens = opens,
                lastOpenMs = lastOpenNanos / NANOS_PER_MS,
                maxOpenMs = maxOpenNanos / NANOS_PER_MS,
            )
        }

    fun resetTiming() {
        synchronized(this) {
            edits = 0
            totalEditNanos = 0L
            lastEditNanos = 0L
            maxEditNanos = 0L
            opens = 0
            lastOpenNanos = 0L
            maxOpenNanos = 0L
            frameCount = 0
            totalFrameNanos = 0L
            maxFrameNanos = 0L
            slowFrameCount = 0
        }
    }

    private const val NANOS_PER_MS = 1_000_000.0
    private const val SLOW_FRAME_NANOS = 32_000_000L
}
