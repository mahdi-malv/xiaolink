package wtf.mahdi.glassesconnect.stream

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * AMF0 subset for RTMP commands and metadata. Decoded types: Double, Boolean, String, null,
 * Map<String, Any?> (object / ECMA array), List<Any?> (strict array). Unknown markers stop decoding.
 */
object Amf0 {
    fun decode(data: ByteArray, offset: Int = 0): List<Any?> {
        val buf = ByteBuffer.wrap(data, offset, data.size - offset)
        val out = mutableListOf<Any?>()
        while (buf.hasRemaining()) {
            val v = runCatching { value(buf) }.getOrElse { break }
            if (v === Stop || v === End) break
            out += v
        }
        return out
    }

    private object Stop
    private object End

    private fun string(buf: ByteBuffer, length: Int): String {
        require(length in 0..buf.remaining())
        return ByteArray(length).also(buf::get).toString(Charsets.UTF_8)
    }

    private fun properties(buf: ByteBuffer): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (buf.hasRemaining()) {
            val key = string(buf, buf.short.toInt() and 0xffff)
            val v = value(buf)
            if (v === End || v === Stop) break
            map[key] = v
        }
        return map
    }

    private fun value(buf: ByteBuffer): Any? = when (buf.get().toInt()) {
        0 -> buf.double
        1 -> buf.get().toInt() != 0
        2 -> string(buf, buf.short.toInt() and 0xffff)
        3 -> properties(buf)
        5, 6 -> null
        8 -> { buf.int; properties(buf) }
        9 -> End
        10 -> List(buf.int.also { require(it in 0..65_536) }) { value(buf) }
        11 -> { buf.double; buf.short; null }
        12 -> string(buf, buf.int)
        else -> Stop
    }

    fun encode(vararg values: Any?): ByteArray = ByteArrayOutputStream().apply { values.forEach { write(this, it) } }.toByteArray()

    private fun write(out: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> out.write(5)
            is Number -> { out.write(0); out.write(ByteBuffer.allocate(8).putDouble(v.toDouble()).array()) }
            is Boolean -> { out.write(1); out.write(if (v) 1 else 0) }
            is String -> { out.write(2); key(out, v) }
            is Map<*, *> -> {
                out.write(3)
                v.forEach { (k, value) -> key(out, k.toString()); write(out, value) }
                out.write(0); out.write(0); out.write(9)
            }
            else -> error("AMF0: unsupported ${v.javaClass}")
        }
    }

    private fun key(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write(b.size ushr 8); out.write(b.size and 255); out.write(b)
    }
}
