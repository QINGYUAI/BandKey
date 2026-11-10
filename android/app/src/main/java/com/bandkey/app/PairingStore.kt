package com.bandkey.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class PairingRecord(
    val deviceId: String,
    val deviceName: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val pairedAt: Long
) {
    fun toKeyPair(): KeyPair {
        val factory = KeyFactory.getInstance("EC")
        return KeyPair(
            factory.generatePublic(X509EncodedKeySpec(publicKey)),
            factory.generatePrivate(PKCS8EncodedKeySpec(privateKey))
        )
    }
}

/**
 * 配对信息持久化：
 * - 私钥、公钥、deviceId 序列化后由 Android Keystore 中的 AES-256-GCM 密钥加密；
 * - 密文放入 SharedPreferences，避免重启 App 后重新配对。
 */
class PairingStore(context: Context) {

    companion object {
        private const val PREFS = "bandkey_secure"
        private const val KEY_BLOB = "pairing_blob"
        private const val KEY_ALIAS = "bandkey_pairing_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(record: PairingRecord) {
        val json = JSONObject()
            .put("deviceId", record.deviceId)
            .put("deviceName", record.deviceName)
            .put("privateKey", encode(record.privateKey))
            .put("publicKey", encode(record.publicKey))
            .put("pairedAt", record.pairedAt)
            .toString()

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ciphertext
        prefs.edit().putString(KEY_BLOB, encode(blob)).apply()
    }

    fun load(): PairingRecord? {
        val stored = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val blob = decode(stored)
            require(blob.size > 12) { "ciphertext too short" }
            val iv = blob.copyOfRange(0, 12)
            val ciphertext = blob.copyOfRange(12, blob.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))

            val deviceId = json.optString("deviceId")
            if (deviceId.isEmpty()) {
                return null
            }

            PairingRecord(
                deviceId = deviceId,
                deviceName = json.optString("deviceName", "我的手机"),
                privateKey = decode(json.getString("privateKey")),
                publicKey = decode(json.getString("publicKey")),
                pairedAt = json.optLong("pairedAt", 0L)
            )
        } catch (_: Exception) {
            null
        }
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
