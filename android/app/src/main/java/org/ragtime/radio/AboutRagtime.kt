package org.ragtime.radio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutRagtime(onNotice:(Notice)->Unit,onDismiss:()->Unit) {
    val context=LocalContext.current
    val notes=remember { noticeParts(runCatching { context.assets.open("release-notes.md").bufferedReader().use { it.readText() } }.getOrDefault("")) }
    ModalBottomSheet(onDismissRequest=onDismiss) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxWidth().testTag("about-list"),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=40.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
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
                // docs/ANDROID_RELEASE_NOTES.md, copied in when the APK is built.
                items(notes) { part->
                    when(part.kind) {
                        NoticePart.Kind.Title -> Text("Release notes",style=MaterialTheme.typography.titleLarge)
                        NoticePart.Kind.Heading -> Text(part.text,style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=8.dp))
                        NoticePart.Kind.Bullet -> Row { Text("•  ");Text(part.text) }
                        else -> Text(part.text)
                    }
                }
            }
        }
    }
}
