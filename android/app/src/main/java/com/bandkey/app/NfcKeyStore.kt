package com.bandkey.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class NfcKeyRecord(
    val id: String,
    val name: String,
    val tokenHash: ByteArray,
    val hint: String,
    val boundAt: Long,
    val lastUsedAt: Long
)

/**
 * NFC 钥匙只保存随机令牌的 SHA-256，不保存卡片原文。
 * 记录与配对私钥一样，使用 Android Keystore 中的 AES-GCM 密钥加密。
 */
class NfcKeyStore(context: Context) {

    companion object {
        const val MAX_KEYS = 3
        private const val PREFS = "bandkey_secure"
        private const val KEY_BLOB = "nfc_keys_blob"
        private const val KEY_ALIAS = "bandkey_nfc_keys_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<NfcKeyRecord> {
        val stored = prefs.getString(KEY_BLOB, null) ?: return emptyList()
        return runCatching {
            val blob = decode(stored)
            require(blob.size > 12) { "ciphertext too short" }
            val iv = blob.copyOfRange(0, 12)
            val ciphertext = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val array = JSONArray(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val hash = decode(item.optString("tokenHash"))
                    if (hash.size != 32) {
                        continue
                    }
                    add(
                        NfcKeyRecord(
                            id = item.optString("id").ifBlank { "nfc-${index + 1}" },
                            name = item.optString("name").ifBlank { "NFC 钥匙" },
                            tokenHash = hash,
                            hint = item.optString("hint").ifBlank { NfcCredential.hint(hash) },
                            boundAt = item.optLong("boundAt", 0L),
                            lastUsedAt = item.optLong("lastUsedAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(records: List<NfcKeyRecord>) {
        val array = JSONArray()
        records.take(MAX_KEYS).forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("name", record.name)
                    .put("tokenHash", encode(record.tokenHash))
                    .put("hint", record.hint)
                    .put("boundAt", record.boundAt)
                    .put("lastUsedAt", record.lastUsedAt)
            )
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_BLOB, encode(cipher.iv + ciphertext)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)
}
