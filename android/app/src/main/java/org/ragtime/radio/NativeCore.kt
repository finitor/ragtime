package org.ragtime.radio

/** All calls belong to the connection worker; no callbacks or native threads. */
internal object NativeCore {
    init { System.loadLibrary("ragtime_jni") }
    external fun create(call: ByteArray, locator: ByteArray, path: ByteArray, version:ByteArray, now: Long): Long
    external fun invoke(id: Long, operation: Int, input: ByteArray, now: Long): ByteArray
    external fun destroy(id: Long)
    /** Demo mode's net of made-up stations: its KISS and command ports on loopback. */
    external fun demoStart(folder: ByteArray, call: ByteArray): IntArray
    external fun demoStop()
    /** Inline pictures (platform/android/AndroidPictures.h). They hold no client and no lock. */
    external fun picturesAvailable(): Boolean
    /** RGBA pixels, shrunk to maxSide and coded as a JPEG 2000 codestream of at most maxBytes. */
    external fun encodePicture(rgba: ByteArray, width: Int, height: Int, maxSide: Int, maxBytes: Int): ByteArray
    /** Width and height (4 bytes each, little-endian), then RGBA rows; empty if there is no picture yet. */
    external fun decodePicture(coded: ByteArray, maxSide: Int): ByteArray
}

internal class Engine(call: String, locator: String, directory: String) : AutoCloseable {
    private var id = NativeCore.create(call.toByteArray(), locator.toByteArray(), directory.toByteArray(),
        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})".toByteArray(), System.currentTimeMillis())
    fun invoke(operation: Int, input: ByteArray = byteArrayOf()): ByteArray {
        check(id != 0L) { "Engine is closed" }
        return NativeCore.invoke(id, operation, input, System.currentTimeMillis())
    }
    /** A picture's bytes as they stand: "s:ID", "b:ID" or "p:PATH" (AndroidClient::pictureBytes). */
    fun pictureBytes(source: String) = invoke(31, source.toByteArray())
    fun action(name: String, value: String = "") = invoke(40, "$name\u0000$value".toByteArray()).firstOrNull() == 1.toByte()
    override fun close() { if (id != 0L) { NativeCore.destroy(id); id = 0 } }
}
