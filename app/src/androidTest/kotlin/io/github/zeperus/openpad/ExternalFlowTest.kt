package io.github.zeperus.openpad

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * External Markdown documents on a real Android runtime: "Open with", in-place editing, Share, and the manifest's
 * intent filters. The "other app" is simulated with this app's own FileProvider (a real `content://` URI with grant
 * flags, served by a different component than the one that reads it).
 */
@RunWith(AndroidJUnit4::class)
class ExternalFlowTest {
    private val app = ApplicationProvider.getApplicationContext<OpenPadApplication>()

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    // The scenario is deliberately not closed: with a singleTask activity started by a VIEW intent `close()` waits 45 s for
    // a DESTROYED that never comes. The orchestrator gives every test its own process, so nothing leaks.
    @After fun closeActivity() {
        scenario = null
    }

    private fun externalFile(name: String, text: String): Pair<File, Uri> {
        val dir = File(app.cacheDir, "share/external-test").apply { mkdirs() }
        val file = File(dir, name).apply { writeText(text) }
        return file to FileProvider.getUriForFile(app, ShareHelper.authority(app), file)
    }

    private fun viewIntent(uri: Uri, action: String = Intent.ACTION_VIEW) =
        Intent(app, MainActivity::class.java).setAction(action).setDataAndType(uri, "text/markdown")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun tabTitles() = rule.onAllNodes(hasTestTag("tab")).fetchSemanticsNodes().map { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""
    }

    private fun waitForTab(title: String) = rule.waitUntil(timeoutMillis = 8_000) { title in tabTitles() }

    private fun overflow(item: String) {
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText(item).performClick()
    }

    // ---- Open with -------------------------------------------------------------------------------------------

    @Test fun openWithOpensTheFileAndEditsItInPlace() {
        val (file, uri) = externalFile("Notes.md", "# Original\n")
        scenario = ActivityScenario.launch(viewIntent(uri))
        waitForTab("Notes")

        rule.typeInLastRow(" and more")
        rule.waitUntil(timeoutMillis = 8_000) { file.readText().contains("and more") }
        assertTrue(file.readText().startsWith("# Original"))
        // the document was edited where it is: no copy of it among the app's own notes
        val internal = File(app.filesDir, "openpad/notes").listFiles { f -> f.name.endsWith(".md") }.orEmpty()
        assertTrue("no internal copy expected, found ${internal.map { it.name }}", internal.none { it.readText().contains("Original") })
    }

    @Test fun editIntentWorksLikeView() {
        val (file, uri) = externalFile("Edit.md", "text")
        scenario = ActivityScenario.launch(viewIntent(uri, Intent.ACTION_EDIT))
        waitForTab("Edit")
        rule.typeInLastRow("!")
        rule.waitUntil(timeoutMillis = 8_000) { file.readText().contains("!") }
    }

    @Test fun externalDocumentIsListedWithAMarkerAndCanBeRemovedWithoutDeletingTheFile() {
        val (file, uri) = externalFile("Keep.md", "precious")
        scenario = ActivityScenario.launch(viewIntent(uri))
        waitForTab("Keep")

        rule.onNodeWithContentDescription("Open navigation").performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("Keep") and hasClickAction() and !hasTestTag("tab")).onFirst().assertIsDisplayed()
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("External file")).onFirst().assertIsDisplayed()
        rule.onNodeWithText("Open file…").assertIsDisplayed()
        // leave the drawer by selecting the open note
        rule.onAllNodes(hasText("Keep") and hasClickAction() and !hasTestTag("tab")).onFirst().performClick()
        rule.waitForIdle()

        overflow("Remove from openPad++…")
        rule.onNodeWithText("The file itself is not deleted or changed.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()
        rule.waitUntil(timeoutMillis = 8_000) { "Keep" !in tabTitles() }
        assertTrue(file.exists())
        assertEquals("precious", file.readText())
    }

    @Test fun anExternalDocumentOpenedASecondTimeDoesNotDuplicateTheTab() {
        val (_, uri) = externalFile("Once.md", "x")
        scenario = ActivityScenario.launch(viewIntent(uri))
        waitForTab("Once")
        scenario!!.onActivity { it.startActivity(viewIntent(uri)) } // the same document handed over again
        rule.waitForIdle()
        Thread.sleep(1_000)
        assertEquals(1, tabTitles().count { it == "Once" })
    }

    // ---- Share -----------------------------------------------------------------------------------------------

    @Test fun shareProducesAReadableMarkdownFileNamedAfterTheNote() {
        val intent = ShareHelper.createIntent(app, "Shopping list", "# Shopping\n\n- milk\n")
        @Suppress("DEPRECATION")
        val send = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/markdown", send.type)
        @Suppress("DEPRECATION")
        val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("# Shopping\n\n- milk\n", app.contentResolver.openInputStream(stream)!!.bufferedReader().use { it.readText() })
        app.contentResolver.query(stream, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals("Shopping list.md", it.getString(0))
        }
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun shareUsesASafeFileNameForAwkwardTitles() {
        val intent = ShareHelper.createIntent(app, "a/b: c?", "text")
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        app.contentResolver.query(stream, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
            it.moveToFirst()
            assertEquals("a b c.md", it.getString(0))
        }
    }

    // ---- Manifest: which links open openPad++ ----------------------------------------------------------------

    private fun handlers(uri: String, type: String?, action: String = Intent.ACTION_VIEW) =
        app.packageManager.queryIntentActivities(
            Intent(action).setDataAndType(Uri.parse(uri), type).addCategory(Intent.CATEGORY_DEFAULT),
            PackageManager.MATCH_DEFAULT_ONLY,
        ).map { it.activityInfo.packageName }

    @Test fun markdownMimeTypesAreHandled() {
        assertTrue(app.packageName in handlers("content://provider/doc/1", "text/markdown"))
        assertTrue(app.packageName in handlers("content://provider/doc/1", "text/x-markdown"))
        assertTrue(app.packageName in handlers("content://provider/doc/1", "text/markdown", Intent.ACTION_EDIT))
    }

    /** On failure the test shows which intent filters Android registered for the app (the way to debug manifest matching). */
    private fun assertHandled(uri: String, type: String) {
        if (app.packageName in handlers(uri, type)) return
        val dump = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys package ${app.packageName}")
            .let { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText() }
        val section = dump.lines().dropWhile { !it.contains("MainActivity filter") && !it.contains("Activity Resolver Table") }.take(60).joinToString("\n")
        throw AssertionError("$uri ($type) is not handled. Registered filters:\n$section")
    }

    @Test fun mdFilesWithAGenericTypeAreHandledByName() {
        assertHandled("content://com.example.files/path/to/notes.md", "application/octet-stream")
        assertHandled("content://com.example.files/path/to/notes.markdown", "application/octet-stream")
        assertHandled("content://com.example.files/a.b/notes.v2.md", "application/octet-stream")
    }

    @Test fun otherFilesAreNotClaimed() {
        assertTrue(app.packageName !in handlers("content://com.example.files/path/to/photo.jpg", "image/jpeg"))
        assertTrue(app.packageName !in handlers("content://com.example.files/path/to/archive.zip", "application/zip"))
        assertTrue(app.packageName !in handlers("content://com.example.files/path/to/notes.md.zip", "application/octet-stream"))
    }
}
