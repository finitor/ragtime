package org.ragtime.radio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in real-host LAN test: two Data2G hosts crossed through audio FIFOs. */
class RealModemPilotTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()

    @Test fun addressedBroadcastAcknowledgedByDesktopOverRealModems() {
        val arguments=InstrumentationRegistry.getArguments()
        val host=arguments.getString("pilotHost") ?: ""
        assumeTrue("Requires explicit pilotHost and the real-modem bench",host.isNotBlank())
        val marker=arguments.getString("pilotMarker") ?: "S26-pilot-${System.currentTimeMillis()}"
        val call=arguments.getString("pilotCall") ?: "W1AAA"
        val locator=arguments.getString("pilotLocator") ?: "CN87"
        val peer=arguments.getString("pilotPeer") ?: "W1BBB"
        val kiss=(arguments.getString("pilotKiss") ?: "18100").toInt()
        val command=(arguments.getString("pilotCommand") ?: "18300").toInt()
        val settings=HostSettings(host,call,locator,kiss,command)
        val model=app.activity.radioModel
        if(app.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()) {
            fun field(label:String,value:String) {
                val node=app.onNode(hasSetTextAction() and hasText(label))
                node.performTextClearance();node.performTextInput(value)
            }
            field("Host name or IP address",host);field("Callsign",call);field("Locator (optional)",locator)
            field("KISS port",kiss.toString());field("Command port",command.toString())
            app.onNodeWithText("Connect").performScrollTo().performClick()
        } else app.runOnUiThread { model.connect(settings) }
        app.waitUntil(20000) { model.snapshot.ready&&model.snapshot.dataUp }
        app.onNode(hasText("Conversations") and hasClickAction()).performClick()
        val typed="@$peer $marker — café 🚀 / phone to desktop"
        app.onNode(hasSetTextAction() and hasText("To everyone on D2G")).performTextInput(typed)
        val sentAt=System.currentTimeMillis()
        app.onNodeWithContentDescription("Send message").performClick()
        // These are actual waveforms and ACK bursts, not the fake host's clock.
        app.waitUntil(180000) {
            model.snapshot.broadcast.any { it.text.contains(marker)&&it.state=="Acknowledged" }
        }
        val row=model.snapshot.broadcast.first { it.text.contains(marker) }
        assertEquals(peer,row.to)
        assertTrue(row.text.contains("café 🚀"))
        val result=JSONObject().put("result","pass").put("marker",marker).put("host",host)
            .put("call",settings.call).put("peer",peer).put("text",row.text)
            .put("state",row.state).put("sent_at_ms",sentAt).put("ack_at_ms",System.currentTimeMillis())
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"real-modem-pilot.json").writeText(result.toString(2))
        // Keep the saved bench settings; a normal app launch still requires Connect.
        app.runOnUiThread { model.disconnect() }
    }
}
