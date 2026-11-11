package com.bandkey.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NfcDiagEvent(
    val at: Long,
    val action: String,
    val detail: String
)

object NfcDiagnostics {

    private const val PREFS = "bandkey_nfc_diag"
    private const val KEY_AT = "last_event_at"
    private const val KEY_ACTION = "last_event_action"
    private const val KEY_DETAIL = "last_event_detail"
    private const val LOG_FILE = "nfc-events.log"
    private const val MAX_LOG_BYTES = 32 * 1024L

    fun record(context: Context, action: String, detail: String) {
        val appContext = context.applicationContext
        val at = System.currentTimeMillis()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_AT, at)
            .putString(KEY_ACTION, action)
            .putString(KEY_DETAIL, detail)
            .apply()
        Log.i("BandKeyNfc", "$action · $detail")

        runCatching {
            val file = File(appContext.filesDir, LOG_FILE)
            synchronized(this) {
                if (file.length() > MAX_LOG_BYTES) {
                    file.delete()
                }
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(at))
                file.appendText("$time $action · $detail\n")
            }
        }
    }

    fun snapshot(context: Context, intent: Intent, tag: Tag?): String {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val messages = ndefMessages(intent)
        val records = messages.firstOrNull()?.records.orEmpty()
        val first = records.firstOrNull()
        val firstType = first?.type
            ?.toString(Charsets.UTF_8)
            ?.replace("\n", " ")
            ?.take(48)
            .orEmpty()
        val tagId = tag?.id
            ?.joinToString("") { "%02X".format(it) }
            .orEmpty()
        val technologies = tag?.techList
            ?.map { it.substringAfterLast('.') }
            ?.joinToString("+")
            .orEmpty()
        val tagIntent = NfcTagIntentAccess.read(context)

        return buildString {
            append("action=")
            append(intent.action?.removePrefix("android.nfc.action.") ?: "-")
            append(" screenOn=")
            append(power?.isInteractive == true)
            append(" keyguard=")
            append(keyguard?.isKeyguardLocked == true)
            append(" deviceLocked=")
            append(keyguard?.isDeviceLocked == true)
            append(" auto=")
            append(BackgroundUnlock.isNfcAutoUnlockEnabled(context))
            append(" nfcAllowed=")
            append(tagIntent.allowed)
            append(" tag=")
            append(tagId.ifBlank { "-" })
            append(" tech=")
            append(technologies.ifBlank { "-" })
            append(" ndefMessages=")
            append(messages.size)
            append(" firstRecords=")
            append(records.size)
            append(" firstTnf=")
            append(first?.let { tnfName(it.tnf) } ?: "-")
            append(" firstType=")
            append(firstType.ifBlank { "-" })
        }
    }

    fun lastEvent(context: Context): NfcDiagEvent? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_AT, 0L)
        if (at <= 0L) {
            return null
        }
        return NfcDiagEvent(
            at = at,
            action = prefs.getString(KEY_ACTION, "").orEmpty(),
            detail = prefs.getString(KEY_DETAIL, "").orEmpty()
        )
    }

    private fun ndefMessages(intent: Intent): List<NdefMessage> {
        @Suppress("DEPRECATION")
        val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        return raw?.filterIsInstance<NdefMessage>().orEmpty()
    }

    private fun tnfName(tnf: Short): String = when (tnf.toInt()) {
        NdefRecord.TNF_EMPTY.toInt() -> "empty"
        NdefRecord.TNF_WELL_KNOWN.toInt() -> "well-known"
        NdefRecord.TNF_MIME_MEDIA.toInt() -> "mime"
        NdefRecord.TNF_ABSOLUTE_URI.toInt() -> "absolute-uri"
        NdefRecord.TNF_EXTERNAL_TYPE.toInt() -> "external"
        NdefRecord.TNF_UNKNOWN.toInt() -> "unknown"
        NdefRecord.TNF_UNCHANGED.toInt() -> "unchanged"
        else -> tnf.toString()
    }
}
