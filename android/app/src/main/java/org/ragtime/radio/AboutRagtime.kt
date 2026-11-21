package org.ragtime.radio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutRagtime(onNotice:(Notice)->Unit,onDismiss:()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxWidth(),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=40.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { Text("About Ragtime",style=MaterialTheme.typography.headlineSmall) }
                item { Text("Ragtime for Android ${BuildConfig.VERSION_NAME}\nBuild ${BuildConfig.VERSION_CODE}",style=MaterialTheme.typography.titleMedium) }
                item { Text("Chat and file transfer through a Data2G modem. Stations, Conversations, Field desk and Files share one connection.") }
                // The licences ask for their notices in the app, and Google asks every
                // app for its privacy text there too.
                item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={onNotice(Notice.Privacy)}) { Text("Privacy") }
                    OutlinedButton(onClick={onNotice(Notice.Licences)}) { Text("Open source licences") }
                } }
                item { HorizontalDivider() }
                item { Text("Release notes · 0.1.0",style=MaterialTheme.typography.titleLarge) }
                item { Text("November 20, 2026",style=MaterialTheme.typography.bodySmall) }
                items(listOf(
                    "Send and receive session files, with accept, decline, cancel, progress and retained partial files.",
                    "Broadcast files with digest verification and requests for missing pieces.",
                    "Choose documents using Android's file picker and save completed files to a location you choose. The size limit is 300 KB.",
                    "Keep the modem connection active while switching apps. Use the ongoing notification to return to Ragtime or disconnect.",
                    "Preserve transfer history and received files in app-private storage.",
                    "Show version and build numbers in Settings and About."
                    ,"Show a red TX indicator in every view while Data2G reports PTT on."
                    ,"Disconnect leaves an active ragchew at the modem, so the next Connect can call again."
                    ,"Demo mode in Settings: practise with simulated stations that check in, answer you, take a call and trade files. A guide suggests what to try; nothing is transmitted."
                    ,"Privacy and Open source licences are in About, and Privacy is in Settings too."
                    ,"The Demo mode switch starts the demo when turned on and ends it when turned off."
                    ,"Notifications while Ragtime is in the background: a station called you, wrote to you with @, said something in your ragchew, or offers you a file. Tap one to land on it. Settings has the switch."
                    ,"Roll call is on Stations, beside Call station, not in Settings."
                    ,"Settings: callsign and locator come first, and a localhost shortcut fills in Data2G running on this phone."
                )) { note->Text("• $note") }
                item { Text("Inline picture and voice playback are not included in this build.",style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
