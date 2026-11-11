package com.bandkey.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class BackgroundUnlockResult(
    val success: Boolean,
    val state: String,
    val message: String,
    val detail: String = "",
    val channel: String = "",
    val requestId: Long = 0L
)

/**
 * 供桌面小组件、息屏 NFC 等无界面入口复用的解锁流程。
 * 连接策略与主界面保持一致：蓝牙优先，传输失败时回退局域网。
 */
object BackgroundUnlock {

    const val PREFS = "bandkey"
    const val KEY_NFC_AUTO_UNLOCK = "nfc_auto_unlock"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_TRANSPORT_MODE = "transport_mode"
    const val KEY_LAST_MESSAGE = "widget_last_message"
    const val KEY_LAST_AT = "widget_last_at"

    private const val DEFAULT_SERVER_URL = "ws://192.168.1.71:23234/bandkey"
    private const val DEFAULT_DEVICE_NAME = "我的手机"

    data class Config(
        val mode: String,
        val serverUrl: String,
        val deviceName: String
    )

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(context: Context): Config {
        val prefs = prefs(context)
        val mode = prefs.getString(KEY_TRANSPORT_MODE, null)
            ?.takeIf { it == "ble" || it == "wifi" }
            ?: "ble"
        return Config(
            mode = mode,
            serverUrl = prefs.getString(KEY_SERVER_URL, null)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_SERVER_URL,
            deviceName = prefs.getString("device_name", null)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_DEVICE_NAME
        )
    }

    fun isNfcAutoUnlockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NFC_AUTO_UNLOCK, false)

    fun setNfcAutoUnlockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NFC_AUTO_UNLOCK, enabled).apply()
    }

    fun isPaired(context: Context): Boolean =
        runCatching { PairingStore(context).load()?.deviceId?.isNotBlank() == true }
            .getOrDefault(false)

    fun recordResult(context: Context, result: BackgroundUnlockResult) {
        prefs(context).edit()
            .putString(KEY_LAST_MESSAGE, result.message)
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()
    }

    suspend fun unlock(
        context: Context,
        method: String = "phone",
        keyHint: String? = null
    ): BackgroundUnlockResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val record = PairingStore(appContext).load()
            ?: return@withContext BackgroundUnlockResult(
                success = false,
                state = "failed",
                message = "本机尚未配对，请先打开 BandKey 完成配对",
                detail = "no_pairing"
            )
        if (record.deviceId.isBlank()) {
            return@withContext BackgroundUnlockResult(
                success = false,
                state = "failed",
                message = "配对信息不完整，请重新配对",
                detail = "invalid_pairing"
            )
        }
        val keyPair = runCatching { record.toKeyPair() }.getOrElse {
            return@withContext BackgroundUnlockResult(
                success = false,
                state = "failed",
                message = "配对信息已损坏，请重新配对",
                detail = it.message.orEmpty()
            )
        }

        val config = config(appContext)
        val attempts = buildAttempts(appContext, config)
        if (attempts.isEmpty()) {
            return@withContext BackgroundUnlockResult(
                success = false,
                state = "failed",
                message = "没有可用的连接通道，请授予蓝牙权限或检查局域网地址",
                detail = "no_transport"
            )
        }

        var lastTransportError: Throwable? = null
        for (attempt in attempts) {
            val client = ProtocolClient(attempt.createTransport())
            try {
                val result = performUnlock(client, record.deviceId, keyPair, method, keyHint)
                return@withContext result.copy(channel = attempt.label)
            } catch (e: ProtocolException) {
                return@withContext protocolFailure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BandKeyUnlock", "transport attempt failed: ${attempt.label}", e)
                lastTransportError = e
            } finally {
                client.close()
            }
        }

        BackgroundUnlockResult(
            success = false,
            state = "failed",
            message = transportFailureMessage(lastTransportError?.message.orEmpty()),
            detail = if (lastTransportError != null) "transport_error" else "no_transport"
        )
    }

    private fun buildAttempts(context: Context, config: Config): List<Attempt> {
        val attempts = mutableListOf<Attempt>()
        val host = hostOf(config.serverUrl)
        if (config.mode == "ble") {
            if (hasBlePermissions(context)) {
                attempts += Attempt("蓝牙 LE") { BleTransport(context) }
            }
            if (!host.isNullOrBlank()) {
                attempts += Attempt("局域网 TCP") { LanTransport(host) }
            }
        } else {
            if (config.serverUrl.isNotBlank()) {
                attempts += Attempt("局域网 WebSocket") { WebSocketTransport(config.serverUrl) }
            }
            if (!host.isNullOrBlank()) {
                attempts += Attempt("局域网 TCP") { LanTransport(host) }
            }
        }
        return attempts
    }

    private suspend fun performUnlock(
        client: ProtocolClient,
        deviceId: String,
        keyPair: java.security.KeyPair,
        method: String,
        keyHint: String?
    ): BackgroundUnlockResult {
        val response = client.unlock(
            keyPair = keyPair,
            deviceId = deviceId,
            method = method,
            keyHint = keyHint
        )
        val payload = response.getJSONObject("payload")
        val accepted = payload.optBoolean("accepted", payload.optBoolean("ok"))
        if (!accepted) {
            return BackgroundUnlockResult(
                success = false,
                state = "failed",
                message = "电脑拒绝了本次解锁请求",
                detail = "unlock_denied"
            )
        }

        val requestId = payload.optLong("requestId", 0L)
        if (requestId <= 0L) {
            return BackgroundUnlockResult(
                success = false,
                state = "failed",
                message = "电脑没有返回解锁请求编号",
                detail = "bad_response"
            )
        }

        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            val status = try {
                client.unlockStatus(requestId)
            } catch (e: ProtocolException) {
                if (e.message.orEmpty().contains("unknown_type")) {
                    return submitted(requestId)
                }
                throw e
            }
            val result = status.getJSONObject("payload")
            when (result.optString("state", "submitted")) {
                "succeeded" -> return BackgroundUnlockResult(
                    success = true,
                    state = "succeeded",
                    message = "电脑已解锁",
                    requestId = requestId
                )
                "failed" -> {
                    val raw = result.optString("error")
                    return BackgroundUnlockResult(
                        success = false,
                        state = "failed",
                        message = protocolMessage(raw.ifBlank { "unlock_failed" }),
                        detail = raw,
                        requestId = requestId
                    )
                }
            }
            delay(600)
        }
        return submitted(requestId)
    }

    private fun submitted(requestId: Long) = BackgroundUnlockResult(
        success = true,
        state = "submitted",
        message = "请求已提交，等待 Windows 完成登录",
        requestId = requestId
    )

    private fun protocolFailure(error: ProtocolException): BackgroundUnlockResult {
        val raw = error.message.orEmpty()
        return BackgroundUnlockResult(
            success = false,
            state = "failed",
            message = protocolMessage(raw),
            detail = raw
        )
    }

    private fun protocolMessage(raw: String): String {
        val detail = raw.substringAfter(": ", raw)
        return when {
            raw.contains("unlock_denied") -> "电脑拒绝了本次解锁请求"
            raw.contains("computer_not_locked") -> "电脑当前未锁定，无需解锁"
            raw.contains("no_credential") -> "电脑尚未保存 Windows 解锁凭据"
            raw.contains("credential_unreadable") -> "Windows 凭据无法读取，请在电脑端重新设置"
            raw.contains("password_invalid") ||
                raw.contains("credential_invalid") -> "Windows 凭据已失效，请在电脑端更新"
            raw.contains("unlock_not_ready") -> "解锁组件未就绪，请重启电脑后重试"
            raw.contains("unknown_device") ||
                raw.contains("no_pairing") ||
                raw.contains("revoked") -> "设备未配对或已被电脑撤销，请重新配对"
            raw.contains("unlock_failed") -> "Windows 登录失败，请检查电脑端凭据"
            raw.contains("replayed_nonce") -> "本次请求已失效，请重新贴卡"
            raw.contains("unknown_type") -> "电脑端服务版本过旧，请升级 BandKey"
            raw.contains("timeout") || raw.contains("超时") -> "电脑响应超时，请检查连接后重试"
            detail.isNotBlank() -> detail
            else -> "解锁失败，请检查电脑端服务状态"
        }
    }

    private fun transportFailureMessage(detail: String): String = when {
        detail.contains("BLUETOOTH_SCAN", ignoreCase = true) ||
            detail.contains("BLUETOOTH_CONNECT", ignoreCase = true) ||
            detail.contains("权限", ignoreCase = true) -> "蓝牙权限未授予，请打开 BandKey 授予权限"
        detail.contains("蓝牙未开启") -> "蓝牙未开启，且局域网通道不可用"
        detail.contains("局域网发现失败") -> "未发现电脑，请确认手机与电脑在同一网络"
        detail.contains("failed to connect", ignoreCase = true) ||
            detail.contains("connection refused", ignoreCase = true) ||
            detail.contains("no route to host", ignoreCase = true) ||
            detail.contains("unreachable", ignoreCase = true) ->
            "无法连接电脑，请确认电脑端服务已启动且处于同一网络"
        detail.contains("timeout", ignoreCase = true) ||
            detail.contains("timed out", ignoreCase = true) ->
            "连接电脑超时，请检查网络后重试"
        detail.contains("连接失败") || detail.contains("未连接") -> "无法连接电脑，请检查蓝牙与网络"
        detail.isNotBlank() -> detail
        else -> "无法连接电脑，请检查电脑端服务是否运行"
    }

    private fun transportFailureMessage(raw: Throwable?): String =
        transportFailureMessage(raw?.message.orEmpty())

    private fun hasBlePermissions(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun hostOf(url: String): String? {
        val authority = url.substringAfter("://", url).substringBefore('/')
        if (authority.isBlank()) {
            return null
        }
        return if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBeforeLast(':', authority)
        }.takeIf { it.isNotBlank() }
    }

    private class Attempt(
        val label: String,
        val createTransport: () -> Transport
    )
}
