package wtf.mahdi.glassesconnect.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors from tools/fetch_key.py. */
class XiaomiCloudTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun rc4DropsFirst1024Bytes() =
        assertEquals("f624d2dc7c91b19b2bba67504a", XiaomiCloud.Rc4("0123456789abcdef".toByteArray()).crypt("hello glasses".toByteArray()).hex())

    @Test fun sha1SigSortsParams() =
        assertEquals("63fdaf9b3768a1f8faf8affcf7f44c0e32c4a251",
            XiaomiCloud.sha1Sig("post", XiaomiCloud.PATH, mapOf("b" to "2", "a" to "1"), "nonce").hex())
}
