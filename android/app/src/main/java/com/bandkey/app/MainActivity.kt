package com.bandkey.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import java.security.KeyPair
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

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
        ensureBlePermissions()

        setContent {
            BandKeyTheme {
                BandKeyScreen(
                    initialMode = savedMode,
                    initialUrl = savedUrl ?: "ws://192.168.1.71:23234/bandkey",
                    initialDeviceName = savedDeviceName,
                    onModeChange = { value ->
                        prefs.edit().putString("transport_mode", value).apply()
                    },
                    onUrlChange = { value ->
                        prefs.edit().putString("server_url", value).apply()
                    },
                    onDeviceNameChange = { value ->
                        prefs.edit().putString("device_name", value).apply()
                    },
                    onNeedBlePermissions = { ensureBlePermissions() }
                )
            }
        }
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

private data class UnlockReceipt(
    val at: Long,
    val target: String,
    val channel: String
)

@Composable
private fun BandKeyScreen(
    initialMode: String,
    initialUrl: String,
    initialDeviceName: String,
    onModeChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onNeedBlePermissions: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val pairingStore = remember { PairingStore(context) }

    var tab by remember { mutableStateOf(MainTab.UNLOCK) }
    var mode by remember { mutableStateOf(initialMode) }
    var serverUrl by remember { mutableStateOf(initialUrl) }
    var deviceName by remember { mutableStateOf(initialDeviceName) }
    var status by remember { mutableStateOf("等待建立安全通道") }
    var keyPair by remember { mutableStateOf<KeyPair?>(null) }
    var deviceId by remember { mutableStateOf("") }
    var pairedAt by remember { mutableStateOf(0L) }
    var pairingId by remember { mutableStateOf<String?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var pairError by remember { mutableStateOf<String?>(null) }
    var pairingRemaining by remember { mutableStateOf(300) }
    var busy by remember { mutableStateOf(false) }
    var credentialReady by remember { mutableStateOf(false) }
    var activeChannel by remember { mutableStateOf<String?>(null) }
    var showPairing by remember { mutableStateOf(false) }
    var pendingUnpair by remember { mutableStateOf(false) }
    var uiError by remember { mutableStateOf<UiError?>(null) }
    var receipt by remember { mutableStateOf<UnlockReceipt?>(null) }

    LaunchedEffect(Unit) {
        val record = withContext(Dispatchers.IO) { pairingStore.load() }
        if (record != null) {
            runCatching { record.toKeyPair() }
                .onSuccess { loaded ->
                    keyPair = loaded
                    deviceId = record.deviceId
                    pairedAt = record.pairedAt
                    credentialReady = true
                    deviceName = forName(record.deviceName, initialDeviceName)
                    status = "已加载本机配对信息，可直接解锁"
                }
                .onFailure {
                    status = "配对信息已损坏，请重新配对"
                }
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
                    val at = System.currentTimeMillis()
                    deviceId = devId
                    pairedAt = at
                    credentialReady = true
                    val record = PairingRecord(
                        deviceId = devId,
                        deviceName = deviceName.ifBlank { "我的手机" },
                        privateKey = pair.private.encoded,
                        publicKey = pair.public.encoded,
                        pairedAt = at
                    )
                    withContext(Dispatchers.IO) { pairingStore.save(record) }
                    pairingId = null
                    pinInput = ""
                    showPairing = false
                    tab = MainTab.UNLOCK
                    status = "配对成功，配对信息已安全保存"
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

    fun unlock() {
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
            status = "正在验证签名并请求解锁…"
            try {
                runWithTransport { client ->
                    val result = client.unlock(pair, deviceId)
                    val ok = result.getJSONObject("payload").optBoolean("ok")
                    if (!ok) {
                        throw ProtocolException("unlock_denied: 电脑拒绝了本次解锁请求")
                    }
                }
                val channel = if (activeChannel == "lan") "局域网 TCP" else "蓝牙 LE"
                receipt = UnlockReceipt(
                    at = System.currentTimeMillis(),
                    target = targetLabel(serverUrl, mode),
                    channel = channel
                )
                credentialReady = true
                status = "解锁成功，电脑正在进入桌面"
            } catch (e: Exception) {
                val raw = e.message.orEmpty()
                credentialReady = when {
                    raw.contains("no_credential") -> false
                    raw.contains("password_invalid") -> false
                    raw.contains("unlock_not_ready") -> false
                    else -> credentialReady
                }
                uiError = unlockError(raw)
                status = uiError?.title ?: "解锁失败"
            } finally {
                busy = false
            }
        }
    }

    fun unpair() {
        scope.launch {
            withContext(Dispatchers.IO) { pairingStore.clear() }
            keyPair = null
            deviceId = ""
            pairedAt = 0L
            pairingId = null
            pinInput = ""
            credentialReady = false
            activeChannel = null
            pendingUnpair = false
            receipt = null
            uiError = null
            status = "已解除本机配对"
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
                            credentialReady = credentialReady,
                            busy = busy,
                            status = status,
                            lastReceipt = receipt,
                            onUnlock = { unlock() },
                            onPair = { openPairing() }
                        )
                        MainTab.DEVICES -> DevicesScreen(
                            deviceName = deviceName,
                            deviceId = deviceId,
                            pairedAt = pairedAt,
                            busy = busy,
                            onPair = { openPairing() },
                            onRequestUnpair = { pendingUnpair = true }
                        )
                        MainTab.SETTINGS -> SettingsScreen(
                            mode = mode,
                            serverUrl = serverUrl,
                            deviceName = deviceName,
                            deviceId = deviceId,
                            credentialReady = credentialReady,
                            busy = busy,
                            status = status,
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
                    SuccessOverlay(
                        receipt = value,
                        onDone = { receipt = null }
                    )
                }
            }

            if (pendingUnpair) {
                ConfirmUnpairOverlay(
                    busy = busy,
                    onCancel = { pendingUnpair = false },
                    onConfirm = { unpair() }
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
    credentialReady: Boolean,
    busy: Boolean,
    status: String,
    lastReceipt: UnlockReceipt?,
    onUnlock: () -> Unit,
    onPair: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dialSize = minOf(maxWidth * 0.68f, maxHeight * 0.38f, 248.dp)
        val heading = when {
            busy -> "正在连接电脑"
            !paired -> "先配对一台电脑"
            !credentialReady -> "请检查 Windows 凭据"
            else -> "电脑已就绪"
        }
        val dialTitle = when {
            busy -> "正在验证"
            !paired -> "等待配对"
            !credentialReady -> "凭据待确认"
            else -> "手机已认证"
        }
        val dialLabel = when {
            busy -> "VERIFYING"
            !paired -> "STANDBY"
            credentialReady -> "READY"
            else -> "ATTENTION"
        }
        val channelValue = when (activeChannel) {
            "ble" -> "蓝牙 LE"
            "lan" -> "局域网"
            else -> if (mode == "ble") "蓝牙优先" else "局域网"
        }
        val channelMeta = when (activeChannel) {
            "ble" -> "安全通道已连接"
            "lan" -> "TCP 通道已连接"
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
                    text = heading,
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
                        channelReady = channelReadyState(mode, activeChannel),
                        identityReady = paired,
                        credentialReady = credentialReady,
                        busy = busy,
                        statusLabel = dialLabel,
                        title = dialTitle,
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
                            meta = channelMeta,
                            tone = if (mode == "ble") Bk.Mint else Bk.Iris,
                            pulsing = busy
                        ),
                        RailItem(
                            label = "设备身份",
                            value = if (paired) "已认证" else "未配对",
                            meta = if (paired) "ECDSA P-256" else "等待设备密钥",
                            tone = if (paired) Bk.Iris else Bk.Amber
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
                enabled = !busy,
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
private fun DevicesScreen(
    deviceName: String,
    deviceId: String,
    pairedAt: Long,
    busy: Boolean,
    onPair: () -> Unit,
    onRequestUnpair: () -> Unit
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
private fun SettingsScreen(
    mode: String,
    serverUrl: String,
    deviceName: String,
    deviceId: String,
    credentialReady: Boolean,
    busy: Boolean,
    status: String,
    onModeChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onRequestUnpair: () -> Unit
) {
    val paired = deviceId.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        BrandHeader(
            statusText = if (mode == "ble") "BLE FIRST" else "LAN MODE",
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

private fun pairingError(raw: String): String = when {
    raw.contains("wrong_pin") -> "PIN 不正确，请核对电脑托盘中的 6 位数字"
    raw.contains("pairing_expired") -> "PIN 已过期，请重新发送配对请求"
    raw.contains("unknown_pairing") -> "配对已失效，请重新发起配对"
    raw.isBlank() -> "配对失败，请检查电脑端服务状态"
    else -> "配对失败：${humanMessage(raw)}"
}

private fun unlockError(raw: String): UiError {
    val code = raw.substringBefore(':').trim().ifBlank { "unknown" }.take(48)
    return when {
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

private fun channelReadyState(mode: String, activeChannel: String?): Boolean =
    activeChannel != null || mode == "ble" || mode == "wifi"

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
