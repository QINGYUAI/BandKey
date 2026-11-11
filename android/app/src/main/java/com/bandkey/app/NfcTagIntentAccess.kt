package com.bandkey.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter

data class NfcTagIntentState(
    val nfcAvailable: Boolean,
    val preferenceSupported: Boolean,
    val allowed: Boolean
)

/**
 * Android 16 起，系统增加了“通过 NFC 启动”的按应用允许名单。
 * 用户拒绝后，NDEF / TECH / TAG 三类 NFC Intent 都不会再分发给本应用。
 *
 * compileSdk 仍为 35，因此这里通过反射调用 API 36 的方法，
 * 避免为了两个查询接口整体提升编译目标。
 */
object NfcTagIntentAccess {

    private const val ACTION_CHANGE_TAG_INTENT_PREFERENCE =
        "android.nfc.action.CHANGE_TAG_INTENT_PREFERENCE"

    fun read(context: Context): NfcTagIntentState {
        val adapter = NfcAdapter.getDefaultAdapter(context)
            ?: return NfcTagIntentState(
                nfcAvailable = false,
                preferenceSupported = false,
                allowed = true
            )
        val supported = callBoolean(adapter, "isTagIntentAppPreferenceSupported") ?: false
        val allowed = if (supported) {
            callBoolean(adapter, "isTagIntentAllowed") ?: true
        } else {
            true
        }
        return NfcTagIntentState(
            nfcAvailable = true,
            preferenceSupported = supported,
            allowed = allowed
        )
    }

    fun openSettings(context: Context): Boolean {
        val intent = Intent(ACTION_CHANGE_TAG_INTENT_PREFERENCE)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
        }.isSuccess || VendorSettings.openNfcSettings(context)
    }

    private fun callBoolean(adapter: NfcAdapter, methodName: String): Boolean? =
        runCatching {
            NfcAdapter::class.java.getMethod(methodName).invoke(adapter) as? Boolean
        }.getOrNull()
}
