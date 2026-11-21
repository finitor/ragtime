package org.ragtime.radio

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

internal data class HostSettings(val host: String = "", val call: String = "", val locator: String = "", val kiss: Int = 8100, val command: Int = 8300,
                                 val demo: Boolean = false) {
    fun validated(): HostSettings {
        // Demo mode needs no host and will do without a callsign.
        val normalized = call.trim().uppercase(java.util.Locale.ROOT).ifEmpty { if(demo) DEMO_CALL else "" }
        require(normalized.matches(Regex("[A-Z0-9/-]{1,10}"))) { "Enter a callsign of 1–10 letters, digits, / or -" }
        val square = locator.trim().uppercase(java.util.Locale.ROOT)
        require(square.isEmpty() || square.matches(Regex("[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2})?)?"))) { "Enter a Maidenhead locator, or leave it blank" }
        if(demo) return copy(host = host.trim(), call = normalized, locator = square)
        require(host.isNotBlank() && !host.contains(Regex("[\\s/]"))) { "Enter a host name or IP address" }
        require(kiss in 1..65535 && command in 1..65534 && kiss != command && kiss != command + 1) { "Choose distinct KISS, command and session ports (command + 1)" }
        return copy(host = host.trim(), call = normalized, locator = square)
    }
    companion object {
        const val DEMO_CALL = "DEMO"
        /** A Data2G TNC on this phone, as it listens unless told otherwise. */
        const val LOCALHOST = "127.0.0.1"
        const val LOCAL_KISS = 8100
        const val LOCAL_COMMAND = 8300
    }
}

/** Nonblocking sockets, framing and timers all run on the model's single worker. */
internal class HostTransport(private val settings: HostSettings, private val engine: Engine) : AutoCloseable {
    private val selector = Selector.open()
    private data class Port(val index: Int, val channel: SocketChannel, var connected: Boolean, val started: Long,
                            val pending: ArrayDeque<ByteBuffer> = ArrayDeque())
    private val ports = arrayOfNulls<Port>(3)
    private var announced = false
    private var dataRetry = 0L
    private val buffer = ByteBuffer.allocate(8192)
    var ready = false; private set

    init {
        try {
            open(0);open(1)
            try { open(2) } catch (_: IOException) { dataRetry=System.currentTimeMillis()+5000 }
        }
        catch (e: Exception) { close(); throw e }
    }
    private fun open(index: Int) {
        val channel = SocketChannel.open()
        try {
            channel.configureBlocking(false)
            channel.socket().tcpNoDelay = true
            val number = when(index) { 0 -> settings.kiss; 1 -> settings.command; else -> settings.command + 1 }
            val connected = channel.connect(InetSocketAddress(settings.host, number))
            val port = Port(index, channel, connected, System.currentTimeMillis())
            ports[index] = port
            channel.register(selector, if(connected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT, port)
        } catch(e: Exception) { channel.close(); throw e }
    }
    fun step() {
        val now = System.currentTimeMillis()
        if(ports[2] == null && now >= dataRetry) {
            dataRetry = now + 5000
            try { open(2) } catch (_: IOException) { /* Broadcast remains usable. */ }
        }
        selector.selectNow()
        val iterator = selector.selectedKeys().iterator()
        while(iterator.hasNext()) {
            val key = iterator.next(); iterator.remove()
            if(!key.isValid) continue
            val port = key.attachment() as Port
            try {
                if(key.isConnectable && port.channel.finishConnect()) {
                    port.connected = true
                    key.interestOps(SelectionKey.OP_READ)
                    if(port.index == 2) engine.invoke(2)
                }
                if(port.connected && key.isReadable) {
                    var reads = 0
                    while(reads++ < 32) {
                        buffer.clear()
                        val count = port.channel.read(buffer)
                        if(count < 0) throw IOException("Host closed ${listOf("KISS", "command", "session")[port.index]} port")
                        if(count == 0) break
                        engine.invoke(10 + port.index, buffer.array().copyOf(count))
                    }
                }
            } catch(e: IOException) {
                if(port.index != 2) throw e
                dropData()
            }
        }
        for(port in ports.filterNotNull()) {
            if(!port.connected && now - port.started > 10000) {
                if(port.index == 2) dropData() else throw IOException("Host connection timed out")
            }
        }
        if(!announced && ports[0]?.connected == true && ports[1]?.connected == true) {
            announced = true
            engine.invoke(0)
            if(ports[2]?.connected == true) engine.invoke(2)
        }
        if(!announced) return
        engine.invoke(4)
        for(port in ports.filterNotNull().filter { it.connected }) {
            val output = engine.invoke(20 + port.index)
            if(output.isNotEmpty()) port.pending.add(ByteBuffer.wrap(output))
            if(port.pending.sumOf { it.remaining() } > 1024 * 1024) throw IOException("Host is not reading queued output")
            try {
                while(port.pending.isNotEmpty()) {
                    val head = port.pending.first()
                    port.channel.write(head)
                    if(head.hasRemaining()) break
                    port.pending.removeFirst()
                }
            } catch(e: IOException) {
                if(port.index != 2) throw e
                dropData()
            }
        }
        ready = true
    }
    /**
     * Hands the host what the engine still has to say before the sockets
     * close: a Leave must reach the modem, or its ARQ link outlives us.
     */
    fun flush(timeoutMs: Long = 500) {
        if(!announced) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while(true) {
            var waiting = false
            for(port in ports.filterNotNull().filter { it.connected }) {
                val output = engine.invoke(20 + port.index)
                if(output.isNotEmpty()) port.pending.add(ByteBuffer.wrap(output))
                while(port.pending.isNotEmpty()) {
                    val head = port.pending.first()
                    port.channel.write(head)
                    if(head.hasRemaining()) { waiting = true; break }
                    port.pending.removeFirst()
                }
            }
            if(!waiting || System.currentTimeMillis() >= deadline) return
            Thread.sleep(10)
        }
    }
    private fun dropData() {
        ports[2]?.channel?.close(); ports[2] = null
        dataRetry = System.currentTimeMillis() + 5000
        engine.invoke(3)
    }
    override fun close() {
        for(port in ports.filterNotNull()) try { port.channel.close() } catch (_: IOException) {}
        selector.close()
        ready = false
    }
}
