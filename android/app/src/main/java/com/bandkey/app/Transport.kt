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
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var listener: ((String) -> Unit)? = null
    private var connected = CompletableDeferred<Unit>()

    override fun setListener(listener: (String) -> Unit) {
        this.listener = listener
    }

    override suspend fun open() {
        val deferred: CompletableDeferred<Unit>
        synchronized(lock) {
            if (socket != null) {
                return
            }
            deferred = CompletableDeferred()
            connected = deferred
        }

        val request = Request.Builder().url(url).build()
        val webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                deferred.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                deferred.completeExceptionally(TransportException("连接失败：${t.message}", t))
                clearSocket(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(
                        TransportException("连接已关闭（code=$code）")
                    )
                }
                clearSocket(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket === webSocket) {
                    listener?.invoke(text)
                }
            }
        })

        synchronized(lock) {
            if (connected === deferred) {
                socket = webSocket
            } else {
                webSocket.cancel()
            }
        }

        try {
            deferred.await()
        } catch (e: Exception) {
            clearSocket(webSocket)
            webSocket.cancel()
            throw e
        }
    }

    override suspend fun send(text: String) {
        val ws = socket ?: throw TransportException("未连接")
        if (!ws.send(text)) {
            throw TransportException("发送失败")
        }
    }

    override fun close() {
        val current = synchronized(lock) {
            if (!connected.isCompleted) {
                connected.completeExceptionally(TransportException("连接已关闭"))
            }
            socket.also { socket = null }
        }
        current?.close(1000, null)
    }

    private fun clearSocket(webSocket: WebSocket) {
        synchronized(lock) {
            if (socket === webSocket) {
                socket = null
            }
        }
    }
}
