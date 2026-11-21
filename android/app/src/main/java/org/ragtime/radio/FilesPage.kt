package org.ragtime.radio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun FilesPage(model:RadioModel,onPick:(String)->Unit,onSave:(TransferRow)->Unit) {
    val s=model.snapshot
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        Text("Send a file",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=12.dp))
        Text("Up to 300 KB. Broadcast is public; a session sends to its partner. Pictures go from the + beside a conversation's entry line.",style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={onPick("")},enabled=s.ready,modifier=Modifier.weight(1f)) { Text("Broadcast file") }
            Button(onClick={onPick(s.peer)},enabled=s.ready&&s.phase==2,modifier=Modifier.weight(1f)) {
                Text(if(s.phase==2)"To ${s.peer}" else "Call a station first")
            }
        }
        if(model.documentStatus.isNotEmpty())Text(model.documentStatus,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(bottom=8.dp))
        val live=s.files.filter { it.live||it.key.startsWith("k:") }.sortedByDescending { it.time }
        val done=s.files.filter { !it.live&&!it.key.startsWith("k:") }.sortedByDescending { it.time }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=16.dp)) {
            if(s.files.isEmpty())item { Text("No file transfers yet.",modifier=Modifier.padding(vertical=24.dp)) }
            if(live.isNotEmpty())item { Text("In progress & parts",style=MaterialTheme.typography.titleMedium) }
            items(live,key={it.key}) { row->TransferCard(model,row,s.ready,{action->model.action("file-$action",row.key)},onSave) }
            if(done.isNotEmpty())item { Text("Completed",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=12.dp)) }
            items(done,key={it.key}) { row->TransferCard(model,row,s.ready,{action->model.action("file-$action",row.key)},onSave) }
        }
    }
}

@Composable
private fun TransferCard(model:RadioModel,row:TransferRow,connected:Boolean,action:(String)->Unit,onSave:(TransferRow)->Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(row.name,fontWeight=FontWeight.Medium)
            Text("${row.via} · ${if(row.ours)"To" else "From"} ${row.peer}",style=MaterialTheme.typography.bodySmall)
            Text(row.state,style=MaterialTheme.typography.bodyMedium)
            if(row.pictureSource.isNotEmpty())InlinePicture(model,row.pictureSource,row.done,"Picture ${row.name}",maxWidth=200)
            if(row.size>0) {
                LinearProgressIndicator(progress={(row.done.toFloat()/row.size).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                Text("${row.done} / ${row.size} bytes",style=MaterialTheme.typography.labelSmall)
            }
            if(row.checked)Text("Verified complete",style=MaterialTheme.typography.labelSmall)
            if(row.via=="Broadcast"&&row.ours)Text("Broadcast receivers do not acknowledge files.",style=MaterialTheme.typography.labelSmall)
            if(row.key.startsWith("k:")&&!row.ours)Text("The sender can offer this file again to send the rest.",style=MaterialTheme.typography.bodySmall)
            if(row.error.isNotEmpty())Text(row.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(row.receive)Button(onClick={action("receive")},enabled=connected) { Text("Receive") }
                if(row.decline)TextButton(onClick={action("decline")}) { Text("Decline") }
                if(row.cancel)TextButton(onClick={action("cancel")}) { Text("Cancel") }
                if(row.resume)Button(onClick={action("resume")},enabled=connected) { Text("Send rest") }
                if(row.path.isNotEmpty())Button(onClick={onSave(row)}) { Text("Save a copy") }
                if(row.forget)TextButton(onClick={action("forget")}) { Text("Forget") }
            }
        }
    }
}
