package io.github.zeperus.openpad

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The language selector in Settings: Deutsch, English, System default - switching the app's screens without changing the phone. */
@RunWith(AndroidJUnit4::class)
class Alpha4LanguageTest {
    private val app = ApplicationProvider.getApplicationContext<OpenPadApplication>()

    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun cleanUp() {
        scenario?.close()
        runCatching { app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList() }
    }

    private fun openSettings() {
        rule.overflow("Settings")
        rule.waitFor("the settings", { "" }) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("language-options")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theLanguageCanBeSwitchedBetweenGermanEnglishAndTheSystemDefault() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitFor("the app", { "" }) { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("More options")).fetchSemanticsNodes().isNotEmpty() }
        openSettings()
        // the phone (emulator) is English: "System default" is English
        rule.onNodeWithText("Startup").assertExists()
        rule.onNodeWithText("System default").assertExists()

        rule.onNodeWithText("Deutsch").performClick()
        rule.waitFor("German", { "" }) { rule.onAllNodes(androidx.compose.ui.test.hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Systemstandard").assertExists()
        rule.onNodeWithText("Sitzung fortsetzen").assertExists()

        rule.onNodeWithText("English").performClick()
        rule.waitFor("English", { "" }) { rule.onAllNodes(androidx.compose.ui.test.hasText("Resume session")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("System default").assertExists()

        rule.onNodeWithText("Deutsch").performClick()
        rule.waitFor("German again", { "" }) { rule.onAllNodes(androidx.compose.ui.test.hasText("Systemstandard")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Systemstandard").performClick() // back to following the phone: English here
        rule.waitFor("system default (English)", { "" }) { rule.onAllNodes(androidx.compose.ui.test.hasText("Resume session")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theChosenLanguageIsKeptWhenTheScreenIsOpenedAgain() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitFor("the app", { "" }) { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("More options")).fetchSemanticsNodes().isNotEmpty() }
        openSettings()
        rule.onNodeWithText("Deutsch").performClick()
        rule.waitFor("German", { "" }) { rule.onAllNodes(androidx.compose.ui.test.hasText("Sitzung fortsetzen")).fetchSemanticsNodes().isNotEmpty() }
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitFor("German after reopening", { "" }) { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Weitere Optionen")).fetchSemanticsNodes().isNotEmpty() }
    }
}
