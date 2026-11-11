package com.bandkey.app

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.security.KeyPair
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    private var nfcAdapter: NfcAdapter? = null
    @Volatile
    private var nfcTagHandler: ((Tag) -> Unit)? = null
    private var nfcReaderWanted = false
    private var nfcTagDelivered = false
    private var screenReceiverRegistered = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> disableNfcReader()
                Intent.ACTION_SCREEN_ON -> {
                    if (nfcReaderWanted && !nfcTagDelivered) {
                        enableNfcReader()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        val prefs = getSharedPreferences("bandkey", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", null)
        val savedMode = prefs.getString("transport_mode", null)
            ?.takeIf { it == "ble" || it == "wifi" }
            ?: "ble"
        val savedDeviceName = prefs.getString("device_name", null) ?: "我的手机"
        val savedUnlockChannel = prefs.getString("unlock_channel", "phone")
            ?.takeIf { it == "phone" || it == "nfc" }
            ?: "phone"
        val savedNfcAutoUnlock = prefs.getBoolean(BackgroundUnlock.KEY_NFC_AUTO_UNLOCK, false)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        ensureBlePermissions()

        setContent {
            BandKeyTheme {
                BandKeyScreen(
                    initialMode = savedMode,
                    initialUrl = savedUrl ?: "ws://192.168.1.71:23234/bandkey",
                    initialDeviceName = savedDeviceName,
                    initialUnlockChannel = savedUnlockChannel,
                    initialNfcAutoUnlock = savedNfcAutoUnlock,
                    onModeChange = { value ->
                        prefs.edit().putString("transport_mode", value).apply()
                    },
                    onUrlChange = { value ->
                        prefs.edit().putString("server_url", value).apply()
                    },
                    onDeviceNameChange = { value ->
                        prefs.edit().putString("device_name", value).apply()
                    },
                    onUnlockChannelChange = { value ->
                        prefs.edit().putString("unlock_channel", value).apply()
                    },
                    onNfcAutoUnlockChange = { enabled ->
                        BackgroundUnlock.setNfcAutoUnlockEnabled(this@MainActivity, enabled)
                        BandKeyWidgetProvider.refresh(this@MainActivity)
                    },
                    onNeedBlePermissions = { ensureBlePermissions() },
                    nfcAvailabilityProvider = { currentNfcAvailability() },
                    onStartNfcReader = { handler -> startNfcReader(handler) },
                    onStopNfcReader = { stopNfcReader() },
                    onOpenNfcSettings = { openNfcSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerScreenReceiver()
        BandKeyWidgetProvider.refresh(this)
        if (nfcReaderWanted &&
            !nfcTagDelivered &&
            getSystemService(PowerManager::class.java)?.isInteractive == true
        ) {
            enableNfcReader()
        }
    }

    override fun onPause() {
        unregisterScreenReceiver()
        disableNfcReader()
        super.onPause()
    }

    override fun onStop() {
        // 息屏或切到后台后必须释放 Reader Mode，否则系统 NFC 分发会被前台读卡器抢占。
        disableNfcReader()
        super.onStop()
    }

    override fun onDestroy() {
        stopNfcReader()
        super.onDestroy()
    }

    override fun onTagDiscovered(tag: Tag) {
        runOnUiThread {
            if (nfcTagDelivered) {
                return@runOnUiThread
            }
            val handler = nfcTagHandler ?: return@runOnUiThread
            nfcTagDelivered = true
            handler(tag)
        }
    }

    private fun startNfcReader(handler: (Tag) -> Unit): NfcAvailability {
        val availability = currentNfcAvailability()
        if (availability != NfcAvailability.READY) {
            return availability
        }
        nfcTagHandler = handler
        nfcReaderWanted = true
        nfcTagDelivered = false
        return if (enableNfcReader()) NfcAvailability.READY else NfcAvailability.DISABLED
    }

    private fun stopNfcReader() {
        nfcReaderWanted = false
        nfcTagHandler = null
        nfcTagDelivered = false
        disableNfcReader()
    }

    private fun currentNfcAvailability(): NfcAvailability {
        val adapter = nfcAdapter ?: return NfcAvailability.UNSUPPORTED
        return if (adapter.isEnabled) NfcAvailability.READY else NfcAvailability.DISABLED
    }

    private fun enableNfcReader(): Boolean {
        val adapter = nfcAdapter ?: return false
        if (!adapter.isEnabled) {
            return false
        }
        return runCatching {
            adapter.enableReaderMode(
                this,
                this,
                // BandKey 使用 NDEF。不能设置 FLAG_READER_SKIP_NDEF_CHECK，
                // 否则系统不会枚举 Ndef / NdefFormatable，所有卡都会被误判为不支持 NDEF。
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V,
                null
            )
            true
        }.getOrDefault(false)
    }

    private fun disableNfcReader() {
        runCatching { nfcAdapter?.disableReaderMode(this) }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        screenReceiverRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
            true
        }.getOrDefault(false)
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) {
            return
        }
        runCatching { unregisterReceiver(screenStateReceiver) }
        screenReceiverRegistered = false
    }

    private fun openNfcSettings() {
        VendorSettings.openNfcSettings(this)
    }

    private fun ensureBlePermissions() {
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

private data class RecoveryStep(
    val title: String,
    val detail: String
)

private data class UiError(
    val title: String,
    val message: String,
    val steps: List<RecoveryStep>,
    val code: String
)

private enum class NfcAvailability {
    READY,
    DISABLED,
    UNSUPPORTED
}

private enum class NfcPhase {
    IDLE,
    SCANNING,
    BINDING,
    CONFIRM,
    VERIFYING,
    REJECTED,
    ERROR
}

private enum class NfcScanPurpose {
    BIND,
    UNLOCK
}

private data class UnlockReceipt(
    val at: Long,
    val target: String,
    val channel: String,
    val keyHint: String? = null,
    val state: String = "succeeded",
    val requestId: Long? = null
)

private data class PcStatus(
    val online: Boolean = false,
    val checked: Boolean = false,
    val paired: Boolean = false,
    val revoked: Boolean = false,
    val credentialSet: Boolean = false,
    val credentialRejected: Boolean = false,
    val credentialProviderConnected: Boolean = false,
    val isLocked: Boolean = false,
    val unlockReady: Boolean = false,
    val pcName: String = "",
    val serverVersion: String = "",
    val protocolVersion: Int = 1
)

private data class NfcOverwriteTarget(
    val tag: Tag,
    val recordCount: Int
)

@Composable
private fun BandKeyScreen(
    initialMode: String,
    initialUrl: String,
    initialDeviceName: String,
    initialUnlockChannel: String,
    initialNfcAutoUnlock: Boolean,
    onModeChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onUnlockChannelChange: (String) -> Unit,
    onNfcAutoUnlockChange: (Boolean) -> Unit,
    onNeedBlePermissions: () -> Unit,
    nfcAvailabilityProvider: () -> NfcAvailability,
    onStartNfcReader: ((Tag) -> Unit) -> NfcAvailability,
    onStopNfcReader: () -> Unit,
    onOpenNfcSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val pairingStore = remember { PairingStore(context) }
    val nfcKeyStore = remember { NfcKeyStore(context) }

    var tab by remember { mutableStateOf(MainTab.UNLOCK) }
    var mode by remember { mutableStateOf(initialMode) }
    var serverUrl by remember { mutableStateOf(initialUrl) }
    var deviceName by remember { mutableStateOf(initialDeviceName) }
    var unlockChannel by remember { mutableStateOf(initialUnlockChannel) }
    var nfcAutoUnlock by remember { mutableStateOf(initialNfcAutoUnlock) }
    var status by remember { mutableStateOf("等待建立安全通道") }
    var keyPair by remember { mutableStateOf<KeyPair?>(null) }
    var deviceId by remember { mutableStateOf("") }
    var pairedAt by remember { mutableStateOf(0L) }
    var pairingId by remember { mutableStateOf<String?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var pairError by remember { mutableStateOf<String?>(null) }
    var pairingRemaining by remember { mutableStateOf(300) }
    var busy by remember { mutableStateOf(false) }
    var pcStatus by remember { mutableStateOf(PcStatus()) }
    var statusRefreshing by remember { mutableStateOf(false) }
    var activeChannel by remember { mutableStateOf<String?>(null) }
    var showPairing by remember { mutableStateOf(false) }
    var pendingUnpair by remember { mutableStateOf(false) }
    var uiError by remember { mutableStateOf<UiError?>(null) }
    var receipt by remember { mutableStateOf<UnlockReceipt?>(null) }
    var receiptNotice by remember { mutableStateOf("") }
    var nfcAvailability by remember { mutableStateOf(nfcAvailabilityProvider()) }
    var nfcKeys by remember { mutableStateOf<List<NfcKeyRecord>>(emptyList()) }
    var nfcPhase by remember { mutableStateOf(NfcPhase.IDLE) }
    var nfcScanPurpose by remember { mutableStateOf<NfcScanPurpose?>(null) }
    var nfcNotice by remember { mutableStateOf("") }
    var nfcRejectedHint by remember { mutableStateOf("") }
    var nfcRenameTarget by remember { mutableStateOf<NfcKeyRecord?>(null) }
    var nfcRenameValue by remember { mutableStateOf("") }
    var nfcDeleteTarget by remember { mutableStateOf<NfcKeyRecord?>(null) }
    var nfcOverwriteTarget by remember { mutableStateOf<NfcOverwriteTarget?>(null) }
    var nfcLastPurpose by remember { mutableStateOf(NfcScanPurpose.UNLOCK) }

    val credentialReady = pcStatus.online
        && pcStatus.paired
        && pcStatus.credentialSet
        && !pcStatus.credentialRejected

    LaunchedEffect(Unit) {
        val record = withContext(Dispatchers.IO) { pairingStore.load() }
        if (record != null) {
            runCatching { record.toKeyPair() }
                .onSuccess { loaded ->
                    keyPair = loaded
                    deviceId = record.deviceId
                    pairedAt = record.pairedAt
                    deviceName = forName(record.deviceName, initialDeviceName)
                    status = "已加载本机配对信息，可直接解锁"
                }
                .onFailure {
                    status = "配对信息已损坏，请重新配对"
                }
        }
    }

    LaunchedEffect(nfcKeyStore) {
        nfcKeys = withContext(Dispatchers.IO) { nfcKeyStore.load() }
        nfcAvailability = nfcAvailabilityProvider()
        if (!nfcKeys.isEmpty()) {
            nfcNotice = "已加载 ${nfcKeys.size} 张 NFC 钥匙"
        }
    }

    LaunchedEffect(tab) {
        if (tab == MainTab.SETTINGS) {
            nfcAutoUnlock = BackgroundUnlock.isNfcAutoUnlockEnabled(context)
            nfcAvailability = nfcAvailabilityProvider()
        }
    }

    LaunchedEffect(showPairing, pairingId) {
        if (!showPairing || pairingId == null) {
            return@LaunchedEffect
        }
        if (pairingRemaining <= 0) {
            pairingId = null
            pairError = "PIN 已过期，请重新发送配对请求"
            return@LaunchedEffect
        }

        while (showPairing && pairingId != null && pairingRemaining > 0) {
            delay(1_000)
            pairingRemaining = (pairingRemaining - 1).coerceAtLeast(0)
        }
        if (pairingRemaining == 0 && pairingId != null) {
            pairingId = null
            pairError = "PIN 已过期，请重新发送配对请求"
            status = "配对 PIN 已过期"
        }
    }

    suspend fun runWithTransport(block: suspend (ProtocolClient) -> Unit) {
        val primary = if (mode == "ble") {
            ProtocolClient(BleTransport(context))
        } else {
            ProtocolClient(WebSocketTransport(serverUrl))
        }

        try {
            block(primary)
            activeChannel = if (mode == "ble") "ble" else "lan"
        } catch (first: Exception) {
            if (mode != "ble" || first is ProtocolException) {
                throw first
            }

            status = "蓝牙通道不可用，尝试局域网备用通道…"
            val fallback = ProtocolClient(LanTransport(hostFromUrl(serverUrl)))
            try {
                block(fallback)
                activeChannel = "lan"
            } catch (second: Exception) {
                activeChannel = null
                if (second is ProtocolException) {
                    throw second
                }
                throw TransportException(
                    "${second.message}（蓝牙：${first.message}）",
                    second
                )
            } finally {
                fallback.close()
            }
        } finally {
            primary.close()
        }
    }

    fun applyServerStatus(payload: JSONObject) {
        pcStatus = PcStatus(
            online = payload.optBoolean("online", true),
            checked = true,
            paired = payload.optBoolean("paired"),
            revoked = payload.optBoolean("revoked"),
            credentialSet = payload.optBoolean("credentialSet"),
            credentialRejected = payload.optBoolean("credentialRejected"),
            credentialProviderConnected = payload.optBoolean("credentialProviderConnected"),
            isLocked = payload.optBoolean("isLocked"),
            unlockReady = payload.optBoolean("unlockReady"),
            pcName = payload.optString("pcName", ""),
            serverVersion = payload.optString("serverVersion", ""),
            protocolVersion = payload.optInt("protocolVersion", 1)
        )
        status = when {
            pcStatus.protocolVersion < 2 -> "电脑服务版本过旧，请升级 BandKey 后再试"
            else -> pcStatusSummary(pcStatus)
        }
    }

    fun applyLegacyServerStatus() {
        pcStatus = pcStatus.copy(
            online = true,
            checked = true,
            paired = true,
            revoked = false,
            credentialProviderConnected = false,
            isLocked = false,
            unlockReady = false,
            protocolVersion = 1
        )
        status = "电脑服务版本过旧，请升级电脑端 BandKey 后再试"
    }

    fun refreshStatus() {
        if (busy || statusRefreshing || keyPair == null || deviceId.isEmpty()) {
            return
        }

        scope.launch {
            statusRefreshing = true
            try {
                runWithTransport { client ->
                    val response = client.serverStatus(deviceId)
                    applyServerStatus(response.getJSONObject("payload"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProtocolException) {
                if (e.message.orEmpty().contains("unknown_type")) {
                    applyLegacyServerStatus()
                } else {
                    pcStatus = pcStatus.copy(
                        online = false,
                        checked = true,
                        credentialProviderConnected = false,
                        isLocked = false,
                        unlockReady = false
                    )
                    activeChannel = null
                    status = "电脑离线，等待重新连接"
                }
            } catch (e: Exception) {
                pcStatus = pcStatus.copy(
                    online = false,
                    checked = true,
                    credentialProviderConnected = false,
                    isLocked = false,
                    unlockReady = false
                )
                activeChannel = null
                status = "电脑离线，等待重新连接"
            } finally {
                statusRefreshing = false
            }
        }
    }

    LaunchedEffect(keyPair, deviceId) {
        if (keyPair != null && deviceId.isNotEmpty()) {
            refreshStatus()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val currentRefreshStatus by rememberUpdatedState { refreshStatus() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentRefreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun startPairing() {
        if (busy) {
            return
        }
        if (mode == "ble") {
            onNeedBlePermissions()
        }
        onModeChange(mode)
        onUrlChange(serverUrl)
        pairError = null
        uiError = null
        pinInput = ""
        pairingId = null
        pairingRemaining = 300
        activeChannel = null

        scope.launch {
            busy = true
            status = if (mode == "ble") "正在扫描电脑蓝牙…" else "正在连接服务…"
            try {
                runWithTransport { client ->
                    val pair = Crypto.generateKeyPair()
                    val id = client.pairChallenge(
                        pair,
                        deviceName.ifBlank { "我的手机" }
                    )
                    keyPair = pair
                    pairingId = id
                    pairingRemaining = 300
                    status = "已发送配对请求，请在电脑托盘查看 PIN"
                }
            } catch (e: Exception) {
                val mapped = pairingError(e.message.orEmpty())
                pairError = mapped
                status = mapped
            } finally {
                busy = false
            }
        }
    }

    fun openPairing() {
        if (busy) {
            return
        }
        tab = MainTab.UNLOCK
        pendingUnpair = false
        showPairing = true
        startPairing()
    }

    fun closePairing() {
        if (busy && pairingId != null) {
            return
        }
        showPairing = false
        pairingId = null
        pinInput = ""
        pairError = null
        pairingRemaining = 300
    }

    fun confirmPairing() {
        val id = pairingId ?: return
        val pair = keyPair
        if (pair == null || pinInput.length != 6) {
            pairError = "请输入完整的 6 位配对 PIN"
            return
        }
        if (busy) {
            return
        }
        if (mode == "ble") {
            onNeedBlePermissions()
        }

        scope.launch {
            busy = true
            pairError = null
            status = "正在确认配对…"
            try {
                runWithTransport { client ->
                    val devId = client.pairConfirm(id, pinInput)
                    val statusAttempt = runCatching {
                        client.serverStatus(devId).getJSONObject("payload")
                    }
                    val statusPayload = statusAttempt.getOrNull()
                    val at = System.currentTimeMillis()
                    deviceId = devId
                    pairedAt = at
                    val record = PairingRecord(
                        deviceId = devId,
                        deviceName = deviceName.ifBlank { "我的手机" },
                        privateKey = pair.private.encoded,
                        publicKey = pair.public.encoded,
                        pairedAt = at
                    )
                    withContext(Dispatchers.IO) { pairingStore.save(record) }
                    BandKeyWidgetProvider.refresh(context)
                    pairingId = null
                    pinInput = ""
                    showPairing = false
                    tab = MainTab.UNLOCK
                    if (statusPayload != null) {
                        applyServerStatus(statusPayload)
                    } else if (statusAttempt.exceptionOrNull()?.message.orEmpty().contains("unknown_type")) {
                        applyLegacyServerStatus()
                    } else {
                        status = "配对成功，正在等待电脑状态确认"
                    }
                }
            } catch (e: Exception) {
                val raw = e.message.orEmpty()
                val mapped = pairingError(raw)
                pairError = mapped
                status = mapped
                if (raw.contains("pairing_expired") || raw.contains("unknown_pairing")) {
                    pairingId = null
                }
            } finally {
                busy = false
            }
        }
    }

    suspend fun awaitUnlockOutcome(
        client: ProtocolClient,
        requestId: Long
    ): Pair<String, String?> {
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            val response = try {
                client.unlockStatus(requestId)
            } catch (e: ProtocolException) {
                if (e.message.orEmpty().contains("unknown_type")) {
                    return "submitted" to null
                }
                throw e
            }

            val payload = response.getJSONObject("payload")
            val state = payload.optString("state", "submitted")
            if (state == "succeeded" || state == "failed") {
                return state to payload.optString("error").takeIf { it.isNotBlank() }
            }
            delay(600)
        }
        return "submitted" to null
    }

    fun unlock(channelLabel: String? = null, nfcKeyHint: String? = null) {
        val pair = keyPair
        if (pair == null || deviceId.isEmpty()) {
            openPairing()
            return
        }
        if (busy) {
            return
        }
        if (mode == "ble") {
            onNeedBlePermissions()
        }

        scope.launch {
            busy = true
            uiError = null
            receipt = null
            receiptNotice = ""
            var requestId = 0L
            var outcomeState = "submitted"
            status = "正在验证签名并请求解锁…"
            try {
                runWithTransport { client ->
                    val result = client.unlock(
                        keyPair = pair,
                        deviceId = deviceId,
                        method = if (nfcKeyHint == null) "phone" else "nfc",
                        keyHint = nfcKeyHint
                    )
                    val payload = result.getJSONObject("payload")
                    val accepted = payload.optBoolean("accepted", payload.optBoolean("ok"))
                    if (!accepted) {
                        throw ProtocolException("unlock_denied: 电脑拒绝了本次解锁请求")
                    }
                    requestId = payload.optLong("requestId", 0L)
                    if (requestId <= 0) {
                        throw ProtocolException("bad_response: 电脑没有返回解锁请求编号")
                    }

                    val (state, error) = awaitUnlockOutcome(client, requestId)
                    outcomeState = state
                    if (state == "failed") {
                        throw ProtocolException("unlock_failed:${error ?: "logon_failure"}")
                    }
                }
                val transport = if (activeChannel == "lan") "局域网 TCP" else "蓝牙 LE"
                val channel = channelLabel ?: transport
                receipt = UnlockReceipt(
                    at = System.currentTimeMillis(),
                    target = pcStatus.pcName.ifBlank { targetLabel(serverUrl, mode) },
                    channel = channel,
                    keyHint = nfcKeyHint,
                    state = outcomeState,
                    requestId = requestId
                )
                if (nfcKeyHint != null) {
                    nfcNotice = "NFC 验证成功 · $nfcKeyHint"
                }
                pcStatus = pcStatus.copy(
                    online = true,
                    isLocked = outcomeState != "succeeded",
                    credentialProviderConnected = outcomeState != "succeeded",
                    unlockReady = outcomeState != "succeeded"
                )
                status = if (outcomeState == "succeeded") {
                    "Windows 已确认登录成功"
                } else {
                    "已提交解锁请求，等待 Windows 完成登录"
                }
                if (nfcKeyHint != null && outcomeState != "succeeded") {
                    nfcNotice = "NFC 已提交 · 等待 Windows 登录结果"
                }
            } catch (e: Exception) {
                val raw = e.message.orEmpty()
                pcStatus = when {
                    raw.contains("no_credential") -> pcStatus.copy(credentialSet = false)
                    raw.contains("credential_unreadable") -> pcStatus.copy(credentialSet = false)
                    raw.contains("password_invalid") ||
                        raw.contains("credential_invalid") -> pcStatus.copy(credentialRejected = true)
                    raw.contains("computer_not_locked") ||
                        raw.contains("unlock_not_ready") -> pcStatus.copy(
                        isLocked = false,
                        credentialProviderConnected = false,
                        unlockReady = false
                    )
                    raw.contains("unknown_device") -> pcStatus.copy(paired = false, revoked = true)
                    else -> pcStatus
                }
                uiError = unlockError(raw)
                status = uiError?.title ?: "解锁失败"
            } finally {
                busy = false
                nfcPhase = NfcPhase.IDLE
            }
        }
    }

    fun refreshReceiptOutcome() {
        val current = receipt ?: return
        val requestId = current.requestId ?: return
        if (busy) {
            return
        }

        scope.launch {
            busy = true
            receiptNotice = ""
            try {
                runWithTransport { client ->
                    val payload = client.unlockStatus(requestId).getJSONObject("payload")
                    if (receipt?.requestId != requestId) {
                        return@runWithTransport
                    }
                    when (val state = payload.optString("state", "unknown")) {
                        "succeeded" -> {
                            receipt = current.copy(state = state)
                            status = "Windows 已确认登录成功"
                            pcStatus = pcStatus.copy(
                                online = true,
                                isLocked = false,
                                credentialProviderConnected = false,
                                unlockReady = false
                            )
                        }
                        "failed" -> {
                            val error = payload.optString("error").ifBlank { "logon_failure" }
                            pcStatus = when (error) {
                                "credential_invalid" -> pcStatus.copy(credentialRejected = true)
                                "credential_unreadable" -> pcStatus.copy(credentialSet = false)
                                "component_not_connected",
                                "component_disconnected",
                                "component_error" -> pcStatus.copy(
                                    credentialProviderConnected = false,
                                    isLocked = false,
                                    unlockReady = false
                                )
                                else -> pcStatus
                            }
                            receipt = null
                            uiError = unlockError("unlock_failed:$error")
                            status = uiError?.title ?: "解锁失败"
                        }
                        "unknown" -> {
                            receiptNotice = "电脑已重启或请求记录已过期，请重新发起解锁。"
                        }
                        else -> {
                            receiptNotice = "Windows 尚未返回最终结果，请稍后再次刷新。"
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                receiptNotice = if (e.message.orEmpty().contains("unknown_type")) {
                    "电脑服务版本过旧，无法查询最终登录结果。"
                } else {
                    "暂时无法查询电脑状态，请检查连接后重试。"
                }
            } finally {
                busy = false
            }
        }
    }

    fun unpair() {
        scope.launch {
            withContext(Dispatchers.IO) { pairingStore.clear() }
            BandKeyWidgetProvider.refresh(context)
            keyPair = null
            deviceId = ""
            pairedAt = 0L
            pairingId = null
            pinInput = ""
            pcStatus = PcStatus()
            activeChannel = null
            pendingUnpair = false
            receipt = null
            uiError = null
            status = "已解除本机配对"
        }
    }

    fun selectUnlockChannel(channel: String) {
        if (channel == unlockChannel) {
            if (channel == "nfc") {
                nfcAvailability = nfcAvailabilityProvider()
            }
            return
        }
        nfcScanPurpose = null
        nfcPhase = NfcPhase.IDLE
        onStopNfcReader()
        unlockChannel = channel
        onUnlockChannelChange(channel)
        if (channel == "nfc") {
            nfcAvailability = nfcAvailabilityProvider()
            nfcNotice = if (nfcKeys.isEmpty()) {
                "绑定一张实体卡，作为本机备用钥匙"
            } else {
                "将 NFC 钥匙靠近手机背面"
            }
        }
    }

    fun stopNfcScan(message: String? = null) {
        nfcScanPurpose = null
        nfcOverwriteTarget = null
        nfcPhase = NfcPhase.IDLE
        onStopNfcReader()
        if (message != null) {
            nfcNotice = message
        }
    }

    suspend fun bindNfcTag(tag: Tag, allowOverwrite: Boolean = false) {
        val existing = withContext(Dispatchers.IO) {
            runCatching { NfcCard.readPayload(tag) }.getOrNull()
        }
        val existingHash = existing
            ?.takeIf(NfcCredential::isValid)
            ?.let(NfcCredential::hash)
        val remaining = nfcKeys.filterNot { record ->
            existingHash != null && NfcCredential.hashEquals(record.tokenHash, existingHash)
        }
        if (remaining.size >= NfcKeyStore.MAX_KEYS) {
            nfcPhase = NfcPhase.ERROR
            nfcNotice = "最多绑定 ${NfcKeyStore.MAX_KEYS} 张 NFC 钥匙，请先移除旧钥匙"
            return
        }

        val payload = NfcCredential.newPayload()
        val result = withContext(Dispatchers.IO) {
            runCatching { NfcCard.writePayload(tag, payload, allowOverwrite) }
                .getOrElse { NfcCard.WriteResult.Failed(it.message ?: "NFC 写入失败") }
        }
        when (result) {
            NfcCard.WriteResult.Success -> Unit
            NfcCard.WriteResult.NotWritable -> {
                nfcPhase = NfcPhase.ERROR
                nfcNotice = "这张卡是只读卡，无法写入；请更换可写 NFC 卡"
                return
            }
            is NfcCard.WriteResult.Unsupported -> {
                nfcPhase = NfcPhase.ERROR
                val detected = result.technologies.joinToString(" / ").ifBlank { "未知技术" }
                nfcNotice =
                    "这张卡不支持 NDEF（检测到 $detected）；请使用 NTAG213/215/216 等 NDEF 卡"
                return
            }
            is NfcCard.WriteResult.TooLarge -> {
                nfcPhase = NfcPhase.ERROR
                nfcNotice = "卡片容量不足：需要 ${result.required} 字节，本卡可用 ${result.capacity} 字节"
                return
            }
            is NfcCard.WriteResult.HasForeignData -> {
                nfcOverwriteTarget = NfcOverwriteTarget(tag, result.recordCount)
                nfcPhase = NfcPhase.CONFIRM
                nfcNotice = "卡片已有 ${result.recordCount} 条其他 NDEF 记录，请确认是否覆盖"
                return
            }
            is NfcCard.WriteResult.Failed -> {
                nfcPhase = NfcPhase.ERROR
                nfcNotice =
                    "写卡失败：${result.reason}；请保持卡片贴近手机后重试，若仍失败可能是卡片已加密或锁定"
                return
            }
        }

        val hash = NfcCredential.hash(payload)
        val hint = NfcCredential.hint(hash)
        val now = System.currentTimeMillis()
        val record = NfcKeyRecord(
            id = java.util.UUID.randomUUID().toString(),
            name = "NFC 钥匙 ${remaining.size + 1}",
            tokenHash = hash,
            hint = hint,
            boundAt = now,
            lastUsedAt = 0L
        )
        val updated = (listOf(record) + remaining).take(NfcKeyStore.MAX_KEYS)
        withContext(Dispatchers.IO) { nfcKeyStore.save(updated) }
        BandKeyWidgetProvider.refresh(context)
        nfcKeys = updated
        nfcPhase = NfcPhase.IDLE
        nfcOverwriteTarget = null
        nfcRejectedHint = ""
        nfcNotice = "NFC 钥匙已绑定 · $hint"
    }

    suspend fun readNfcUnlock(tag: Tag) {
        val payload = withContext(Dispatchers.IO) {
            runCatching { NfcCard.readPayload(tag) }.getOrNull()
        }
        // 读取完成后才关闭 Reader Mode；绑定流程则要一直保持到写入结束。
        onStopNfcReader()
        if (payload == null) {
            nfcPhase = NfcPhase.ERROR
            nfcNotice = "没有读取到 NDEF 数据，请重新贴卡"
            return
        }
        if (!NfcCredential.isValid(payload)) {
            nfcPhase = NfcPhase.REJECTED
            nfcRejectedHint = ""
            nfcNotice = "已读取卡片，但其中没有 BandKey 身份令牌"
            return
        }

        val hash = NfcCredential.hash(payload)
        val record = nfcKeys.firstOrNull {
            NfcCredential.hashEquals(it.tokenHash, hash)
        }
        if (record == null) {
            nfcPhase = NfcPhase.REJECTED
            nfcRejectedHint = NfcCredential.hint(hash)
            nfcNotice = "这张卡尚未加入本机授权列表"
            return
        }

        val used = record.copy(lastUsedAt = System.currentTimeMillis())
        val updated = nfcKeys.map { if (it.id == used.id) used else it }
        withContext(Dispatchers.IO) { nfcKeyStore.save(updated) }
        nfcKeys = updated
        nfcRejectedHint = ""
        nfcPhase = NfcPhase.VERIFYING
        nfcNotice = "正在核对 ${used.hint}"
        unlock(channelLabel = "NFC · 本地验签", nfcKeyHint = used.hint)
    }

    fun handleNfcTag(tag: Tag) {
        val purpose = nfcScanPurpose ?: return
        nfcScanPurpose = null
        nfcLastPurpose = purpose
        scope.launch {
            nfcPhase = if (purpose == NfcScanPurpose.BIND) NfcPhase.BINDING else NfcPhase.VERIFYING
            try {
                when (purpose) {
                    NfcScanPurpose.BIND -> bindNfcTag(tag)
                    NfcScanPurpose.UNLOCK -> readNfcUnlock(tag)
                }
            } catch (e: Exception) {
                nfcPhase = NfcPhase.ERROR
                nfcNotice = if (purpose == NfcScanPurpose.BIND) {
                    "写卡失败：${e.message ?: "请保持卡片贴近手机后重试"}"
                } else {
                    "NFC 读取失败：${e.message ?: "请重新贴卡"}"
                }
            } finally {
                // 写卡/读卡期间必须保持 Reader Mode 和 RF 场，完成后才释放。
                onStopNfcReader()
            }
        }
    }

    fun beginNfcScan(purpose: NfcScanPurpose) {
        if (busy) {
            return
        }
        val availability = nfcAvailabilityProvider()
        nfcAvailability = availability
        if (availability != NfcAvailability.READY) {
            nfcScanPurpose = null
            nfcPhase = NfcPhase.IDLE
            nfcNotice = when (availability) {
                NfcAvailability.UNSUPPORTED -> "这台手机没有 NFC 读卡硬件"
                NfcAvailability.DISABLED -> "请先在系统设置中开启 NFC"
                NfcAvailability.READY -> ""
            }
            return
        }

        nfcScanPurpose = purpose
        nfcLastPurpose = purpose
        nfcPhase = NfcPhase.SCANNING
        nfcNotice = if (purpose == NfcScanPurpose.BIND) {
            "请将 NFC 卡贴近手机背面，已有数据会先询问是否覆盖"
        } else {
            "将已绑定的 NFC 钥匙贴近手机背面"
        }
        val started = onStartNfcReader { tag -> handleNfcTag(tag) }
        if (started != NfcAvailability.READY) {
            nfcAvailability = started
            nfcScanPurpose = null
            nfcPhase = NfcPhase.IDLE
            onStopNfcReader()
            nfcNotice = when (started) {
                NfcAvailability.UNSUPPORTED -> "这台手机没有 NFC 读卡硬件"
                NfcAvailability.DISABLED -> "请先在系统设置中开启 NFC"
                NfcAvailability.READY -> ""
            }
        }
    }

    fun bindNfcKey() {
        beginNfcScan(NfcScanPurpose.BIND)
    }

    fun confirmNfcOverwrite() {
        val target = nfcOverwriteTarget ?: return
        nfcOverwriteTarget = null
        nfcPhase = NfcPhase.BINDING
        scope.launch {
            try {
                bindNfcTag(target.tag, allowOverwrite = true)
            } catch (e: Exception) {
                nfcPhase = NfcPhase.ERROR
                nfcNotice = "覆盖绑定失败：${e.message ?: "请重新贴卡"}"
            }
        }
    }

    fun cancelNfcOverwrite() {
        nfcOverwriteTarget = null
        nfcPhase = NfcPhase.IDLE
        nfcNotice = "已取消绑定，卡片内容未被修改"
    }

    fun openNfcBinding() {
        tab = MainTab.UNLOCK
        if (unlockChannel != "nfc") {
            selectUnlockChannel("nfc")
        }
        bindNfcKey()
    }

    fun scanNfcUnlock() {
        if (nfcKeys.isEmpty()) {
            bindNfcKey()
            return
        }
        if (deviceId.isEmpty() || keyPair == null) {
            openPairing()
            return
        }
        beginNfcScan(NfcScanPurpose.UNLOCK)
    }

    fun renameNfcKey(record: NfcKeyRecord, name: String) {
        val safe = name.trim().take(32)
        if (safe.isEmpty()) {
            nfcNotice = "钥匙名称不能为空"
            return
        }
        scope.launch {
            val updated = nfcKeys.map { if (it.id == record.id) it.copy(name = safe) else it }
            withContext(Dispatchers.IO) { nfcKeyStore.save(updated) }
            nfcKeys = updated
            nfcRenameTarget = null
            nfcNotice = "钥匙已重命名为 $safe"
        }
    }

    fun deleteNfcKey(record: NfcKeyRecord) {
        if (nfcPhase == NfcPhase.SCANNING) {
            stopNfcScan()
        }
        scope.launch {
            val updated = nfcKeys.filterNot { it.id == record.id }
            withContext(Dispatchers.IO) { nfcKeyStore.save(updated) }
            BandKeyWidgetProvider.refresh(context)
            nfcKeys = updated
            nfcDeleteTarget = null
            nfcPhase = NfcPhase.IDLE
            nfcRejectedHint = ""
            nfcNotice = if (updated.isEmpty()) {
                "已移除全部 NFC 钥匙，可随时重新绑定"
            } else {
                "已移除 ${record.hint}"
            }
        }
    }

    val paired = deviceId.isNotEmpty() && keyPair != null
    val channelReady = mode == "ble" || serverUrl.isNotBlank()

    BkBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    transitionSpec = {
                        (
                            fadeIn(tween(220)) +
                                slideInVertically(
                                    animationSpec = tween(220),
                                    initialOffsetY = { it / 22 }
                                )
                            ).togetherWith(fadeOut(tween(140)))
                    },
                    label = "main-tab"
                ) { current ->
                    when (current) {
                        MainTab.UNLOCK -> UnlockHome(
                            deviceName = deviceName,
                            mode = mode,
                            activeChannel = activeChannel,
                            paired = paired,
                            pcStatus = pcStatus,
                            credentialReady = credentialReady,
                            busy = busy,
                            status = status,
                            lastReceipt = receipt,
                            nfcMode = unlockChannel == "nfc",
                            nfcAvailability = nfcAvailability,
                            nfcKeys = nfcKeys,
                            nfcPhase = nfcPhase,
                            nfcNotice = nfcNotice,
                            nfcRejectedHint = nfcRejectedHint,
                            nfcLastPurpose = nfcLastPurpose,
                            onChannelChange = { selectUnlockChannel(it) },
                            onBindNfc = { bindNfcKey() },
                            onScanNfc = { scanNfcUnlock() },
                            onCancelNfc = { stopNfcScan("已取消 NFC 扫描") },
                            onOpenNfcSettings = onOpenNfcSettings,
                            onRefreshNfc = {
                                nfcAvailability = nfcAvailabilityProvider()
                                nfcNotice = when (nfcAvailability) {
                                    NfcAvailability.READY -> "NFC 已可用，可以继续贴卡"
                                    NfcAvailability.DISABLED -> "NFC 仍处于关闭状态"
                                    NfcAvailability.UNSUPPORTED -> "这台手机没有 NFC 读卡硬件"
                                }
                            },
                            onUnlock = { unlock() },
                            onPair = { openPairing() }
                        )
                        MainTab.DEVICES -> DevicesScreen(
                            deviceName = deviceName,
                            deviceId = deviceId,
                            pairedAt = pairedAt,
                            busy = busy,
                            nfcAvailability = nfcAvailability,
                            nfcKeys = nfcKeys,
                            onPair = { openPairing() },
                            onRequestUnpair = { pendingUnpair = true },
                            onBindNfc = { openNfcBinding() },
                            onRenameNfc = { record ->
                                nfcRenameTarget = record
                                nfcRenameValue = record.name
                            },
                            onDeleteNfc = { record -> nfcDeleteTarget = record }
                        )
                        MainTab.SETTINGS -> SettingsScreen(
                            mode = mode,
                            serverUrl = serverUrl,
                            deviceName = deviceName,
                            deviceId = deviceId,
                            pcStatus = pcStatus,
                            credentialReady = credentialReady,
                            busy = busy,
                            status = status,
                            nfcKeysCount = nfcKeys.size,
                            nfcAvailability = nfcAvailability,
                            nfcAutoUnlock = nfcAutoUnlock,
                            onModeChange = { value ->
                                mode = value
                                onModeChange(value)
                                if (value == "ble") {
                                    onNeedBlePermissions()
                                }
                            },
                            onUrlChange = { value ->
                                serverUrl = value
                                onUrlChange(value)
                            },
                            onDeviceNameChange = { value ->
                                val safe = value.take(64)
                                deviceName = safe
                                onDeviceNameChange(safe)
                            },
                            onNfcAutoUnlockChange = { enabled ->
                                nfcAutoUnlock = enabled
                                onNfcAutoUnlockChange(enabled)
                            },
                            onOpenDevices = { tab = MainTab.DEVICES },
                            onOpenNfcSettings = onOpenNfcSettings,
                            onRequestUnpair = { pendingUnpair = true }
                        )
                    }
                }

                if (!showPairing && uiError == null && receipt == null) {
                    BottomNav(
                        selected = tab,
                        onSelect = { tab = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .navigationBarsPadding()
                    )
                }
            }

            AnimatedVisibility(
                visible = showPairing,
                enter = slideInVertically(
                    animationSpec = tween(280),
                    initialOffsetY = { it }
                ) + fadeIn(tween(180)),
                exit = slideOutVertically(
                    animationSpec = tween(220),
                    targetOffsetY = { it }
                ) + fadeOut(tween(140)),
                modifier = Modifier.fillMaxSize()
            ) {
                PairingOverlay(
                    deviceName = deviceName,
                    mode = mode,
                    activeChannel = activeChannel,
                    pairingId = pairingId,
                    pinInput = pinInput,
                    pairError = pairError,
                    remainingSeconds = pairingRemaining,
                    busy = busy,
                    status = status,
                    onPinChange = { pinInput = it },
                    onConfirm = { confirmPairing() },
                    onResend = { startPairing() },
                    onClose = { closePairing() }
                )
            }

            AnimatedVisibility(
                visible = uiError != null,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(140)),
                modifier = Modifier.fillMaxSize()
            ) {
                uiError?.let { error ->
                    ErrorOverlay(
                        error = error,
                        onRetry = {
                            uiError = null
                            if (keyPair == null || deviceId.isEmpty()) {
                                openPairing()
                            } else {
                                unlock()
                            }
                        },
                        onDismiss = {
                            uiError = null
                            status = "已返回解锁主页"
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = receipt != null,
                enter = fadeIn(tween(260)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.fillMaxSize()
            ) {
                receipt?.let { value ->
                    if (value.state == "succeeded") {
                        SuccessOverlay(
                            receipt = value,
                            onDone = { receipt = null }
                        )
                    } else {
                        SubmittedOverlay(
                            receipt = value,
                            busy = busy,
                            notice = receiptNotice,
                            onRefresh = { refreshReceiptOutcome() },
                            onDone = {
                                receipt = null
                                receiptNotice = ""
                            }
                        )
                    }
                }
            }

            if (pendingUnpair) {
                ConfirmUnpairOverlay(
                    busy = busy,
                    onCancel = { pendingUnpair = false },
                    onConfirm = { unpair() }
                )
            }

            nfcRenameTarget?.let { record ->
                NfcKeyNameOverlay(
                    value = nfcRenameValue,
                    onValueChange = { nfcRenameValue = it.take(32) },
                    onCancel = { nfcRenameTarget = null },
                    onConfirm = { renameNfcKey(record, nfcRenameValue) }
                )
            }

            nfcDeleteTarget?.let { record ->
                NfcKeyDeleteOverlay(
                    record = record,
                    onCancel = { nfcDeleteTarget = null },
                    onConfirm = { deleteNfcKey(record) }
                )
            }

            nfcOverwriteTarget?.let { target ->
                NfcOverwriteOverlay(
                    recordCount = target.recordCount,
                    onCancel = { cancelNfcOverwrite() },
                    onConfirm = { confirmNfcOverwrite() }
                )
            }
        }
    }
}

@Composable
private fun UnlockHome(
    deviceName: String,
    mode: String,
    activeChannel: String?,
    paired: Boolean,
    pcStatus: PcStatus,
    credentialReady: Boolean,
    busy: Boolean,
    status: String,
    lastReceipt: UnlockReceipt?,
    nfcMode: Boolean,
    nfcAvailability: NfcAvailability,
    nfcKeys: List<NfcKeyRecord>,
    nfcPhase: NfcPhase,
    nfcNotice: String,
    nfcRejectedHint: String,
    nfcLastPurpose: NfcScanPurpose,
    onChannelChange: (String) -> Unit,
    onBindNfc: () -> Unit,
    onScanNfc: () -> Unit,
    onCancelNfc: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onRefreshNfc: () -> Unit,
    onUnlock: () -> Unit,
    onPair: () -> Unit
) {
    if (nfcMode) {
        NfcUnlockHome(
            deviceName = deviceName,
            paired = paired,
            pcStatus = pcStatus,
            credentialReady = credentialReady,
            busy = busy,
            status = status,
            lastReceipt = lastReceipt,
            nfcAvailability = nfcAvailability,
            nfcKeys = nfcKeys,
            nfcPhase = nfcPhase,
            nfcNotice = nfcNotice,
            nfcRejectedHint = nfcRejectedHint,
            nfcLastPurpose = nfcLastPurpose,
            onChannelChange = onChannelChange,
            onBindNfc = onBindNfc,
            onScanNfc = onScanNfc,
            onCancelNfc = onCancelNfc,
            onOpenNfcSettings = onOpenNfcSettings,
            onRefreshNfc = onRefreshNfc,
            onPair = onPair
        )
        return
    }

    PhoneUnlockHome(
        deviceName = deviceName,
        mode = mode,
        activeChannel = activeChannel,
        paired = paired,
        pcStatus = pcStatus,
        credentialReady = credentialReady,
        busy = busy,
        status = status,
        lastReceipt = lastReceipt,
        onChannelChange = onChannelChange,
        onUnlock = onUnlock,
        onPair = onPair
    )
}

@Composable
private fun PhoneUnlockHome(
    deviceName: String,
    mode: String,
    activeChannel: String?,
    paired: Boolean,
    pcStatus: PcStatus,
    credentialReady: Boolean,
    busy: Boolean,
    status: String,
    lastReceipt: UnlockReceipt?,
    onChannelChange: (String) -> Unit,
    onUnlock: () -> Unit,
    onPair: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dialSize = minOf(maxWidth * 0.68f, maxHeight * 0.38f, 248.dp)
        val channelValue = when (activeChannel) {
            "ble" -> "蓝牙 LE"
            "lan" -> "局域网"
            else -> if (mode == "ble") "蓝牙优先" else "局域网"
        }

        val pcHeading = when {
            busy -> "正在连接电脑"
            !paired -> "先配对一台电脑"
            !pcStatus.checked -> "正在确认电脑状态"
            pcStatus.protocolVersion < 2 -> "电脑服务需要升级"
            !pcStatus.online -> "电脑离线"
            pcStatus.revoked -> "设备已被电脑撤销"
            !pcStatus.paired -> "电脑未确认此设备"
            pcStatus.credentialRejected -> "Windows 凭据已失效"
            !pcStatus.credentialSet -> "电脑缺少解锁凭据"
            !pcStatus.isLocked -> "电脑未锁屏或组件未就绪"
            else -> "电脑已就绪"
        }
        val pcDialTitle = when {
            busy -> "正在验证"
            !paired -> "等待配对"
            !pcStatus.checked -> "正在同步"
            pcStatus.protocolVersion < 2 -> "需要升级"
            !pcStatus.online -> "等待电脑"
            pcStatus.revoked -> "授权已撤销"
            pcStatus.credentialRejected -> "凭据已失效"
            !pcStatus.credentialSet -> "凭据待确认"
            !pcStatus.isLocked -> "未锁屏/组件未就绪"
            else -> "手机已认证"
        }
        val pcDialLabel = when {
            busy -> "VERIFYING"
            !paired -> "STANDBY"
            !pcStatus.checked -> "SYNCING"
            pcStatus.protocolVersion < 2 -> "UPGRADE"
            !pcStatus.online -> "OFFLINE"
            pcStatus.revoked -> "REVOKED"
            pcStatus.credentialRejected || !pcStatus.credentialSet -> "ATTENTION"
            !pcStatus.isLocked -> "NOT READY"
            else -> "READY"
        }
        val pcChannelMeta = when {
            !pcStatus.checked -> "正在同步"
            pcStatus.online -> "状态已确认"
            else -> "按需建立连接"
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            BrandHeader(
                statusText = when {
                    busy -> "CONNECTING"
                    paired -> "PAIRED"
                    else -> "STANDBY"
                },
                tone = when {
                    busy -> Bk.Amber
                    paired -> Bk.Mint
                    else -> Bk.Iris
                },
                pulsing = busy
            )
            Spacer(Modifier.height(12.dp))
            SegmentedControl(
                options = listOf("蓝牙 LE", "NFC 钥匙"),
                selectedIndex = 0,
                onSelect = { index -> if (index == 1) onChannelChange("nfc") },
                modifier = Modifier.fillMaxWidth()
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "${greeting()}，${deviceName.ifBlank { "我的手机" }}",
                    color = Bk.Muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.4.sp
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = pcHeading,
                    color = Bk.Text,
                    fontSize = 29.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.2.sp
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = if (paired) {
                        "本机签名验证 · 私钥不出手机"
                    } else {
                        "蓝牙优先，局域网自动回退"
                    },
                    color = Bk.Dim,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp
                )

                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TrustDial(
                        channelReady = pcStatus.online,
                        identityReady = paired && !pcStatus.revoked,
                        credentialReady = credentialReady,
                        busy = busy,
                        statusLabel = pcDialLabel,
                        title = pcDialTitle,
                        subtitle = if (paired) {
                            "ECDSA P-256 · ANDROID"
                        } else {
                            "等待绑定设备身份"
                        },
                        modifier = Modifier.size(dialSize)
                    )
                }

                Spacer(Modifier.height(14.dp))
                StatusRail(
                    items = listOf(
                        RailItem(
                            label = "连接通道",
                            value = channelValue,
                            meta = pcChannelMeta,
                            tone = if (mode == "ble") Bk.Mint else Bk.Iris,
                            pulsing = busy
                        ),
                        RailItem(
                            label = "设备身份",
                            value = when {
                                pcStatus.revoked -> "已撤销"
                                paired -> "已认证"
                                else -> "未配对"
                            },
                            meta = if (paired) "ECDSA P-256" else "等待设备密钥",
                            tone = if (pcStatus.revoked) Bk.Coral else if (paired) Bk.Iris else Bk.Amber
                        ),
                        RailItem(
                            label = "Windows 凭据",
                            value = if (credentialReady) "已保存" else "待确认",
                            meta = "仅存于电脑",
                            tone = if (credentialReady) Bk.Mint else Bk.Amber,
                            pulsing = !credentialReady
                        )
                    )
                )

                Spacer(Modifier.height(20.dp))
                SectionLabel(
                    text = "ACTIVITY",
                    trailing = if (lastReceipt != null) "RECEIPT" else "LOCAL"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = lastReceipt?.let {
                        "上次解锁 ${formatShortTime(it.at)} · ${it.channel}"
                    } ?: "最近一次解锁回执会在成功后显示",
                    color = if (lastReceipt != null) Bk.Muted else Bk.Dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(16.dp))
            }

            PrimaryAction(
                text = if (paired) {
                    if (busy) "正在解锁…" else "解 锁 电 脑"
                } else {
                    "配对电脑"
                },
                secondaryText = if (paired) {
                    if (busy) "等待电脑响应" else "点按后等待电脑响应"
                } else {
                    "安全绑定本机密钥"
                },
                enabled = !busy && (
                    !paired || (
                        (pcStatus.checked && pcStatus.protocolVersion < 2) ||
                            (
                                pcStatus.online &&
                                    pcStatus.paired &&
                                    pcStatus.credentialSet &&
                                    !pcStatus.credentialRejected
                                )
                        )
                    ),
                busy = busy,
                glyph = if (paired) BkGlyph.LOCK else BkGlyph.KEY,
                onClick = if (paired) onUnlock else onPair,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(9.dp))
            StatusNote(
                text = status,
                tone = if (status.contains("失败") || status.contains("错误")) {
                    Bk.Coral
                } else {
                    Bk.Dim
                },
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun NfcUnlockHome(
    deviceName: String,
    paired: Boolean,
    pcStatus: PcStatus,
    credentialReady: Boolean,
    busy: Boolean,
    status: String,
    lastReceipt: UnlockReceipt?,
    nfcAvailability: NfcAvailability,
    nfcKeys: List<NfcKeyRecord>,
    nfcPhase: NfcPhase,
    nfcNotice: String,
    nfcRejectedHint: String,
    nfcLastPurpose: NfcScanPurpose,
    onChannelChange: (String) -> Unit,
    onBindNfc: () -> Unit,
    onScanNfc: () -> Unit,
    onCancelNfc: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onRefreshNfc: () -> Unit,
    onPair: () -> Unit
) {
    val activeKey = nfcKeys.firstOrNull()
    val unavailable = nfcAvailability != NfcAvailability.READY
    val scanning = nfcPhase == NfcPhase.SCANNING
    val writing = nfcPhase == NfcPhase.BINDING
    val confirming = nfcPhase == NfcPhase.CONFIRM
    val verifying = nfcPhase == NfcPhase.VERIFYING
    val rejected = nfcPhase == NfcPhase.REJECTED
    val failed = nfcPhase == NfcPhase.ERROR

    val heading = when {
        nfcAvailability == NfcAvailability.UNSUPPORTED -> "这台手机不支持 NFC"
        nfcAvailability == NfcAvailability.DISABLED -> "NFC 已关闭"
        scanning && activeKey == null -> "绑定 NFC 钥匙"
        scanning -> "贴卡解锁"
        writing -> "正在写入卡片"
        confirming -> "确认覆盖卡片"
        verifying -> "正在验证卡片"
        rejected -> "这张卡尚未绑定"
        failed -> "NFC 读取失败"
        activeKey == null -> "还没有 NFC 钥匙"
        !paired -> "先配对一台电脑"
        !pcStatus.checked -> "正在确认电脑状态"
        pcStatus.protocolVersion < 2 -> "电脑服务需要升级"
        !pcStatus.online -> "电脑离线"
        pcStatus.revoked -> "设备授权已撤销"
        !credentialReady -> "请检查 Windows 凭据"
        else -> "贴卡解锁"
    }
    val subtitle = when {
        nfcAvailability == NfcAvailability.UNSUPPORTED ->
            "NFC 解锁需要具备读卡能力的 Android 手机，当前仍可使用蓝牙或局域网。"
        nfcAvailability == NfcAvailability.DISABLED ->
            "请先在系统设置中开启 NFC，然后返回重试。"
        scanning && activeKey == null ->
            "请将 NFC 卡贴近手机背面；空白卡会直接写入新的随机身份令牌，已有数据的卡会先询问是否覆盖。"
        scanning ->
            "将 NFC 钥匙靠近手机背面，保持 1–2 秒。"
        writing ->
            "请保持卡片贴近手机，写入完成后会自动保存。"
        confirming ->
            nfcNotice.ifBlank { "卡片已有其他 NDEF 数据，请确认是否覆盖。" }
        verifying ->
            "已读取身份令牌，正在本机核对授权。"
        rejected ->
            nfcRejectedHint.ifBlank {
                "已读取 BandKey 卡，但卡片内容不属于本机。"
            }.let { "$it · 未加入本机授权列表" }
        failed ->
            nfcNotice.ifBlank { "请重新贴卡，或检查卡片是否支持 NDEF。" }
        activeKey == null ->
            "绑定一张实体卡，作为这台电脑的本地备用钥匙。"
        !paired ->
            "NFC 钥匙已绑定，但解锁电脑前仍需完成一次设备配对。"
        else ->
            "轻触实体钥匙即可解锁电脑，卡片内容不会离开手机。"
    }
    val dialState = when {
        unavailable -> NfcDialState.UNAVAILABLE
        scanning -> NfcDialState.SCANNING
        confirming -> NfcDialState.SCANNING
        writing || verifying || busy -> NfcDialState.VERIFYING
        rejected -> NfcDialState.REJECTED
        activeKey == null -> NfcDialState.UNBOUND
        else -> NfcDialState.READY
    }
    val dialLabel = when (dialState) {
        NfcDialState.UNBOUND -> "NO KEY"
        NfcDialState.READY -> "NFC READY"
        NfcDialState.SCANNING -> if (confirming) "CONFIRM" else "SCANNING"
        NfcDialState.VERIFYING -> if (writing) "WRITING" else "VERIFYING"
        NfcDialState.REJECTED -> "NOT BOUND"
        NfcDialState.UNAVAILABLE -> "UNAVAILABLE"
    }
    val tone = when {
        rejected || failed -> Bk.Coral
        scanning || writing || confirming || verifying || busy -> Bk.Amber
        activeKey != null && paired && !unavailable -> Bk.Mint
        else -> Bk.Dim
    }
    val statusText = when {
        nfcAvailability == NfcAvailability.UNSUPPORTED -> "NO RADIO"
        nfcAvailability == NfcAvailability.DISABLED -> "NFC OFF"
        rejected -> "DENIED"
        failed -> "ERROR"
        scanning -> "SCANNING"
        confirming -> "CONFIRM"
        writing || verifying || busy -> "VERIFY"
        activeKey == null -> "NO KEY"
        paired -> "NFC READY"
        else -> "PAIR FIRST"
    }
    val primaryText = when {
        nfcAvailability == NfcAvailability.UNSUPPORTED -> "NFC 不可用"
        nfcAvailability == NfcAvailability.DISABLED -> "前往系统设置"
        scanning -> "正在扫描…"
        writing -> "正在写入卡片…"
        confirming -> "等待确认覆盖…"
        verifying || busy -> "正在验证卡片…"
        rejected || failed ->
            if (nfcLastPurpose == NfcScanPurpose.BIND || activeKey == null) "重新绑定 NFC 钥匙" else "重新贴卡"
        activeKey == null -> "绑定 NFC 钥匙"
        !paired -> "先配对电脑"
        else -> "开始贴卡解锁"
    }
    val primaryBusy = scanning || writing || confirming || verifying || busy
    val primaryEnabled = when {
        nfcAvailability == NfcAvailability.UNSUPPORTED -> false
        primaryBusy -> false
        nfcAvailability == NfcAvailability.DISABLED -> true
        else -> true
    }
    val primaryAction = when {
        nfcAvailability == NfcAvailability.DISABLED -> onOpenNfcSettings
        rejected || failed ->
            if (nfcLastPurpose == NfcScanPurpose.BIND || activeKey == null) onBindNfc else onScanNfc
        scanning || writing || confirming || verifying || busy -> ({})
        activeKey == null -> onBindNfc
        !paired -> onPair
        else -> onScanNfc
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dialSize = minOf(maxWidth * 0.64f, 228.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            BrandHeader(
                statusText = statusText,
                tone = tone,
                pulsing = scanning || writing || confirming || verifying || busy
            )
            Spacer(Modifier.height(12.dp))
            SegmentedControl(
                options = listOf("蓝牙 LE", "NFC 钥匙"),
                selectedIndex = 1,
                onSelect = { index -> if (index == 0) onChannelChange("phone") },
                modifier = Modifier.fillMaxWidth()
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "${greeting()}，${deviceName.ifBlank { "我的手机" }}",
                    color = Bk.Muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.4.sp
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = heading,
                    color = Bk.Text,
                    fontSize = 27.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    color = Bk.Muted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )

                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    NfcTapDial(
                        state = dialState,
                        statusLabel = dialLabel,
                        modifier = Modifier.size(dialSize)
                    )
                }
                Spacer(Modifier.height(8.dp))

                StatusRail(
                    items = listOf(
                        RailItem(
                            label = "NFC 读取器",
                            value = when (nfcAvailability) {
                                NfcAvailability.READY ->
                                    if (scanning) "正在扫描" else "已启用"
                                NfcAvailability.DISABLED -> "已关闭"
                                NfcAvailability.UNSUPPORTED -> "不可用"
                            },
                            meta = "NFC READER",
                            tone = when (nfcAvailability) {
                                NfcAvailability.READY ->
                                    if (scanning) Bk.Amber else Bk.Mint
                                NfcAvailability.DISABLED -> Bk.Amber
                                NfcAvailability.UNSUPPORTED -> Bk.Coral
                            },
                            pulsing = scanning
                        ),
                        RailItem(
                            label = "NFC 钥匙",
                            value = activeKey?.hint ?: "未绑定",
                            meta = activeKey?.name ?: "等待绑定",
                            tone = when {
                                rejected || failed -> Bk.Coral
                                activeKey != null -> Bk.Iris
                                else -> Bk.Dim
                            }
                        ),
                        RailItem(
                            label = "Windows 凭据",
                            value = if (credentialReady) "已保存" else "待确认",
                            meta = "仅存于电脑",
                            tone = if (credentialReady) Bk.Mint else Bk.Amber,
                            pulsing = !credentialReady
                        )
                    )
                )

                Spacer(Modifier.height(18.dp))
                SectionLabel(
                    text = "NFC ACTIVITY",
                    trailing = if (lastReceipt != null) "RECEIPT" else "LOCAL"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = lastReceipt?.let {
                        "上次解锁 ${formatShortTime(it.at)} · ${it.channel}"
                    } ?: "卡片只在手机本地读取，电脑只接收签名解锁请求",
                    color = if (lastReceipt != null) Bk.Muted else Bk.Dim,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(12.dp))
                if (nfcNotice.isNotBlank()) {
                    StatusNote(
                        text = nfcNotice,
                        tone = when {
                            rejected || failed -> Bk.Coral
                            scanning || writing || verifying || busy -> Bk.Amber
                            activeKey != null -> Bk.Mint
                            else -> Bk.Muted
                        },
                        glyph = when {
                            rejected || failed -> BkGlyph.ALERT
                            activeKey != null -> BkGlyph.SHIELD
                            else -> BkGlyph.NFC
                        }
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            PrimaryAction(
                text = primaryText,
                secondaryText = when {
                    nfcAvailability == NfcAvailability.UNSUPPORTED -> "当前设备没有 NFC 读卡硬件"
                    nfcAvailability == NfcAvailability.DISABLED -> "开启 NFC 后返回重试"
                    scanning -> "把卡片贴近手机背面"
                    writing -> "请保持卡片贴近手机"
                    verifying || busy -> "正在核对钥匙身份"
                    rejected || failed -> "重新读取并验证卡片"
                    activeKey == null -> "卡片只保存随机身份令牌"
                    !paired -> "安全绑定本机密钥"
                    else -> activeKey.hint
                },
                enabled = primaryEnabled,
                busy = primaryBusy,
                glyph = if (activeKey == null) BkGlyph.NFC else BkGlyph.LOCK,
                tone = if (rejected || failed) Bk.Coral else Bk.Mint,
                onClick = primaryAction,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            SecondaryAction(
                text = when {
                    nfcAvailability == NfcAvailability.DISABLED -> "重新检测 NFC"
                    nfcAvailability == NfcAvailability.UNSUPPORTED -> "返回蓝牙解锁"
                    scanning -> "取消扫描"
                    rejected -> "继续使用蓝牙"
                    activeKey == null -> "继续使用蓝牙"
                    else -> "继续使用蓝牙"
                },
                onClick = when {
                    nfcAvailability == NfcAvailability.DISABLED -> onRefreshNfc
                    nfcAvailability == NfcAvailability.UNSUPPORTED -> ({ onChannelChange("phone") })
                    scanning -> onCancelNfc
                    rejected -> ({ onChannelChange("phone") })
                    else -> ({ onChannelChange("phone") })
                },
                enabled = !primaryBusy || scanning,
                tone = if (scanning) Bk.Amber else Bk.Muted,
                glyph = when {
                    nfcAvailability == NfcAvailability.DISABLED -> BkGlyph.REFRESH
                    scanning -> BkGlyph.CLOSE
                    rejected -> BkGlyph.BLUETOOTH
                    else -> BkGlyph.BLUETOOTH
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            StatusNote(
                text = status,
                tone = if (status.contains("失败") || status.contains("错误")) Bk.Coral else Bk.Dim
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun DevicesScreen(
    deviceName: String,
    deviceId: String,
    pairedAt: Long,
    busy: Boolean,
    nfcAvailability: NfcAvailability,
    nfcKeys: List<NfcKeyRecord>,
    onPair: () -> Unit,
    onRequestUnpair: () -> Unit,
    onBindNfc: () -> Unit,
    onRenameNfc: (NfcKeyRecord) -> Unit,
    onDeleteNfc: (NfcKeyRecord) -> Unit
) {
    val paired = deviceId.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        BrandHeader(
            statusText = if (paired) "01 DEVICE" else "NO DEVICE",
            tone = if (paired) Bk.Mint else Bk.Iris,
            pulsing = false
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "授权设备",
                    color = Bk.Text,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "AUTHORIZED DEVICES",
                    color = Bk.Mint,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp
                )
            }
            IconAction(
                glyph = BkGlyph.PLUS,
                onClick = onPair,
                tint = Bk.Mint,
                enabled = !busy
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 18.dp)
        ) {
            TechCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (paired) "01" else "00",
                            color = if (paired) Bk.Mint else Bk.Dim,
                            fontSize = 38.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "台设备可解锁本机",
                            color = Bk.Muted,
                            fontSize = 10.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "LAST SYNC",
                            color = Bk.Dim,
                            fontSize = 8.sp,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = if (pairedAt > 0) formatShortTime(pairedAt) else "--:--",
                            color = Bk.Text,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "仅显示本机当前配对设备，不伪造多设备列表。",
                    color = Bk.Dim,
                    fontSize = 9.sp
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel(
                text = "NFC KEYS",
                trailing = "${nfcKeys.size}/${NfcKeyStore.MAX_KEYS}"
            )
            Spacer(Modifier.height(8.dp))
            if (nfcKeys.isEmpty()) {
                TechCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphBadge(
                            glyph = BkGlyph.NFC,
                            tone = Bk.Iris,
                            size = 42.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "还没有 NFC 钥匙",
                                color = Bk.Text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "卡片只保存随机令牌，原文不会离开手机。",
                                color = Bk.Muted,
                                fontSize = 9.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SecondaryAction(
                        text = "绑定 NFC 钥匙",
                        onClick = onBindNfc,
                        enabled = !busy && nfcAvailability != NfcAvailability.UNSUPPORTED,
                        tone = Bk.Iris,
                        glyph = BkGlyph.NFC,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                nfcKeys.forEachIndexed { index, record ->
                    if (index > 0) {
                        Spacer(Modifier.height(8.dp))
                    }
                    NfcKeyRow(
                        record = record,
                        onRename = { onRenameNfc(record) },
                        onDelete = { onDeleteNfc(record) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                SecondaryAction(
                    text = if (nfcKeys.size >= NfcKeyStore.MAX_KEYS) {
                        "已达 ${NfcKeyStore.MAX_KEYS} 张上限"
                    } else {
                        "添加另一张 NFC 钥匙"
                    },
                    onClick = onBindNfc,
                    enabled = !busy &&
                        nfcKeys.size < NfcKeyStore.MAX_KEYS &&
                        nfcAvailability != NfcAvailability.UNSUPPORTED,
                    tone = Bk.Iris,
                    glyph = BkGlyph.PLUS,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
            StatusNote(
                text = when (nfcAvailability) {
                    NfcAvailability.READY -> "NFC 由手机读取；电脑只接收已签名的解锁请求。"
                    NfcAvailability.DISABLED -> "系统 NFC 已关闭，绑定前需要先开启。"
                    NfcAvailability.UNSUPPORTED -> "当前手机没有 NFC 读卡硬件。"
                },
                tone = when (nfcAvailability) {
                    NfcAvailability.READY -> Bk.Dim
                    NfcAvailability.DISABLED -> Bk.Amber
                    NfcAvailability.UNSUPPORTED -> Bk.Coral
                },
                glyph = when (nfcAvailability) {
                    NfcAvailability.READY -> BkGlyph.SHIELD
                    else -> BkGlyph.ALERT
                }
            )

            Spacer(Modifier.height(18.dp))
            SectionLabel(text = "DEVICE DETAILS", trailing = "LOCAL")
            Spacer(Modifier.height(10.dp))
            if (paired) {
                DeviceRow(
                    name = deviceName.ifBlank { "我的手机" },
                    meta = "Android · ECDSA P-256 · 本机签名",
                    trailing = "当前设备",
                    active = true
                )
                Spacer(Modifier.height(14.dp))
                SettingCard(
                    title = "设备凭据",
                    subtitle = "配对记录由 Android Keystore 加密保存",
                    statusText = "VERIFIED",
                    statusTone = Bk.Mint
                ) {
                    InfoRow(
                        label = "配对时间",
                        value = formatFullTime(pairedAt),
                        mono = true
                    )
                    InfoRow(
                        label = "设备 ID",
                        value = shortDeviceId(deviceId),
                        mono = true,
                        valueColor = Bk.Mint,
                        valueSize = 10
                    )
                    InfoRow(
                        label = "签名算法",
                        value = "ECDSA P-256"
                    )
                    InfoRow(
                        label = "私钥位置",
                        value = "Android Keystore",
                        valueColor = Bk.Iris
                    )
                }
                Spacer(Modifier.height(18.dp))
                SectionLabel(text = "ACCESS POLICY", trailing = "LOCAL ONLY")
                Spacer(Modifier.height(8.dp))
                StatusNote(
                    text = "删除设备后，其私钥立即失效，无法再解锁这台电脑。",
                    tone = Bk.Muted,
                    glyph = BkGlyph.SHIELD
                )
                Spacer(Modifier.height(14.dp))
                SecondaryAction(
                    text = "解除本机配对",
                    onClick = onRequestUnpair,
                    tone = Bk.Coral,
                    glyph = BkGlyph.TRASH,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                TechCard(modifier = Modifier.fillMaxWidth(), padding = 20.dp) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Bk.Iris.copy(alpha = 0.09f)),
                        contentAlignment = Alignment.Center
                    ) {
                        BkIcon(
                            glyph = BkGlyph.PHONE,
                            tint = Bk.Iris,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.height(15.dp))
                    Text(
                        text = "尚未绑定设备",
                        color = Bk.Text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "完成一次配对后，这台手机才会出现在授权设备中。",
                        color = Bk.Muted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    PrimaryAction(
                        text = "开始配对",
                        enabled = !busy,
                        glyph = BkGlyph.KEY,
                        onClick = onPair,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NfcKeyRow(
    record: NfcKeyRecord,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    TechCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphBadge(
                glyph = BkGlyph.NFC,
                tone = Bk.Iris,
                size = 40.dp
            )
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.name,
                    color = Bk.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = record.hint,
                    color = Bk.Mint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            }
            IconAction(
                glyph = BkGlyph.KEY,
                onClick = onRename,
                tint = Bk.Muted
            )
            Spacer(Modifier.width(6.dp))
            IconAction(
                glyph = BkGlyph.TRASH,
                onClick = onDelete,
                tint = Bk.Coral
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (record.lastUsedAt > 0) {
                    "最近使用 ${formatShortTime(record.lastUsedAt)}"
                } else {
                    "尚未使用"
                },
                color = Bk.Muted,
                fontSize = 9.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "TOKEN SHA-256",
                color = Bk.Dim,
                fontSize = 8.sp,
                letterSpacing = 0.8.sp
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    mode: String,
    serverUrl: String,
    deviceName: String,
    deviceId: String,
    pcStatus: PcStatus,
    credentialReady: Boolean,
    busy: Boolean,
    status: String,
    nfcKeysCount: Int,
    nfcAvailability: NfcAvailability,
    nfcAutoUnlock: Boolean,
    onModeChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onNfcAutoUnlockChange: (Boolean) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onRequestUnpair: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val paired = deviceId.isNotEmpty()
    var nfcLastEvent by remember { mutableStateOf(NfcDiagnostics.lastEvent(context)) }
    var nfcTagIntentState by remember { mutableStateOf(NfcTagIntentAccess.read(context)) }
    val nfcLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(nfcLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                nfcLastEvent = NfcDiagnostics.lastEvent(context)
                nfcTagIntentState = NfcTagIntentAccess.read(context)
            }
        }
        nfcLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { nfcLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val vendorSetupRequired = remember {
        VendorSettings.isXiaomi() || VendorSettings.isHuaweiOrHonor()
    }
    val isXiaomi = remember { VendorSettings.isXiaomi() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        BrandHeader(
            statusText = when {
                pcStatus.online -> "ONLINE"
                mode == "ble" -> "BLE FIRST"
                else -> "LAN MODE"
            },
            tone = if (mode == "ble") Bk.Mint else Bk.Iris,
            pulsing = false
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = "连接与安全",
            color = Bk.Text,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "CONNECTION / SECURITY",
            color = Bk.Mint,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.7.sp
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 18.dp)
        ) {
            SectionLabel(text = "TRANSPORT", trailing = "PREFERRED")
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                options = listOf("蓝牙优先", "局域网"),
                selectedIndex = if (mode == "ble") 0 else 1,
                onSelect = { index ->
                    onModeChange(if (index == 0) "ble" else "wifi")
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))

            SettingCard(
                title = "蓝牙 LE",
                subtitle = "自动扫描电脑广播，失败时回退局域网 TCP。",
                statusText = if (mode == "ble") "优先" else "备用",
                statusTone = if (mode == "ble") Bk.Mint else Bk.Muted
            ) {
                InfoRow(label = "设备发现", value = "BandKey 广播 / 自动扫描")
                InfoRow(label = "失败回退", value = "TCP 47321", mono = true)
                InfoRow(
                    label = "当前设备名",
                    value = deviceName.ifBlank { "我的手机" },
                    valueColor = Bk.Iris
                )
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(
                title = "服务地址",
                subtitle = "用于局域网回退和 WebSocket 调试。",
                statusText = if (mode == "wifi") "当前" else "备用",
                statusTone = if (mode == "wifi") Bk.Iris else Bk.Muted
            ) {
                TechTextField(
                    value = serverUrl,
                    onValueChange = onUrlChange,
                    label = "WebSocket 地址",
                    placeholder = "ws://192.168.1.71:23234/bandkey",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingText = "EDIT",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                StatusNote(
                    text = "局域网回退会读取该地址中的电脑 IP，无需额外配置端口。",
                    tone = Bk.Dim,
                    glyph = BkGlyph.LINK
                )
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(
                title = "设备名称",
                subtitle = "下次配对时显示在电脑托盘与配对弹窗中。",
                statusText = "LOCAL",
                statusTone = Bk.Iris
            ) {
                TechTextField(
                    value = deviceName,
                    onValueChange = onDeviceNameChange,
                    label = "电脑端显示名称",
                    placeholder = "我的手机",
                    trailingText = "${deviceName.length}/64",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(
                title = "息屏 NFC 自动解锁",
                subtitle = "亮屏锁屏或息屏时贴卡；息屏触发取决于系统 NFC 策略。",
                statusText = if (nfcAutoUnlock) "ARMED" else "OFF",
                statusTone = if (nfcAutoUnlock) Bk.Mint else Bk.Muted
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "自动请求电脑解锁",
                            color = Bk.Text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "识别卡片后直接验签；亮屏锁屏时无需打开 App，息屏能力由系统决定。",
                            color = Bk.Muted,
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = nfcAutoUnlock,
                        onCheckedChange = onNfcAutoUnlockChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Bk.Background,
                            checkedTrackColor = Bk.Mint,
                            uncheckedThumbColor = Bk.Muted,
                            uncheckedTrackColor = Bk.Surface2,
                            uncheckedBorderColor = Bk.LineStrong
                        )
                    )
                }
                Spacer(Modifier.height(8.dp))
                InfoRow(
                    label = "已绑定 NFC 钥匙",
                    value = "$nfcKeysCount / ${NfcKeyStore.MAX_KEYS}",
                    valueColor = if (nfcKeysCount > 0) Bk.Mint else Bk.Amber
                )
                InfoRow(
                    label = "系统 NFC",
                    value = when (nfcAvailability) {
                        NfcAvailability.READY -> "已开启"
                        NfcAvailability.DISABLED -> "已关闭"
                        NfcAvailability.UNSUPPORTED -> "不支持"
                    },
                    valueColor = when (nfcAvailability) {
                        NfcAvailability.READY -> Bk.Mint
                        NfcAvailability.DISABLED -> Bk.Amber
                        NfcAvailability.UNSUPPORTED -> Bk.Coral
                    }
                )
                InfoRow(
                    label = "系统分发",
                    value = "NDEF 优先 / TECH 兜底",
                    valueColor = Bk.Mint
                )
                InfoRow(
                    label = "NFC 启动权限",
                    value = when {
                        !nfcTagIntentState.nfcAvailable -> "无 NFC 硬件"
                        !nfcTagIntentState.preferenceSupported -> "系统未限制"
                        nfcTagIntentState.allowed -> "已允许"
                        else -> "已禁止"
                    },
                    valueColor = when {
                        !nfcTagIntentState.nfcAvailable -> Bk.Coral
                        !nfcTagIntentState.preferenceSupported -> Bk.Muted
                        nfcTagIntentState.allowed -> Bk.Mint
                        else -> Bk.Coral
                    }
                )
                InfoRow(
                    label = "上次卡片事件",
                    value = nfcLastEvent?.let {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.at))
                    } ?: "未收到",
                    valueColor = if (nfcLastEvent == null) Bk.Amber else Bk.Mint
                )
                InfoRow(
                    label = "生效场景",
                    value = "亮屏锁屏 / 息屏（视机型）",
                    valueColor = Bk.Iris
                )
                val lastEvent = nfcLastEvent
                if (lastEvent != null) {
                    Spacer(Modifier.height(8.dp))
                    StatusNote(
                        text = "上次事件：${lastEvent.action} · ${lastEvent.detail}",
                        tone = Bk.Dim,
                        glyph = BkGlyph.NFC
                    )
                }

                if (nfcKeysCount == 0) {
                    Spacer(Modifier.height(10.dp))
                    StatusNote(
                        text = "还没有绑定 NFC 钥匙。请先在设备页写入并绑定一张 NDEF 卡。",
                        tone = Bk.Amber,
                        glyph = BkGlyph.NFC
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryAction(
                        text = "前往设备页绑定",
                        onClick = onOpenDevices,
                        glyph = BkGlyph.NFC,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!nfcTagIntentState.allowed) {
                    Spacer(Modifier.height(8.dp))
                    StatusNote(
                        text = "系统已禁止 BandKey 接收 NFC 卡片事件。Android 16 及以上会在「通过 NFC 启动」中按应用管控，关闭后贴卡不会启动任何 BandKey 页面。",
                        tone = Bk.Coral,
                        glyph = BkGlyph.ALERT
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryAction(
                        text = "允许 BandKey 通过 NFC 启动",
                        onClick = {
                            if (!NfcTagIntentAccess.openSettings(context)) {
                                Toast.makeText(
                                    context,
                                    "无法打开 NFC 启动权限页，请前往 设置 → 应用 → 特殊应用权限 → 通过 NFC 启动",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        glyph = BkGlyph.NFC,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(8.dp))
                SecondaryAction(
                    text = "检查锁屏 NFC 与系统设置",
                    onClick = onOpenNfcSettings,
                    glyph = BkGlyph.SETTINGS,
                    modifier = Modifier.fillMaxWidth()
                )

                if (nfcAutoUnlock && nfcKeysCount > 0 && nfcLastEvent == null) {
                    Spacer(Modifier.height(8.dp))
                    StatusNote(
                        text = "还没有收到系统 NFC 分发。贴卡后这里仍显示「未收到」，表示事件没有交给 BandKey，请优先开启系统「锁屏时使用 NFC」。",
                        tone = Bk.Amber,
                        glyph = BkGlyph.ALERT
                    )
                }

                Spacer(Modifier.height(8.dp))
                StatusNote(
                    text = "Android 通常只在屏幕解锁后扫描 NFC，部分厂商才支持锁屏/息屏读取。若息屏贴卡无反应，请先点亮屏幕并保持锁屏再贴卡，以区分系统限制和 App 读卡问题。",
                    tone = Bk.Dim,
                    glyph = BkGlyph.SHIELD
                )

                if (vendorSetupRequired) {
                    Spacer(Modifier.height(8.dp))
                    StatusNote(
                        text = if (isXiaomi) {
                            "小米 / HyperOS 若贴卡无反应，请开启系统 NFC 的「锁屏时使用 NFC」，并允许 BandKey「后台弹出界面 / 锁屏显示」、自启动，电池策略设为「无限制」。"
                        } else {
                            "若贴卡完全没有反应，请在应用权限中允许「后台弹出界面 / 锁屏显示」，并开启系统 NFC 的「锁屏时使用 NFC」。"
                        },
                        tone = Bk.Amber,
                        glyph = BkGlyph.ALERT
                    )
                    if (isXiaomi) {
                        Spacer(Modifier.height(8.dp))
                        SecondaryAction(
                            text = "小米自启动与后台权限",
                            onClick = { VendorSettings.openAutoStart(context) },
                            glyph = BkGlyph.SETTINGS,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        SecondaryAction(
                            text = "小米锁屏显示与权限",
                            onClick = { VendorSettings.openPermissionEditor(context) },
                            glyph = BkGlyph.SHIELD,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        SecondaryAction(
                            text = "小米省电策略",
                            onClick = { VendorSettings.openBatterySettings(context) },
                            glyph = BkGlyph.LINK,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        SecondaryAction(
                            text = "默认触碰付款应用",
                            onClick = { VendorSettings.openNfcPaymentSettings(context) },
                            glyph = BkGlyph.NFC,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                        SecondaryAction(
                            text = "打开应用权限设置",
                            onClick = { VendorSettings.openAppDetails(context) },
                            glyph = BkGlyph.SETTINGS,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(
                title = "桌面小组件",
                subtitle = "无需打开应用，在桌面查看状态并一键解锁。",
                statusText = "WIDGET",
                statusTone = Bk.Iris
            ) {
                InfoRow(
                    label = "组件尺寸",
                    value = "2 × 1 至 4 × 3（可调整）",
                    valueColor = Bk.Iris
                )
                InfoRow(
                    label = "快捷操作",
                    value = "解锁 / 打开应用 / NFC 开关"
                )
                if (isXiaomi) {
                    InfoRow(
                        label = "小米桌面",
                        value = "专属 2 × 2 卡片",
                        valueColor = Bk.Mint
                    )
                    InfoRow(
                        label = "小米小部件中心",
                        value = "需平台审核",
                        valueColor = Bk.Amber
                    )
                }
                Spacer(Modifier.height(10.dp))
                SecondaryAction(
                    text = if (isXiaomi) "添加小米 2×2 卡片" else "添加到桌面",
                    onClick = {
                        val manager = AppWidgetManager.getInstance(context)
                        val provider = ComponentName(
                            context,
                            if (isXiaomi) {
                                BandKeyMiuiWidgetProvider::class.java
                            } else {
                                BandKeyWidgetProvider::class.java
                            }
                        )
                        val extras = if (isXiaomi) {
                            Bundle().apply {
                                putString("addType", "appWidgetDetail")
                                putString(
                                    "widgetName",
                                    "${context.packageName}/${BandKeyMiuiWidgetProvider::class.java.name}"
                                )
                            }
                        } else {
                            null
                        }
                        val pinned = if (manager.isRequestPinAppWidgetSupported) {
                            runCatching {
                                manager.requestPinAppWidget(provider, extras, null)
                            }.getOrDefault(false)
                        } else {
                            false
                        }
                        if (!pinned) {
                            Toast.makeText(
                                context,
                                "当前桌面不支持自动添加，请长按桌面选择「添加小部件 / 微件」手动添加",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    glyph = BkGlyph.SHIELD,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isXiaomi) {
                    Spacer(Modifier.height(6.dp))
                    SecondaryAction(
                        text = "兼容：添加安卓小组件",
                        onClick = {
                            val manager = AppWidgetManager.getInstance(context)
                            val provider = ComponentName(
                                context,
                                BandKeyWidgetProvider::class.java
                            )
                            val pinned = manager.isRequestPinAppWidgetSupported &&
                                runCatching {
                                    manager.requestPinAppWidget(provider, null, null)
                                }.getOrDefault(false)
                            if (!pinned) {
                                Toast.makeText(
                                    context,
                                    "请长按桌面 → 添加小部件 → 安卓小部件 → BandKey",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        glyph = BkGlyph.LINK,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                StatusNote(
                    text = if (isXiaomi) {
                        "小米桌面会弹出添加确认；若未出现，请长按桌面 → 添加小部件 → BandKey 选择小米 2×2 卡片。"
                    } else {
                        "部分启动器会弹出确认窗口；添加后可长按组件调整大小。"
                    },
                    tone = Bk.Dim,
                    glyph = BkGlyph.LINK
                )
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(
                title = "安全参数",
                subtitle = "配对私钥只保存在本机 Keystore 中。",
                statusText = "VERIFIED",
                statusTone = Bk.Mint
            ) {
                InfoRow(label = "签名算法", value = "ECDSA P-256", mono = true)
                InfoRow(label = "私钥存储", value = "ANDROID KEYSTORE", mono = true)
                InfoRow(label = "反重放", value = "NONCE + TIMESTAMP", mono = true)
                InfoRow(
                    label = "配对状态",
                    value = if (paired) "已绑定" else "未绑定",
                    valueColor = if (paired) Bk.Mint else Bk.Amber
                )
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(
                title = "Windows 凭据",
                subtitle = "由电脑端解锁组件负责保存与提交。",
                statusText = if (credentialReady) "已保存" else "待确认",
                statusTone = if (credentialReady) Bk.Mint else Bk.Amber
            ) {
                InfoRow(
                    label = "存放位置",
                    value = "电脑本机 · DPAPI",
                    valueColor = Bk.Mint
                )
                InfoRow(label = "手机保存", value = "否")
                StatusNote(
                    text = "如果 Windows 密码变更，需要在电脑托盘重新设置解锁凭据。",
                    tone = Bk.Dim,
                    glyph = BkGlyph.KEY
                )
            }

            if (paired) {
                Spacer(Modifier.height(18.dp))
                SecondaryAction(
                    text = "解除本机配对",
                    onClick = onRequestUnpair,
                    tone = Bk.Coral,
                    glyph = BkGlyph.TRASH,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(18.dp))
            StatusNote(text = status, tone = Bk.Dim)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BrandHeader(
    statusText: String,
    tone: Color,
    pulsing: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphBadge(
            glyph = BkGlyph.SHIELD,
            tone = Bk.Mint,
            size = 38.dp
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = "BANDKEY",
                color = Bk.Mint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.3.sp
            )
            Text(
                text = "信任仪表",
                color = Bk.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.weight(1f))
        StatusPill(
            text = statusText,
            tone = tone,
            pulsing = pulsing
        )
    }
}

@Composable
private fun PairingOverlay(
    deviceName: String,
    mode: String,
    activeChannel: String?,
    pairingId: String?,
    pinInput: String,
    pairError: String?,
    remainingSeconds: Int,
    busy: Boolean,
    status: String,
    onPinChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onResend: () -> Unit,
    onClose: () -> Unit
) {
    val canClose = pairingId == null || !busy
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.74f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = canClose,
                    onClick = onClose
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .imePadding()
                .navigationBarsPadding()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Bk.Surface2, Bk.Surface, Bk.Background2)
                    )
                )
                .border(
                    1.dp,
                    Bk.LineStrong,
                    RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .align(Alignment.CenterHorizontally)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Bk.LineStrong)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(
                    glyph = BkGlyph.BACK,
                    onClick = onClose,
                    enabled = canClose,
                    tint = Bk.Muted
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "PAIRING / 02",
                    color = Bk.Mint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp
                )
                Spacer(Modifier.weight(1f))
                StatusPill(
                    text = when {
                        busy && pairingId == null -> "REQUEST"
                        pairingId != null -> "PIN READY"
                        else -> "WAITING"
                    },
                    tone = if (pairingId != null) Bk.Mint else Bk.Amber,
                    pulsing = busy
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "配对这台电脑",
                    color = Bk.Text,
                    fontSize = 25.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "在电脑托盘点按配对，读取 6 位 PIN 后输入到下方。",
                    color = Bk.Muted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(16.dp))

                TechCard(modifier = Modifier.fillMaxWidth(), padding = 13.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphBadge(
                            glyph = BkGlyph.PHONE,
                            tone = Bk.Iris,
                            size = 44.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = deviceName.ifBlank { "我的手机" },
                                color = Bk.Text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "Android · ECDSA P-256 · ${
                                    if ((activeChannel ?: mode) == "ble") "BLE" else "LAN"
                                }",
                                color = Bk.Dim,
                                fontSize = 9.sp
                            )
                        }
                        StatusPill(
                            text = if (pairingId != null) "已发送" else "建立中",
                            tone = if (pairingId != null) Bk.Mint else Bk.Amber,
                            pulsing = busy
                        )
                    }
                }

                Spacer(Modifier.height(13.dp))
                StatusNote(
                    text = pairError ?: when {
                        pairingId != null -> "已发送配对请求，请在电脑托盘查看本次 PIN。"
                        busy -> "正在建立加密通道，请稍候…"
                        else -> status
                    },
                    tone = when {
                        pairError != null -> Bk.Coral
                        pairingId != null -> Bk.Mint
                        else -> Bk.Amber
                    },
                    glyph = when {
                        pairError != null -> BkGlyph.ALERT
                        pairingId != null -> BkGlyph.CHECK
                        else -> BkGlyph.BLUETOOTH
                    }
                )

                Spacer(Modifier.height(18.dp))
                SectionLabel(text = "配对 PIN", trailing = "6 DIGITS")
                Spacer(Modifier.height(9.dp))
                OtpInput(
                    value = pinInput,
                    onValueChange = onPinChange,
                    enabled = pairingId != null && !busy,
                    error = pairError?.contains("PIN") == true
                )
                Spacer(Modifier.height(14.dp))
                CountdownBar(
                    totalSeconds = 300,
                    remainingSeconds = remainingSeconds
                )

                Spacer(Modifier.height(18.dp))
                TechCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp) {
                    StatusNote(
                        text = "PIN 只对本次配对有效。电脑端会同时显示设备名称，确认无误后再提交。",
                        tone = Bk.Muted,
                        glyph = BkGlyph.SHIELD
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Bk.Surface.copy(alpha = 0.96f))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                PrimaryAction(
                    text = when {
                        busy && pairingId == null -> "正在请求配对"
                        busy -> "正在确认…"
                        else -> "确认配对"
                    },
                    secondaryText = "配对后本机可解锁电脑",
                    enabled = pinInput.length == 6 && pairingId != null && !busy,
                    busy = busy,
                    glyph = BkGlyph.CHECK,
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                SecondaryAction(
                    text = "没有收到？重新发送配对请求",
                    onClick = onResend,
                    enabled = !busy,
                    tone = Bk.Muted,
                    glyph = BkGlyph.REFRESH,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ErrorOverlay(
    error: UiError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    BkBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ERROR / RECOVERY",
                    color = Bk.Coral,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                    modifier = Modifier.weight(1f)
                )
                IconAction(
                    glyph = BkGlyph.CLOSE,
                    onClick = onDismiss,
                    tint = Bk.Muted
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(26.dp))
                GlyphBadge(
                    glyph = BkGlyph.ALERT,
                    tone = Bk.Amber,
                    size = 72.dp
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    text = error.title,
                    color = Bk.Text,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    text = error.message,
                    color = Bk.Muted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    error.steps.forEachIndexed { index, step ->
                        StepCard(
                            number = index + 1,
                            title = step.title,
                            detail = step.detail
                        )
                    }
                }
                if (error.code.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel(text = "DIAGNOSTIC", trailing = error.code)
                }
                Spacer(Modifier.height(18.dp))
            }

            PrimaryAction(
                text = "重新尝试",
                secondaryText = "重新发起解锁请求",
                enabled = true,
                glyph = BkGlyph.REFRESH,
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            SecondaryAction(
                text = "返回解锁主页",
                onClick = onDismiss,
                tone = Bk.Muted,
                glyph = BkGlyph.BACK,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SuccessOverlay(
    receipt: UnlockReceipt,
    onDone: () -> Unit
) {
    BkBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SUCCESS / 06",
                    color = Bk.Mint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                    modifier = Modifier.weight(1f)
                )
                IconAction(
                    glyph = BkGlyph.CLOSE,
                    onClick = onDone,
                    tint = Bk.Muted
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))
                SuccessRing(modifier = Modifier.size(210.dp))
                Spacer(Modifier.height(22.dp))
                Text(
                    text = "已解锁",
                    color = Bk.Text,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "电脑正在进入桌面，请稍候",
                    color = Bk.Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(24.dp))
                TechCard(modifier = Modifier.fillMaxWidth()) {
                    InfoRow(
                        label = "时间",
                        value = formatReceiptTime(receipt.at),
                        mono = true
                    )
                    InfoRow(
                        label = "设备",
                        value = receipt.target,
                        mono = true
                    )
                    InfoRow(
                        label = "通道",
                        value = receipt.channel
                    )
                    InfoRow(
                        label = "验证",
                        value = "ECDSA P-256 · OK",
                        valueColor = Bk.Mint,
                        mono = true
                    )
                }
                Spacer(Modifier.height(16.dp))
                StatusNote(
                    text = "如果电脑仍停留在锁屏界面，请检查 Windows 凭据是否仍然有效。",
                    tone = Bk.Dim,
                    glyph = BkGlyph.LINK
                )
                Spacer(Modifier.height(18.dp))
            }

            PrimaryAction(
                text = "完成",
                secondaryText = "返回解锁主页",
                enabled = true,
                glyph = BkGlyph.CHECK,
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SubmittedOverlay(
    receipt: UnlockReceipt,
    busy: Boolean,
    notice: String,
    onRefresh: () -> Unit,
    onDone: () -> Unit
) {
    BkBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SUBMITTED / 06",
                    color = Bk.Amber,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                    modifier = Modifier.weight(1f)
                )
                IconAction(
                    glyph = BkGlyph.CLOSE,
                    onClick = onDone,
                    tint = Bk.Muted
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(30.dp))
                GlyphBadge(
                    glyph = BkGlyph.LINK,
                    tone = Bk.Amber,
                    size = 96.dp
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    text = "已提交解锁请求",
                    color = Bk.Text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Windows 尚未返回最终登录结果。电脑可能正在进入桌面，也可能仍在锁屏。",
                    color = Bk.Muted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                TechCard(modifier = Modifier.fillMaxWidth()) {
                    InfoRow(
                        label = "时间",
                        value = formatReceiptTime(receipt.at),
                        mono = true
                    )
                    InfoRow(
                        label = "设备",
                        value = receipt.target,
                        mono = true
                    )
                    InfoRow(
                        label = "通道",
                        value = receipt.channel
                    )
                    InfoRow(
                        label = "请求",
                        value = receipt.requestId?.toString() ?: "-",
                        mono = true
                    )
                    InfoRow(
                        label = "状态",
                        value = "ACCEPTED / SUBMITTED",
                        valueColor = Bk.Amber,
                        mono = true
                    )
                }
                Spacer(Modifier.height(16.dp))
                StatusNote(
                    text = "请在电脑上确认是否已进入桌面。若仍停留在锁屏，请检查 Windows 凭据或查看 cp.log。",
                    tone = Bk.Dim,
                    glyph = BkGlyph.ALERT
                )
                if (notice.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    StatusNote(
                        text = notice,
                        tone = Bk.Amber,
                        glyph = BkGlyph.REFRESH
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            PrimaryAction(
                text = if (busy) "正在查询…" else "刷新最终状态",
                secondaryText = "查询 Windows 登录结果",
                enabled = !busy,
                busy = busy,
                glyph = BkGlyph.REFRESH,
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ConfirmUnpairOverlay(
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.76f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !busy,
                    onClick = onCancel
                )
        )
        TechCard(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            padding = 20.dp
        ) {
            GlyphBadge(
                glyph = BkGlyph.TRASH,
                tone = Bk.Coral,
                size = 54.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "解除本机配对？",
                color = Bk.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = "本机保存的私钥和配对记录会被删除。之后必须重新配对才能解锁电脑。",
                color = Bk.Muted,
                fontSize = 10.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryAction(
                    text = "取消",
                    onClick = onCancel,
                    enabled = !busy,
                    tone = Bk.Muted,
                    modifier = Modifier.weight(1f)
                )
                PrimaryAction(
                    text = "解除",
                    enabled = !busy,
                    tone = Bk.Coral,
                    glyph = BkGlyph.TRASH,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NfcKeyNameOverlay(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.76f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel
                )
        )
        TechCard(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            padding = 20.dp
        ) {
            GlyphBadge(
                glyph = BkGlyph.NFC,
                tone = Bk.Iris,
                size = 52.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(15.dp))
            Text(
                text = "重命名 NFC 钥匙",
                color = Bk.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "名称只保存在本机，用于区分不同的实体卡。",
                color = Bk.Muted,
                fontSize = 10.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            TechTextField(
                value = value,
                onValueChange = onValueChange,
                label = "钥匙名称",
                placeholder = "例如：工牌卡",
                trailingText = "${value.length}/32",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryAction(
                    text = "取消",
                    onClick = onCancel,
                    tone = Bk.Muted,
                    modifier = Modifier.weight(1f)
                )
                PrimaryAction(
                    text = "保存",
                    enabled = value.trim().isNotEmpty(),
                    glyph = BkGlyph.CHECK,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NfcOverwriteOverlay(
    recordCount: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.76f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel
                )
        )
        TechCard(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            padding = 20.dp
        ) {
            GlyphBadge(
                glyph = BkGlyph.ALERT,
                tone = Bk.Amber,
                size = 54.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "覆盖卡片内容？",
                color = Bk.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = "检测到 $recordCount 条其他 NDEF 记录",
                color = Bk.Amber,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(9.dp))
            Text(
                text = "继续后会清除这张卡原有的 NDEF 数据，并写入 BandKey 身份令牌。此操作不可撤销。",
                color = Bk.Muted,
                fontSize = 10.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryAction(
                    text = "取消",
                    onClick = onCancel,
                    tone = Bk.Muted,
                    modifier = Modifier.weight(1f)
                )
                PrimaryAction(
                    text = "覆盖并绑定",
                    enabled = true,
                    tone = Bk.Amber,
                    glyph = BkGlyph.NFC,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NfcKeyDeleteOverlay(
    record: NfcKeyRecord,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.76f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel
                )
        )
        TechCard(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            padding = 20.dp
        ) {
            GlyphBadge(
                glyph = BkGlyph.TRASH,
                tone = Bk.Coral,
                size = 54.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "移除这张 NFC 钥匙？",
                color = Bk.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = "${record.name} · ${record.hint}",
                color = Bk.Iris,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(9.dp))
            Text(
                text = "移除后这张卡将无法触发本机解锁。卡片内的随机令牌不会被清除，可重新绑定。",
                color = Bk.Muted,
                fontSize = 10.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryAction(
                    text = "取消",
                    onClick = onCancel,
                    tone = Bk.Muted,
                    modifier = Modifier.weight(1f)
                )
                PrimaryAction(
                    text = "移除",
                    enabled = true,
                    tone = Bk.Coral,
                    glyph = BkGlyph.TRASH,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun pairingError(raw: String): String = when {
    raw.contains("wrong_pin") -> "PIN 不正确，请核对电脑托盘中的 6 位数字"
    raw.contains("pairing_locked") -> "PIN 错误次数过多，本次配对已锁定，请重新发起配对"
    raw.contains("pairing_expired") -> "PIN 已过期，请重新发送配对请求"
    raw.contains("unknown_pairing") -> "配对已失效，请重新发起配对"
    raw.isBlank() -> "配对失败，请检查电脑端服务状态"
    else -> "配对失败：${humanMessage(raw)}"
}

private fun unlockError(raw: String): UiError {
    val code = raw.substringBefore(':').trim().ifBlank { "unknown" }.take(48)
    return when {
        raw.contains("unknown_device") -> UiError(
            title = "设备授权已失效",
            message = "电脑端已删除这台手机，当前私钥不能继续解锁。",
            steps = listOf(
                RecoveryStep("重新配对", "在电脑托盘确认设备列表，然后回到手机重新配对。"),
                RecoveryStep("检查电脑端", "确认没有误删当前设备，或先恢复电脑端服务。"),
                RecoveryStep("保存新私钥", "重新配对会生成新的设备密钥，旧密钥自动失效。")
            ),
            code = code
        )
        raw.contains("unlock_failed:credential_invalid") ||
            raw.contains("unlock_failed:logon_failure") -> UiError(
            title = "Windows 登录失败",
            message = "电脑已收到解锁请求，但 Windows 拒绝了保存的凭据。",
            steps = listOf(
                RecoveryStep("更新解锁凭据", "在电脑托盘重新保存当前 Windows 账户密码。"),
                RecoveryStep("确认账号类型", "Microsoft 账号、域账号或仅 PIN 账号可能需要额外配置。"),
                RecoveryStep("查看 cp.log", "如密码正确仍失败，请检查 C:\\ProgramData\\BandKey\\cp.log。")
            ),
            code = code
        )
        raw.contains("unlock_failed:credential_unreadable") -> UiError(
            title = "无法读取解锁凭据",
            message = "Windows 登录组件无法解密本机保存的凭据，通常与权限或系统账户变更有关。",
            steps = listOf(
                RecoveryStep("重新保存凭据", "在电脑托盘重新输入当前 Windows 账户密码。"),
                RecoveryStep("检查目录权限", "确认 C:\\ProgramData\\BandKey 仅允许 SYSTEM 与管理员访问。"),
                RecoveryStep("重启后重试", "如凭据文件损坏，重启电脑后重新保存并再次解锁。")
            ),
            code = code
        )
        raw.contains("unlock_failed:component_error") ||
            raw.contains("unlock_failed:component_not_connected") ||
            raw.contains("unlock_failed:component_disconnected") -> UiError(
            title = "解锁组件中断",
            message = "凭据提供程序在提交登录前断开，Windows 没有完成登录。",
            steps = listOf(
                RecoveryStep("重新锁定电脑", "按 Win + L 让登录界面重新加载解锁组件。"),
                RecoveryStep("重启服务", "在电脑托盘或服务管理器中重启 BandKeyService。"),
                RecoveryStep("重新尝试", "回到手机后再次发起解锁请求。")
            ),
            code = code
        )
        raw.contains("unlock_failed:outcome_timeout") -> UiError(
            title = "等待解锁结果超时",
            message = "电脑已收到请求，但 Windows 在限制时间内没有返回最终登录结果。",
            steps = listOf(
                RecoveryStep("确认电脑状态", "查看电脑是否已经进入桌面；如果仍停留在锁屏，请重新发起解锁。"),
                RecoveryStep("重新锁定电脑", "按 Win + L 让登录组件重新加载，再重试一次。"),
                RecoveryStep("检查电脑端", "查看 C:\\ProgramData\\BandKey\\cp.log 是否有对应的 UNLOCK_RESULT 记录。")
            ),
            code = code
        )
        raw.contains("bad_signature") -> UiError(
            title = "签名校验失败",
            message = "电脑无法验证这台手机的签名，设备密钥可能已损坏。",
            steps = listOf(
                RecoveryStep("重新配对", "删除本机配对记录后重新生成设备密钥。"),
                RecoveryStep("检查系统时间", "确认手机和电脑时间相差不超过 2 分钟。"),
                RecoveryStep("重试", "重新配对完成后再发起解锁。")
            ),
            code = code
        )
        raw.contains("timestamp_out_of_range") -> UiError(
            title = "设备时间不同步",
            message = "手机与电脑时间相差超过允许范围，请求被拒绝。",
            steps = listOf(
                RecoveryStep("开启自动时间", "在手机和电脑上开启自动设置日期与时间。"),
                RecoveryStep("重新同步", "等待系统完成时间同步后再试。"),
                RecoveryStep("重新解锁", "返回主页重新发起请求。")
            ),
            code = code
        )
        raw.contains("replayed_nonce") -> UiError(
            title = "请求已被使用",
            message = "这次解锁请求已经提交过，电脑拒绝了重复请求。",
            steps = listOf(
                RecoveryStep("重新发起", "返回主页后再次点击解锁，手机会生成新的随机数。"),
                RecoveryStep("不要重复点击", "等待电脑返回最终结果后再操作。"),
                RecoveryStep("检查网络", "连接不稳定时请靠近电脑或切换到局域网。")
            ),
            code = code
        )
        raw.contains("computer_not_locked") -> UiError(
            title = "电脑当前未锁定",
            message = "解锁请求只在锁屏状态生效。请先锁定电脑，再回到手机上重试。",
            steps = listOf(
                RecoveryStep("在电脑上锁屏", "按 Win + L，或从开始菜单选择锁屏。"),
                RecoveryStep("回到手机重试", "点击下方「重新尝试」，等待电脑响应。"),
                RecoveryStep("确认进入桌面", "成功后手机会显示本次解锁回执。")
            ),
            code = code
        )
        raw.contains("no_credential") -> UiError(
            title = "尚未保存 Windows 凭据",
            message = "电脑还没有保存解锁所需的 Windows 密码，手机端无法代为提交。",
            steps = listOf(
                RecoveryStep("打开电脑托盘", "右键 BandKey 图标，选择「设置解锁凭据」。"),
                RecoveryStep("保存 Windows 密码", "密码只保存在电脑本机，不会发送到手机。"),
                RecoveryStep("重新发起解锁", "返回手机后点击「重新尝试」。")
            ),
            code = code
        )
        raw.contains("password_invalid") -> UiError(
            title = "Windows 凭据已失效",
            message = "电脑保存的密码可能已经更改，需要更新后才能继续使用手机解锁。",
            steps = listOf(
                RecoveryStep("更新解锁凭据", "在电脑托盘重新输入当前 Windows 账户密码。"),
                RecoveryStep("核对 Windows 账户", "确认使用的是当前登录账户，而不是旧账户。"),
                RecoveryStep("回到手机重试", "保存成功后点击「重新尝试」。")
            ),
            code = code
        )
        raw.contains("unlock_not_ready") -> UiError(
            title = "解锁组件未就绪",
            message = "Credential Provider 尚未加载，登录界面暂时无法接收手机解锁请求。",
            steps = listOf(
                RecoveryStep("重启电脑", "让 Credential Provider 随登录界面重新加载。"),
                RecoveryStep("修复 Windows 组件", "如仍失败，重新运行 BandKey Windows 安装程序。"),
                RecoveryStep("检查托盘服务", "确认 BandKey 托盘显示服务已启动。")
            ),
            code = code
        )
        else -> UiError(
            title = "解锁失败",
            message = raw.ifBlank { "电脑没有返回具体原因，请检查连接状态后重试。" },
            steps = listOf(
                RecoveryStep("检查电脑服务", "确认托盘显示 BandKey 服务已启动，蓝牙广播可用。"),
                RecoveryStep("检查连接通道", "手机与电脑保持在同一网络，或保持蓝牙开启。"),
                RecoveryStep("查看诊断日志", "如仍失败，检查电脑端 tray.log 与 cp.log。")
            ),
            code = code
        )
    }
}

private fun humanMessage(raw: String): String {
    val index = raw.indexOf(": ")
    return if (index >= 0) raw.substring(index + 2) else raw
}

private fun pcStatusSummary(status: PcStatus): String = when {
    !status.checked -> "正在确认电脑状态…"
    status.protocolVersion < 2 -> "电脑服务版本过旧，请在电脑上重新安装 BandKey 1.8.0 或更高版本"
    !status.online -> "电脑离线，等待重新连接"
    status.revoked -> "设备已被电脑撤销，请重新配对"
    !status.paired -> "电脑未确认此设备，请重新配对"
    status.credentialRejected -> "Windows 密码已失效，请在电脑托盘更新"
    !status.credentialSet -> "电脑尚未保存 Windows 解锁凭据"
    !status.isLocked -> "电脑当前未锁屏，或解锁组件尚未连接；解锁请求只在锁屏界面生效"
    !status.unlockReady -> "解锁组件尚未就绪，请检查电脑端服务"
    else -> "电脑已就绪，等待手机解锁"
}

private fun targetLabel(serverUrl: String, mode: String): String {
    val host = hostFromUrl(serverUrl)
    return if (host.isNullOrBlank() || mode == "ble") {
        "BANDKEY PC"
    } else {
        host.uppercase(Locale.getDefault())
    }
}

private fun forName(recordName: String, fallback: String): String =
    recordName.ifBlank { fallback }.ifBlank { "我的手机" }

private fun shortDeviceId(deviceId: String): String {
    if (deviceId.length <= 18) {
        return deviceId
    }
    return "${deviceId.take(10)}…${deviceId.takeLast(6)}"
}

private fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour in 5..10 -> "早上好"
        hour in 11..13 -> "中午好"
        hour in 14..17 -> "下午好"
        else -> "晚上好"
    }
}

private fun formatFullTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

private fun formatShortTime(epochMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

private fun formatReceiptTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

private fun hostFromUrl(url: String): String? {
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
