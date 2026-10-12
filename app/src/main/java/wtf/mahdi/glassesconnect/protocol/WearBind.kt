package wtf.mahdi.glassesconnect.protocol

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Glasses side of the PSK bind (WearBinderV2 + PSKWearBinderV2). Packets are WearPacket type 1 (Account),
 * sent plain on Wear type 100 (L2 ch1 op1) like auth (WearApiCall.unauthCall: V2 -> type 100).
 * The Xiaomi server makes all signatures; the phone only relays them (cloud part: XiaomiCloud.bindHello/Ecdh/Confirm).
 *
 * Flow (APK com/xiaomi/fit/device/bind/):
 * 1. id17 BindStartV2 (Account f11) {1 checkDynamicCode=false, 2 MD5(userId)}  -> BindInfoV2 (f12)   WearBinderV2.java:35
 *    BindInfoV2 {1 verifyMode, 2 sn, 3 mac, 4 model, 5 oobMode, 6 deviceRandom, 7 did}; only verifyMode 1 (PSK) is supported.
 * 2. id18 ServerVerify (f13) {1 pub_s, 2 sign_s, 3 random_s}  -> DeviceVerify (f14) {1 devicePublicKey, 2 deviceSign}
 *                                                                               PSKWearBinderV2.verifyServer
 * 3. id19 AppConfirm (f15) {1 appRandom(16), 2 HMAC(deviceKey, appRandom||oob), 3 bindKey}
 *    -> DeviceConfirm (f16) {1 deviceRandom, 2 HMAC(deviceKey, deviceRandom||oob), 3 bindSign}    PSKWearBinderV2.sendConfirm
 * 4. id25 BindResultV2 (f29) {1 AES-CCM(deviceKey, SALT, ResultInfo{1 userId, 2 CompanionDevice}, aad "bind-data")}
 *                                                                               PSKWearBinderV2.sendBindResult
 * Errors: Account f3 errorCode. On failure the APK sends the step id with only errorCode (sendErrorCode).
 * Account oneof numbers: AccountProtos.java (nano) setBindStartV2=11 ... setBindResultV2=29.
 */
object WearBind {
    /** WearBinderV2.SALT; used as the 12-byte CCM nonce. */
    val SALT = byteArrayOf(16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27)
    const val ID_START = 17
    const val ID_SERVER_VERIFY = 18
    const val ID_APP_CONFIRM = 19
    const val ID_RESULT = 25

    data class BindInfo(val verifyMode: Int, val sn: String, val mac: String, val model: String,
                        val oobMode: Int, val deviceRandom: ByteArray, val did: String)
    data class DeviceVerify(val publicKey: ByteArray, val sign: ByteArray)
    data class DeviceConfirm(val deviceRandom: ByteArray, val deviceSign: ByteArray, val bindSign: ByteArray)

    /** Glasses replied with Account.errorCode. WearBinderV2: 1 or 9 = already bound; 6 = cancelled on device; 7 = bad signature. */
    class BindError(val step: String, val code: Long) : Exception("$step: glasses error $code${ERRORS[code]?.let { " ($it)" } ?: ""}")

    val ERRORS = mapOf(1L to "device already bound", 9L to "device already bound", 6L to "cancelled on device", 7L to "signature check failed")

    fun start(userId: String): ByteArray = account(ID_START) {
        message(11) { uint(1, 0); bytes(2, MessageDigest.getInstance("MD5").digest(userId.toByteArray())) }
    }

    fun parseBindInfo(packet: ByteArray): BindInfo {
        val m = Proto.decode(payload(packet, 12, "BindStart"))
        return BindInfo(
            verifyMode = (Proto.scalar(m, 1) ?: 0).toInt(),
            sn = Proto.bytes(m, 2)?.decodeToString().orEmpty(),
            mac = Proto.bytes(m, 3)?.decodeToString().orEmpty(),
            model = Proto.bytes(m, 4)?.decodeToString().orEmpty(),
            oobMode = (Proto.scalar(m, 5) ?: 0).toInt(),
            deviceRandom = Proto.bytes(m, 6) ?: byteArrayOf(),
            did = Proto.bytes(m, 7)?.decodeToString().orEmpty(),
        )
    }

    fun serverVerify(publicKey: ByteArray, sign: ByteArray, random: ByteArray): ByteArray = account(ID_SERVER_VERIFY) {
        message(13) { bytes(1, publicKey); bytes(2, sign); bytes(3, random) }
    }

    fun parseDeviceVerify(packet: ByteArray): DeviceVerify {
        val m = Proto.decode(payload(packet, 14, "ServerVerify"))
        return DeviceVerify(Proto.bytes(m, 1) ?: byteArrayOf(), Proto.bytes(m, 2) ?: byteArrayOf())
    }

    /** [oob] is the server oob_code as "%06d". */
    fun appConfirm(deviceKey: ByteArray, oob: String, bindKey: ByteArray, appRandom: ByteArray = random(16)): ByteArray = account(ID_APP_CONFIRM) {
        message(15) { bytes(1, appRandom); bytes(2, WearCrypto.hmac(deviceKey, appRandom + oob.toByteArray())); bytes(3, bindKey) }
    }

    fun parseDeviceConfirm(packet: ByteArray): DeviceConfirm {
        val m = Proto.decode(payload(packet, 16, "AppConfirm"))
        return DeviceConfirm(Proto.bytes(m, 1) ?: byteArrayOf(), Proto.bytes(m, 2) ?: byteArrayOf(), Proto.bytes(m, 3) ?: byteArrayOf())
    }

    fun deviceSignOk(deviceKey: ByteArray, oob: String, c: DeviceConfirm): Boolean =
        MessageDigest.isEqual(WearCrypto.hmac(deviceKey, c.deviceRandom + oob.toByteArray()), c.deviceSign)

    /** CompanionDevice as in sendBindResult: deviceType 0, systemVersion SDK_INT, deviceName Build.MODEL, appCapability 0. */
    fun result(deviceKey: ByteArray, userId: String, phoneModel: String, sdkInt: Int): ByteArray {
        val info = Proto.encode {
            string(1, userId)
            message(2) { uint(1, 0); float(2, sdkInt.toFloat()); string(3, phoneModel); uint(4, 0) }
        }
        val encrypted = WearCrypto.ccmEncrypt(deviceKey, SALT, info, "bind-data".toByteArray())
        return account(ID_RESULT) { message(29) { bytes(1, encrypted) } }
    }

    /** Checks the reply to BindResultV2: no WearPacket/Account error. */
    fun checkResult(packet: ByteArray) {
        val account = accountOf(packet, "BindResult")
        Proto.scalar(account, 3)?.takeIf { it != 0L }?.let { throw BindError("BindResult", it) }
    }

    /** Failure report like PSKWearBinderV2.sendErrorCode: step id with Account.errorCode only. */
    fun error(id: Int, code: Int): ByteArray = account(id) { uint(3, code) }

    private fun payload(packet: ByteArray, field: Int, step: String): ByteArray {
        val account = accountOf(packet, step)
        Proto.scalar(account, 3)?.let { throw BindError(step, it) }
        return Proto.bytes(account, field) ?: throw IllegalStateException("$step: reply has no Account field $field: ${GlassesCommands.dump(packet).trim()}")
    }

    private fun accountOf(packet: ByteArray, step: String): List<Proto.Field> {
        val envelope = Proto.decode(packet)
        Proto.scalar(envelope, 100)?.takeIf { it != 0L }?.let { throw BindError("$step (WearPacket)", it) }
        return Proto.decode(Proto.bytes(envelope, 3) ?: throw IllegalStateException("$step: reply has no Account: ${GlassesCommands.dump(packet).trim()}"))
    }

    private fun account(id: Int, block: Proto.Writer.() -> Unit) = Proto.encode {
        uint(1, 1); uint(2, id); bytes(3, Proto.encode(block))
    }

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
}
