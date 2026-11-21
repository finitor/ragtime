package org.ragtime.radio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.nio.ByteBuffer

/**
 * Inline pictures (docs/ANDROID.md, "Pictures"). On the air a picture is a
 * JPEG 2000 codestream, or a Cool-chic stream from a desktop; the engines'
 * own codecs read and write those (NativeCore). The picture an operator
 * picks is read by Android, whatever Android can read: JPEG, PNG, WebP,
 * HEIF, GIF, BMP.
 */
internal object Pictures {
    /** What comes from the air is decoded up to this size either way. */
    const val MAX_SHOWN = 2048
    /** A picked picture is read no bigger than this: enough to shrink from. */
    const val MAX_PICKED = 1600
    val SIDES = listOf(320, 640, 1024)
    val BUDGETS = listOf(4, 8, 16, 32)       // KB
    const val DEFAULT_SIDE = 640
    const val DEFAULT_BUDGET = 8

    /** Coded bytes, or as many as have come, to a bitmap; null when there is no picture in them yet. */
    fun decode(coded: ByteArray): Bitmap? {
        if(coded.isEmpty()) return null
        val out = NativeCore.decodePicture(coded, MAX_SHOWN)
        if(out.size < 8) return null
        val header = ByteBuffer.wrap(out, 0, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val width = header.int; val height = header.int
        if(width <= 0 || height <= 0 || out.size != 8 + width * height * 4) return null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(out, 8, out.size - 8)) }
    }

    /** A picked picture, the way up it says, no bigger than MAX_PICKED, on white where it is see-through. */
    fun read(context: Context, uri: Uri): Bitmap {
        val picked: Bitmap = if(Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if(longest > MAX_PICKED) decoder.setTargetSize(maxOf(1, info.size.width * MAX_PICKED / longest), maxOf(1, info.size.height * MAX_PICKED / longest))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while(maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PICKED) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: error("Could not open the picture")
        }
        return opaque(picked)
    }

    fun opaque(source: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        flat.eraseColor(AndroidColor.WHITE)
        Canvas(flat).drawBitmap(source, 0f, 0f, null)
        return flat
    }

    /** RGBA rows from the top, as NativeCore.encodePicture takes them. */
    fun pixels(bitmap: Bitmap): ByteArray {
        val flat = if(bitmap.config == Bitmap.Config.ARGB_8888) bitmap else opaque(bitmap)
        return ByteArray(flat.width * flat.height * 4).also { flat.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
    }

    /** "IMG_2041.heic" goes as "IMG_2041.j2k": what is sent is a JPEG 2000 codestream. */
    fun sentName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment ?: "picture"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if(c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        }
        return sentName(name)
    }
    fun sentName(picked: String): String {
        val base = picked.substringAfterLast('/').substringBeforeLast('.', picked.substringAfterLast('/'))
            .map { if(it.isLetterOrDigit() || it in "-_ ") it else '_' }.joinToString("").trim().take(60)
        return (if(base.any { it.isLetterOrDigit() }) base else "picture") + ".j2k"
    }
}

/** A picture being made ready to send: what the far end will see, and how big it is. */
internal data class PictureDraft(val target: String, val station: String, val name: String,
    val side: Int = Pictures.DEFAULT_SIDE, val budget: Int = Pictures.DEFAULT_BUDGET,
    val preview: ImageBitmap? = null, val bytes: Int = 0, val busy: Boolean = true, val error: String = "")

/**
 * A picture among the messages or on a Files card. It asks for its bytes
 * again whenever more have come (stamp), so one still arriving sharpens as
 * it does; any first part of a picture is the whole picture, softer.
 */
@Composable
internal fun InlinePicture(model: RadioModel, source: String, stamp: Long, description: String, modifier: Modifier = Modifier, maxWidth: Int = 280) {
    if(source.isEmpty()) return
    LaunchedEffect(source, stamp) { model.wantPicture(source, stamp) }
    var open by remember { mutableStateOf(false) }
    val picture = model.pictures[source]
    if(picture == null) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = modifier.widthIn(max = maxWidth.dp)) {
            Text("Picture coming…", Modifier.padding(horizontal = 12.dp, vertical = 20.dp), style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Image(bitmap = picture, contentDescription = description, contentScale = ContentScale.Fit,
        modifier = modifier.widthIn(max = maxWidth.dp).aspectRatio(picture.width.toFloat() / picture.height.toFloat())
            .clip(RoundedCornerShape(12.dp)).clickable { open = true }.testTag("inline-picture"))
    if(open) PictureViewer(picture, description) { open = false }
}

/** The picture alone, on black: pinch to zoom, drag to move, tap to close. */
@Composable
internal fun PictureViewer(picture: ImageBitmap, description: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("picture-viewer").clickable(onClick = onDismiss)
            .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 8f)
                offset = if(scale == 1f) Offset.Zero else offset + pan
            } }, contentAlignment = Alignment.Center) {
            Image(bitmap = picture, contentDescription = description, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
        }
    }
}

/** The sheet between picking a picture and sending it: what will arrive, and at what cost in bytes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PictureSheet(model: RadioModel, draft: PictureDraft) {
    ModalBottomSheet(onDismissRequest = { model.cancelPicture() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if(draft.target.isEmpty()) "Broadcast a picture" else "Send a picture to ${draft.target}", style = MaterialTheme.typography.headlineSmall)
            Text(if(draft.target.isEmpty()) "Everyone on D2G receives it. It arrives blurred and sharpens as more of it comes."
                 else "It arrives blurred and sharpens as more of it comes.", style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp), contentAlignment = Alignment.Center) {
                val preview = draft.preview
                if(preview != null) Image(bitmap = preview, contentDescription = "The picture as it will arrive", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height.toFloat(), matchHeightConstraintsFirst = false)
                        .clip(RoundedCornerShape(12.dp)).testTag("picture-preview"))
                if(draft.busy) CircularProgressIndicator()
            }
            val preview = draft.preview
            Text(when {
                draft.error.isNotEmpty() -> draft.error
                preview != null -> "${preview.width} × ${preview.height}, ${"%.1f".format(draft.bytes / 1024.0)} KB as it will arrive"
                else -> "Reading the picture…"
            }, color = if(draft.error.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("Longest side", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for(side in Pictures.SIDES) FilterChip(selected = draft.side == side, onClick = { model.pictureOptions(side, draft.budget) }, label = { Text("$side") })
            }
            Text("Size on the air: more is sharper, and slower", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for(budget in Pictures.BUDGETS) FilterChip(selected = draft.budget == budget, onClick = { model.pictureOptions(draft.side, budget) }, label = { Text("$budget KB") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { model.sendPicture() }, enabled = !draft.busy && draft.preview != null && draft.error.isEmpty(), modifier = Modifier.testTag("picture-send")) {
                    Text(if(draft.target.isEmpty()) "Broadcast" else "Send")
                }
                OutlinedButton(onClick = { model.cancelPicture() }) { Text("Cancel") }
            }
        }
    }
}
