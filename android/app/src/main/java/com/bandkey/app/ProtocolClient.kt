package com.bandkey.app

import java.io.Closeable
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class ProtocolException(message: String) : Exception(message)

class ProtocolClient(private val transport: Transport) : Closeable {

    private class PendingRequest(
        val expectedType: String,
        val deferred: CompletableDeferred<JSONObject>
    )

    private val requestMutex = Mutex()
    private val pendingLock = Any()
    private var pending: PendingRequest? = null

    @Volatile
    private var opened = false

    init {
        transport.setListener { text -> dispatch(text) }
    }

    private fun dispatch(text: String) {
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }

        val type = obj.optString("type")
        if (type == "error") {
            val payload = obj.optJSONObject("payload")
            val code = payload?.optString("code") ?: "error"
            val message = payload?.optString("message") ?: "服务返回错误"
            val error = ProtocolException("$code: $message")
            takePending()?.deferred?.completeExceptionally(error)
            return
        }

        val request = synchronized(pendingLock) {
            if (pending?.expectedType == type) pending.also { pending = null } else null
        }
        request?.deferred?.complete(obj)
    }

    private fun takePending(): PendingRequest? = synchronized(pendingLock) {
        pending.also { pending = null }
    }

    private suspend fun ensureOpen() {
        if (!opened) {
            transport.open()
            opened = true
        }
    }

    suspend fun request(expectedResponseType: String, type: String, payload: JSONObject): JSONObject {
        return requestMutex.withLock {
            ensureOpen()

            val deferred = CompletableDeferred<JSONObject>()
            synchronized(pendingLock) {
                pending = PendingRequest(expectedResponseType, deferred)
            }

            val message = JSONObject().put("type", type).put("payload", payload)
            try {
                transport.send(message.toString())
                withTimeout(20_000) { deferred.await() }
            } catch (e: ProtocolException) {
                throw e
            } catch (e: Exception) {
                resetConnection()
                throw e
            } finally {
                synchronized(pendingLock) {
                    if (pending?.deferred === deferred) {
                        pending = null
                    }
                }
            }
        }
    }

    private fun resetConnection() {
        opened = false
        runCatching { transport.close() }
    }

    suspend fun pairChallenge(keyPair: KeyPair, deviceName: String): String {
        val publicKey = Crypto.publicKeySpkiBase64(keyPair.public as ECPublicKey)
        val challenge = request(
            "pair_challenge", "pair_request",
            JSONObject().put("deviceName", deviceName).put("devicePublicKey", publicKey)
        )
        return challenge.getJSONObject("payload").getString("pairingId")
    }

    suspend fun pairConfirm(pairingId: String, pin: String): String {
        val success = request(
            "pair_success", "pair_confirm",
            JSONObject().put("pairingId", pairingId).put("pin", pin)
        )
        return success.getJSONObject("payload").getString("deviceId")
    }

    suspend fun serverStatus(deviceId: String?): JSONObject {
        val payload = JSONObject()
        if (!deviceId.isNullOrBlank()) {
            payload.put("deviceId", deviceId)
        }
        return request("status_result", "status", payload)
    }

    suspend fun unlockStatus(requestId: Long): JSONObject {
        return request(
            "unlock_status_result",
            "unlock_status",
            JSONObject().put("requestId", requestId)
        )
    }

    suspend fun unlock(
        keyPair: KeyPair,
        deviceId: String,
        method: String = "phone",
        keyHint: String? = null
    ): JSONObject {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val timestamp = System.currentTimeMillis() / 1000
        val data = "unlock:$deviceId:$nonce:$timestamp".toByteArray(Charsets.UTF_8)
        val signature = Crypto.signUnlockBase64(keyPair, data)
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("nonce", nonce)
            .put("timestamp", timestamp)
            .put("signature", signature)
            .put("method", method)
        if (!keyHint.isNullOrBlank()) {
            payload.put("keyHint", keyHint)
        }
        return request(
            "unlock_result", "unlock",
            payload
        )
    }

    override fun close() {
        opened = false
        takePending()?.deferred?.completeExceptionally(ProtocolException("连接已关闭"))
        transport.close()
    }
}
