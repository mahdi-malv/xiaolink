package wtf.mahdi.glassesconnect.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID

/**
 * Classic Bluetooth RFCOMM link to the glasses.
 * APK: com.xiaomi.wearable.spp.SystemClient uses createRfcommSocketToServiceRecord(SPP UUID) after createBond().
 */
@SuppressLint("MissingPermission")
class SppLink(
    private val device: BluetoothDevice,
    private val onBytes: (ByteArray) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
) {
    private var socket: BluetoothSocket? = null
    @Volatile private var closed = false

    /** Blocking. Call from a background thread. */
    fun open() {
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        socket = s
        s.connect()
        Thread({ readLoop(s) }, "spp-read").start()
    }

    private fun readLoop(s: BluetoothSocket) {
        val buffer = ByteArray(16394)
        var error: Throwable? = null
        try {
            val input = s.inputStream
            while (!closed) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) onBytes(buffer.copyOf(n))
            }
        } catch (e: IOException) {
            if (!closed) error = e
        }
        close()
        onClosed(error)
    }

    @Synchronized
    fun write(bytes: ByteArray) {
        val out = socket?.outputStream ?: throw IOException("Socket closed")
        out.write(bytes); out.flush()
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
