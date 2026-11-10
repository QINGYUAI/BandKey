package com.bandkey.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 局域网备用通道：
 * - UDP 47322 广播发现电脑；
 * - TCP 47321 发送 NDJSON，协议与 WebSocket/BLE 完全一致。
 */
class LanTransport(
    private val preferredHost: String? = null,
    private val discoveryTimeoutMs: Int = 3000
) : Transport {

    companion object {
        const val PC_TCP_PORT = 47321
        const val PC_UDP_PORT = 47322
    }

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: OutputStreamWriter? = null

    @Volatile
    private var closed = false

    private var listener: ((String) -> Unit)? = null
    private var readerThread: Thread? = null
    private val writeMutex = Mutex()

    override fun setListener(listener: (String) -> Unit) {
        this.listener = listener
    }

    override suspend fun open() {
        if (socket != null) {
            return
        }

        withContext(Dispatchers.IO) {
            closed = false
            val host = preferredHost?.takeIf { it.isNotBlank() } ?: discoverHost()
            val client = Socket()
            client.connect(InetSocketAddress(host, PC_TCP_PORT), 5000)
            client.tcpNoDelay = true
            socket = client
            writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)

            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            readerThread = Thread({
                try {
                    while (!closed) {
                        val line = reader.readLine() ?: break
                        if (line.isNotEmpty()) {
                            listener?.invoke(line)
                        }
                    }
                } catch (_: Exception) {
                    // 连接关闭或网络中断
                } finally {
                    try {
                        client.close()
                    } catch (_: Exception) {
                        // 忽略关闭异常
                    }
                }
            }, "bandkey-lan-reader").apply {
                isDaemon = true
                start()
            }
        }
    }

    override suspend fun send(text: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val output = writer ?: throw TransportException("局域网通道未连接")
                try {
                    output.write(text)
                    output.write("\n")
                    output.flush()
                } catch (e: Exception) {
                    throw TransportException("局域网发送失败：${e.message}", e)
                }
            }
        }
    }

    override fun close() {
        closed = true
        try {
            socket?.close()
        } catch (_: Exception) {
            // 忽略关闭异常
        }
        socket = null
        writer = null
        readerThread?.interrupt()
        readerThread = null
    }

    private fun discoverHost(): String {
        DatagramSocket().use { udp ->
            udp.broadcast = true
            udp.soTimeout = discoveryTimeoutMs
            val message = "BANDKEY_DISCOVER".toByteArray(Charsets.UTF_8)
            val request = DatagramPacket(
                message,
                message.size,
                InetAddress.getByName("255.255.255.255"),
                PC_UDP_PORT
            )

            try {
                udp.send(request)
                val buffer = ByteArray(2048)
                val response = DatagramPacket(buffer, buffer.size)
                udp.receive(response)
                val source = response.address?.hostAddress
                if (!source.isNullOrBlank()) {
                    return source
                }
            } catch (e: Exception) {
                throw TransportException("局域网发现失败，请确认手机与电脑在同一网络", e)
            }
        }

        throw TransportException("局域网发现失败：电脑没有响应")
    }
}
