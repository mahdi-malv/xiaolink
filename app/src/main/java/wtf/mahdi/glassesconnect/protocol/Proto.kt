package wtf.mahdi.glassesconnect.protocol

import java.io.ByteArrayOutputStream

/** Small protobuf wire codec; this is a payload codec, not Bluetooth framing. */
object Proto {
    data class Field(val number: Int, val wireType: Int, val value: Long = 0, val data: ByteArray = byteArrayOf())

    class Writer {
        private val output = ByteArrayOutputStream()
        private fun varint(value: Long) {
            var remaining = value
            while (remaining and -128L != 0L) {
                output.write(((remaining and 127) or 128).toInt())
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
        private fun tag(field: Int, wire: Int) {
            require(field in 1..0x1fffffff) { "Invalid protobuf field" }
            varint((field.toLong() shl 3) or wire.toLong())
        }
        fun uint(field: Int, value: Long) { require(value >= 0); tag(field, 0); varint(value) }
        fun uint(field: Int, value: Int) = uint(field, value.toLong())
        /** sint32 (ZigZag). */
        fun sint(field: Int, value: Int) { tag(field, 0); varint(((value shl 1) xor (value shr 31)).toLong() and 0xffffffffL) }
        fun bytes(field: Int, value: ByteArray) { tag(field, 2); varint(value.size.toLong()); output.write(value) }
        fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
        fun float(field: Int, value: Float) {
            tag(field, 5)
            val bits = value.toRawBits()
            repeat(4) { output.write(bits ushr (it * 8)) }
        }
        fun message(field: Int, block: Writer.() -> Unit) = bytes(field, encode(block))
        /** Re-emits a decoded field unchanged (varint written as its raw 64-bit pattern). */
        fun field(f: Field) {
            tag(f.number, f.wireType)
            when (f.wireType) {
                0 -> varint(f.value)
                2 -> { varint(f.data.size.toLong()); output.write(f.data) }
                else -> output.write(f.data)
            }
        }
        internal fun result(): ByteArray = output.toByteArray()
    }
    fun encode(block: Writer.() -> Unit): ByteArray = Writer().apply(block).result()

    /** Bounded parser. Malformed, truncated, group-wire and oversized input is rejected. */
    fun decode(input: ByteArray): List<Field> {
        require(input.size <= 1_048_576) { "Protobuf payload too large" }
        var offset = 0
        fun varint(): Long {
            var value = 0L
            for (index in 0..9) {
                require(offset < input.size) { "Truncated varint" }
                val next = input[offset++].toInt() and 255
                require(index < 9 || next <= 1) { "Varint overflow" }
                value = value or ((next and 127).toLong() shl (index * 7))
                if (next and 128 == 0) return value
            }
            error("Varint overflow")
        }
        fun take(count: Int): ByteArray {
            require(count >= 0 && count <= input.size - offset) { "Truncated field" }
            return input.copyOfRange(offset, offset + count).also { offset += count }
        }
        val result = mutableListOf<Field>()
        while (offset < input.size) {
            require(result.size < 65_536) { "Too many protobuf fields" }
            val tag = varint()
            val number = tag ushr 3
            require(number in 1..0x1fffffffL) { "Invalid protobuf field" }
            val wire = (tag and 7).toInt()
            result += when (wire) {
                0 -> Field(number.toInt(), wire, varint())
                1 -> Field(number.toInt(), wire, data = take(8))
                2 -> {
                    val length = varint()
                    require(length in 0..(input.size - offset).toLong()) { "Invalid field length" }
                    Field(number.toInt(), wire, data = take(length.toInt()))
                }
                5 -> Field(number.toInt(), wire, data = take(4))
                else -> throw IllegalArgumentException("Unsupported protobuf wire type $wire")
            }
        }
        return result
    }
    fun scalar(fields: List<Field>, number: Int): Long? = fields.lastOrNull { it.number == number && it.wireType == 0 }?.value
    fun bytes(fields: List<Field>, number: Int): ByteArray? = fields.lastOrNull { it.number == number && it.wireType == 2 }?.data
    fun hex(input: ByteArray): String = input.joinToString(" ") { "%02x".format(it.toInt() and 255) }
}
