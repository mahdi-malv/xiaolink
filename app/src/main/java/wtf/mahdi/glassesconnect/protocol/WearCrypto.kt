package wtf.mahdi.glassesconnect.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Reimplementation of WearAuthV2 / SecurityUtil. No Xiaomi keys are embedded. */
object WearCrypto {
    data class Keys(val deviceKey: ByteArray, val appKey: ByteArray, val deviceIv: ByteArray, val appIv: ByteArray) {
        fun clear() { listOf(deviceKey, appKey, deviceIv, appIv).forEach { it.fill(0) } }
    }

    fun hmac(key: ByteArray, input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(input)
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..8160)
        val prk = hmac(salt, ikm)
        var previous = byteArrayOf()
        var output = byteArrayOf()
        var counter = 1
        while (output.size < length) {
            previous = hmac(prk, previous + info + counter++.toByte())
            output += previous
        }
        prk.fill(0)
        return output.copyOf(length)
    }

    fun derive(token: ByteArray, appRandom: ByteArray, deviceRandom: ByteArray): Keys {
        require(token.isNotEmpty() && appRandom.size == 16 && deviceRandom.size == 16)
        val material = hkdf(token, appRandom + deviceRandom, "miwear-auth".toByteArray(), 64)
        val keys = Keys(material.copyOfRange(0,16), material.copyOfRange(16,32),
            material.copyOfRange(32,36), material.copyOfRange(36,40))
        material.fill(0)
        return keys
    }

    // CCM with 12-byte nonce, no associated data, and 4-byte tag, as used by the APK.
    fun ccmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        require(nonce.size == 12 && plaintext.size <= 0xffffff)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        fun block(bytes: ByteArray) = cipher.doFinal(bytes)
        val first = byteArrayOf(0x0a) + nonce + length3(plaintext.size)
        var mac = block(first)
        plaintext.asList().chunked(16).forEach { chunk ->
            val padded = chunk.toByteArray().copyOf(16)
            mac = block(ByteArray(16) { (mac[it].toInt() xor padded[it].toInt()).toByte() })
        }
        fun stream(index: Int) = block(byteArrayOf(2) + nonce + length3(index))
        val ciphertext = ByteArray(plaintext.size)
        for (offset in plaintext.indices step 16) {
            val s = stream(offset / 16 + 1)
            for (i in 0 until minOf(16, plaintext.size - offset)) {
                ciphertext[offset+i] = (plaintext[offset+i].toInt() xor s[i].toInt()).toByte()
            }
        }
        val zero = stream(0)
        return ciphertext + ByteArray(4) { (mac[it].toInt() xor zero[it].toInt()).toByte() }
    }

    fun ccmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        require(nonce.size == 12 && ciphertext.size >= 4)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        val plain = ByteArray(ciphertext.size - 4)
        for (offset in plain.indices step 16) {
            val stream = cipher.doFinal(byteArrayOf(2) + nonce + length3(offset / 16 + 1))
            for (i in 0 until minOf(16, plain.size-offset))
                plain[offset+i] = (ciphertext[offset+i].toInt() xor stream[i].toInt()).toByte()
        }
        val expected = ccmEncrypt(key, nonce, plain)
        require(MessageDigest.isEqual(expected, ciphertext)) { "Authentication tag mismatch" }
        return plain
    }

    /** V2 uses AES CTR with the directional key also as IV (APK AESCrypt byte[] overload). */
    fun ctr(key: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/CTR/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key)); doFinal(data)
    }

    private fun length3(value: Int) = byteArrayOf((value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
