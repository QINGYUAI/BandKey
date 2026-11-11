package com.bandkey.app

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException
import java.nio.charset.Charset

object NfcCard {

    private const val PACKAGE_NAME = "com.bandkey.app"

    private val externalType = "com.bandkey:unlock".toByteArray(Charsets.US_ASCII)
    private val legacyExternalType = "bandkey:unlock".toByteArray(Charsets.US_ASCII)
    private val mimeType = "application/vnd.bandkey.unlock".toByteArray(Charsets.US_ASCII)
    private val applicationType = "android.com:pkg".toByteArray(Charsets.US_ASCII)

    sealed class WriteResult {
        data object Success : WriteResult()
        data object NotWritable : WriteResult()
        data class Unsupported(val technologies: List<String>) : WriteResult()
        data class TooLarge(val required: Int, val capacity: Int) : WriteResult()
        data class HasForeignData(val recordCount: Int) : WriteResult()
        data class Failed(val reason: String) : WriteResult()
    }

    fun writePayload(tag: Tag, payload: String, allowOverwrite: Boolean = false): WriteResult {
        val record = NdefRecord(
            NdefRecord.TNF_EXTERNAL_TYPE, externalType, ByteArray(0), payload.toByteArray(Charsets.UTF_8)
        )
        // 第一条记录决定系统如何映射 NDEF；AAR 放在后面，只在过滤器未唯一命中时兜底启动本应用。
        val message = NdefMessage(
            arrayOf(record, NdefRecord.createApplicationRecord(PACKAGE_NAME))
        )
        val required = message.toByteArray().size

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                try {
                    when {
                        !ndef.isWritable -> WriteResult.NotWritable
                        ndef.maxSize < required -> WriteResult.TooLarge(required, ndef.maxSize)
                        else -> {
                            val existing = ndef.ndefMessage ?: ndef.cachedNdefMessage
                            val foreign = existing?.records?.count { !isIgnorableRecord(it) } ?: 0
                            if (!allowOverwrite && foreign > 0) {
                                WriteResult.HasForeignData(foreign)
                            } else {
                                ndef.writeNdefMessage(message)
                                WriteResult.Success
                            }
                        }
                    }
                } finally {
                    ndef.close()
                }
            } catch (e: Exception) {
                WriteResult.Failed(describeFailure(e))
            }
        }

        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            return try {
                formatable.connect()
                try {
                    formatable.format(message)
                    WriteResult.Success
                } finally {
                    formatable.close()
                }
            } catch (e: Exception) {
                WriteResult.Failed(describeFailure(e))
            }
        }
        return WriteResult.Unsupported(
            tag.techList.map { it.substringAfterLast('.') }
        )
    }

    fun readPayload(tag: Tag?, intent: Intent? = null): String? {
        intent?.let { payloadFromIntent(it) }?.let { return it }
        val target = tag ?: return null
        val ndef = Ndef.get(target) ?: return null
        ndef.cachedNdefMessage?.let { payloadFromMessage(it) }?.let { return it }
        ndef.connect()
        try {
            return payloadFromMessage(ndef.ndefMessage ?: ndef.cachedNdefMessage)
        } finally {
            ndef.close()
        }
    }

    fun hasNdefMessage(intent: Intent): Boolean = ndefMessages(intent).isNotEmpty()

    private fun payloadFromIntent(intent: Intent): String? =
        ndefMessages(intent).firstNotNullOfOrNull { payloadFromMessage(it) }

    private fun payloadFromMessage(message: NdefMessage?): String? {
        message ?: return null
        for (record in message.records) {
            if (isBandKeyRecord(record)) {
                return String(record.payload, Charsets.UTF_8)
            }
        }
        return null
    }

    private fun ndefMessages(intent: Intent): List<NdefMessage> {
        @Suppress("DEPRECATION")
        val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        return raw?.filterIsInstance<NdefMessage>().orEmpty()
    }

    private fun isBandKeyRecord(record: NdefRecord): Boolean =
        (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE &&
            (record.type.contentEquals(externalType) ||
                record.type.contentEquals(legacyExternalType))) ||
            (record.tnf == NdefRecord.TNF_MIME_MEDIA &&
                record.type.contentEquals(mimeType))

    private fun isIgnorableRecord(record: NdefRecord): Boolean =
        isBandKeyRecord(record) ||
            record.tnf == NdefRecord.TNF_EMPTY ||
            (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE &&
                record.type.contentEquals(applicationType))

    private fun describeFailure(error: Exception): String = when (error) {
        is TagLostException -> "卡片离开感应区或连接中断"
        is IOException -> error.message?.takeIf { it.isNotBlank() } ?: "卡片通信失败（IOException）"
        is SecurityException -> "卡片已加密或锁定"
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }
}
