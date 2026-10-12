package wtf.mahdi.glassesconnect.protocol

/** Shipped APK: SppVersionReader, SppPacket, TransportL1, CMDPacket, L2Packet.
 * Calls must be serialized by the socket owner. Payload encryption is the caller's job.
 * Bluetooth read boundaries are deliberately independent of protocol frame boundaries.
 */
class WearTransport(
    private val send: (ByteArray) -> Unit,
    private val onMessage: (channel: Int, encrypted: Boolean, payload: ByteArray) -> Unit,
    private val onReady: (version: String) -> Unit,
) {
    var version: String = ""
        private set
    var ready = false
        private set
    val isV2 get() = version.substringBefore('.').toIntOrNull() in 2..3
    private var buffer = byteArrayOf()
    private var serial = 0
    private var txSequence = 0
    private var rxSequence = 0
    private var remoteMps = 64512
    private val pending = linkedMapOf<Int, ByteArray>()
    private var startFrame: ByteArray? = null

    fun start() {
        check(version.isEmpty()) { "Create a new transport for each connection" }
        send(v1Frame(0, 0, serial++, 0, byteArrayOf(), true))
    }

    fun sendMessage(payload: ByteArray, encrypted: Boolean) {
        check(ready) { "Protocol negotiation is not complete" }
        if (isV2) {
            require(payload.size + 2 <= remoteMps) { "Message exceeds negotiated MPS" }
            check(pending.size < 32) { "Transport send window full" }
            val seq = txSequence
            txSequence = (txSequence + 1) and 255
            val frame = l1Frame(3, seq, byteArrayOf(1, if (encrypted) 2 else 1) + payload)
            pending[seq] = frame
            emit(frame)
        } else {
            // SppUtil maps Wear API auth/control (100/101) to SPP channel 2.
            send(v1Frame(2, 2, serial++ and 255, if (encrypted) 1 else 2, payload))
        }
    }

    /** Socket owner controls retry timing and a finite retry budget (SDK default: 10s). */
    fun retryPending() {
        if (!ready) startFrame?.let(::emit)
        else pending.values.toList().forEach(::emit)
    }

    fun receive(bytes: ByteArray) {
        require(buffer.size + bytes.size <= 262144) { "Receive buffer limit exceeded" }
        buffer += bytes
        while (true) {
            if (version.isEmpty() || !isV2) {
                if (!readV1()) return
            } else if (!readV2()) return
        }
    }

    private fun readV1(): Boolean {
        if (buffer.size < 7) return false
        if (u8(buffer[0]) != 0xba || u8(buffer[1]) != 0xdc || u8(buffer[2]) != 0xfe) {
            buffer = buffer.drop(1).toByteArray(); return true
        }
        val length = u16(buffer, 5)
        require(length >= 3) { "Invalid SPP frame length" }
        if (buffer.size < length + 8) return false
        val frame = buffer.copyOfRange(0, length + 8)
        buffer = buffer.copyOfRange(length + 8, buffer.size)
        require(u8(frame.last()) == 0xef) { "Invalid SPP frame terminator" }
        val header = u16(frame, 3)
        require(header and 0x3ff0 == 0) { "Invalid SPP header" }
        val channel = header and 15
        val payload = frame.copyOfRange(10, frame.lastIndex)
        if (version.isEmpty()) {
            if (channel != 0 || payload.isEmpty()) return true
            version = payload.joinToString(".") { u8(it).toString() }
            if (isV2) {
                startFrame = l1Frame(2, 0, byteArrayOf(1) + startConfig())
                emit(startFrame!!)
            } else {
                ready = true
                onReady(version)
            }
        } else {
            if (header and 0x8000 != 0 && payload.isNotEmpty()) {
                onMessage(if (channel == 1 || channel == 2) 1 else channel, frame[9].toInt() == 1, payload)
            }
        }
        return true
    }

    private fun readV2(): Boolean {
        if (buffer.size < 8) return false
        if (u16(buffer, 0) != 0xa5a5) {
            buffer = buffer.drop(1).toByteArray(); return true
        }
        val length = u16(buffer, 4)
        if (buffer.size < 8 + length) return false
        val frame = buffer.copyOfRange(0, 8 + length)
        buffer = buffer.copyOfRange(8 + length, buffer.size)
        val payload = frame.copyOfRange(8, frame.size)
        require(crc16(payload) == u16(frame, 6)) { "L1 CRC mismatch" }
        val type = u8(frame[2]) and 15
        val force = u8(frame[2]) and 16 != 0
        val seq = u8(frame[3])
        when (type) {
            0 -> { // NAK acknowledges all earlier frames and asks for this sequence onward.
                acknowledge((seq - 1) and 255)
                pending.values.toList().forEach(::emit)
            }
            1 -> acknowledge(seq)
            2 -> {
                require(payload.isNotEmpty()) { "Empty L1 command" }
                when (u8(payload[0])) {
                    1 -> { readConfig(payload); emit(l1Frame(2, 0, byteArrayOf(2) + startConfig())) }
                    2 -> {
                        readConfig(payload)
                        if (!ready) { ready = true; startFrame = null; onReady(version) }
                    }
                }
            }
            3 -> {
                require(payload.size >= 2) { "Missing L2 header" }
                check(ready) { "Data before L1 negotiation" }
                val channel = u8(payload[0])
                if (force) {
                    rxSequence = (seq + 1) and 255
                } else if (channel != 7) {
                    if (seq != rxSequence) {
                        if ((seq - rxSequence) and 255 < 128) emit(l1Frame(0, rxSequence, byteArrayOf()))
                        else emit(l1Frame(1, (rxSequence - 1) and 255, byteArrayOf()))
                        return true
                    }
                    emit(l1Frame(1, seq, byteArrayOf()))
                    rxSequence = (rxSequence + 1) and 255
                }
                onMessage(channel, payload[1].toInt() == 2, payload.copyOfRange(2, payload.size))
            }
            else -> error("Unknown L1 packet type $type")
        }
        return true
    }

    private fun acknowledge(seq: Int) {
        val keys = pending.keys.toList()
        if (seq !in keys) return
        for (key in keys) { pending.remove(key); if (key == seq) break }
    }

    private fun readConfig(payload: ByteArray) {
        var offset = 1
        while (offset < payload.size) {
            require(offset + 3 <= payload.size) { "Truncated L1 configuration" }
            val type = u8(payload[offset])
            val length = u16(payload, offset + 1)
            offset += 3
            require(offset + length <= payload.size) { "Truncated L1 configuration value" }
            if (type == 2) {
                require(length == 2) { "Invalid MPS" }
                remoteMps = u16(payload, offset)
                require(remoteMps in 2..64512) { "Unsupported MPS" }
            }
            offset += length
        }
    }

    private fun emit(frame: ByteArray) {
        // SppChannel MTU=16394. L0 fragmentation adds no headers.
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(frame.size, offset + 16394)
            send(frame.copyOfRange(offset, end)); offset = end
        }
    }

    companion object {
        fun crc16(bytes: ByteArray): Int {
            var crc = 0
            for (byte in bytes) {
                crc = crc xor u8(byte)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xa001 else crc ushr 1 }
            }
            return crc and 65535
        }

        fun l1Frame(type: Int, sequence: Int, payload: ByteArray): ByteArray {
            require(payload.size <= 65535)
            val crc = crc16(payload)
            return byteArrayOf(0xa5.toByte(), 0xa5.toByte(), type.toByte(), sequence.toByte(),
                payload.size.toByte(), (payload.size ushr 8).toByte(), crc.toByte(), (crc ushr 8).toByte()) + payload
        }

        fun v1Frame(channel: Int, opcode: Int, sequence: Int, dataType: Int, payload: ByteArray, needResponse: Boolean = false): ByteArray {
            require(payload.size <= 65532)
            val length = payload.size + 3
            return byteArrayOf(0xba.toByte(), 0xdc.toByte(), 0xfe.toByte(), channel.toByte(),
                (if (needResponse) 0xc0 else 0x80).toByte(), length.toByte(), (length ushr 8).toByte(),
                opcode.toByte(), sequence.toByte(), dataType.toByte()) + payload + 0xef.toByte()
        }

        private fun startConfig() = byteArrayOf(1, 3, 0, 1, 0, 0, 2, 2, 0, 0, 0xfc.toByte(), 3, 2, 0, 32, 0, 4, 2, 0, 0x10, 0x27)
        private fun u8(b: Byte) = b.toInt() and 255
        private fun u16(bytes: ByteArray, offset: Int) = u8(bytes[offset]) or (u8(bytes[offset + 1]) shl 8)
    }
}
