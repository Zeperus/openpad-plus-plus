package io.github.zeperus.openpad

import android.app.Application
import io.github.zeperus.openpad.data.DataStoreSettingsStore
import io.github.zeperus.openpad.data.FileNoteRepository
import io.github.zeperus.openpad.data.FileSessionStore
import io.github.zeperus.openpad.domain.NoteRepository
import io.github.zeperus.openpad.domain.SessionStore
import io.github.zeperus.openpad.domain.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Hand-wired dependencies; small enough that a DI framework would be pure overhead. */
class OpenPadApplication : Application() {
    /**
     * Outlives activities so pending saves still finish when the UI goes away. Runs on the main thread because
     * it publishes Compose state; the repository moves file I/O to a background dispatcher itself.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val repository: NoteRepository by lazy { FileNoteRepository(File(filesDir, "openpad")) }

    /** The open-document session: its own small file, separate from the notes and their index. */
    val sessionStore: SessionStore by lazy { FileSessionStore(File(filesDir, "openpad/session.json")) }

    /** Simple settings (startup mode). DataStore does its own I/O, so it gets an IO scope, not [appScope]. */
    val settings: SettingsStore by lazy {
        DataStoreSettingsStore.create(
            File(filesDir, "datastore/settings.preferences_pb"),
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
