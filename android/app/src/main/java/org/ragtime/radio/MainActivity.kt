package org.ragtime.radio

import android.os.Bundle
import android.Manifest
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {
    internal val radioModel by lazy { RadioRuntime.model(application) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if(isSystemInDarkTheme()) darkColorScheme(primary=Color(0xffa9c8ff)) else lightColorScheme(primary=Color(0xff235ba9))
            MaterialTheme(colorScheme=colors) { RadioApp(radioModel) }
        }
        opened(intent)
    }
    // A tapped notification says where to land. singleTop: it comes here, not to a second Ragtime.
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent);setIntent(intent);opened(intent) }
    private fun opened(intent: android.content.Intent?) {
        val view=intent?.getStringExtra(AlertNotifier.EXTRA_OPEN) ?: return
        intent.removeExtra(AlertNotifier.EXTRA_OPEN)
        radioModel.openRequest=view
    }
    // In sight, the screen says it all: the alerts are for when it is not.
    override fun onStart() { super.onStart();radioModel.shown(true) }
    override fun onStop() { radioModel.shown(false);super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
private fun RadioApp(model: RadioModel) {
    val state=model.snapshot
    var view by rememberSaveable { mutableStateOf("Stations") }
    var thread by rememberSaveable { mutableStateOf("Broadcast") }
    var returnTo by rememberSaveable { mutableStateOf<String?>(null) }
    var broadcastDraft by rememberSaveable { mutableStateOf("") }
    var sessionDraft by rememberSaveable(state.peer) { mutableStateOf("") }
    var radio by rememberSaveable { mutableStateOf(model.settings.call.isEmpty()) }
    var picked by remember { mutableStateOf<StationRow?>(null) }
    var leave by rememberSaveable { mutableStateOf(false) }
    var manualCall by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<Notice?>(null) }
    var pickTarget by rememberSaveable { mutableStateOf("") }
    var pickStation by rememberSaveable { mutableStateOf("") }
    var exportPath by rememberSaveable { mutableStateOf("") }
    var exportName by rememberSaveable { mutableStateOf("") }
    var tips by rememberSaveable { mutableStateOf(true) }
    val demo=model.demoActive
    // With the keyboard up there is room for the conversation and the entry
    // field and little else: the demo's tips step aside, or the field is
    // squeezed and the system shoves the whole page upward. The destinations
    // stay: a draft is meant to follow the operator from one view to another.
    val typing=WindowInsets.isImeVisible
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri:Uri?->
        if(uri!=null)model.importFile(uri,pickTarget,pickStation)
    }
    // Android's photo picker: no permission, and whatever Android can read.
    val picturePicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri:Uri?->
        if(uri!=null)model.pickPicture(uri,pickTarget,pickStation)
    }
    fun pickPicture(target:String) {
        pickTarget=target;pickStation=model.settings.call
        picturePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    var attach by remember { mutableStateOf(false) }
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri:Uri?->
        if(uri!=null)model.exportFile(exportPath,exportName,uri)
    }
    // Counted, so Settings looks again at what Android allows once the operator has answered.
    var asked by remember { mutableStateOf(0) }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked++ }
    fun pickFile(target:String) { pickTarget=target;pickStation=model.settings.call;picker.launch(arrayOf("*/*")) }
    fun saveFile(row:TransferRow) { exportPath=row.path;exportName=row.name;saver.launch(row.name) }
    val chat=view=="Conversations"||view=="Field desk"
    // A notification's tap lands on what it was about.
    LaunchedEffect(model.openRequest) {
        when(model.openRequest) {
            "Ragchew" -> { view="Conversations";thread="Ragchew";returnTo=null;radio=false;about=false;notice=null }
            "Broadcast" -> { view="Conversations";thread="Broadcast";returnTo=null;radio=false;about=false;notice=null }
            "Files" -> { view="Files";returnTo=null;radio=false;about=false;notice=null }
        }
        model.openRequest=null
    }
    fun openSession() { returnTo=view;view="Conversations";thread="Ragchew" }
    BackHandler(enabled=returnTo!=null && !radio && picked==null) { view=returnTo!!;returnTo=null }
    Scaffold(
        modifier=Modifier.imePadding(),
        topBar={ Column {
            // Demo mode says so across the very top, status bar and all.
            if(demo) DemoBanner(onClick={radio=true})
            TopAppBar(windowInsets=if(demo)WindowInsets(0,0,0,0) else TopAppBarDefaults.windowInsets,title={Text(view)},navigationIcon={if(returnTo!=null)IconButton(onClick={view=returnTo!!;returnTo=null}){Icon(Icons.Default.ArrowBack,"Back to $returnTo")}},actions={
                IconButton(onClick={radio=true}) { Icon(Icons.Default.Settings,"Settings") }
            })
            Surface(color=MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Text(model.settings.call.ifEmpty { "Set up station" },style=MaterialTheme.typography.labelMedium)
                    // Always laid out, and only seen while transmitting: a badge that
                    // came and went would make the bar taller and nudge everything under it.
                    Surface(color=Color(0xffc62828),shape=MaterialTheme.shapes.small,
                        modifier=Modifier.alpha(if(state.ptt)1f else 0f).then(if(state.ptt)Modifier else Modifier.clearAndSetSemantics { })) {
                        Text("TX",color=Color.White,modifier=Modifier.padding(horizontal=10.dp,vertical=2.dp),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium)
                    }
                    Text(state.connection,style=MaterialTheme.typography.labelMedium)
                }
            }
        } },
        bottomBar={ NavigationBar {
            listOf("Stations" to Icons.Default.Person,"Conversations" to Icons.Default.Email,"Field desk" to Icons.Default.Build,"Files" to Icons.Default.Share).forEach { (name,icon) ->
                NavigationBarItem(selected=view==name,onClick={view=name;returnTo=null},icon={Icon(icon,contentDescription=null)},label={Text(name,maxLines=1,style=MaterialTheme.typography.labelSmall)})
            }
        } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(state.error.isNotEmpty()) Text(state.error,Modifier.fillMaxWidth().padding(12.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(demo&&tips&&!typing) DemoBubble(demoTip(state,model.settings.call),onSuggestion={ tip->
                // The suggested line goes where it belongs; the operator still presses Send.
                if(tip.thread=="Ragchew")sessionDraft=tip.suggestion else broadcastDraft=tip.suggestion
                thread=tip.thread;returnTo=null;if(!chat)view="Conversations"
            },onHide={tips=false})
            if(view=="Stations") {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(state.active) item {
                        Surface(onClick={openSession()},color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.large) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("Ragchewing with ${state.peer}",fontWeight=FontWeight.Medium)
                                Text(state.sessionStatus,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Text("On the net",style=MaterialTheme.typography.titleMedium)
                        // A roll call asks every station to check in: it fills this list,
                        // so it is asked for here. Its row and the answers show in Broadcast.
                        Row {
                            TextButton(onClick={model.action("checkin")},enabled=state.ready) { Text("Roll call") }
                            TextButton(onClick={manualCall=true},enabled=state.ready&&state.dataUp&&!state.active) { Text("Call station") }
                        }
                    } }
                    // Not connected, the tip is also the way to Settings.
                    if(state.stations.isEmpty())item {
                        if(state.ready) Text("Stations will appear as you hear traffic on D2G.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text("Connect to your Data2G host to hear stations.",color=MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier=Modifier.fillMaxWidth().clickable { radio=true })
                    }
                    items(state.stations,key={it.call}) { station ->
                        ListItem(modifier=Modifier.clickable { picked=station },headlineContent={Text(station.call)},supportingContent={Text("${station.status}\n${station.where}")},trailingContent={Column(horizontalAlignment=Alignment.End){Text(station.snr,style=MaterialTheme.typography.labelMedium);Text(station.heard,style=MaterialTheme.typography.labelSmall)}})
                    }
                }
            } else if(chat) {
                if(view=="Field desk") FieldStatus(state)
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=thread=="Broadcast",onClick={thread="Broadcast"},label={Text("Broadcast")})
                    FilterChip(selected=thread=="Ragchew",onClick={thread="Ragchew"},label={Text(if(state.active)state.peer else "Ragchew")})
                }
                state.files.filter { it.live && if(thread=="Broadcast")it.via=="Broadcast" else it.via=="Session"&&it.peer==state.peer }
                    .take(2).forEach { file->
                        AssistChip(onClick={view="Files";returnTo=null},modifier=Modifier.padding(horizontal=16.dp),
                            label={Text("${file.name} · ${file.state}",maxLines=1)})
                    }
                if(thread=="Ragchew") {
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(state.sessionStatus.ifEmpty { "Choose a station to start a ragchew." },Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                        if(state.active)TextButton(onClick={leave=true}) { Text("Leave") }
                        else TextButton(onClick={returnTo=null;view="Stations"}) { Text("Stations") }
                    }
                }
                val rows=if(thread=="Broadcast")state.broadcast else state.session
                val listState=rememberLazyListState()
                LaunchedEffect(thread,rows.lastOrNull()?.id) { if(rows.isNotEmpty())listState.scrollToItem(rows.lastIndex) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(if(view=="Field desk")8.dp else 16.dp)) {
                    if(rows.isEmpty())item { Text(if(thread=="Broadcast")"Broadcast messages will appear here. @CALL addresses a station on the public net." else "Session history appears here when you call a station.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(rows,key={it.id}) { row -> MessageRow(row,view=="Field desk",model,state.files) { view="Files";returnTo=null } }
                }
                val canSend=state.ready && (thread=="Broadcast"||state.phase==2)
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box {
                        IconButton(enabled=state.ready&&(thread=="Broadcast"||state.phase==2),onClick={attach=true}) { Icon(Icons.Default.Add,"Attach a picture or a file") }
                        DropdownMenu(expanded=attach,onDismissRequest={attach=false}) {
                            val target=if(thread=="Broadcast")"" else state.peer
                            DropdownMenuItem(text={Text("Picture")},onClick={attach=false;pickPicture(target)})
                            DropdownMenuItem(text={Text("File")},onClick={attach=false;pickFile(target)})
                        }
                    }
                    OutlinedTextField(value=if(thread=="Broadcast")broadcastDraft else sessionDraft,
                        onValueChange={if(it.toByteArray().size<=2000){if(thread=="Broadcast")broadcastDraft=it else sessionDraft=it}},
                        modifier=Modifier.weight(1f),maxLines=4,label={Text(if(thread=="Broadcast")"To everyone on D2G" else "To ${state.peer.ifEmpty { "station" }}")})
                    val draft=if(thread=="Broadcast")broadcastDraft else sessionDraft
                    FilledIconButton(enabled=canSend&&draft.isNotBlank(),onClick={
                        val sendingThread=thread
                        model.action(if(sendingThread=="Broadcast")"broadcast" else "session",draft) { ok ->
                            if(ok) { if(sendingThread=="Broadcast"&&broadcastDraft==draft)broadcastDraft="" else if(sendingThread=="Ragchew"&&sessionDraft==draft)sessionDraft="" }
                        }
                    }) { Icon(Icons.Default.Send,"Send message") }
                }
            } else {
                FilesPage(model,onPick={pickFile(it)},onSave={saveFile(it)})
            }
        }
    }
    // Material's sheet falls back to its half-open stop whenever its content
    // changes height, as the Demo mode switch makes it: this form has no
    // half-open stop to fall back to.
    if(radio) ModalBottomSheet(onDismissRequest={radio=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        HostControls(model,onConnected={
            radio=false;tips=true
            if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        },onAbout={radio=false;about=true},onPrivacy={notice=Notice.Privacy},asked=asked,
            onAskNotifications={ if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) })
    }
    model.pictureDraft?.let { PictureSheet(model,it) }
    if(about) AboutRagtime(onNotice={notice=it}) { about=false }
    notice?.let { NoticeSheet(it) { notice=null } }
    if(picked!=null) {
        val station=picked!!
        ModalBottomSheet(onDismissRequest={picked=null}) {
            Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(station.call,style=MaterialTheme.typography.headlineSmall)
                Text("${station.status}\n${station.where}\n${station.snr} · heard ${station.heard}")
                Button(enabled=state.ready&&state.dataUp&&(!state.active||state.peer==station.call),onClick={
                    if(state.active){picked=null;openSession()}
                    else model.action("call",station.call) { ok -> if(ok){picked=null;openSession()} }
                }) { Text(if(state.active&&state.peer==station.call)"Open ragchew" else "Call ${station.call}") }
                OutlinedButton(onClick={broadcastDraft="@${station.call} ";returnTo=view;view="Conversations";thread="Broadcast";picked=null}) { Text("Message on Broadcast") }
                if(state.active&&state.peer!=station.call)Text("Leave your current ragchew before calling another station.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    if(leave) AlertDialog(onDismissRequest={leave=false},title={Text("Leave ${state.peer}?")},text={Text("Finish queued session traffic before leaving, or leave immediately.")},confirmButton={TextButton(onClick={model.action("leave");leave=false}){Text("Finish & leave")}},dismissButton={TextButton(onClick={model.action("abort");leave=false}){Text("Leave now")}})
    if(manualCall) CallDialog(onDismiss={manualCall=false},onCall={call->model.action("call",call){ok->if(ok){manualCall=false;openSession()}}})
}

@Composable
private fun FieldStatus(state: RadioSnapshot) {
    Surface(color=MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(state.mode.ifEmpty { "Waiting for host mode" },style=MaterialTheme.typography.titleLarge)
            Text(if(state.ptt)"TX on" else state.channel.ifEmpty { "Host not ready" })
            Text("${if(state.narrow)"500 Hz modes" else "2.3 kHz modes"} · ${if(state.paused)"Sending paused" else "Sending enabled"}",style=MaterialTheme.typography.labelMedium)
            Text("Dial frequency is not available from this host connection.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MessageRow(row: ChatRow, compact: Boolean, model: RadioModel, files: List<TransferRow>, onFile:()->Unit) {
    if(row.kind==2) { HorizontalDivider();return }
    val clock=remember { SimpleDateFormat("HH:mm",Locale.ROOT).apply { timeZone=TimeZone.getTimeZone("UTC") } }
    Surface(color=if(row.ours&&!compact)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(if(compact)4.dp else 12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(row.from,style=MaterialTheme.typography.labelLarge)
                Text(clock.format(Date(row.time))+" UTC",style=MaterialTheme.typography.labelSmall)
            }
            Text((if(row.to.isNotEmpty())"@${row.to} " else "")+row.text)
            // An inline picture, under its line: sharper each time more of it has come.
            if(row.pictureSource.isNotEmpty()) InlinePicture(model,row.pictureSource,
                files.firstOrNull { it.key==row.file }?.done ?: 1L,"Picture from ${row.from}",maxWidth=if(compact)160 else 280)
            if(row.kind==1)TextButton(onClick=onFile) { Text("View in Files") }
            if(row.state.isNotEmpty())Text(row.state,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HostControls(model: RadioModel, onConnected: () -> Unit, onAbout:()->Unit, onPrivacy:()->Unit, asked:Int, onAskNotifications:()->Unit) {
    val state=model.snapshot
    var host by rememberSaveable { mutableStateOf(model.settings.host) }
    var call by rememberSaveable { mutableStateOf(model.settings.call) }
    var locator by rememberSaveable { mutableStateOf(model.settings.locator) }
    var kiss by rememberSaveable { mutableStateOf(model.settings.kiss.toString()) }
    var command by rememberSaveable { mutableStateOf(model.settings.command.toString()) }
    var error by remember { mutableStateOf("") }
    var demoError by remember { mutableStateOf("") }
    // The switch shows what is so: on while the demo runs, however it was
    // started or ended (the notification can end it too).
    var demo by rememberSaveable { mutableStateOf(model.demoActive) }
    LaunchedEffect(model.demoActive) { demo=model.demoActive }
    LazyColumn(Modifier.fillMaxWidth().imePadding(),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { Text("Settings",style=MaterialTheme.typography.headlineSmall) }
        item {
            Text("Ragtime Android ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=onAbout) { Text("About Ragtime & release notes") }
        }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(call,{call=it},Modifier.weight(1f),label={Text("Callsign")},singleLine=true)
            OutlinedTextField(locator,{locator=it},Modifier.weight(1f),label={Text("Locator (optional)")},singleLine=true)
        } }
        // Said where the operator types them, not only in a policy.
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Your callsign and locator go out with everything you send. Anyone listening can receive it.",Modifier.weight(1f),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick=onPrivacy) { Text("Privacy") }
        } }
        item { OutlinedTextField(host,{host=it},Modifier.fillMaxWidth(),label={Text("Host name or IP address")},singleLine=true,enabled=!demo,
            // Data2G on this phone: its address and the ports it listens on unless told otherwise.
            trailingIcon={TextButton(enabled=!demo,onClick={
                host=HostSettings.LOCALHOST;kiss=HostSettings.LOCAL_KISS.toString();command=HostSettings.LOCAL_COMMAND.toString()
            }) { Text("localhost") }}) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(kiss,{kiss=it},Modifier.weight(1f),label={Text("KISS port")},singleLine=true,enabled=!demo,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            OutlinedTextField(command,{command=it},Modifier.weight(1f),label={Text("Command port")},singleLine=true,enabled=!demo,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        } }
        if(error.isNotEmpty())item { Text(error,color=MaterialTheme.colorScheme.error) }
        // Connect applies the callsign, locator, host and ports above it and
        // connects to that modem, so it sits right under them.
        if(!demo)item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick={try { model.connect(HostSettings(host,call,locator,kiss.toIntOrNull()?:0,command.toIntOrNull()?:0,false));onConnected() }catch(e:IllegalArgumentException){error=e.message?:"Check settings"}}) { Text("Connect") }
            OutlinedButton(onClick={model.disconnect()},enabled=state.connection!="Disconnected") { Text("Disconnect") }
        } }
        // Calls and messages while Ragtime is out of sight. Android has the last
        // word on whether any notification shows, so say when it says no.
        item {
            val context=LocalContext.current
            val allowed=remember(asked,model.alertsOn) { context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(end=12.dp)) {
                    Text("Notify me of calls and messages")
                    Text(if(model.alertsOn&&!allowed)"Android is blocking Ragtime's notifications." else "When a station calls you, writes to you or offers a file while Ragtime is in the background.",
                        style=MaterialTheme.typography.bodySmall,color=if(model.alertsOn&&!allowed)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if(model.alertsOn&&!allowed)TextButton(contentPadding=PaddingValues(0.dp),onClick={
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))
                    }) { Text("Open Android's notification settings") }
                }
                Switch(checked=model.alertsOn,modifier=Modifier.testTag("alerts-switch"),onCheckedChange={ on->
                    model.setAlerts(on)
                    if(on)onAskNotifications()
                })
            }
        }
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text("500 Hz modes only");Switch(checked=state.narrow,onCheckedChange={model.action("narrow",it.toString())},enabled=state.ready)
        } }
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Pause sending");Switch(checked=state.paused,onCheckedChange={model.action("pause",it.toString())},enabled=state.ready)
        } }
        // Last, above the note: it is for trying Ragtime out, not for every day.
        // The switch is the demo: on starts it, off ends it, and no button
        // repeats either. Not while in a ragchew on the air, which a slip of
        // the thumb would otherwise leave.
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            val onTheAir=state.active&&!model.demoActive
            Column(Modifier.weight(1f).padding(end=12.dp)) {
                Text("Demo mode")
                Text(if(onTheAir)"Leave your ragchew before trying the demo." else "Practise with simulated stations inside the app. No modem is used and nothing is transmitted.",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=demo,enabled=!onTheAir,modifier=Modifier.testTag("demo-switch"),onCheckedChange={ on->
                error="";demoError=""
                if(on) try {
                    model.connect(HostSettings(host,call,locator,kiss.toIntOrNull()?:0,command.toIntOrNull()?:0,true))
                    demo=true;onConnected()
                } catch(e:IllegalArgumentException) { demoError=e.message?:"Check settings" }
                else { demo=false;model.demoOff() }
            })
        } }
        // Said beside the switch: the fields it complains of are a screen away.
        if(demoError.isNotEmpty())item { Text(demoError,color=MaterialTheme.colorScheme.error) }
        item { Text(if(demo)"Demo mode keeps its own history, apart from your station's, and starts fresh each time." else "The modem runs on your separate Data2G host. Ragtime keeps running when you switch to other apps. If you have notifications enabled, you can tap the notification to reopen Ragtime, or tap Disconnect on the notification to end the connection.",style=MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CallDialog(onDismiss: () -> Unit, onCall: (String) -> Unit) {
    var call by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Call station")},text={OutlinedTextField(value=call,onValueChange={call=it},singleLine=true,label={Text("Callsign")})},confirmButton={TextButton(onClick={onCall(call.trim())},enabled=call.isNotBlank()){Text("Call")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
