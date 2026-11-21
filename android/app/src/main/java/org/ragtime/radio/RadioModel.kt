package org.ragtime.radio

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class ChatRow(val id: Long, val from: String, val text: String, val to: String, val ours: Boolean, val time: Long, val state: String, val kind: Int,
    val past: Boolean = false,
    // A file's row: its key while the file engines have it, its path once saved, and whether it is an inline picture.
    val file: String = "", val path: String = "", val picture: Boolean = false) {
    /** Where an inline picture's bytes are, for RadioModel.wantPicture; empty if this is no picture. */
    val pictureSource: String get() = if(!picture) "" else if(file.isNotEmpty()) file else if(path.isNotEmpty()) "p:$path" else ""
}
internal data class StationRow(val call: String, val where: String, val status: String, val heard: String, val snr: String)
internal data class TransferRow(val key:String,val via:String,val name:String,val peer:String,val state:String,val path:String,
    val size:Long,val done:Long,val time:Long,val ours:Boolean,val live:Boolean,val checked:Boolean,
    val receive:Boolean,val decline:Boolean,val cancel:Boolean,val resume:Boolean,val forget:Boolean,val error:String,
    val picture:Boolean=false) {
    val pictureSource: String get() = if(!picture) "" else if(live&&(key.startsWith("s:")||key.startsWith("b:"))) key else if(path.isNotEmpty()) "p:$path" else ""
}
internal data class RadioSnapshot(
    val connection: String = "Disconnected", val error: String = "", val ready: Boolean = false,
    val ptt: Boolean = false, val busy: Boolean = false, val paused: Boolean = false, val narrow: Boolean = false,
    val dataUp: Boolean = false, val mode: String = "", val channel: String = "", val peer: String = "",
    val active: Boolean = false, val phase: Int = 0, val incoming: Boolean = false, val sessionStatus: String = "",
    val broadcast: List<ChatRow> = emptyList(), val session: List<ChatRow> = emptyList(), val stations: List<StationRow> = emptyList(),
    val files:List<TransferRow> = emptyList()
) {
    companion object {
        fun parse(text: String): RadioSnapshot {
            val j = JSONObject(text)
            fun rows(name: String): List<ChatRow> {
                val array = j.getJSONArray(name)
                return (0 until array.length()).map { i -> array.getJSONObject(i).let {
                    ChatRow(it.getLong("id"),it.getString("from"),it.getString("text"),it.optString("to"),it.getBoolean("ours"),it.getLong("time"),it.getString("state"),it.getInt("kind"),it.optBoolean("past"),
                        it.optString("file"),it.optString("path"),it.optBoolean("picture"))
                } }
            }
            val stations = j.getJSONArray("stations")
            val files=j.optJSONArray("files")
            return RadioSnapshot(ready=j.getBoolean("ready"),ptt=j.getBoolean("ptt"),busy=j.getBoolean("busy"),paused=j.getBoolean("paused"),narrow=j.getBoolean("narrow"),dataUp=j.getBoolean("dataUp"),mode=j.getString("mode"),channel=j.getString("channel"),peer=j.getString("peer"),active=j.getBoolean("active"),phase=j.getInt("phase"),incoming=j.optBoolean("incoming"),sessionStatus=j.getString("sessionStatus"),broadcast=rows("broadcast"),session=rows("session"),stations=(0 until stations.length()).map { i -> stations.getJSONObject(i).let { StationRow(it.getString("call"),it.getString("where"),it.getString("status"),it.getString("heard"),it.getString("snr")) } },
                files=if(files==null)emptyList() else (0 until files.length()).map { i->files.getJSONObject(i).let {
                    TransferRow(it.getString("key"),it.getString("via"),it.getString("name"),it.getString("peer"),it.getString("state"),it.getString("path"),
                        it.getLong("size"),it.getLong("done"),it.getLong("time"),it.getBoolean("ours"),it.getBoolean("live"),it.getBoolean("checked"),
                        it.getBoolean("receive"),it.getBoolean("decline"),it.getBoolean("cancel"),it.getBoolean("resume"),it.getBoolean("forget"),it.getString("error"),
                        it.optBoolean("picture"))
                } })
        }
    }
}

internal class RadioModel(application: Application) : AndroidViewModel(application) {
    private companion object { const val MAX_PICTURES=24 }
    private val preferences = application.getSharedPreferences("radio", 0)
    var settings by mutableStateOf(HostSettings(preferences.getString("host", "")!!, preferences.getString("call", "")!!, preferences.getString("locator", "")!!, preferences.getInt("kiss",8100),preferences.getInt("command",8300),preferences.getBoolean("demo",false)))
        private set
    /** A notification was tapped: the view it asks for, until the screen has gone there. */
    var openRequest by mutableStateOf<String?>(null)
    // Calls, lines addressed to the operator and file offers, told by a
    // notification while no Ragtime window is in sight.
    private val alertTracker=AlertTracker()
    private val alerts by lazy { AlertNotifier(application) }
    @Volatile private var visible=false
    fun shown(inSight:Boolean) { visible=inSight;if(inSight)alerts.clear() }
    /** Settings > Notify me of calls and messages. On unless the operator says otherwise. */
    var alertsOn by mutableStateOf(preferences.getBoolean("alerts",true))
        private set
    @Volatile private var alerting=preferences.getBoolean("alerts",true)
    fun setAlerts(on:Boolean) {
        alertsOn=on;alerting=on
        preferences.edit().putBoolean("alerts",on).apply()
        if(!on)alerts.clear()
    }
    /** Demo mode is running: made-up stations on a net inside the app, no modem. */
    var demoActive by mutableStateOf(false)
        private set
    var snapshot by mutableStateOf(RadioSnapshot())
        private set
    var documentStatus by mutableStateOf("")
        private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"ragtime-host") }
    private val documents=Executors.newSingleThreadExecutor { r->Thread(r,"ragtime-documents") }
    private var engine: Engine? = null
    private var transport: HostTransport? = null
    // Where the worker connects: the settings, or demo mode's loopback ports.
    private var link = HostSettings()
    @Volatile private var stationDirectory: File? = null
    @Volatile var requested = false
        private set
    private var retryAt = 0L
    private var failures = 0
    private var status = "Disconnected"
    private var error = ""
    private var lastJson = ""
    private val poll = worker.scheduleWithFixedDelay({ guarded { step() } },0,100,TimeUnit.MILLISECONDS)

    private fun guarded(block: () -> Unit) {
        try { block() }
        catch(e: Exception) { failed(e.message ?: "Connection failed") }
        catch(e: UnsatisfiedLinkError) { failed("Native library could not load: ${e.message}") }
    }
    private fun failed(message: String) {
        // A failing native/storage operation must not recursively fail cleanup
        // or kill the scheduled worker, leaving a stale Connected screen.
        runCatching { transport?.close() };transport=null
        runCatching { engine?.invoke(1) }
        val wasDemo=link.demo
        stopDemo()
        error=message;status="Disconnected";requested=false
        main.post { snapshot=(if(wasDemo)RadioSnapshot() else snapshot).copy(connection="Disconnected",error=message,ready=false,active=false) }
    }
    fun connect(value: HostSettings) {
        val valid = value.validated()
        settings=valid
        RadioService.connect(getApplication(),valid)
    }
    fun startConnection(value:HostSettings) {
        val valid=value.validated();settings=valid
        requested=true
        worker.execute { guarded {
            releaseSession();disconnectTransport(); engine?.close(); engine=null;stopDemo()
            val files=getApplication<Application>().filesDir
            val directory:File
            if(valid.demo) {
                // A fresh net and a fresh log every time, apart from the
                // operator's own stations/ folders; the host and ports saved
                // for the real modem are left as they are.
                preferences.edit().putBoolean("demo",true).apply()
                val root=File(files,"demo");root.deleteRecursively()
                val ports=NativeCore.demoStart(File(root,"air").absolutePath.toByteArray(),valid.call.toByteArray())
                link=valid.copy(host=HostSettings.LOCALHOST,kiss=ports[0],command=ports[1])
                directory=File(root,"me")
                main.post { demoActive=true }
            } else {
                preferences.edit().putString("host",valid.host).putString("call",valid.call).putString("locator",valid.locator).putInt("kiss",valid.kiss).putInt("command",valid.command).putBoolean("demo",false).apply()
                link=valid
                directory=File(files,"stations/"+valid.call.replace('/','_'))
            }
            stationDirectory=directory
            alertTracker.reset();forgetPictures()
            engine=Engine(valid.call,valid.locator,directory.absolutePath)
            lastJson="";requested=true;failures=0;retryAt=0;error="";status="Connecting";publish()
        } }
    }
    /**
     * The Demo mode switch turned off: said once and it is so. The demo ends
     * if it is running, and the next Connect is to the operator's own modem.
     */
    fun demoOff() {
        val running=demoActive||settings.demo
        settings=settings.copy(demo=false)
        preferences.edit().putBoolean("demo",false).apply()
        if(running)disconnect()
    }
    private fun stopDemo() {
        // What the demo's stations said goes with the banner: none of it is
        // left on the screen to be taken for the air.
        if(link.demo) { runCatching { engine?.close() };engine=null;link=HostSettings();stationDirectory=null;lastJson="";forgetPictures() }
        runCatching { NativeCore.demoStop() }
        main.post { demoActive=false }
    }
    fun disconnect() { worker.execute { guarded { requested=false;releaseSession();disconnectTransport();stopDemo();status="Disconnected";error="";publish() } } }
    fun connectionFailed(message:String) { worker.execute { failed(message) } }
    fun action(name: String, value: String = "", completed: (Boolean) -> Unit = {}) {
        worker.execute {
            val ok=try { engine?.action(name,value) ?: false } catch(e:Exception) { error=e.message ?: "Could not complete action";false }
            if(ok)error="" else if(error.isEmpty())error="Action unavailable: check the host connection and session state"
            guarded { publish() };main.post { completed(ok) }
        }
    }
    fun importFile(uri:Uri,target:String,station:String) {
        documentStatus="Preparing file…"
        documents.execute {
            var imported:File?=null
            try {
                val base=stationDirectory ?: File(getApplication<Application>().filesDir,"stations/"+station.replace('/','_'))
                val file=Documents.import(getApplication(),uri,base)
                imported=file
                main.post {
                    if(settings.call!=station) { file.delete();documentStatus="Station changed; choose the file again" }
                    else action(if(target.isEmpty())"file-broadcast" else "file-offer",if(target.isEmpty())file.absolutePath else "$target\u0000${file.absolutePath}") { ok->
                        documentStatus=if(ok)"Queued ${file.name}" else "File was not queued"
                        if(!ok)file.delete()
                    }
                }
            } catch(e:Exception) { imported?.delete();main.post { documentStatus=e.message ?: "Could not read file" } }
        }
    }
    fun exportFile(path:String,name:String,uri:Uri) {
        documentStatus="Saving $name…"
        documents.execute {
            try {
                Documents.export(getApplication(),File(path),uri)
                main.post { documentStatus="Saved $name" }
            } catch(e:Exception) { main.post { documentStatus=e.message ?: "Could not save file" } }
        }
    }
    /**
     * Closing the sockets does not end the modem's ARQ link: leave the
     * ragchew while the command port is still open, as the desktop does on
     * quit, so the next Connect can call again. Never on a broken transport.
     */
    private fun releaseSession() {
        val open=transport ?: return
        runCatching { engine?.action("abort");open.flush() }
    }
    private fun disconnectTransport() {
        try { transport?.close() } finally { transport=null;engine?.invoke(1) }
    }

    // Inline pictures. A picture's bytes are fetched on the worker, which
    // owns the engine, and decoded on a thread of their own: a Cool-chic
    // picture takes seconds, and the link must not wait for it.
    /** Decoded pictures by source: "s:ID" and "b:ID" while they move, "p:PATH" once saved. */
    val pictures = mutableStateMapOf<String, ImageBitmap>()
    private val pictureOrder = ArrayDeque<String>()
    private val pictureShown = HashMap<String, Long>()
    private val pictureWanted = HashMap<String, Long>()
    private val pictureBusy = HashSet<String>()
    private val pictureThread = Executors.newSingleThreadExecutor { r->Thread(r,"ragtime-pictures") }
    /** stamp: anything that changes when more of the picture has come, so it is decoded again then. Main thread. */
    fun wantPicture(source:String,stamp:Long) {
        if(source.isEmpty())return
        pictureWanted[source]=stamp
        if(pictureShown[source]==stamp||source in pictureBusy)return
        pictureBusy+=source
        worker.execute {
            val bytes=runCatching { engine?.pictureBytes(source) }.getOrNull() ?: ByteArray(0)
            pictureThread.execute {
                val bitmap=runCatching { Pictures.decode(bytes) }.getOrNull()
                main.post {
                    pictureBusy-=source;pictureShown[source]=stamp
                    if(bitmap!=null) {
                        if(source !in pictures) { pictureOrder.addLast(source);while(pictureOrder.size>MAX_PICTURES)pictures.remove(pictureOrder.removeFirst()) }
                        pictures[source]=bitmap.asImageBitmap()
                    }
                    // More came while this was decoding: show that too.
                    val latest=pictureWanted[source]
                    if(latest!=null&&latest!=stamp)wantPicture(source,latest)
                }
            }
        }
    }
    private fun forgetPictures() { main.post { pictures.clear();pictureOrder.clear();pictureShown.clear();pictureWanted.clear() } }

    /** The picture being made ready to send (PictureSheet), or none. */
    var pictureDraft by mutableStateOf<PictureDraft?>(null)
        private set
    private var draftPixels:ByteArray?=null
    private var draftWidth=0
    private var draftHeight=0
    private var draftCoded:ByteArray?=null
    private var draftSerial=0
    /** A picture the operator picked: read by Android, shrunk and coded by the engines' own codec. */
    fun pickPicture(uri:Uri,target:String,station:String) {
        pictureDraft=PictureDraft(target,station,"picture.j2k")
        draftPixels=null;draftCoded=null
        val serial=++draftSerial
        documents.execute {
            try {
                val app=getApplication<Application>()
                val bitmap=Pictures.read(app,uri)
                val name=Pictures.sentName(app,uri)
                val rgba=Pictures.pixels(bitmap)
                main.post { if(serial==draftSerial)drafted(rgba,bitmap.width,bitmap.height,name) }
            } catch(e:Throwable) { main.post { if(serial==draftSerial)pictureDraft=pictureDraft?.copy(busy=false,error=e.message ?: "Could not read the picture") } }
        }
    }
    /** A picture already in hand, with no picker. */
    fun pictureFrom(bitmap:Bitmap,name:String,target:String,station:String) {
        pictureDraft=PictureDraft(target,station,Pictures.sentName(name))
        draftSerial++
        drafted(Pictures.pixels(Pictures.opaque(bitmap)),bitmap.width,bitmap.height,Pictures.sentName(name))
    }
    private fun drafted(rgba:ByteArray,width:Int,height:Int,name:String) {
        draftPixels=rgba;draftWidth=width;draftHeight=height
        pictureDraft=pictureDraft?.copy(name=name)
        codeDraft()
    }
    fun pictureOptions(side:Int,budget:Int) { pictureDraft=pictureDraft?.copy(side=side,budget=budget);codeDraft() }
    private fun codeDraft() {
        val draft=pictureDraft ?: return
        val rgba=draftPixels ?: return
        val serial=++draftSerial
        val width=draftWidth;val height=draftHeight
        pictureDraft=draft.copy(busy=true,error="")
        documents.execute {
            try {
                val coded=NativeCore.encodePicture(rgba,width,height,draft.side,draft.budget*1024)
                // Shown as the far end will see it: decoded from the very bytes that go.
                val preview=Pictures.decode(coded)?.asImageBitmap()
                main.post { if(serial==draftSerial) {
                    draftCoded=coded
                    pictureDraft=pictureDraft?.copy(preview=preview,bytes=coded.size,busy=false,error=if(preview==null)"The picture could not be read back" else "")
                } }
            } catch(e:Throwable) { main.post { if(serial==draftSerial)pictureDraft=pictureDraft?.copy(busy=false,error=e.message ?: "The picture could not be coded") } }
        }
    }
    fun sendPicture() {
        val draft=pictureDraft ?: return
        val coded=draftCoded ?: return
        val base=stationDirectory
        if(base==null||settings.call!=draft.station) { pictureDraft=draft.copy(error="Connect before sending the picture");return }
        cancelPicture()
        documentStatus="Queuing ${draft.name}…"
        documents.execute {
            val directory=File(base,"outgoing/"+java.util.UUID.randomUUID()).apply { mkdirs() }
            val file=File(directory,draft.name)
            try {
                file.writeBytes(coded)
                main.post {
                    action(if(draft.target.isEmpty())"picture-broadcast" else "picture-offer",
                        if(draft.target.isEmpty())file.absolutePath else "${draft.target}\u0000${file.absolutePath}") { ok->
                        documentStatus=if(ok)"Queued ${draft.name}" else "The picture was not queued"
                        if(!ok)directory.deleteRecursively()
                    }
                }
            } catch(e:Exception) { directory.deleteRecursively();main.post { documentStatus=e.message ?: "Could not queue the picture" } }
        }
    }
    fun cancelPicture() { draftSerial++;pictureDraft=null;draftPixels=null;draftCoded=null }
    private fun step() {
        val current=engine ?: return
        if(!requested)return
        val now=System.currentTimeMillis()
        if(transport==null && now>=retryAt) {
            try { status="Connecting";transport=HostTransport(link,current) }
            catch(e: Exception) { retry(e.message ?: "Could not reach host") }
        }
        try {
            transport?.step()
            if(transport?.ready==true&&status!="Connected") { status="Connected";failures=0;error="" }
        } catch(e: Exception) { disconnectTransport();retry(e.message ?: "Host disconnected") }
        if(transport==null && retryAt>now)status="Reconnecting in ${((retryAt-now+999)/1000).coerceAtLeast(1)} s"
        publish()
    }
    private fun retry(reason: String) {
        failures++
        val seconds=listOf(2,4,8,15,30)[(failures-1).coerceAtMost(4)]
        retryAt=System.currentTimeMillis()+seconds*1000;error=reason
    }
    private fun publish() {
        val json=engine?.invoke(30)?.toString(Charsets.UTF_8) ?: ""
        val signature="$status\u0000$error\u0000$json"
        if(signature==lastJson)return
        lastJson=signature
        val value=if(json.isEmpty())RadioSnapshot() else RadioSnapshot.parse(json)
        if(json.isNotEmpty()) {
            // Told every snapshot, so that what was read in sight is not news later.
            val news=alertTracker.update(value,link.call)
            if(!visible&&alerting) for(alert in news) runCatching { alerts.post(alert,link.demo) }
        }
        val published=value.copy(connection=status,error=error)
        main.post { snapshot=published }
    }
    override fun onCleared() {
        poll.cancel(false)
        worker.execute { try { releaseSession();disconnectTransport() } finally { engine?.close();engine=null;stopDemo() } }
        worker.shutdown()
        documents.shutdown()
        pictureThread.shutdown()
        super.onCleared()
    }
}
