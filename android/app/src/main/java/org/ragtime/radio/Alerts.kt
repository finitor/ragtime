package org.ragtime.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Something the operator would want to know about while Ragtime is out of
 * sight: a station called, wrote to them, or offers a file. open: where a
 * tap should land ("Ragchew", "Broadcast" or "Files").
 */
internal data class Alert(val key: String, val title: String, val text: String, val open: String, val call: Boolean = false)

/**
 * Finds the alerts in each new snapshot. It keeps only what it has already
 * seen, so it is told every snapshot, in sight or not; what was on the
 * screen when the connection started, and what a new ragchew reads back
 * from its log, alerts nobody.
 */
internal class AlertTracker {
    private var primed=false
    private var broadcastSeen=0L
    private var sessionSeen=0L
    private var offers=emptySet<String>()
    private var inRagchewWith=""

    fun reset() { primed=false;broadcastSeen=0;sessionSeen=0;offers=emptySet();inRagchewWith="" }

    fun update(s: RadioSnapshot, call: String): List<Alert> {
        // A file's line in the ragchew has an id of its own kind, far above the rest.
        val broadcastMax=s.broadcast.maxOfOrNull { it.id } ?: 0L
        val sessionMax=s.session.filter { it.kind==0 }.maxOfOrNull { it.id } ?: 0L
        val offered=s.files.filter { it.via=="Session"&&it.receive }.map { it.key }.toSet()
        val connected=if(s.phase==2)s.peer else ""
        val alerts=mutableListOf<Alert>()
        if(primed) {
            if(connected.isNotEmpty()&&connected!=inRagchewWith&&s.incoming)
                alerts+=Alert("c:$connected","$connected called you","You are in a ragchew with $connected.","Ragchew",call=true)
            for(row in s.broadcast) if(row.id>broadcastSeen&&!row.ours&&row.kind==0&&row.to==call&&call.isNotEmpty())
                alerts+=Alert("b:${row.from}","${row.from} on D2G",row.text,"Broadcast")
            for(row in s.session) if(row.id>sessionSeen&&!row.ours&&row.kind==0&&!row.past)
                alerts+=Alert("s:${row.from}",row.from,row.text,"Ragchew")
            for(file in s.files) if(file.via=="Session"&&file.receive&&file.key !in offers)
                alerts+=Alert("f:${file.peer}","${file.peer} offers ${file.name}","${file.size} bytes. Open Files to receive it.","Files")
        }
        primed=true;broadcastSeen=maxOf(broadcastSeen,broadcastMax);sessionSeen=maxOf(sessionSeen,sessionMax);offers=offered;inRagchewWith=connected
        return alerts
    }
}

/** The alerts as Android notifications: heads-up, and gone once Ragtime is looked at. */
internal class AlertNotifier(private val context: Context) {
    private val manager=context.getSystemService(NotificationManager::class.java)
    private val shown=mutableSetOf<Int>()

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Calls and messages",NotificationManager.IMPORTANCE_HIGH).apply {
            description="A station called you, wrote to you, or offers you a file while Ragtime is in the background."
        })
    }

    @Synchronized fun post(alert: Alert, demo: Boolean) {
        // One notification a station and kind: its newest line replaces the last.
        val id=FIRST+(alert.key.hashCode() and 0xffff)
        val open=PendingIntent.getActivity(context,OPEN.indexOf(alert.open)+10,
            Intent(context,MainActivity::class.java).putExtra(EXTRA_OPEN,alert.open).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(context,CHANNEL)
            // In demo mode the notification says so too: nothing here is from the air.
            .setContentTitle(if(demo)"Demo: ${alert.title}" else alert.title).setContentText(alert.text)
            .setStyle(Notification.BigTextStyle().bigText(alert.text))
            .setSmallIcon(R.drawable.ic_stat_ragtime)
            .setCategory(if(alert.call)Notification.CATEGORY_CALL else Notification.CATEGORY_MESSAGE)
            .setContentIntent(open).setAutoCancel(true).setShowWhen(true).build()
        manager.notify(id,notification)
        shown+=id
    }

    @Synchronized fun clear() {
        for(id in shown) manager.cancel(id)
        shown.clear()
    }

    companion object {
        const val CHANNEL="ragtime.alerts"
        const val EXTRA_OPEN="open"
        const val FIRST=100
        val OPEN=listOf("Ragchew","Broadcast","Files")
    }
}
