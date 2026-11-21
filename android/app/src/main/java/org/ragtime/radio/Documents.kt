package org.ragtime.radio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

internal object Documents {
    const val MAX_BYTES=300*1024
    fun import(context:Context,uri:Uri,stationDirectory:File):File {
        val resolver=context.contentResolver
        // A provider that reports no display name still has a last path segment.
        var name=uri.lastPathSegment ?: "file"
        resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c->
            if(c.moveToFirst()) {
                val size=c.getColumnIndex(OpenableColumns.SIZE)
                if(size>=0&&!c.isNull(size)&&c.getLong(size)>MAX_BYTES)error("Files can be at most 300 KB")
                val column=c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if(column>=0)name=c.getString(column) ?: "file"
            }
        }
        name=name.substringAfterLast('/').substringAfterLast('\\').map { if(it.isISOControl()||it in ":*?\"<>|")'_' else it }.joinToString("").trim('.',' ')
        if(name.isBlank())name="file"
        while(name.toByteArray(Charsets.UTF_8).size>180)name=name.substring(0,name.offsetByCodePoints(name.length,-1))
        val directory=File(stationDirectory,"outgoing/"+UUID.randomUUID()).apply { mkdirs() }
        val file=File(directory,name)
        try {
            resolver.openInputStream(uri)?.use { input->file.outputStream().use { output->
                val buffer=ByteArray(8192);var total=0
                while(true) {
                    val n=input.read(buffer);if(n<0)break
                    total+=n;if(total>MAX_BYTES)error("Files can be at most 300 KB")
                    output.write(buffer,0,n)
                }
                if(total==0)error("Choose a file that is not empty")
            } } ?: error("Could not open the selected file")
            return file
        } catch(e:Exception) { directory.deleteRecursively();throw e }
    }
    fun export(context:Context,source:File,uri:Uri) {
        val relative=source.canonicalFile.relativeToOrNull(context.filesDir.canonicalFile)
        require(relative!=null&&relative.path!=".."&&!relative.path.startsWith("../")&&source.isFile) { "This saved file is no longer available" }
        context.contentResolver.openOutputStream(uri,"w")?.use { output->source.inputStream().use { it.copyTo(output) } }
            ?: error("Could not open the selected destination")
    }
}
