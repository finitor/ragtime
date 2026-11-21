package org.ragtime.radio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.content.ContextCompat

/** The user's explicit Connect owns the connection; activities are views. */
internal object RadioRuntime {
    private var instance: RadioModel? = null
    @Synchronized fun model(context: Context): RadioModel = instance ?: RadioModel(context.applicationContext as Application).also { instance=it }
}

class RadioService : Service() {
    private val handler=Handler(Looper.getMainLooper())
    private val model get()=RadioRuntime.model(this)
    private var wakeLock: PowerManager.WakeLock? = null
    private var renewAt=0L
    private var shown=""
    private val update=object:Runnable {
        override fun run() {
            if(!model.requested) { stopSelf();return }
            val now=SystemClock.elapsedRealtime()
            if(now>=renewAt) { wakeLock?.acquire(120000);renewAt=now+60000 }
            val s=model.snapshot
            val text=model.settings.call+" · "+s.connection+(if(s.active)" · "+s.peer else "")
            if(text!=shown) { shown=text;getSystemService(NotificationManager::class.java).notify(1,notification(text)) }
            handler.postDelayed(this,1000)
        }
    }
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action!=START) { model.disconnect();stopSelf();return START_NOT_STICKY }
        val channel=NotificationChannel(CHANNEL,"Radio connection",NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        try {
            val notice=notification("Connecting")
            if(Build.VERSION.SDK_INT>=34)startForeground(1,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1,notice)
            if(wakeLock==null)wakeLock=(getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Ragtime:connection").apply { setReferenceCounted(false) }
            model.startConnection(HostSettings(intent.getStringExtra("host") ?: "",intent.getStringExtra("call") ?: "",
                intent.getStringExtra("locator") ?: "",intent.getIntExtra("kiss",8100),intent.getIntExtra("command",8300),
                intent.getBooleanExtra("demo",false)))
            handler.removeCallbacks(update);handler.postDelayed(update,500)
        } catch(e:Exception) { model.connectionFailed(e.message ?: "Could not start connection service");stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(update)
        if(wakeLock?.isHeld==true)wakeLock?.release()
        model.disconnect()
        super.onDestroy()
    }
    private fun notification(text:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,RadioService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setContentTitle(if(model.settings.demo)"Ragtime demo mode" else "Ragtime connected")
            .setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null,if(model.settings.demo)"End demo" else "Disconnect",stop).build()).build()
    }
    companion object {
        const val CHANNEL="ragtime.connection"
        const val START="org.ragtime.radio.CONNECT"
        const val STOP="org.ragtime.radio.DISCONNECT"
        internal fun connect(context:Context,value:HostSettings) {
            ContextCompat.startForegroundService(context,Intent(context,RadioService::class.java).setAction(START)
                .putExtra("host",value.host).putExtra("call",value.call).putExtra("locator",value.locator)
                .putExtra("kiss",value.kiss).putExtra("command",value.command).putExtra("demo",value.demo))
        }
    }
}
