package wtf.mahdi.glassesconnect.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class WearBindTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun account(packet: ByteArray) = Proto.decode(Proto.bytes(Proto.decode(packet), 3)!!)
    private fun reply(id: Int, block: Proto.Writer.() -> Unit) = Proto.encode { uint(1, 1); uint(2, id); bytes(3, Proto.encode(block)) }

    /** NIST SP 800-38C Example 1: 7-byte nonce, 8-byte AAD, 4-byte tag. */
    @Test fun ccmNistExample1() {
        val c = WearCrypto.ccmEncrypt(hex("404142434445464748494a4b4c4d4e4f"), hex("10111213141516"), hex("20212223"), hex("0001020304050607"))
        assertArrayEquals(hex("7162015b4dac255d"), c)
    }

    @Test fun ccmAadRoundTrip() {
        val key = ByteArray(16) { it.toByte() }
        val plain = ByteArray(40) { (it * 3).toByte() }
        val c = WearCrypto.ccmEncrypt(key, WearBind.SALT, plain, "bind-data".toByteArray())
        assertArrayEquals(plain, WearCrypto.ccmDecrypt(key, WearBind.SALT, c, "bind-data".toByteArray()))
        assertFalse(runCatching { WearCrypto.ccmDecrypt(key, WearBind.SALT, c) }.isSuccess)
    }

    @Test fun startCarriesMd5UserId() {
        val p = WearBind.start("12345")
        assertEquals(17L, Proto.scalar(Proto.decode(p), 2))
        val start = Proto.decode(Proto.bytes(account(p), 11)!!)
        assertEquals(0L, Proto.scalar(start, 1))
        assertArrayEquals(MessageDigest.getInstance("MD5").digest("12345".toByteArray()), Proto.bytes(start, 2))
    }

    @Test fun parseBindInfo() {
        val info = WearBind.parseBindInfo(reply(17) { message(12) {
            uint(1, 1); string(2, "SN1"); string(3, "AA:BB"); string(4, "miwear.glasses.o95"); uint(5, 3); bytes(6, byteArrayOf(9, 8)); string(7, "did1")
        } })
        assertEquals(1, info.verifyMode); assertEquals(3, info.oobMode); assertEquals("SN1", info.sn)
        assertEquals("miwear.glasses.o95", info.model); assertEquals("did1", info.did); assertArrayEquals(byteArrayOf(9, 8), info.deviceRandom)
    }

    @Test(expected = WearBind.BindError::class) fun alreadyBoundError() {
        WearBind.parseBindInfo(reply(17) { uint(3, 1) })
    }

    @Test fun confirmSignaturesBothWays() {
        val deviceKey = ByteArray(16) { (it + 1).toByte() }
        val oob = "012345"
        val appRandom = ByteArray(16) { 7 }
        val confirm = Proto.decode(Proto.bytes(account(WearBind.appConfirm(deviceKey, oob, "KEY".toByteArray(), appRandom)), 15)!!)
        assertArrayEquals(WearCrypto.hmac(deviceKey, appRandom + oob.toByteArray()), Proto.bytes(confirm, 2))
        assertArrayEquals("KEY".toByteArray(), Proto.bytes(confirm, 3))
        val devRandom = ByteArray(16) { 3 }
        val c = WearBind.parseDeviceConfirm(reply(19) { message(16) {
            bytes(1, devRandom); bytes(2, WearCrypto.hmac(deviceKey, devRandom + oob.toByteArray())); bytes(3, byteArrayOf(1, 2))
        } })
        assertTrue(WearBind.deviceSignOk(deviceKey, oob, c))
        assertFalse(WearBind.deviceSignOk(deviceKey, "000000", c))
    }

    @Test fun resultDecryptsToUserAndCompanion() {
        val deviceKey = ByteArray(16) { 5 }
        val p = WearBind.result(deviceKey, "999", "Pixel", 34)
        assertEquals(25L, Proto.scalar(Proto.decode(p), 2))
        val enc = Proto.bytes(Proto.decode(Proto.bytes(account(p), 29)!!), 1)!!
        val info = Proto.decode(WearCrypto.ccmDecrypt(deviceKey, WearBind.SALT, enc, "bind-data".toByteArray()))
        assertEquals("999", Proto.bytes(info, 1)!!.decodeToString())
        val companion = Proto.decode(Proto.bytes(info, 2)!!)
        assertEquals("Pixel", Proto.bytes(companion, 3)!!.decodeToString())
        WearBind.checkResult(reply(25) { })
    }
}
