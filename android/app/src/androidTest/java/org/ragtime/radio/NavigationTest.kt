package org.ragtime.radio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NavigationTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()
    private fun model()=app.activity.radioModel
    private fun tab(name:String)=app.onNode(hasText(name) and hasClickAction())
    private fun connectToFakeHost() {
        // Submit the real setup sheet on first launch; a synthetic dispatcher
        // Back bypasses the dialog window and can finish the Activity instead.
        if(app.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()) {
            fun field(label:String,value:String) {
                val node=app.onNode(hasSetTextAction() and hasText(label))
                node.performTextClearance();node.performTextInput(value)
            }
            field("Host name or IP address","127.0.0.1")
            field("Callsign","TESTUI")
            field("Locator (optional)","CN87")
            field("KISS port",benchPort(8100).toString())
            field("Command port",benchPort(8300).toString())
            app.onNodeWithText("Connect").performScrollTo().performClick()
        } else app.runOnUiThread { model().connect(HostSettings("127.0.0.1","TESTUI","CN87",benchPort(8100),benchPort(8300))) }
        app.waitUntil(15000) { model().snapshot.ready&&model().snapshot.dataUp }
    }
    @Test fun viewsShareDraftsAndSurviveActivityRecreation() {
        connectToFakeHost()
        tab("Conversations").performClick()
        app.onNode(hasSetTextAction() and hasText("To everyone on D2G")).performTextInput("kept broadcast draft")
        tab("Field desk").performClick()
        app.onNodeWithText("kept broadcast draft").assertExists()
        app.onNodeWithText("Ragchew").performClick()
        app.onNodeWithText("kept broadcast draft").assertDoesNotExist()
        app.onNodeWithText("Broadcast").performClick()
        app.onNodeWithText("kept broadcast draft").assertExists()
        tab("Files").performClick()
        app.onNodeWithText("Send a file").assertExists()
        tab("Conversations").performClick()
        app.onNodeWithText("kept broadcast draft").assertExists()
        app.activityRule.scenario.recreate()
        app.onNodeWithText("kept broadcast draft").assertExists()
        app.runOnUiThread { model().disconnect() }
    }
    @Test fun pickedDocumentIsBroadcastAndShownInFiles() {
        connectToFakeHost()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val picked=File(context.cacheDir,"ui-bulletin-${System.nanoTime()}.txt").apply { writeText("QST de TESTUI\n".repeat(40)) }
        try {
            // What the document picker's result does, without the system picker.
            app.runOnUiThread { model().importFile(android.net.Uri.fromFile(picked),"",model().settings.call) }
            app.waitUntil(15000) { model().snapshot.files.any { it.name==picked.name&&it.via=="Broadcast"&&it.ours } }
            assertEquals("Queued ${picked.name}",model().documentStatus)
            tab("Files").performClick()
            app.onNodeWithText(picked.name).assertExists()
            app.onNodeWithText("Broadcast receivers do not acknowledge files.").assertExists()
            tab("Conversations").performClick()
            app.waitUntil(15000) { model().snapshot.broadcast.any { it.kind==1&&it.text.contains(picked.name) } }
            app.onAllNodesWithText("View in Files").onFirst().assertExists()
        } finally {
            app.runOnUiThread { model().disconnect() }
            picked.delete()
        }
    }
    @Test fun stationSessionEntryBackAndBackgroundConnection() {
        connectToFakeHost()
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"ui-peer-${System.nanoTime()}")
        val peer=Engine("TESTP1","FN31",directory.absolutePath)
        val transport=HostTransport(HostSettings("127.0.0.1","TESTP1","FN31",benchPort(8110),benchPort(8310)),peer)
        fun peerState()=RadioSnapshot.parse(peer.invoke(30).toString(Charsets.UTF_8))
        fun until(condition:()->Boolean) { app.waitUntil(15000) { transport.step();condition() } }
        try {
            until { peerState().ready }
            assertTrue(peer.action("broadcast","CQ D2G de TESTP1"))
            until { model().snapshot.stations.any { it.call=="TESTP1" } }
            tab("Stations").performClick()
            app.onNode(hasText("TESTP1") and hasClickAction()).performClick()
            app.onNodeWithText("Call TESTP1").performClick()
            until { model().snapshot.phase==2&&peerState().phase==2 }
            tab("Stations").performClick()
            app.onNodeWithText("Ragchewing with TESTP1").performClick()
            app.onNodeWithContentDescription("Back to Stations").assertExists().performClick()
            app.onNodeWithText("Ragchewing with TESTP1").assertExists()
            assertTrue("Returning to Stations must keep the session",model().snapshot.active)

            app.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val backgroundUntil=android.os.SystemClock.elapsedRealtime()+1200
            while(android.os.SystemClock.elapsedRealtime()<backgroundUntil) { transport.step();android.os.SystemClock.sleep(10) }
            assertTrue("The connection service keeps the ragchew while hidden",model().snapshot.active)
            app.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            until { model().snapshot.ready&&model().snapshot.connection=="Connected" }
            assertTrue("Resuming keeps the existing session",model().snapshot.active)
            // Disconnect leaves the ragchew at the modem too: the far end sees it end.
            app.runOnUiThread { model().disconnect() }
            until { !peerState().active }
        } finally {
            app.runOnUiThread { model().disconnect() }
            runCatching { peer.action("abort");repeat(30) { transport.step();android.os.SystemClock.sleep(10) } }
            transport.close();peer.close();directory.deleteRecursively()
        }
    }
}
