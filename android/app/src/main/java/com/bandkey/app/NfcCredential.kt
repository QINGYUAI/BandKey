package com.bandkey.app

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object NfcCredential {

    private const val PREFIX = "bandkey:v1:"
    private const val TOKEN_BYTES = 32
    private val random = SecureRandom()

    fun newPayload(): String {
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val encoded = Base64.encodeToString(
            token,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return PREFIX + encoded
    }

    fun isValid(payload: String?): Boolean = tokenBytes(payload) != null

    fun hash(payload: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))

    fun hashEquals(left: ByteArray, right: ByteArray): Boolean =
        MessageDigest.isEqual(left, right)

    fun hint(hash: ByteArray): String {
        require(hash.size >= 2) { "hash too short" }
        return "BK-NFC ··· " + hash.take(2).joinToString("") {
            "%02X".format(it.toInt() and 0xFF)
        }
    }

    private fun tokenBytes(payload: String?): ByteArray? {
        if (payload == null || !payload.startsWith(PREFIX)) {
            return null
        }
        val encoded = payload.removePrefix(PREFIX)
        if (encoded.length != 43) {
            return null
        }
        return runCatching {
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull()?.takeIf { it.size == TOKEN_BYTES }
    }
}
