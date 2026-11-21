package org.ragtime.radio

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class ChatRow(val id: Long, val from: String, val text: String, val to: String, val ours: Boolean, val time: Long, val state: String, val kind: Int)
internal data class StationRow(val call: String, val where: String, val status: String, val heard: String, val snr: String)
internal data class TransferRow(val key:String,val via:String,val name:String,val peer:String,val state:String,val path:String,
    val size:Long,val done:Long,val time:Long,val ours:Boolean,val live:Boolean,val checked:Boolean,
    val receive:Boolean,val decline:Boolean,val cancel:Boolean,val resume:Boolean,val forget:Boolean,val error:String)
internal data class RadioSnapshot(
    val connection: String = "Disconnected", val error: String = "", val ready: Boolean = false,
    val ptt: Boolean = false, val busy: Boolean = false, val paused: Boolean = false, val narrow: Boolean = false,
    val dataUp: Boolean = false, val mode: String = "", val channel: String = "", val peer: String = "",
    val active: Boolean = false, val phase: Int = 0, val sessionStatus: String = "",
    val broadcast: List<ChatRow> = emptyList(), val session: List<ChatRow> = emptyList(), val stations: List<StationRow> = emptyList(),
    val files:List<TransferRow> = emptyList()
) {
    companion object {
        fun parse(text: String): RadioSnapshot {
            val j = JSONObject(text)
            fun rows(name: String): List<ChatRow> {
                val array = j.getJSONArray(name)
                return (0 until array.length()).map { i -> array.getJSONObject(i).let {
                    ChatRow(it.getLong("id"),it.getString("from"),it.getString("text"),it.optString("to"),it.getBoolean("ours"),it.getLong("time"),it.getString("state"),it.getInt("kind"))
                } }
            }
            val stations = j.getJSONArray("stations")
            val files=j.optJSONArray("files")
            return RadioSnapshot(ready=j.getBoolean("ready"),ptt=j.getBoolean("ptt"),busy=j.getBoolean("busy"),paused=j.getBoolean("paused"),narrow=j.getBoolean("narrow"),dataUp=j.getBoolean("dataUp"),mode=j.getString("mode"),channel=j.getString("channel"),peer=j.getString("peer"),active=j.getBoolean("active"),phase=j.getInt("phase"),sessionStatus=j.getString("sessionStatus"),broadcast=rows("broadcast"),session=rows("session"),stations=(0 until stations.length()).map { i -> stations.getJSONObject(i).let { StationRow(it.getString("call"),it.getString("where"),it.getString("status"),it.getString("heard"),it.getString("snr")) } },
                files=if(files==null)emptyList() else (0 until files.length()).map { i->files.getJSONObject(i).let {
                    TransferRow(it.getString("key"),it.getString("via"),it.getString("name"),it.getString("peer"),it.getString("state"),it.getString("path"),
                        it.getLong("size"),it.getLong("done"),it.getLong("time"),it.getBoolean("ours"),it.getBoolean("live"),it.getBoolean("checked"),
                        it.getBoolean("receive"),it.getBoolean("decline"),it.getBoolean("cancel"),it.getBoolean("resume"),it.getBoolean("forget"),it.getString("error"))
                } })
        }
    }
}

internal class RadioModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("radio", 0)
    var settings by mutableStateOf(HostSettings(preferences.getString("host", "")!!, preferences.getString("call", "")!!, preferences.getString("locator", "")!!, preferences.getInt("kiss",8100),preferences.getInt("command",8300),preferences.getBoolean("demo",false)))
        private set
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
            engine=Engine(valid.call,valid.locator,directory.absolutePath)
            lastJson="";requested=true;failures=0;retryAt=0;error="";status="Connecting";publish()
        } }
    }
    private fun stopDemo() {
        // What the demo's stations said goes with the banner: none of it is
        // left on the screen to be taken for the air.
        if(link.demo) { runCatching { engine?.close() };engine=null;link=HostSettings();stationDirectory=null;lastJson="" }
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
        val published=value.copy(connection=status,error=error)
        main.post { snapshot=published }
    }
    override fun onCleared() {
        poll.cancel(false)
        worker.execute { try { releaseSession();disconnectTransport() } finally { engine?.close();engine=null;stopDemo() } }
        worker.shutdown()
        documents.shutdown()
        super.onCleared()
    }
}
