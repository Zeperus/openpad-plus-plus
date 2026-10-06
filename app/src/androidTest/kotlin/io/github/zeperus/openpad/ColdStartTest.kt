package io.github.zeperus.openpad

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression test for "notes vanished from the drawer after relaunch": state that was published before Compose
 * had started observing was never shown.
 *
 * It only means something in a **fresh process**: the Android Test Orchestrator (see app/build.gradle.kts) starts
 * every test in its own process with cleared app data, so the first activity and the first composition of this
 * process happen *after* the note already exists on disk, exactly like launching the app the next day.
 * The test deliberately avoids the Compose test rule (which installs its own snapshot machinery) and looks at
 * the real accessibility tree through UiAutomator instead.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartTest {
    private val app = ApplicationProvider.getApplicationContext<OpenPadApplication>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun noteFromAnEarlierSessionIsListedAndOpensWithItsMarkdown() {
        assertFalse(
            "Not a cold start: an activity already existed in this process. " +
                "Run instrumented tests through Gradle (connectedDebugAndroidTest) so the Test Orchestrator is used.",
            ProcessHistory.activityCreated.get(),
        )

        // "Yesterday's" session: a note that exists only as a file + index entry.
        val markdown = "# Cold start\nbody text\n- [ ] item"
        val info = runBlocking { app.repository.createNote(markdown) }
        assertEquals(markdown, File(app.filesDir, "openpad/notes/${info.id.value}.md").readText())

        ActivityScenario.launch(MainActivity::class.java).use {
            val menu = device.wait(Until.findObject(By.desc("Open navigation")), TIMEOUT)
            assertNotNull("app did not start", menu)
            menu.click()

            val entry = device.wait(Until.findObject(By.text("Cold start")), TIMEOUT)
            assertNotNull("note is missing from FILES after a cold start", entry)
            entry.click()

            // the formatted editor shows one text field per row, without any Markdown syntax
            val rows = { device.findObjects(By.clazz("android.widget.EditText")).map { (it.text ?: "").removePrefix("\u200B") } }
            val deadline = System.currentTimeMillis() + TIMEOUT
            while (rows() != listOf("Cold start", "body text", "item") && System.currentTimeMillis() < deadline) Thread.sleep(200)
            assertEquals(listOf("Cold start", "body text", "item"), rows())
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
