package org.ragtime.radio

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** What sits between Android's document picker and the engines. */
class DocumentsTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun importCopiesIntoTheStationFolderAndExportCopiesOut() {
        val work=File(context.cacheDir,"documents-${System.nanoTime()}").apply { mkdirs() }
        val station=File(context.filesDir,"stations/TESTDOC")
        try {
            val bytes=ByteArray(4096) { (it*7).toByte() }
            val picked=File(work,"field notes π.bin").apply { writeBytes(bytes) }
            val imported=Documents.import(context,Uri.fromFile(picked),station)
            assertEquals("field notes π.bin",imported.name)
            assertTrue(imported.canonicalPath.startsWith(File(station,"outgoing").canonicalPath))
            assertArrayEquals(bytes,imported.readBytes())

            val copy=File(work,"saved.bin")
            Documents.export(context,imported,Uri.fromFile(copy))
            assertArrayEquals(bytes,copy.readBytes())
            // Nothing outside the app's own files is ever exported.
            assertThrows(IllegalArgumentException::class.java) { Documents.export(context,picked,Uri.fromFile(File(work,"leak.bin"))) }

            val before=File(station,"outgoing").list()!!.size
            val large=File(work,"large.bin").apply { writeBytes(ByteArray(Documents.MAX_BYTES+1)) }
            assertThrows(IllegalStateException::class.java) { Documents.import(context,Uri.fromFile(large),station) }
            val empty=File(work,"empty.bin").apply { writeBytes(ByteArray(0)) }
            assertThrows(IllegalStateException::class.java) { Documents.import(context,Uri.fromFile(empty),station) }
            assertEquals("A refused file leaves nothing behind",before,File(station,"outgoing").list()!!.size)
            val exact=File(work,"exact.bin").apply { writeBytes(ByteArray(Documents.MAX_BYTES) { 1 }) }
            assertEquals(Documents.MAX_BYTES.toLong(),Documents.import(context,Uri.fromFile(exact),station).length())
        } finally { work.deleteRecursively();station.deleteRecursively() }
    }
}
