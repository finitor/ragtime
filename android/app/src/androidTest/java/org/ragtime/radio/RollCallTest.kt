package org.ragtime.radio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * A roll call asked for from Stations, on demo mode's net, where there are
 * stations to answer it by Hand. (On an empty net the host has shown no
 * Hands yet, and the engine sends a plain check-in with no row.)
 */
class RollCallTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()

    @Test fun rollCallIsAskedForOnStationsAndAnsweredInBroadcast() {
        val model=app.activity.radioModel
        try {
            app.runOnUiThread { model.connect(HostSettings(call=model.settings.call,demo=true)) }
            app.waitUntil(20000) { model.demoActive&&model.snapshot.ready&&model.snapshot.dataUp }
            // Every station has checked in: the host has shown its Hands by then.
            app.waitUntil(40000) { model.snapshot.stations.size>=4 }
            app.onNode(hasText("Stations") and isSelectable()).performClick()
            app.onNodeWithText("Roll call").assertIsEnabled().performClick()
            try { app.waitUntil(20000) { model.snapshot.broadcast.any { it.ours&&it.kind==4 } } }
            catch(e: androidx.compose.ui.test.ComposeTimeoutException) {
                fail("No roll call row. Broadcast: "+model.snapshot.broadcast.joinToString(" | ") { "${it.from}: ${it.text} [${it.kind}]" })
            }
            // The stations answer by Hand, and the row names them.
            try { app.waitUntil(90000) { model.snapshot.broadcast.any { it.ours&&it.kind==4&&it.text.contains("W1QSO") } } }
            catch(e: androidx.compose.ui.test.ComposeTimeoutException) {
                fail("Nobody answered. Row: "+model.snapshot.broadcast.filter { it.kind==4 }.joinToString(" | ") { it.text })
            }
            // Settings is for settings: the action is not there any more.
            app.onNodeWithContentDescription("Settings").performClick()
            app.onNodeWithText("Request roll call").assertDoesNotExist()
            androidx.test.espresso.Espresso.pressBack()
        } finally {
            app.runOnUiThread { model.demoOff() }
        }
    }
}
