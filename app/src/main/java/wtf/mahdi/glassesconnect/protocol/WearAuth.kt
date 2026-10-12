package wtf.mahdi.glassesconnect.protocol

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * WearAuthV2 / WearAuthSpp reimplementation (APK: com.xiaomi.wearable.wear.api.WearAuthV2).
 * Packets are WearPacket(type=1 Account). Sent unencrypted on Wear API type 100 (L2 channel 1, opcode 1).
 *
 * 1. App -> AppVerify (id 26, Account field 30): appRandom(16)
 * 2. Dev -> DeviceVerify (Account field 31): deviceRandom, deviceSign
 *    keys = HKDF-SHA256(ikm=token, salt=appRandom||devRandom, info="miwear-auth", 64)
 *    check deviceSign == HMAC(deviceKey, devRandom||appRandom)
 * 3. App -> AppConfirm (id 27, Account field 32): appSign=HMAC(appKey, appRandom||devRandom),
 *    encryptCompanionDevice = AES-CCM(appKey, appIV||0^8, CompanionDevice)
 * 4. Dev -> DeviceConfirm (Account field 33): confirmResult=true -> session keys active.
 */
class WearAuth(
    private val token: ByteArray,
    private val phoneModel: String = "Android",
    private val sdkInt: Int = 34,
) {
    private val appRandom = ByteArray(16).also { SecureRandom().nextBytes(it) }
    var keys: WearCrypto.Keys? = null
        private set

    fun appVerify(): ByteArray = account(26) { message(30) { bytes(1, appRandom) } }

    /** Returns AppConfirm packet. Throws on signature mismatch (token wrong for this device). */
    fun onDeviceVerify(packet: ByteArray): ByteArray {
        val account = accountOf(packet)
        Proto.scalar(account, 3)?.let { code ->
            throw AuthException(if (code == 4L) "Device not bound (error 4). Bind with the official app first." else "AppVerify error $code")
        }
        val verify = Proto.decode(Proto.bytes(account, 31) ?: throw AuthException("Unexpected reply to AppVerify"))
        val deviceRandom = Proto.bytes(verify, 1) ?: throw AuthException("Missing deviceRandom")
        val deviceSign = Proto.bytes(verify, 2) ?: throw AuthException("Missing deviceSign")
        val k = WearCrypto.derive(token, appRandom, deviceRandom)
        val expected = WearCrypto.hmac(k.deviceKey, deviceRandom + appRandom)
        if (!MessageDigest.isEqual(expected, deviceSign)) {
            k.clear(); throw AuthException("Device signature mismatch: token (encrypt_key) is wrong")
        }
        keys = k
        val appSign = WearCrypto.hmac(k.appKey, appRandom + deviceRandom)
        val companion = Proto.encode {
            uint(1, 0) // deviceType = phone
            float(2, sdkInt.toFloat())
            string(3, phoneModel)
        }
        val nonce = k.appIv + ByteArray(8)
        val encrypted = WearCrypto.ccmEncrypt(k.appKey, nonce, companion)
        return account(27) { message(32) { bytes(1, appSign); bytes(2, encrypted) } }
    }

    /** Returns device capability fields on success. */
    fun onDeviceConfirm(packet: ByteArray): String {
        val account = accountOf(packet)
        Proto.scalar(account, 3)?.let { throw AuthException("AppConfirm error $it") }
        val confirm = Proto.decode(Proto.bytes(account, 33) ?: throw AuthException("Unexpected reply to AppConfirm"))
        if (Proto.scalar(confirm, 1) != 1L) throw AuthException("Device rejected confirm")
        return "cap=${Proto.scalar(confirm, 2)} cap2=${Proto.scalar(confirm, 3)} cap3=${Proto.scalar(confirm, 4)}"
    }

    private fun account(id: Int, block: Proto.Writer.() -> Unit) = Proto.encode {
        uint(1, 1); uint(2, id); bytes(3, Proto.encode(block))
    }

    private fun accountOf(packet: ByteArray): List<Proto.Field> {
        val envelope = Proto.decode(packet)
        Proto.scalar(envelope, 100)?.takeIf { it != 0L }?.let { throw AuthException("WearPacket error $it") }
        return Proto.decode(Proto.bytes(envelope, 3) ?: throw AuthException("Reply has no Account payload"))
    }
}

class AuthException(message: String) : Exception(message)
