package org.ragtime.radio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Inline pictures on the device: the codecs through JNI, and a picture
 * made ready, sent and shown among the messages, on demo mode's net.
 */
class PicturesTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()

    /** A test card: sky, a green hill, a mast and its guy wires. */
    private fun card(width:Int,height:Int):Bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { bitmap->
        val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(120,170,235))
        paint.color=Color.rgb(60,125,55);canvas.drawRect(0f,height*0.7f,width.toFloat(),height.toFloat(),paint)
        paint.color=Color.rgb(35,35,35);paint.strokeWidth=6f;canvas.drawLine(width/2f,height*0.15f,width/2f,height*0.75f,paint)
        paint.strokeWidth=2f
        canvas.drawLine(width/2f,height*0.2f,width*0.2f,height*0.75f,paint);canvas.drawLine(width/2f,height*0.2f,width*0.8f,height*0.75f,paint)
    }

    @Test fun aPictureIsCodedSmallAndShowsFromAnyFirstPart() {
        assertTrue("This build has the picture codecs",NativeCore.picturesAvailable())
        val source=card(800,600)
        val coded=NativeCore.encodePicture(Pictures.pixels(source),800,600,640,8*1024)
        assertTrue("Within the bytes asked: ${coded.size}",coded.size in 300..8*1024)
        val whole=Pictures.decode(coded)!!
        assertEquals(640,whole.width);assertEquals(480,whole.height)
        // The sky is still blue and the hill still green.
        val sky=whole.getPixel(40,40);val hill=whole.getPixel(40,460)
        assertTrue(Color.blue(sky)>Color.red(sky));assertTrue(Color.green(hill)>Color.blue(hill))
        val part=Pictures.decode(coded.copyOf(coded.size/2))!!
        assertEquals("Any first part is the whole picture, softer",640 to 480,part.width to part.height)
        assertNull("What is no picture shows nothing",Pictures.decode(ByteArray(600) { 0x5a }))
        assertThrows(IllegalStateException::class.java) { NativeCore.encodePicture(Pictures.pixels(source),800,600,640,40) }
        assertEquals("IMG 2041.j2k",Pictures.sentName("IMG 2041.heic"))
        assertEquals("picture.j2k",Pictures.sentName("...."))
    }

    @Test fun aPictureIsMadeReadySentAndShownInline() {
        val model=app.activity.radioModel
        try {
            app.runOnUiThread { model.connect(HostSettings(call=model.settings.call,demo=true)) }
            app.waitUntil(20000) { model.demoActive&&model.snapshot.ready&&model.snapshot.dataUp }
            val call=model.settings.call
            app.onNode(hasText("Conversations") and isSelectable()).performClick()

            // What the picker's result does, with a picture already in hand.
            app.runOnUiThread { model.pictureFrom(card(1200,900),"test card.png","",call) }
            app.onNodeWithText("Broadcast a picture").assertExists()
            app.waitUntil(20000) { model.pictureDraft?.busy==false }
            assertEquals("",model.pictureDraft!!.error)
            app.onNodeWithTag("picture-preview").assertExists()
            assertEquals(640,model.pictureDraft!!.preview!!.width)
            val first=model.pictureDraft!!.bytes
            assertTrue(first in 300..8*1024)
            // A smaller size on the air is a smaller, softer picture.
            app.onNodeWithText("4 KB").performClick()
            app.waitUntil(20000) { model.pictureDraft?.busy==false&&model.pictureDraft?.budget==4 }
            assertTrue(model.pictureDraft!!.bytes<=4*1024)
            app.onNodeWithText("320").performClick()
            app.waitUntil(20000) { model.pictureDraft?.busy==false&&model.pictureDraft?.preview?.width==320 }

            app.onNodeWithTag("picture-send").performClick()
            app.waitUntil(5000) { model.pictureDraft==null }
            app.waitUntil(30000) { model.snapshot.broadcast.any { it.ours&&it.picture&&it.pictureSource.isNotEmpty() } }
            // Ours shows at once, whole, among the messages.
            app.waitUntil(20000) { model.pictures.isNotEmpty() }
            app.waitUntil(10000) { app.onAllNodesWithTag("inline-picture").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(320,model.pictures.values.first().width)
            // A tap opens it alone, on black.
            app.onAllNodesWithTag("inline-picture").onFirst().performScrollTo().performClick()
            app.waitUntil(5000) { app.onAllNodesWithTag("picture-viewer").fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.pressBack()
            // And on its card in Files.
            assertTrue(model.snapshot.files.any { it.name=="test card.j2k"&&it.picture&&it.via=="Broadcast" })
            app.onNode(hasText("Files") and isSelectable()).performClick()
            app.onNodeWithText("test card.j2k").assertExists()
        } finally {
            app.runOnUiThread { model.cancelPicture();model.demoOff() }
        }
    }
}
