package com.bandkey.app

import android.util.Base64
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

object Crypto {

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    fun publicKeySpkiBase64(publicKey: ECPublicKey): String =
        Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

    fun signUnlockBase64(keyPair: KeyPair, data: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keyPair.private)
        signature.update(data)
        return Base64.encodeToString(derToP1363(signature.sign()), Base64.NO_WRAP)
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var idx = 0
        require(der[idx++].toInt() == 0x30) { "bad DER sequence" }
        idx = skipLength(der, idx)
        require(der[idx++].toInt() == 0x02) { "bad r" }
        val rLen = readLength(der, idx)
        idx += lengthOfLength(der, idx)
        val r = BigInteger(1, der.copyOfRange(idx, idx + rLen))
        idx += rLen
        require(der[idx++].toInt() == 0x02) { "bad s" }
        val sLen = readLength(der, idx)
        idx += lengthOfLength(der, idx)
        val s = BigInteger(1, der.copyOfRange(idx, idx + sLen))

        val out = ByteArray(64)
        System.arraycopy(fixed32(r), 0, out, 0, 32)
        System.arraycopy(fixed32(s), 0, out, 32, 32)
        return out
    }

    private fun fixed32(value: BigInteger): ByteArray {
        var bytes = value.toByteArray()
        while (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        require(bytes.size <= 32) { "r/s too large" }
        val out = ByteArray(32)
        System.arraycopy(bytes, 0, out, 32 - bytes.size, bytes.size)
        return out
    }

    private fun skipLength(der: ByteArray, idx: Int): Int = idx + lengthOfLength(der, idx)

    private fun lengthOfLength(der: ByteArray, idx: Int): Int {
        val first = der[idx].toInt() and 0xFF
        return if (first < 0x80) 1 else 1 + (first and 0x7F)
    }

    private fun readLength(der: ByteArray, idx: Int): Int {
        val first = der[idx].toInt() and 0xFF
        if (first < 0x80) return first
        val count = first and 0x7F
        var length = 0
        for (i in 1..count) {
            length = (length shl 8) or (der[idx + i].toInt() and 0xFF)
        }
        return length
    }
}
