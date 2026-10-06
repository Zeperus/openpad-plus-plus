package io.github.zeperus.openpad

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    // a stuck provider or screen must fail this test, not stall the whole run
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(90)

    private val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName

    private fun uri(id: String) = DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, id)

    /** What the document holds now, read with the grant that was handed to openPad++ (the tests run with its identity). */
    private fun content(id: String): String = app.contentResolver.openInputStream(uri(id))!!.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Opens the provider's document in openPad++ the way another app's "Open with" does. */
    private fun open(id: String, write: Boolean) {
        InstrumentationRegistry.getInstrumentation().targetContext.startActivity(
            Intent().setClassName(testPackage, "io.github.zeperus.openpad.OpenWithActivity")
                .putExtra(OpenWithActivity.EXTRA_ID, id).putExtra(OpenWithActivity.EXTRA_WRITE, write)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun banner() = rule.tagCount("read-only-banner")

    // ---- E. writable ------------------------------------------------------------------------------------------

    @Test fun aDocumentOpenedWithWriteAccessIsEditedInPlace() {
        open("writable.md", write = true)
        rule.waitFor("the document", { "rows=${rule.rowTexts()}" }) { rule.rowTexts().firstOrNull() == "Original" }
        assertEquals("no read-only banner", 0, banner())
        rule.typeInLastRow(" and more")
        rule.waitFor("the original file", { content("writable.md") }) { content("writable.md").contains("and more") }
        assertTrue(content("writable.md").startsWith("# Original"))
    }

    @Test fun aProviderWithoutCapabilityFlagsThatAcceptsWritesIsWritable() {
        open("noflags.md", write = true)
        rule.waitFor("the document", { "rows=${rule.rowTexts()}" }) { rule.rowTexts().firstOrNull() == "text" }
        assertEquals(0, banner())
        rule.typeInLastRow("!")
        rule.waitFor("the file", { content("noflags.md") }) { content("noflags.md").startsWith("text!") }
    }

    // ---- F. read-only -----------------------------------------------------------------------------------------

    @Test fun aDocumentOpenedWithReadAccessOnlyIsReadOnlyAndSaysWhy() {
        open("writable.md", write = false)
        rule.waitFor("the banner", { "rows=${rule.rowTexts()}" }) { banner() == 1 }
        rule.onNodeWithTag("read-only-banner").assertExists()
        rule.waitFor("the title says read only", { "" }) {
            rule.onNodeWithTag("title").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }?.contains("Read only") == true
        }
        assertEquals(1, rule.tagCount("open-writable")) // the way out: pick the file again, asking for write access
        Thread.sleep(1_500)
        assertEquals("# Original\n", content("writable.md")) // nothing was written
    }

    @Test fun aDocumentTheProviderRefusesToWriteIsReadOnlyEvenWithAGrant() {
        open("readonly.md", write = true)
        rule.waitFor("the banner", { "rows=${rule.rowTexts()}" }) { banner() == 1 }
        assertEquals("providers that refuse writes offer no way to ask again", 0, rule.tagCount("open-writable"))
        assertEquals("locked\n", content("readonly.md"))
    }

    @Test fun aProviderThatSaysItCanWriteButRefusesIsReadOnlyToo() {
        open("liar.md", write = true)
        rule.waitFor("the banner", { "rows=${rule.rowTexts()}" }) { banner() == 1 }
        assertEquals("x\n", content("liar.md"))
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
