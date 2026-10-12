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

    /**
     * AES-CCM with a 4-byte tag, as used by the APK (SecurityUtil.AESEncryptWithAuth, BouncyCastle CCMBlockCipher, mac 32 bits).
     * Auth uses a 12-byte nonce and no associated data; bind (BindResultV2) adds [aad] "bind-data".
     */
    fun ccmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        require(nonce.size in 7..13 && aad.size < 0xff00)
        val q = 15 - nonce.size
        require(q >= 4 || plaintext.size < (1 shl (8 * q)))
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        fun block(bytes: ByteArray) = cipher.doFinal(bytes)
        fun absorb(mac: ByteArray, data: ByteArray): ByteArray {
            var m = mac
            data.asList().chunked(16).forEach { chunk ->
                val padded = chunk.toByteArray().copyOf(16)
                m = block(ByteArray(16) { (m[it].toInt() xor padded[it].toInt()).toByte() })
            }
            return m
        }
        val flags = (if (aad.isNotEmpty()) 0x40 else 0) or (((4 - 2) / 2) shl 3) or (q - 1)
        var mac = block(byteArrayOf(flags.toByte()) + nonce + lengthBytes(plaintext.size, q))
        if (aad.isNotEmpty()) mac = absorb(mac, lengthBytes(aad.size, 2) + aad)
        mac = absorb(mac, plaintext)
        fun stream(index: Int) = block(byteArrayOf((q - 1).toByte()) + nonce + lengthBytes(index, q))
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

    fun ccmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        require(nonce.size in 7..13 && ciphertext.size >= 4)
        val q = 15 - nonce.size
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        val plain = ByteArray(ciphertext.size - 4)
        for (offset in plain.indices step 16) {
            val stream = cipher.doFinal(byteArrayOf((q - 1).toByte()) + nonce + lengthBytes(offset / 16 + 1, q))
            for (i in 0 until minOf(16, plain.size-offset))
                plain[offset+i] = (ciphertext[offset+i].toInt() xor stream[i].toInt()).toByte()
        }
        val expected = ccmEncrypt(key, nonce, plain, aad)
        require(MessageDigest.isEqual(expected, ciphertext)) { "Authentication tag mismatch" }
        return plain
    }

    /** V2 uses AES CTR with the directional key also as IV (APK AESCrypt byte[] overload). */
    fun ctr(key: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/CTR/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key)); doFinal(data)
    }

    /** Big-endian [value] in [size] bytes. */
    private fun lengthBytes(value: Int, size: Int) = ByteArray(size) { (value.toLong() ushr (8 * (size - 1 - it))).toByte() }
}
