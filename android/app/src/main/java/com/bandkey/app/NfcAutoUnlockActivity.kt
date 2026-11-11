package com.bandkey.app

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 息屏/锁屏 NFC 自动解锁。
 *
 * 该 Activity 由 Android NFC 系统分发启动，不使用 Reader Mode。
 * 因此即使 BandKey 不在前台，也能在系统允许的前提下接收卡片。
 */
class NfcAutoUnlockActivity : QuickActionActivity() {

    private companion object {
        const val DUPLICATE_WINDOW_MS = 3_000L

        @Volatile
        private var lastTagId: String? = null

        @Volatile
        private var lastTagAt: Long = 0L
    }

    private var processing = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!processing) {
            NfcDiagnostics.record(
                this,
                "系统分发",
                NfcDiagnostics.snapshot(this, intent, readTag(intent))
            )
            handleIntent(intent)
        }
    }

    override fun onActionReady(intent: Intent) {
        setActionTitle("NFC 自动解锁")
        setBadge("NFC")
        setStatus("正在读取卡片…", BkColor.TEXT)
        setDetail("请保持卡片贴近手机感应区。")

        NfcDiagnostics.record(
            this,
            "系统分发",
            NfcDiagnostics.snapshot(this, intent, readTag(intent))
        )
        if (!BackgroundUnlock.isNfcAutoUnlockEnabled(this)) {
            NfcDiagnostics.record(this, "已拦截", "自动解锁开关未开启")
            showFailure(
                title = "NFC 自动解锁未开启",
                message = "系统已识别到卡片，但自动解锁开关处于关闭状态",
                detail = "请在 BandKey「设置」中开启「息屏 NFC 自动解锁」后重新贴卡。"
            )
            return
        }
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null || !adapter.isEnabled) {
            NfcDiagnostics.record(this, "已拦截", "系统 NFC 不可用")
            showFailure(
                title = "NFC 不可用",
                message = "请先在系统设置中开启 NFC",
                detail = "开启后重新贴卡，即可自动发起解锁。"
            )
            return
        }
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (processing) {
            return
        }
        val tag = readTag(intent)
        if (tag == null && !NfcCard.hasNdefMessage(intent)) {
            NfcDiagnostics.record(this, "读取失败", "系统 Intent 中没有 Tag 或 NDEF 数据")
            showFailure(
                title = "未识别到卡片",
                message = "系统没有提供 NFC 标签数据",
                detail = "请重新贴卡；如仍失败，请先在 BandKey 中确认 NFC 已开启。"
            )
            return
        }

        val tagId = tag?.id?.joinToString("") { "%02X".format(it) }.orEmpty()
        val now = System.currentTimeMillis()
        if (tagId.isNotBlank() &&
            tagId == lastTagId &&
            now - lastTagAt < DUPLICATE_WINDOW_MS
        ) {
            NfcDiagnostics.record(this, "已忽略", "短时间重复贴卡 tag=$tagId")
            finish()
            return
        }
        lastTagId = tagId
        lastTagAt = now

        processing = true
        launchAction {
            try {
                verifyAndUnlock(tag, intent)
            } finally {
                processing = false
            }
        }
    }

    private suspend fun verifyAndUnlock(tag: Tag?, intent: Intent) {
        val payload = readPayloadWithRetry(tag, intent)
        if (payload == null) {
            NfcDiagnostics.record(this, "读取失败", "没有读取到 BandKey NDEF 数据")
            showFailure(
                title = "读取失败",
                message = "没有读取到 NDEF 数据，请重新贴卡",
                detail = "请确认卡片是 BandKey 已写入的 NDEF 钥匙卡。"
            )
            return
        }
        if (!NfcCredential.isValid(payload)) {
            NfcDiagnostics.record(this, "已拒绝", "NDEF 中没有 BandKey 身份令牌")
            showFailure(
                title = "卡片未授权",
                message = "这张卡中没有 BandKey 身份令牌",
                detail = "请使用 BandKey「设备」页写入并绑定的 NFC 钥匙。"
            )
            return
        }

        val hash = NfcCredential.hash(payload)
        val keys = withContext(Dispatchers.IO) {
            runCatching { NfcKeyStore(this@NfcAutoUnlockActivity).load() }
                .getOrDefault(emptyList())
        }
        val key = keys.firstOrNull {
            NfcCredential.hashEquals(it.tokenHash, hash)
        }
        if (key == null) {
            NfcDiagnostics.record(this, "已拒绝", "卡片令牌未绑定 hash=${NfcCredential.hint(hash)}")
            showFailure(
                title = "卡片未绑定",
                message = "这张卡还没有加入本机 NFC 钥匙",
                detail = "请在 BandKey「设备」页绑定这张卡后再使用自动解锁。"
            )
            return
        }

        if (withContext(Dispatchers.IO) { PairingStore(this@NfcAutoUnlockActivity).load() } == null) {
            NfcDiagnostics.record(this, "已拒绝", "本机没有电脑配对信息")
            showFailure(
                title = "尚未配对",
                message = "请先打开 BandKey 完成电脑配对",
                detail = "NFC 钥匙验证通过，但本机没有可用的电脑配对信息。"
            )
            return
        }

        withContext(Dispatchers.IO) {
            val used = key.copy(lastUsedAt = System.currentTimeMillis())
            NfcKeyStore(this@NfcAutoUnlockActivity).save(
                keys.map { if (it.id == used.id) used else it }
            )
        }
        NfcDiagnostics.record(this, "验证通过", "key=${key.hint}")

        setBadge("NFC VERIFIED", BkColor.MINT)
        setStatus("NFC 钥匙验证通过", BkColor.MINT)
        setDetail("${key.hint} · 正在连接电脑…")
        runBackgroundUnlock(method = "nfc", keyHint = key.hint)
    }

    private suspend fun readPayloadWithRetry(tag: Tag?, intent: Intent): String? {
        repeat(3) { attempt ->
            val payload = withContext(Dispatchers.IO) {
                runCatching { NfcCard.readPayload(tag, intent) }
                    .onFailure { Log.w("BandKeyNfc", "read payload failed attempt=${attempt + 1}", it) }
                    .getOrNull()
            }
            if (payload != null) {
                return payload
            }
            if (attempt < 2) {
                delay(220)
            }
        }
        return null
    }

    private fun readTag(intent: Intent): Tag? {
        if (intent.action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            intent.action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) {
            return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
    }
}
