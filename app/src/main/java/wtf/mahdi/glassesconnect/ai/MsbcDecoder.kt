package wtf.mahdi.glassesconnect.ai

import kotlin.math.PI
import kotlin.math.cos

/**
 * Pure-Kotlin mSBC decoder (HFP wideband speech: SBC with fixed parameters 16 kHz, mono, 15 blocks, 8 subbands,
 * loudness allocation, bitpool 26; 57-byte frames that start with sync 0xAD 0x00 0x00). Follows the SBC decoder in
 * the A2DP specification (Appendix B). Replaces the official app's libsbc.so.
 *
 * The glasses send 10 frames per voice packet (analysis/commands.md, AIVS). Bytes before a sync word are skipped,
 * and a partial frame at the end of a packet is kept for the next call. One instance per audio stream: the
 * synthesis filter keeps state across frames.
 */
class MsbcDecoder {
    private val v = DoubleArray(160)
    private val pending = java.io.ByteArrayOutputStream()

    /** Decodes [length] bytes of [input] and returns 16 kHz mono s16le PCM (240 bytes per complete frame). */
    fun decode(input: ByteArray, length: Int = input.size): ByteArray {
        pending.write(input, 0, length)
        val buf = pending.toByteArray()
        val out = java.io.ByteArrayOutputStream(buf.size / FRAME_LEN * PCM_BYTES_PER_FRAME + PCM_BYTES_PER_FRAME)
        var pos = 0
        while (pos + FRAME_LEN <= buf.size) {
            if (!isSync(buf, pos)) { pos++; continue }
            decodeFrame(buf, pos, out)
            pos += FRAME_LEN
        }
        pending.reset()
        pending.write(buf, pos, buf.size - pos)
        return out.toByteArray()
    }

    fun reset() { v.fill(0.0); pending.reset() }

    private fun isSync(b: ByteArray, p: Int) = b[p] == SYNC && b[p + 1].toInt() == 0 && b[p + 2].toInt() == 0

    private fun decodeFrame(frame: ByteArray, start: Int, out: java.io.ByteArrayOutputStream) {
        val bits = BitReader(frame, start + 4) // sync, 2 reserved bytes, CRC
        val scale = IntArray(SUBBANDS) { bits.read(4) }
        val alloc = allocate(scale)
        val sb = DoubleArray(SUBBANDS)
        repeat(BLOCKS) {
            for (i in 0 until SUBBANDS) {
                val n = alloc[i]
                sb[i] = if (n == 0) 0.0 else {
                    val levels = (1 shl n) - 1
                    (1 shl (scale[i] + 1)) * ((2.0 * bits.read(n) + 1.0) / levels - 1.0)
                }
            }
            synthesize(sb, out)
        }
    }

    /** Bit allocation for one channel, loudness method (A2DP spec 12.6.3). */
    private fun allocate(scale: IntArray): IntArray {
        val need = IntArray(SUBBANDS) { sb ->
            if (scale[sb] == 0) -5 else {
                val loudness = scale[sb] - OFFSET_16K[sb]
                if (loudness > 0) loudness / 2 else loudness
            }
        }
        var slice = need.max() + 1
        var count = 0
        var sliceCount = 0
        do {
            slice--
            count += sliceCount
            sliceCount = 0
            for (n in need) {
                if (n > slice + 1 && n < slice + 16) sliceCount++ else if (n == slice + 1) sliceCount += 2
            }
        } while (count + sliceCount < BITPOOL)
        if (count + sliceCount == BITPOOL) { count += sliceCount; slice-- }
        val bits = IntArray(SUBBANDS) { if (need[it] < slice + 2) 0 else minOf(need[it] - slice, 16) }
        var sb = 0
        while (count < BITPOOL && sb < SUBBANDS) {
            if (bits[sb] in 2..15) { bits[sb]++; count++ }
            else if (need[sb] == slice + 1 && BITPOOL > count + 1) { bits[sb] = 2; count += 2 }
            sb++
        }
        sb = 0
        while (count < BITPOOL && sb < SUBBANDS) {
            if (bits[sb] < 16) { bits[sb]++; count++ }
            sb++
        }
        return bits
    }

    /** Synthesis filter bank for 8 subbands (A2DP spec 12.6.4): 8 subband samples -> 8 PCM samples. */
    private fun synthesize(s: DoubleArray, out: java.io.ByteArrayOutputStream) {
        System.arraycopy(v, 0, v, 16, 144)
        for (k in 0 until 16) {
            var sum = 0.0
            for (i in 0 until SUBBANDS) sum += N[k][i] * s[i]
            v[k] = sum
        }
        for (j in 0 until SUBBANDS) {
            var x = 0.0
            for (i in 0 until 5) {
                x += v[i * 32 + j] * D[i * 16 + j]
                x += v[i * 32 + 24 + j] * D[i * 16 + 8 + j]
            }
            val pcm = Math.round(x * GAIN).toInt().coerceIn(-32768, 32767)
            out.write(pcm and 255)
            out.write(pcm shr 8 and 255)
        }
    }

    private class BitReader(private val b: ByteArray, private var byte: Int) {
        private var bit = 0
        fun read(n: Int): Int {
            var r = 0
            repeat(n) {
                r = (r shl 1) or ((b[byte].toInt() shr (7 - bit)) and 1)
                if (++bit == 8) { bit = 0; byte++ }
            }
            return r
        }
    }

    companion object {
        const val FRAME_LEN = 57
        const val PCM_BYTES_PER_FRAME = 240
        private const val SYNC = 0xAD.toByte()
        private const val SUBBANDS = 8
        private const val BLOCKS = 15
        private const val BITPOOL = 26
        private const val GAIN = -8.0
        private val OFFSET_16K = intArrayOf(-2, 0, 0, 0, 0, 0, 0, 1)
        private val N = Array(16) { k -> DoubleArray(SUBBANDS) { i -> cos((i + 0.5) * (k + 4) * PI / 8) } }

        /** Prototype filter, 8 subbands (A2DP spec table 12.23, Proto_8_80). */
        private val D = doubleArrayOf(
            0.00000000E+00, 1.56575398E-04, 3.43256425E-04, 5.54620202E-04,
            8.23919506E-04, 1.13992507E-03, 1.47640169E-03, 1.78371725E-03,
            2.01182542E-03, 2.10371989E-03, 1.99454554E-03, 1.61656283E-03,
            9.02154502E-04, -1.78805361E-04, -1.64973098E-03, -3.49717454E-03,
            5.65949473E-03, 8.02941163E-03, 1.04584443E-02, 1.27472335E-02,
            1.46525263E-02, 1.59045603E-02, 1.62208471E-02, 1.53184106E-02,
            1.29371806E-02, 8.85757540E-03, 2.92408442E-03, -4.91578024E-03,
            -1.46404076E-02, -2.61098752E-02, -3.90751381E-02, -5.31873032E-02,
            6.79989431E-02, 8.29847578E-02, 9.75753918E-02, 1.11196689E-01,
            1.23264548E-01, 1.33264415E-01, 1.40753505E-01, 1.45389847E-01,
            1.46955068E-01, 1.45389847E-01, 1.40753505E-01, 1.33264415E-01,
            1.23264548E-01, 1.11196689E-01, 9.75753918E-02, 8.29847578E-02,
            -6.79989431E-02, -5.31873032E-02, -3.90751381E-02, -2.61098752E-02,
            -1.46404076E-02, -4.91578024E-03, 2.92408442E-03, 8.85757540E-03,
            1.29371806E-02, 1.53184106E-02, 1.62208471E-02, 1.59045603E-02,
            1.46525263E-02, 1.27472335E-02, 1.04584443E-02, 8.02941163E-03,
            -5.65949473E-03, -3.49717454E-03, -1.64973098E-03, -1.78805361E-04,
            9.02154502E-04, 1.61656283E-03, 1.99454554E-03, 2.10371989E-03,
            2.01182542E-03, 1.78371725E-03, 1.47640169E-03, 1.13992507E-03,
            8.23919506E-04, 5.54620202E-04, 3.43256425E-04, 1.56575398E-04,
        )
    }
}
