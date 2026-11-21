package org.ragtime.radio

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run only against ragtime_fake_air via adb reverse; never a real radio. */
class NativeInteropTest {
    @Test fun utf8BroadcastSessionHistoryAndReconnect() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"interop-${System.nanoTime()}")
        val aDirectory=File(directory,"a").absolutePath
        var a=Engine("TESTA","CN87",aDirectory)
        val b=Engine("TESTB","FN31",File(directory,"b").absolutePath)
        var aTransport=HostTransport(HostSettings("127.0.0.1","TESTA","CN87",benchPort(8100),benchPort(8300)),a)
        val bTransport=HostTransport(HostSettings("127.0.0.1","TESTB","FN31",benchPort(8110),benchPort(8310)),b)
        fun snapshot(engine:Engine)=RadioSnapshot.parse(engine.invoke(30).toString(Charsets.UTF_8))
        fun until(label:String,timeout:Long=15000,condition:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+timeout
            while(SystemClock.elapsedRealtime()<deadline) {
                aTransport.step();bTransport.step()
                if(condition())return
                SystemClock.sleep(10)
            }
            fail("Timed out: $label\nA: ${snapshot(a)}\nB: ${snapshot(b)}")
        }
        try {
            until("both groups open") { snapshot(a).ready&&snapshot(b).ready&&snapshot(a).dataUp&&snapshot(b).dataUp }
            val text="CQ D2G π 🚀\n\"quote\" and \\ slash"
            assertTrue(a.action("broadcast",text))
            until("UTF-8 broadcast heard and sent") { snapshot(b).broadcast.any { it.text==text } && snapshot(a).broadcast.any { it.text==text&&it.state=="Sent" } }
            assertTrue(snapshot(b).stations.any { it.call=="TESTA" })
            assertTrue(b.action("broadcast","@TESTA FB 73"))
            until("addressed message acknowledged") { snapshot(b).broadcast.any { it.state=="Acknowledged" } }
            assertTrue(a.action("call","TESTB"))
            until("session connected") { snapshot(a).phase==2&&snapshot(b).phase==2 }
            val sessionText="Android ragchew 🏕️"
            assertTrue(a.action("session",sessionText))
            until("session text delivered") { snapshot(b).session.any { it.text==sessionText }&&snapshot(a).session.any { it.text==sessionText&&it.state=="Delivered" } }
            // A session file through JNI: the offer's NUL-separated value, the
            // rows' keys, and the bytes saved under B's own folder.
            val bytes=ByteArray(1500) { (it*31+7).toByte() }
            val source=File(aDirectory,"outgoing/interop/note.bin").apply { parentFile!!.mkdirs();writeBytes(bytes) }
            assertTrue(a.action("file-offer","TESTB\u0000${source.absolutePath}"))
            until("file offered") { snapshot(b).files.any { it.name=="note.bin"&&it.receive } }
            assertTrue(b.action("file-receive",snapshot(b).files.first { it.name=="note.bin"&&it.receive }.key))
            until("file saved and sent") {
                snapshot(b).files.any { it.name=="note.bin"&&it.state=="Saved"&&it.checked&&it.path.isNotEmpty() } &&
                    snapshot(a).files.any { it.name=="note.bin"&&it.state=="Sent" }
            }
            assertArrayEquals(bytes,File(snapshot(b).files.first { it.name=="note.bin"&&it.state=="Saved" }.path).readBytes())
            assertTrue("The file's line is in the ragchew",snapshot(b).session.any { it.kind==1&&it.text.startsWith("note.bin") })
            assertTrue(a.action("leave"))
            until("explicit leave") { !snapshot(a).active&&!snapshot(b).active }
            // A broadcast file: announced, taken with Receive, whole by its digest.
            val news=ByteArray(2500) { (it*13+5).toByte() }
            val bulletin=File(aDirectory,"outgoing/interop/news.bin").apply { writeBytes(news) }
            assertTrue(a.action("file-broadcast",bulletin.absolutePath))
            until("broadcast file announced") { snapshot(b).files.any { it.name=="news.bin"&&it.via=="Broadcast"&&it.receive } }
            assertTrue(b.action("file-receive",snapshot(b).files.first { it.name=="news.bin"&&it.receive }.key))
            // The pieces sent before Receive are asked for again once the
            // sender's seal has come: about 45 s on the bench.
            until("broadcast file whole",120000) { snapshot(b).files.any { it.name=="news.bin"&&it.checked } }
            assertArrayEquals(news,File(directory,"b/received/news.bin").readBytes())
            assertTrue("Its card is in Broadcast",snapshot(b).broadcast.any { it.kind==1&&it.text.contains("news.bin") })
            // Only a file under the station's own folder is ever sent.
            val outside=File(directory,"outside.bin").apply { writeBytes(news) }
            assertThrows(IllegalStateException::class.java) { a.action("file-broadcast",outside.absolutePath) }
            aTransport.close();a.invoke(1);a.close()
            a=Engine("TESTA","CN87",aDirectory)
            assertTrue("Broadcast history survives native recreation",snapshot(a).broadcast.any { it.text==text })
            aTransport=HostTransport(HostSettings("127.0.0.1","TESTA","CN87",benchPort(8100),benchPort(8300)),a)
            until("reconnect") { snapshot(a).ready }
            assertTrue(a.action("broadcast","Reconnected 73"))
            until("post-reconnect broadcast") { snapshot(b).broadcast.any { it.text=="Reconnected 73" } }
        } finally {
            // A failure part way must not leave the bench's hosts in a ragchew.
            runCatching {
                a.action("abort");b.action("abort")
                repeat(30) { aTransport.step();bTransport.step();SystemClock.sleep(10) }
            }
            aTransport.close();bTransport.close();a.close();b.close();directory.deleteRecursively()
        }
    }
}
