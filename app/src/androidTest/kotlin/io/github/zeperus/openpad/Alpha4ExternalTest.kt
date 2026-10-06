package io.github.zeperus.openpad

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import io.github.zeperus.openpad.data.OpenWritableDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * External documents from a Storage Access Framework provider, opened the way another app's "Open with" does it: with a write grant
 * or with a read grant only. (The picker itself is the system's; what openPad++ asks it for is checked on the intent.)
 */
@RunWith(AndroidJUnit4::class)
class Alpha4ExternalTest {
    private val context = InstrumentationRegistry.getInstrumentation().context // the test package: it owns the provider and hands out grants
    private val app = ApplicationProvider.getApplicationContext<OpenPadApplication>()

    @get:Rule val rule = createEmptyComposeRule()

    private fun open(id: String, text: String, write: Boolean) = TestDocumentsProvider.reset(context, id, text).also {
        val uri = DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, id)
        val intent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0) or Intent.FLAG_ACTIVITY_NEW_TASK)
        // with the test package as the sender, the grants are really given (as by a file manager's "Open with")
        app.grantUriPermissionsFromTestPackage(uri, write)
        ActivityScenario.launch<MainActivity>(intent) // not closed: with singleTask, close() waits 45 s (see ExternalFlowTest)
    }

    private fun android.content.Context.grantUriPermissionsFromTestPackage(uri: android.net.Uri, write: Boolean) {
        InstrumentationRegistry.getInstrumentation().context.grantUriPermission(
            packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0),
        )
    }

    private fun banner() = rule.tagCount("read-only-banner")

    // ---- E. writable ------------------------------------------------------------------------------------------

    @Test fun aDocumentOpenedWithWriteAccessIsEditedInPlace() {
        val file = open("writable.md", "# Original\n", write = true)
        rule.waitFor("the document", { "rows=${rule.rowTexts()}" }) { rule.rowTexts().firstOrNull() == "Original" }
        assertEquals("no read-only banner", 0, banner())
        rule.typeInLastRow(" and more")
        rule.waitFor("the original file", { file.readText() }) { file.readText().contains("and more") }
        assertTrue(file.readText().startsWith("# Original"))
    }

    @Test fun aProviderWithoutCapabilityFlagsThatAcceptsWritesIsWritable() {
        val file = open("noflags.md", "text\n", write = true)
        rule.waitFor("the document", { "rows=${rule.rowTexts()}" }) { rule.rowTexts().firstOrNull() == "text" }
        assertEquals(0, banner())
        rule.typeInLastRow("!")
        rule.waitFor("the file", { file.readText() }) { file.readText().startsWith("text!") }
    }

    // ---- F. read-only -----------------------------------------------------------------------------------------

    @Test fun aDocumentOpenedWithReadAccessOnlyIsReadOnlyAndSaysWhy() {
        val file = open("writable.md", "keep me\n", write = false)
        rule.waitFor("the banner", { "rows=${rule.rowTexts()}" }) { banner() == 1 }
        rule.onNodeWithTag("read-only-banner").assertExists()
        rule.waitFor("the title says read only", { "" }) {
            rule.onNodeWithTag("title").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }?.contains("Read only") == true
        }
        assertEquals(1, rule.tagCount("open-writable")) // the way out: pick the file again, asking for write access
        Thread.sleep(1_500)
        assertEquals("keep me\n", file.readText()) // nothing was written
    }

    @Test fun aDocumentTheProviderRefusesToWriteIsReadOnlyEvenWithAGrant() {
        val file = open("readonly.md", "locked\n", write = true)
        rule.waitFor("the banner", { "rows=${rule.rowTexts()}" }) { banner() == 1 }
        assertEquals("providers that refuse writes offer no way to ask again", 0, rule.tagCount("open-writable"))
        assertEquals("locked\n", file.readText())
    }

    @Test fun aProviderThatSaysItCanWriteButRefusesIsReadOnlyToo() {
        val file = open("liar.md", "x\n", write = true)
        rule.waitFor("the banner", { "rows=${rule.rowTexts()}" }) { banner() == 1 }
        assertEquals("x\n", file.readText())
    }

    // ---- what the picker is asked for -------------------------------------------------------------------------

    @Test fun theDocumentPickerAsksForWriteAndPersistableAccess() {
        val intent = OpenWritableDocument().createIntent(app, arrayOf("*/*"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        val flags = intent.flags
        assertTrue(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue("write access must be requested or the picker grants read only", flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        val withHint = OpenWritableDocument(android.net.Uri.parse("content://x/y")).createIntent(app, arrayOf("*/*"))
        assertEquals("content://x/y", withHint.getParcelableExtra<android.net.Uri>(DocumentsContract.EXTRA_INITIAL_URI).toString())
    }
}
