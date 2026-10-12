package wtf.mahdi.glassesconnect.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearAuthTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun hkdfRfc5869Case1() {
        val okm = WearCrypto.hkdf(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test fun handshakeAgainstSimulatedDevice() {
        val token = hex("00112233445566778899aabbccddeeff")
        val auth = WearAuth(token, "Test", 34)
        val verify = Proto.decode(auth.appVerify())
        assertEquals(1L, Proto.scalar(verify, 1)); assertEquals(26L, Proto.scalar(verify, 2))
        val appRandom = Proto.bytes(Proto.decode(Proto.bytes(Proto.decode(Proto.bytes(verify, 3)!!), 30)!!), 1)!!
        // Device side.
        val deviceRandom = ByteArray(16) { it.toByte() }
        val k = WearCrypto.derive(token, appRandom, deviceRandom)
        val reply = Proto.encode { uint(1, 1); uint(2, 26); message(3) { message(31) {
            bytes(1, deviceRandom); bytes(2, WearCrypto.hmac(k.deviceKey, deviceRandom + appRandom))
        } } }
        val confirm = Proto.decode(Proto.bytes(Proto.decode(Proto.bytes(Proto.decode(auth.onDeviceVerify(reply)), 3)!!), 32)!!)
        assertArrayEquals(WearCrypto.hmac(k.appKey, appRandom + deviceRandom), Proto.bytes(confirm, 1))
        val companion = WearCrypto.ccmDecrypt(k.appKey, k.appIv + ByteArray(8), Proto.bytes(confirm, 2)!!)
        assertTrue(GlassesCommands.dump(companion).contains("\"Test\""))
        val ok = Proto.encode { uint(1, 1); uint(2, 27); message(3) { message(33) { uint(1, 1) } } }
        auth.onDeviceConfirm(ok)
    }

    @Test(expected = AuthException::class) fun wrongTokenRejected() {
        val auth = WearAuth(hex("01"), "Test", 34)
        auth.appVerify()
        val reply = Proto.encode { uint(1, 1); uint(2, 26); message(3) { message(31) { bytes(1, ByteArray(16)); bytes(2, ByteArray(32)) } } }
        auth.onDeviceVerify(reply)
    }
}
