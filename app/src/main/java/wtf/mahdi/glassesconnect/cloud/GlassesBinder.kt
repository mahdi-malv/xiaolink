package wtf.mahdi.glassesconnect.cloud

import wtf.mahdi.glassesconnect.protocol.Proto
import wtf.mahdi.glassesconnect.protocol.WearBind
import wtf.mahdi.glassesconnect.protocol.WearSession
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Fresh PSK bind without the official app: relays between the glasses (bind-mode [session]) and Xiaomi cloud.
 * Port of WearBinderV2 + PSKWearBinderV2 + MiWearBindDataRepository. UNTESTED on device.
 * Blocking: call [run] on a background thread after the session reaches State.Unauthenticated,
 * and feed every plain packet to [onPlain].
 */
class GlassesBinder(
    private val cloud: XiaomiCloud,
    private val auth: XiaomiCloud.Auth,
    private val session: WearSession,
    /** BT address; sent as mac and randomMac (WearBinderV2 passes DeviceInfo.getAddress() for both). */
    private val address: String,
    /** BT name; bind/bind extra_values.name (PSKWearBinderV2.deviceName). */
    private val deviceName: String,
    /** Settings.Secure.ANDROID_ID (PSKWearBinderV2.applyBind). */
    private val phoneId: String,
    /** LocaleUtil.getCurrentLocale(): language_country, lower case. */
    private val locale: String,
    private val phoneModel: String,
    private val sdkInt: Int,
    private val log: (String) -> Unit,
    private val onOob: (String) -> Unit,
) {
    data class Result(val did: String, val model: String, val sn: String)

    private val replies = LinkedBlockingQueue<ByteArray>()
    @Volatile private var cancelled = false

    fun onPlain(packet: ByteArray) { replies.offer(packet) }
    fun cancel() { cancelled = true; replies.offer(byteArrayOf()) }

    fun run(): Result {
        log("Bind: userId=${auth.userId} address=$address name=$deviceName locale=$locale phoneId=$phoneId")

        // 1. BindStartV2 -> BindInfoV2
        val info = WearBind.parseBindInfo(exchange("BindStart", WearBind.start(auth.userId), 15))
        log("Bind: BindInfoV2 verifyMode=${info.verifyMode} oobMode=${info.oobMode} model=${info.model} sn=${info.sn} " +
            "mac=${info.mac} did=${info.did} deviceRandom=${hex(info.deviceRandom)}")
        if (info.verifyMode != 1) error("verifyMode ${info.verifyMode} not supported (APK only binds PSK verifyMode 1)")

        // 2. Server hello -> ServerVerify -> DeviceVerify
        val hello = step(WearBind.ID_SERVER_VERIFY, 5, "bind/hello") {
            cloud.bindHello(auth, info.verifyMode, info.model, address, phoneId, info.sn, info.did, info.deviceRandom)
        }
        val bindIndex = hello.optString("bind_index")
        val bindDid = hello.optString("bind_did")
        log("Bind: hello bind_index=$bindIndex bind_did=$bindDid")
        val verify = WearBind.parseDeviceVerify(exchange("ServerVerify", WearBind.serverVerify(
            b64(hello, "pub_s"), b64(hello, "sign_s"), b64(hello, "random_s")), 15))
        log("Bind: DeviceVerify pub=${hex(verify.publicKey)} sign=${hex(verify.sign)}")

        // 3. Server ECDH -> AppConfirm -> DeviceConfirm
        val ecdh = step(WearBind.ID_APP_CONFIRM, 100, "bind/ecdh") {
            cloud.bindEcdh(auth, bindIndex, bindDid, verify.publicKey, verify.sign)
        }
        val deviceKey = b64(ecdh, "device_key")
        val bindKey = ecdh.optString("bind_key").toByteArray() // PSKWearBinderV2: getBindKey().getBytes(), not base64-decoded
        val oob = "%06d".format(ecdh.optInt("oob_code"))
        log("Bind: ecdh device_key=${hex(deviceKey)} bind_key=${ecdh.optString("bind_key")} oob=$oob")
        if (info.oobMode == 1) { log("Bind: oobMode 1, pairing code $oob (confirm on the glasses if asked)"); onOob(oob) }
        val confirm = WearBind.parseDeviceConfirm(exchange("AppConfirm", WearBind.appConfirm(deviceKey, oob, bindKey), 60))
        log("Bind: DeviceConfirm deviceRandom=${hex(confirm.deviceRandom)} sign=${hex(confirm.deviceSign)} bindSign=${hex(confirm.bindSign)}")
        if (!WearBind.deviceSignOk(deviceKey, oob, confirm)) {
            session.sendPlain(WearBind.error(WearBind.ID_RESULT, 7))
            error("Device sign check failed (HMAC(deviceKey, deviceRandom||oob))")
        }

        // 4. Server confirm -> BindResultV2
        val bound = step(WearBind.ID_RESULT, 5, "bind/bind") {
            cloud.bindConfirm(auth, locale, bindIndex, bindDid, confirm.bindSign, deviceName)
        }
        log("Bind: server bind result $bound")
        // BindResult.isSuccess() = ret == 1 (BindResult.java:72); the server sends only {"ret":1}.
        if (bound.optInt("ret") != 1) {
            session.sendPlain(WearBind.error(WearBind.ID_RESULT, 5))
            error("Server bind not successful: $bound")
        }
        val reply = exchange("BindResult", WearBind.result(deviceKey, auth.userId, phoneModel, sdkInt), 15)
        // APK onSendBindResultFailed also calls server unbind; we only report it.
        runCatching { WearBind.checkResult(reply) }.onFailure {
            error("Glasses rejected BindResult (server already has the bind for did $bindDid): ${it.message}")
        }
        log("Bind: done, did=$bindDid")
        return Result(bindDid, info.model, info.sn)
    }

    /** Runs a cloud step; on failure tells the glasses like PSKWearBinderV2.sendErrorCode([id], [code]). */
    private fun <T> step(id: Int, code: Int, name: String, block: () -> T): T = try {
        block().also { log("Bind: cloud $name OK: ${cloud.lastResponse.take(800)}") }
    } catch (e: Exception) {
        log("Bind: cloud $name failed: ${cloud.lastResponse.take(800)}")
        session.sendPlain(WearBind.error(id, code))
        throw e
    }

    private fun exchange(step: String, packet: ByteArray, timeoutS: Long): ByteArray {
        if (cancelled) error("Cancelled")
        replies.clear()
        log("Bind: → $step")
        session.sendPlain(packet)
        val reply = replies.poll(timeoutS, TimeUnit.SECONDS) ?: error("$step: no reply from glasses in $timeoutS s")
        if (cancelled) error("Cancelled")
        return reply
    }

    private fun b64(o: org.json.JSONObject, key: String): ByteArray =
        o.optString(key).takeIf { it.isNotEmpty() }?.let(XiaomiCloud::b64urlDecode) ?: error("Server reply has no $key: $o")

    private fun hex(b: ByteArray) = Proto.hex(b).replace(" ", "")
}
