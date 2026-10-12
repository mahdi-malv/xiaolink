package wtf.mahdi.glassesconnect

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi

/**
 * CompanionDeviceManager association with the glasses (Android 13+). While associated the app may start its
 * foreground service from the background and is exempt from Doze; the system binds [GlassesCompanionService]
 * when the glasses appear (Bluetooth connect) even if the process was killed.
 * APK: feature/channel/presentation/newversion/companion/DeviceCompanionManager.java:312 (startObservingDevicePresence),
 * :328 (BluetoothDeviceFilter by MAC), service/ConnectivityCompanionDeviceService.java (onDeviceAppeared).
 */
object CompanionLink {
    val supported get() = Build.VERSION.SDK_INT >= 33

    @RequiresApi(33)
    private fun cdm(context: Context) = context.getSystemService(CompanionDeviceManager::class.java)

    fun isAssociated(context: Context, mac: String): Boolean =
        Build.VERSION.SDK_INT >= 33 && runCatching { cdm(context).myAssociations.any { it.deviceMacAddress?.toString().equals(mac, true) } }.getOrDefault(false)

    /** Starts the association; [onPending] gets the system confirmation dialog to launch from the Activity. */
    @RequiresApi(33)
    fun associate(context: Context, mac: String, onPending: (IntentSender) -> Unit, log: (String) -> Unit) {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(mac).build())
            .setSingleDevice(true)
            .build()
        cdm(context).associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                log("Companion: associated ${associationInfo.deviceMacAddress}")
                observe(context, mac, log)
            }
            override fun onFailure(error: CharSequence?) = log("Companion: association failed: $error")
        })
    }

    /** Asks the system to bind [GlassesCompanionService] when the glasses connect or disconnect. */
    fun observe(context: Context, mac: String, log: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || !isAssociated(context, mac)) return
        @Suppress("DEPRECATION")
        runCatching { cdm(context).startObservingDevicePresence(mac) }
            .onSuccess { log("Companion: observing presence of $mac") }
            .onFailure { log("Companion: observe failed: ${it.message}") }
    }

    fun ignoresBatteryOptimizations(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}

/** Bound by the system on glasses presence changes (CDM). Starts the foreground service to reconnect. */
@RequiresApi(33)
class GlassesCompanionService : CompanionDeviceService() {
    @Deprecated("Deprecated in API 36")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val controller = GlassesController.get(this)
        controller.log("Companion: glasses appeared ${associationInfo.deviceMacAddress}")
        if (controller.keepConnected) GlassesService.start(this, "companion device appeared")
    }

    @Deprecated("Deprecated in API 36")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        GlassesController.get(this).log("Companion: glasses disappeared ${associationInfo.deviceMacAddress}")
    }
}
