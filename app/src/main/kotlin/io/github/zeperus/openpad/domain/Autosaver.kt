package io.github.zeperus.openpad.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Debounces saves: [save] runs [idleMillis] after the last change, but at the latest [maxWaitMillis] after the
 * first unsaved change of a burst, so continuous typing still reaches the disk regularly.
 * Failures are reported through [onError]; the next change or [flush] simply tries again.
 */
class Autosaver(
    scope: CoroutineScope,
    private val idleMillis: Long = 800,
    private val maxWaitMillis: Long = 5_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onError: (Throwable) -> Unit = {},
    private val save: suspend () -> Unit,
) {
    private val changes = Channel<Unit>(Channel.CONFLATED)

    private val loop: Job = scope.launch {
        while (true) {
            if (changes.receiveCatching().isClosed) return@launch
            val burstStart = clock()
            while (true) {
                val remaining = maxWaitMillis - (clock() - burstStart)
                if (remaining <= 0) break
                val next = withTimeoutOrNull(minOf(idleMillis, remaining)) { changes.receiveCatching() }
                if (next == null) break
                if (next.isClosed) break
            }
            runSave()
        }
    }

    fun notifyChanged() {
        changes.trySend(Unit)
    }

    /** Saves immediately (used when leaving the app or switching notes). */
    suspend fun flush() = runSave()

    /** Stops the background loop. Does not save; call [flush] first if needed. */
    fun close() {
        changes.close()
        loop.cancel()
    }

    private suspend fun runSave() {
        try {
            save()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e)
        }
    }
}
