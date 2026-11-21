package org.ragtime.radio

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The gearshift on Field desk, on demo mode's net, whose hosts give
 * thresholds with their modes as data2g-host does from 0.5.1.
 */
class GearTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()

    @Test fun theGearIsShownChosenAndPutBackOnAuto() {
        val model=app.activity.radioModel
        fun gear()=model.snapshot.gear
        try {
            app.runOnUiThread { model.connect(HostSettings(call=model.settings.call,demo=true)) }
            app.waitUntil(20000) { model.demoActive&&model.snapshot.ready&&gear().ladder.size>=3 }
            assertTrue(gear().hostThresholds)
            assertTrue("Auto until the operator says otherwise",gear().auto)
            assertTrue(gear().label.startsWith("Gear"))

            // Field desk keeps the gear in sight; a tap opens the gearshift.
            app.onNode(hasText("Field desk") and isSelectable()).performClick()
            app.onNodeWithTag("gear-button").assertExists().performClick()
            app.onNodeWithText("Gearshift").assertExists()
            app.onNodeWithTag("gear-auto").assertIsOn()

            // Once a station has been heard, Auto goes by it and says so.
            app.waitUntil(40000) { gear().snr.isNotEmpty() }
            assertTrue(gear().snrFrom.isNotEmpty())
            app.onNodeWithText("Going by ${gear().snrFrom}",substring=true).assertExists()

            // A gear tapped is the gear, by hand.
            val wanted=gear().ladder.first { !it.current }
            app.onNodeWithTag("gear-list").performScrollToNode(hasText(wanted.mode))
            app.onNode(hasText(wanted.mode)).performClick()
            app.waitUntil(5000) { gear().mode==wanted.mode&&!gear().auto }
            app.onNodeWithTag("gear-list").performScrollToNode(hasTestTag("gear-auto"))
            app.onNodeWithTag("gear-auto").assertIsOff()

            // A worse path, and back.
            app.onNodeWithTag("gear-list").performScrollToNode(hasText("Poor"))
            app.onNodeWithText("Poor").performClick()
            app.waitUntil(5000) { gear().pathLabel=="Poor" }
            app.onNodeWithText("Calm").performClick()
            app.waitUntil(5000) { gear().pathLabel=="Calm" }

            // Auto again.
            app.onNodeWithTag("gear-auto").performClick()
            app.waitUntil(5000) { gear().auto }
            androidx.test.espresso.Espresso.pressBack()
            app.waitUntil(5000) { app.onAllNodesWithText("Gearshift").fetchSemanticsNodes().isEmpty() }
            app.onNodeWithTag("gear-button").assertExists()

            // A picture for D2G says what it costs in airtime in that gear.
            // (Not one flat colour: the codec takes a picture with nothing in it for one not yet arrived.)
            val card=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888).also { bitmap->
                val canvas=android.graphics.Canvas(bitmap);val paint=android.graphics.Paint()
                canvas.drawColor(Color.rgb(90,140,200))
                paint.color=Color.rgb(50,110,60);canvas.drawRect(0f,330f,640f,480f,paint)
                paint.color=Color.rgb(30,30,30);paint.strokeWidth=5f;canvas.drawLine(320f,60f,320f,360f,paint);canvas.drawLine(320f,80f,120f,360f,paint)
            }
            app.runOnUiThread { model.pictureFrom(card,"sky.png","",model.settings.call) }
            app.waitUntil(20000) { model.pictureDraft?.busy==false }
            assertEquals("",model.pictureDraft!!.error)
            assertTrue("bytes ${model.pictureDraft!!.bytes}, gear ${gear()}",model.pictureDraft!!.bytes>0&&gear().bps>0)
            app.onNodeWithTag("picture-airtime").assertExists()
            app.onNodeWithText("on the air in ${gear().label}",substring=true).assertExists()
        } finally {
            app.runOnUiThread { model.cancelPicture();model.action("gear-auto","true");model.demoOff() }
        }
    }
}
