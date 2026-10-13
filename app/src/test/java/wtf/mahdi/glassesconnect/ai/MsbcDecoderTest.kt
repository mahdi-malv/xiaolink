package wtf.mahdi.glassesconnect.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * Reference: signal.msbc (2 s chirp + tone + pink noise) was encoded and signal.pcm decoded with ffmpeg's SBC codec
 * (... -ac 1 -ar 16000 -c:a sbc -msbc 1 -f sbc signal.msbc; ffmpeg -f sbc -i signal.msbc -f s16le signal.pcm).
 */
class MsbcDecoderTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/msbc/$name")!!.readBytes()
    private fun samples(b: ByteArray) = ShortArray(b.size / 2) { ((b[2 * it].toInt() and 255) or (b[2 * it + 1].toInt() shl 8)).toShort() }

    @Test fun matchesFfmpeg() {
        val msbc = res("signal.msbc")
        val pcm = MsbcDecoder().decode(msbc)
        val ref = res("signal.pcm")
        assertEquals(ref.size, pcm.size)
        val a = samples(pcm); val r = samples(ref)
        var signal = 0.0; var noise = 0.0
        for (i in r.indices) { signal += r[i].toDouble() * r[i]; noise += (a[i] - r[i]).toDouble() * (a[i] - r[i]) }
        val snr = 10 * log10(signal / maxOf(noise, 1.0))
        assertTrue("SNR $snr dB", snr > 60)
    }

    @Test fun packetsAndSplitFramesGiveSameOutput() {
        val msbc = res("signal.msbc")
        val whole = MsbcDecoder().decode(msbc)
        val d = MsbcDecoder()
        val out = java.io.ByteArrayOutputStream()
        // 570-byte packets as the glasses send, then odd sizes that split frames.
        var pos = 0
        for (size in generateSequence(570) { if (it == 570) 100 else 570 }) {
            if (pos >= msbc.size) break
            val n = minOf(size, msbc.size - pos)
            out.write(d.decode(msbc.copyOfRange(pos, pos + n)))
            pos += n
        }
        assertArrayEquals(whole, out.toByteArray())
    }

    @Test fun skipsGarbageBeforeSync() {
        val msbc = res("signal.msbc").copyOf(MsbcDecoder.FRAME_LEN * 10)
        val clean = MsbcDecoder().decode(msbc)
        assertArrayEquals(clean, MsbcDecoder().decode(byteArrayOf(1, 2, 3) + msbc))
    }
}
