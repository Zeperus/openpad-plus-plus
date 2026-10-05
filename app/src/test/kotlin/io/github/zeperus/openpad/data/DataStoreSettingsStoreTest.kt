package io.github.zeperus.openpad.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.zeperus.openpad.domain.StartupMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val file get() = File(tmp.root, "datastore/settings.preferences_pb")

    /** Runs [block] with a store on [file] and releases the file afterwards (one DataStore per file at a time). */
    private fun <T> withStore(block: suspend (DataStoreSettingsStore) -> T): T = runBlocking {
        val job = Job()
        try {
            block(DataStoreSettingsStore.create(file, CoroutineScope(Dispatchers.IO + job)))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun `resume plus blank note is the default for a new installation`() {
        assertEquals(StartupMode.ResumeAndBlank, withStore { it.startupMode() })
    }

    @Test fun `each mode can be chosen and read back`() {
        for (mode in StartupMode.entries) {
            withStore { it.setStartupMode(mode) }
            assertEquals(mode, withStore { it.startupMode() })
        }
    }

    @Test fun `the choice survives recreating the store`() {
        withStore { it.setStartupMode(StartupMode.BlankNote) }
        assertEquals(StartupMode.BlankNote, withStore { it.startupMode() }) // a new DataStore instance on the same file
        withStore { it.setStartupMode(StartupMode.ResumeSession) }
        assertEquals(StartupMode.ResumeSession, withStore { it.startupMode() })
    }

    @Test fun `an unknown stored value falls back to the default`() = runBlocking {
        val job = Job()
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
            dataStore.edit { it[stringPreferencesKey("startup_mode")] = "SomethingFromTheFuture" }
            assertEquals(StartupMode.Default, DataStoreSettingsStore(dataStore).startupMode())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun `a corrupted settings file falls back to the default and can be overwritten`() {
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(0x13, 0x37, 0xFF.toByte(), 0x00, 0x42))
        assertEquals(StartupMode.Default, withStore { it.startupMode() })
        withStore { it.setStartupMode(StartupMode.BlankNote) }
        assertEquals(StartupMode.BlankNote, withStore { it.startupMode() })
    }
}
