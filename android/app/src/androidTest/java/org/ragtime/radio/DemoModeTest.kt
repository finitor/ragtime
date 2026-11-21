package org.ragtime.radio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Demo mode through the real sheet and the real engines: no bench, no
 * forwarded ports and no modem. The made-up stations are inside the app.
 */
class DemoModeTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()

    @Test fun demoRunsBehindItsBannerAndTheGuideLeadsThroughIt() {
        val model=app.activity.radioModel
        val preferences=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("radio",0)
        val savedHost=preferences.getString("host","")
        val savedKiss=preferences.getInt("kiss",8100)
        try {
            if(app.onAllNodesWithText("Settings").fetchSemanticsNodes().isEmpty())
                app.onNodeWithContentDescription("Settings").performClick()
            app.onNodeWithTag("demo-switch").performScrollTo()
            if(!model.settings.demo||app.onAllNodes(hasTestTag("demo-switch") and isOff()).fetchSemanticsNodes().isNotEmpty())
                app.onNodeWithTag("demo-switch").performClick()
            app.onNodeWithTag("demo-switch").assertIsOn()
            // The real host's fields are out of reach while the demo is picked.
            app.onNode(hasText("Host name or IP address") and isNotEnabled()).assertExists()
            app.onNode(hasSetTextAction() and hasText("Callsign")).assertExists()
            app.onNodeWithText("Start demo").performScrollTo().performClick()

            app.waitUntil(20000) { model.demoActive&&model.snapshot.ready&&model.snapshot.dataUp }
            val call=model.settings.call
            app.onNodeWithText("DEMO MODE").assertExists()
            app.onNodeWithText("Simulated stations; nothing is sent.").assertExists()
            app.waitUntil(40000) { model.snapshot.stations.size>=3 }
            assertTrue(model.snapshot.stations.any { it.call=="W1QSO"&&it.where.contains("FN42") })

            // The guide's bubble fills in the line; the operator sends it.
            app.onNodeWithContentDescription("Demo guide").performClick()
            app.onNode(hasSetTextAction() and hasText("CQ D2G de $call, how copy?")).assertExists()
            app.onNodeWithContentDescription("Send message").performClick()
            app.waitUntil(40000) { model.snapshot.broadcast.any { !it.ours&&it.to==call&&it.text.contains("good copy on you") } }

            app.waitUntil(5000) { demoTip(model.snapshot,call).suggestion.startsWith("@W1QSO") }
            app.onNodeWithContentDescription("Demo guide").performClick()
            app.onNodeWithContentDescription("Send message").performClick()
            app.waitUntil(40000) { model.snapshot.broadcast.any { it.ours&&it.to=="W1QSO"&&it.state=="Acknowledged" } }
            try { app.waitUntil(40000) { model.snapshot.broadcast.any { !it.ours&&it.text.contains("73 de W1QSO") } } }
            catch(e: androidx.compose.ui.test.ComposeTimeoutException) {
                fail("W1QSO never answered. Broadcast: "+model.snapshot.broadcast.joinToString(" | ") { "${it.from}>${it.to}: ${it.text} [${it.state}]" })
            }

            // A ragchew with a made-up station, started from Stations as the guide says.
            assertTrue(demoTip(model.snapshot,call).text.contains("Call K7QTH"))
            app.onNode(hasText("Stations") and isSelectable()).performClick()
            app.onNode(hasText("K7QTH") and hasClickAction()).performClick()
            app.onNodeWithText("Call K7QTH").performClick()
            app.waitUntil(60000) { model.snapshot.phase==2 }
            app.waitUntil(40000) { model.snapshot.session.any { !it.ours&&it.text.contains("Name here is Kay") } }
            app.onNodeWithContentDescription("Demo guide").performClick()
            app.onNode(hasSetTextAction() and hasText("Good copy here. What's your rig?")).assertExists()
            app.onNodeWithContentDescription("Send message").performClick()
            app.waitUntil(40000) { model.snapshot.session.any { !it.ours&&it.text=="Copy that, fine business." } }

            // The operator's real host settings are as they were.
            assertEquals(savedHost,preferences.getString("host",""))
            assertEquals(savedKiss,preferences.getInt("kiss",8100))

            // The switch turned off ends the demo there and then: the bar comes
            // down, the ragchew is left, and the switch stays off.
            app.onNodeWithContentDescription("Settings").performClick()
            app.onNodeWithTag("demo-switch").performScrollTo().assertIsOn().performClick()
            app.waitUntil(15000) { !model.demoActive&&model.snapshot.connection=="Disconnected" }
            app.onNodeWithTag("demo-switch").assertIsOff()
            assertFalse(model.settings.demo)
            assertFalse(preferences.getBoolean("demo",true))
            androidx.test.espresso.Espresso.pressBack()
            app.waitUntil(5000) { app.onAllNodesWithTag("demo-switch").fetchSemanticsNodes().isEmpty() }
            app.onNodeWithText("DEMO MODE").assertDoesNotExist()
            assertTrue("Nothing the demo's stations said outlives the banner",
                model.snapshot.stations.isEmpty()&&model.snapshot.broadcast.isEmpty()&&model.snapshot.session.isEmpty())

            // Not connected, the Stations tip is the way back to Settings.
            app.onNode(hasText("Stations") and isSelectable()).performClick()
            app.onNodeWithText("Connect to your Data2G host to hear stations.").performClick()
            app.onNodeWithText("Settings").assertExists()
            app.onNodeWithTag("demo-switch").performScrollTo().assertIsOff()
        } finally {
            app.runOnUiThread { model.disconnect() }
        }
    }
}
