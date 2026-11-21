package org.ragtime.radio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Demo mode's guide: what to try next, from what the screen already shows.
 * It keeps no state of its own, so it is right after a rotation, a restart
 * of the demo, or an operator who wanders off the path.
 *
 * suggestion: a line to put in the entry field when the bubble is tapped;
 * thread: where that line belongs ("Broadcast" or "Ragchew").
 */
/** The demo stations the guide points at first (platform/android/DemoAir.cpp). */
internal const val DEMO_NET_CONTROL = "W1QSO"
internal const val DEMO_RAGCHEW = "K7QTH"

internal data class DemoTip(val text: String, val suggestion: String = "", val thread: String = "")

internal fun demoTip(s: RadioSnapshot, call: String): DemoTip {
    if(!s.ready) return DemoTip("Starting the demo net…")
    if(s.stations.isEmpty()) return DemoTip("Listening. The demo's stations check in within a few seconds: watch Stations fill.")
    val spoke=s.broadcast.any { it.ours&&it.kind==0 }
    if(!spoke) return DemoTip("Say hello to everyone on the net. Tap this bubble to fill in the line, then Send.",
        "CQ D2G de $call, how copy?","Broadcast")
    val addressed=s.broadcast.any { it.ours&&it.kind==0&&it.to.isNotEmpty() }
    if(!addressed) {
        val who=s.stations.firstOrNull { it.call==DEMO_NET_CONTROL }?.call ?: s.stations.first().call
        return DemoTip("Now address one station with @CALL. It acknowledges, so you know your line got through.",
            "@$who good evening from $call","Broadcast")
    }
    val chewed=s.session.any { it.kind==0 }
    if(s.active&&s.phase==1) return DemoTip("Calling ${s.peer}… the ragchew starts when the station answers.")
    if(s.active&&s.phase==2) {
        val peer=s.peer
        if(s.session.none { it.ours&&it.kind==0 }) return DemoTip("You're in a ragchew with $peer: one to one, and every line is confirmed delivered.",
            "Good copy here. What's your rig?","Ragchew")
        val got=s.files.any { it.via=="Session"&&!it.ours&&it.state=="Saved" }
        if(s.files.any { it.via=="Session"&&it.receive }) return DemoTip("$peer is offering you a file. Open Files and tap Receive.")
        if(!got) {
            val asked=s.session.any { it.ours&&(it.text.contains("file",true)||it.text.contains("bulletin",true)) }
            return if(asked) DemoTip("$peer is getting its bulletin ready…")
            else DemoTip("Ask $peer for a file.","Can you send me your bulletin file?","Ragchew")
        }
        if(s.session.none { it.ours&&it.text.contains("73") }) return DemoTip("The file is in Files, checked whole. Sign off with 73, or tap Leave.",
            "Thanks for the ragchew, 73","Ragchew")
        return DemoTip("$peer is signing off…")
    }
    if(s.active) return DemoTip("Leaving the ragchew…")
    if(!chewed) {
        val who=s.stations.firstOrNull { it.call==DEMO_RAGCHEW }?.call ?: s.stations.firstOrNull { !it.status.contains("No sessions") }?.call ?: s.stations.first().call
        return DemoTip("Start a ragchew, a one-to-one session: open Stations, tap $who, then Call $who.")
    }
    s.files.firstOrNull { it.via=="Broadcast"&&it.receive }?.let {
        return DemoTip("${it.peer} is broadcasting ${it.name} to everyone. Open Files and tap Receive; pieces you missed are asked for after its seal.")
    }
    return DemoTip("That's the tour. Keep exploring, or turn Demo mode off in Settings to connect to your own Data2G modem.")
}

/** The bar across the very top while demo mode runs: nobody should mistake this for the air. */
@Composable
internal fun DemoBanner(onClick: () -> Unit) {
    Surface(color=Color(0xffffb300),contentColor=Color(0xff1a1200),onClick=onClick) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=16.dp,vertical=6.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("DEMO MODE",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Black,maxLines=1)
            Text("Simulated stations; nothing is sent.",style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

/** The guide's bubble; tapping one with a suggestion fills the entry field. */
@Composable
internal fun DemoBubble(tip: DemoTip, onSuggestion: (DemoTip) -> Unit, onHide: () -> Unit) {
    val bubble=RoundedCornerShape(topStart=4.dp,topEnd=20.dp,bottomEnd=20.dp,bottomStart=20.dp)
    Surface(color=MaterialTheme.colorScheme.tertiaryContainer,contentColor=MaterialTheme.colorScheme.onTertiaryContainer,shape=bubble,
        modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp).semantics { contentDescription="Demo guide" },
        onClick={ if(tip.suggestion.isNotEmpty())onSuggestion(tip) },enabled=tip.suggestion.isNotEmpty()) {
        Column(Modifier.padding(start=14.dp,end=6.dp,top=8.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("Try this",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=6.dp))
                TextButton(onClick=onHide,contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)) { Text("Hide tips") }
            }
            Text(tip.text,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(end=8.dp))
            if(tip.suggestion.isNotEmpty()) Surface(color=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface,
                shape=RoundedCornerShape(12.dp),modifier=Modifier.padding(end=8.dp)) {
                Text("“${tip.suggestion}”",Modifier.padding(horizontal=12.dp,vertical=8.dp),fontWeight=FontWeight.Medium)
            }
        }
    }
}
