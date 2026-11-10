package com.bandkey.app

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.nio.charset.Charset

object NfcCard {

    private val externalType = "bandkey:unlock".toByteArray(Charsets.US_ASCII)

    fun writePayload(tag: Tag, payload: String): Boolean {
        val record = NdefRecord(
            NdefRecord.TNF_EXTERNAL_TYPE, externalType, ByteArray(0), payload.toByteArray(Charsets.UTF_8)
        )
        val message = NdefMessage(arrayOf(record))

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                if (!ndef.isWritable || ndef.maxSize < message.toByteArray().size) return false
                val existing = ndef.ndefMessage ?: ndef.cachedNdefMessage
                if (existing != null && existing.records.any { !isBandKeyRecord(it) }) {
                    return false
                }
                ndef.writeNdefMessage(message)
                return true
            } finally {
                ndef.close()
            }
        }

        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            formatable.connect()
            try {
                formatable.format(message)
                return true
            } finally {
                formatable.close()
            }
        }
        return false
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
}
