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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What About shows besides the release notes. */
internal enum class Notice { Licences, Privacy }

/** One piece of a notice page: a heading, a paragraph, a bullet, or a licence's own text. */
internal data class NoticePart(val kind: Kind, val text: String) {
    enum class Kind { Title, Heading, Paragraph, Bullet, Legal }
}

/**
 * docs/ANDROID_PRIVACY.md is written in the little Markdown this reads:
 * "# " a title, "## " a heading, "- " a bullet, and paragraphs between
 * blank lines. Anything else is shown as it stands.
 */
internal fun noticeParts(markdown: String): List<NoticePart> {
    val parts=mutableListOf<NoticePart>()
    val paragraph=StringBuilder()
    fun flush() { if(paragraph.isNotEmpty()) { parts+=NoticePart(NoticePart.Kind.Paragraph,paragraph.toString());paragraph.clear() } }
    for(raw in markdown.lines()) {
        val line=raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("## ") -> { flush();parts+=NoticePart(NoticePart.Kind.Heading,line.drop(3)) }
            line.startsWith("# ") -> { flush();parts+=NoticePart(NoticePart.Kind.Title,line.drop(2)) }
            line.startsWith("- ") -> { flush();parts+=NoticePart(NoticePart.Kind.Bullet,line.drop(2)) }
            else -> { if(paragraph.isNotEmpty())paragraph.append(' ');paragraph.append(line) }
        }
    }
    flush()
    return parts
}

/** A licence's text, a paragraph to a part so the long ones scroll lazily. */
private fun legal(text: String)=text.trim('\n').split(Regex("\n\\s*\n")).map { NoticePart(NoticePart.Kind.Legal,it.trimEnd()) }

/**
 * The notices the licences ask for, from docs/ANDROID_LICENSES.md's audit:
 * Ragtime's own copyright line and MIT text, and the Apache License for the
 * libraries under it. Redo the audit when a dependency changes.
 */
internal fun licenceParts(ragtime: String, apache: String): List<NoticePart> = buildList {
    add(NoticePart(NoticePart.Kind.Title,"Open source licences"))
    add(NoticePart(NoticePart.Kind.Heading,"Ragtime"))
    add(NoticePart(NoticePart.Kind.Paragraph,"Ragtime for Android is Ragtime's own code throughout: the engines it shares with the desktop app, and this app around them. It is free software under the MIT License."))
    addAll(legal(ragtime))
    add(NoticePart(NoticePart.Kind.Heading,"Libraries under the Apache License 2.0"))
    add(NoticePart(NoticePart.Kind.Bullet,"AndroidX and Jetpack Compose, with Material 3 and the Material icons. The Android Open Source Project."))
    add(NoticePart(NoticePart.Kind.Bullet,"The Kotlin standard library and kotlinx.coroutines. JetBrains s.r.o. and the Kotlin contributors."))
    add(NoticePart(NoticePart.Kind.Bullet,"JetBrains annotations. JetBrains s.r.o."))
    add(NoticePart(NoticePart.Kind.Bullet,"Guava's ListenableFuture. The Guava Authors."))
    addAll(legal(apache))
    add(NoticePart(NoticePart.Kind.Heading,"Also inside"))
    add(NoticePart(NoticePart.Kind.Bullet,"The LLVM C++ runtime (libc++, libc++abi, libunwind and compiler-rt), from the Android NDK, under the Apache License 2.0 with LLVM Exceptions."))
    add(NoticePart(NoticePart.Kind.Bullet,"Coastlines, borders and lakes from Natural Earth, which are in the public domain."))
    add(NoticePart(NoticePart.Kind.Paragraph,"Ragtime does not contain Data2G. The modem is a separate program that you run yourself."))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoticeSheet(notice: Notice, onDismiss: () -> Unit) {
    val context=LocalContext.current
    val parts=remember(notice) {
        fun asset(name: String)=runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
        when(notice) {
            Notice.Privacy -> noticeParts(asset("privacy.md"))
            Notice.Licences -> licenceParts(asset("licenses/ragtime-mit.txt"),asset("licenses/apache-2.0.txt"))
        }
    }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxWidth().testTag("notice-list"),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=40.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(parts) { part->
                    when(part.kind) {
                        NoticePart.Kind.Title -> Text(part.text,style=MaterialTheme.typography.headlineSmall)
                        NoticePart.Kind.Heading -> Text(part.text,style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=10.dp))
                        NoticePart.Kind.Paragraph -> Text(part.text)
                        NoticePart.Kind.Bullet -> Row { Text("•  ");Text(part.text) }
                        NoticePart.Kind.Legal -> Text(part.text,fontFamily=FontFamily.Monospace,fontSize=11.sp,lineHeight=15.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
