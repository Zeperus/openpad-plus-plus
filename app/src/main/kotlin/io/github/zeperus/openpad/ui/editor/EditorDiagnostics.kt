package io.github.zeperus.openpad.ui.editor

import java.util.concurrent.atomic.AtomicInteger

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
}
