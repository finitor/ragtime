package org.ragtime.radio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** The notices the licences and Google Play ask to be in the app itself. */
class AboutTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()
    private fun openSettings() {
        if(app.onAllNodesWithText("Settings").fetchSemanticsNodes().isEmpty())
            app.onNodeWithContentDescription("Settings").performClick()
    }
    @Test fun licencesAndPrivacyAreInTheApp() {
        openSettings()
        // Privacy is one tap from where the callsign is typed.
        app.onNodeWithText("Your callsign and locator go out with everything you send. Anyone listening can receive it.").assertExists()
        app.onNodeWithText("Privacy").performClick()
        app.onNodeWithText("Ragtime for Android: privacy").assertExists()
        app.onNodeWithText("Ragtime's developers receive nothing from this app.",substring=true).assertExists()
        app.onNodeWithTag("notice-list").performScrollToNode(hasText("All of it is sent in the clear.",substring=true))
        app.onNodeWithTag("notice-list").performScrollToNode(hasText("Nothing leaves the phone.",substring=true))
        // A real Back key: it closes the sheet on top, as the operator's would.
        androidx.test.espresso.Espresso.pressBack()
        app.waitUntil(5000) { app.onAllNodesWithText("Ragtime for Android: privacy").fetchSemanticsNodes().isEmpty() }

        app.onNodeWithText("About Ragtime & release notes").performClick()
        app.onNodeWithText("Open source licences").performClick()
        // Ragtime's own notice, which MIT asks for in every copy.
        app.onNodeWithText("Copyright (c) 2026 Jeffrey Baitis",substring=true).assertExists()
        app.onNodeWithText("MIT License").assertExists()
        // The picture codecs' notices: OpenJPEG's and the Cool-chic port's.
        app.onNodeWithTag("notice-list").performScrollToNode(hasText("Universite catholique de Louvain",substring=true))
        app.onNodeWithTag("notice-list").performScrollToNode(hasText("Copyright 2023-2026 Orange",substring=true))
        app.onNodeWithTag("notice-list").performScrollToNode(hasText("Libraries under the Apache License 2.0"))
        app.onNodeWithTag("notice-list").performScrollToNode(hasText("END OF TERMS AND CONDITIONS",substring=true))
        app.onNodeWithTag("notice-list").performScrollToNode(hasText("Ragtime does not contain Data2G.",substring=true))
    }
}
