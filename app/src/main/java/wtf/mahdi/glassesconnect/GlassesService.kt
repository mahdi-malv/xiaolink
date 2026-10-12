package wtf.mahdi.glassesconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that keeps the process, and so the [GlassesController]
 * session, alive in the background. Runs while [GlassesController.keepConnected] is set.
 * Also listens for the glasses' ACL connect and Bluetooth on to reconnect at once.
 * Official app: AiSpeechService / AiForegroundService (foreground) + ConnectivityCompanionDeviceService (CDM).
 */
class GlassesService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controller by lazy { GlassesController.get(this) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device?.address.equals(controller.ui.value.address, ignoreCase = true)) controller.reconnectNow("glasses ACL connected")
                }
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) controller.reconnectNow("Bluetooth on")
            }
        }
    }

    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Glasses connection", NotificationManager.IMPORTANCE_LOW))
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED) else registerReceiver(receiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) { controller.disconnect(); return START_NOT_STICKY }
        if (!controller.keepConnected) { stopSelf(); return START_NOT_STICKY }
        val started = runCatching {
            ServiceCompat.startForeground(this, ID, notification(controller.ui.value.state),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        }
        if (started.isFailure) {
            // Background start not allowed (no CDM association / battery exemption). Stay off; next app open restarts it.
            controller.log("Foreground service not started: ${started.exceptionOrNull()?.message}")
            stopSelf(); return START_NOT_STICKY
        }
        if (!collecting) {
            collecting = true
            scope.launch {
                controller.ui.map { it.state + (it.battery?.let { b -> " · $b%" } ?: "") + if (it.aiState != "Idle") " · AI ${it.aiState}" else "" }
                    .distinctUntilChanged()
                    .collect { getSystemService(NotificationManager::class.java).notify(ID, notification(it)) }
            }
        }
        // Process restart (START_STICKY) or boot/CDM start: open the session if it is not up.
        controller.reconnectNow(intent?.getStringExtra(EXTRA_REASON) ?: "service restarted")
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GlassesService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Glasses")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Disconnect", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val ID = 1
        private const val ACTION_DISCONNECT = "wtf.mahdi.glassesconnect.DISCONNECT"
        private const val EXTRA_REASON = "reason"

        fun start(context: Context, reason: String = "app") {
            val intent = Intent(context, GlassesService::class.java).putExtra(EXTRA_REASON, reason)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { GlassesController.get(context).log("Cannot start service ($reason): ${it.message}") }
        }

        fun stop(context: Context) = context.stopService(Intent(context, GlassesService::class.java))
    }
}

/** Starts the service after boot or app update when the user left the glasses connected. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (GlassesController.get(context).keepConnected) GlassesService.start(context, intent.action ?: "boot")
    }
}
