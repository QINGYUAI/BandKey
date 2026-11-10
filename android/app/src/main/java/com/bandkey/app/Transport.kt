package com.bandkey.app

import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

interface Transport : Closeable {
    suspend fun open()
    suspend fun send(text: String)
    fun setListener(listener: (String) -> Unit)
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

class WebSocketTransport(private val url: String) : Transport {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var listener: ((String) -> Unit)? = null
    private val connected = CompletableDeferred<Unit>()

    override fun setListener(listener: (String) -> Unit) {
        this.listener = listener
    }

    override suspend fun open() {
        if (socket != null) {
            return
        }

        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!connected.isCompleted) {
                    connected.complete(Unit)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(TransportException("连接失败：${t.message}", t))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener?.invoke(text)
            }
        })

        connected.await()
    }

    override suspend fun send(text: String) {
        val ws = socket ?: throw TransportException("未连接")
        if (!ws.send(text)) {
            throw TransportException("发送失败")
        }
    }

    override fun close() {
        socket?.close(1000, null)
        socket = null
    }
}
