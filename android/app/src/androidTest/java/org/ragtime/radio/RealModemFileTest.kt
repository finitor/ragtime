package org.ragtime.radio

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Random

/**
 * Opt-in: files over real modems. The far desktop must take files unasked
 * (android_air_bench.py --auto-files CALL). A session file counts as Sent
 * only once the far end has checked its digest; a broadcast file has no
 * acknowledgement, so the bench compares the far copy by its SHA-256.
 */
class RealModemFileTest {
    @get:Rule val app=createAndroidComposeRule<MainActivity>()

    @Test fun sessionAndBroadcastFilesOverRealModems() {
        val arguments=InstrumentationRegistry.getArguments()
        val host=arguments.getString("pilotHost") ?: ""
        assumeTrue("Requires explicit pilotHost and the real-modem bench",host.isNotBlank())
        val marker=arguments.getString("pilotMarker") ?: "file-${System.currentTimeMillis()}"
        val call=arguments.getString("pilotCall") ?: "W1AAA"
        val peer=arguments.getString("pilotPeer") ?: "W1BBB"
        val size=(arguments.getString("pilotBytes") ?: "1200").toInt()
        // How long to stay on the air after the broadcast file's pieces have gone, for its seal and
        // the repairs a station may ask for: a file that was talked over needs minutes, not seconds.
        val settle=(arguments.getString("pilotSettle") ?: "90").toLong()*1000
        val settings=HostSettings(host,call,arguments.getString("pilotLocator") ?: "CN87",
            (arguments.getString("pilotKiss") ?: "18100").toInt(),(arguments.getString("pilotCommand") ?: "18300").toInt())
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val model=app.activity.radioModel
        val result=JSONObject().put("marker",marker).put("call",settings.validated().call).put("peer",peer)
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // Readable, so the far copy can be opened and looked at, and different
        // every run, so a stale file can't pass for this one.
        fun document(name:String,seed:Long):File=File(context.cacheDir,name).apply {
            val random=Random(seed)
            val text=StringBuilder("Ragtime Android real-modem file test\n$name from ${settings.call} to $peer\n")
            var line=1
            while(text.length<size)text.append("%03d %08x QST QST QST de %s\n".format(line++,random.nextInt(),settings.call))
            writeBytes(text.toString().toByteArray().copyOf(size).also { if(it.last()==0.toByte())it[size-1]='\n'.code.toByte() })
        }
        fun row(name:String,via:String)=model.snapshot.files.filter { it.name==name&&it.via==via }
        val started=System.currentTimeMillis()
        fun elapsed()=System.currentTimeMillis()-started
        try {
            app.runOnUiThread { model.connect(settings) }
            app.waitUntil(30000) { model.snapshot.ready&&model.snapshot.dataUp }

            // A ragchew, then a file in it: Sent means the far end saved and checked it.
            var called=false
            app.runOnUiThread { model.action("call",peer) { called=it } }
            app.waitUntil(240000) { model.snapshot.phase==2 }
            assertTrue(called)
            result.put("connected_ms",elapsed())
            val sessionFile=document("$marker-session.txt",marker.hashCode().toLong())
            result.put("session_name",sessionFile.name).put("session_sha256",sha(sessionFile.readBytes())).put("bytes",size)
            app.runOnUiThread { model.importFile(Uri.fromFile(sessionFile),peer,model.settings.call) }
            app.waitUntil(600000) { row(sessionFile.name,"Session").any { it.state=="Sent"&&it.checked } }
            result.put("session_sent_ms",elapsed())
            assertTrue("The file's line is in the ragchew",model.snapshot.session.any { it.kind==1&&it.text.startsWith(sessionFile.name) })
            app.runOnUiThread { model.action("leave") }
            app.waitUntil(240000) { !model.snapshot.active }
            result.put("left_ms",elapsed())

            // A broadcast file: every piece gone, and its card in Broadcast.
            val broadcastFile=document("$marker-broadcast.txt",marker.hashCode().toLong()+1)
            result.put("broadcast_name",broadcastFile.name).put("broadcast_sha256",sha(broadcastFile.readBytes()))
            app.runOnUiThread { model.importFile(Uri.fromFile(broadcastFile),"",model.settings.call) }
            app.waitUntil(600000) { row(broadcastFile.name,"Broadcast").any { it.ours&&it.size>0&&it.done>=it.size } }
            result.put("broadcast_pieces_gone_ms",elapsed())
            // The seal follows the pieces; a far end that missed one asks then.
            val until=System.currentTimeMillis()+settle
            app.waitUntil(settle+30000) { System.currentTimeMillis()>=until }
            result.put("broadcast_state",row(broadcastFile.name,"Broadcast").firstOrNull()?.state ?: "")
            result.put("result","pass")
        } catch(e:Throwable) {
            result.put("result","fail").put("error",e.toString()).put("failed_ms",elapsed())
                .put("files",model.snapshot.files.joinToString(" | ") { "${it.name}:${it.via}:${it.state}:${it.done}/${it.size}:${it.error}" })
                .put("session_status",model.snapshot.sessionStatus).put("connection",model.snapshot.connection)
            throw e
        } finally {
            File(context.filesDir,"real-modem-file.json").writeText(result.toString(2))
            app.runOnUiThread { model.disconnect() }
        }
    }
}
