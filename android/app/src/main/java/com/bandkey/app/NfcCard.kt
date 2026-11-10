package com.bandkey.app

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException
import java.nio.charset.Charset

object NfcCard {

    private val externalType = "bandkey:unlock".toByteArray(Charsets.US_ASCII)

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
        val message = NdefMessage(arrayOf(record))
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

    fun readPayload(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        ndef.connect()
        try {
            val message = ndef.ndefMessage ?: ndef.cachedNdefMessage ?: return null
            for (record in message.records) {
                if (isBandKeyRecord(record)) {
                    return String(record.payload, Charsets.UTF_8)
                }
            }
        } finally {
            ndef.close()
        }
        return null
    }

    private fun isBandKeyRecord(record: NdefRecord): Boolean =
        record.tnf == NdefRecord.TNF_EXTERNAL_TYPE && record.type.contentEquals(externalType)

    private fun isIgnorableRecord(record: NdefRecord): Boolean =
        isBandKeyRecord(record) || record.tnf == NdefRecord.TNF_EMPTY

    private fun describeFailure(error: Exception): String = when (error) {
        is TagLostException -> "卡片离开感应区或连接中断"
        is IOException -> error.message?.takeIf { it.isNotBlank() } ?: "卡片通信失败（IOException）"
        is SecurityException -> "卡片已加密或锁定"
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }
}
