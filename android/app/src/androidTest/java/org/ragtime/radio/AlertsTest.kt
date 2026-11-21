package org.ragtime.radio

import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * A notification for a line addressed to the operator while Ragtime is out
 * of sight, on demo mode's net: no bench and no modem.
 */
class AlertsTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()

    @Test fun aReplyWhileOutOfSightIsNotifiedAndClearedOnReturn() {
        val model=app.activity.radioModel
        val manager=InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(NotificationManager::class.java)
        fun alerts()=manager.activeNotifications.filter { it.notification.channelId==AlertNotifier.CHANNEL }
        try {
            app.runOnUiThread { model.connect(HostSettings(call=model.settings.call,demo=true)) }
            app.waitUntil(20000) { model.demoActive&&model.snapshot.ready&&model.snapshot.dataUp }
            val call=model.settings.call
            app.waitUntil(40000) { model.snapshot.stations.any { it.call=="W1QSO" } }

            // In sight, a reply is on the screen and needs no notification.
            var sent=false
            app.runOnUiThread { model.action("broadcast","@W1QSO first, in sight") { sent=it } }
            app.waitUntil(40000) { model.snapshot.broadcast.count { !it.ours&&it.to==call&&it.from=="W1QSO" }==1 }
            assertTrue(sent)
            assertTrue("Nothing is notified while Ragtime is in sight",alerts().isEmpty())

            // Out of sight, the same reply is told.
            app.runOnUiThread { model.action("broadcast","@W1QSO second, out of sight") }
            app.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val deadline=android.os.SystemClock.elapsedRealtime()+40000
            while(alerts().isEmpty()&&android.os.SystemClock.elapsedRealtime()<deadline) android.os.SystemClock.sleep(200)
            val shown=alerts()
            assertEquals("One notification for W1QSO's reply",1,shown.size)
            val extras=shown[0].notification.extras
            assertEquals("Demo: W1QSO on D2G",extras.getCharSequence("android.title").toString())
            assertTrue(extras.getCharSequence("android.text").toString().contains("73 de W1QSO"))
            assertNotNull("A tap opens Ragtime",shown[0].notification.contentIntent)

            // Back in sight, the alerts are taken down; the connection's own stays.
            app.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            app.waitUntil(5000) { alerts().isEmpty() }
            assertTrue(manager.activeNotifications.any { it.notification.channelId==RadioService.CHANNEL })

            // A tapped notification's request lands on the conversation.
            app.runOnUiThread { model.openRequest="Broadcast" }
            app.onNodeWithText("To everyone on D2G").assertExists()
            app.runOnUiThread { model.openRequest="Files" }
            app.onNodeWithText("Send a file").assertExists()
        } finally {
            app.runOnUiThread { model.demoOff() }
        }
    }
}
