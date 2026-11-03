package com.hotomeasure.app.autostart

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Opens the app when the measure is switched on, using Android's companion-device presence
 * detection: the system watches for the measure's Bluetooth advertising in the background
 * (low power, no app process needed) and wakes [AutoStartService].
 */
@SuppressLint("MissingPermission")
object AutoStart {
    private const val TAG = "HotoMeasure"
    private const val PREFS = "autostart"
    private const val KEY_RELEASED_AT = "released_at"

    /**
     * After the app lets go of the measure (closed, or the 1-minute grace ran out) the measure
     * starts advertising again and Android reports it "appeared" within ~10 s. Ignore that one so
     * the app doesn't reopen itself; a later appearance means the measure was switched on.
     */
    private const val QUIET_MS = 30_000L

    private fun cdm(ctx: Context) = ctx.getSystemService(CompanionDeviceManager::class.java)

    private fun association(ctx: Context, address: String): AssociationInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            cdm(ctx)?.myAssociations?.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
        } else null

    fun isEnabled(ctx: Context, address: String): Boolean =
        if (Build.VERSION.SDK_INT >= 33) association(ctx, address) != null
        else @Suppress("DEPRECATION") cdm(ctx)?.associations?.any { it.equals(address, ignoreCase = true) } == true

    /**
     * Asks Android to link the app to the measure. Android shows its own confirmation dialog,
     * delivered through [onPending]; the measure must be on and not connected so it's advertising.
     */
    fun request(ctx: Context, address: String, onPending: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val manager = cdm(ctx) ?: return onError("This phone doesn't support companion devices")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(address.uppercase()).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)

            @Deprecated("Replaced by onAssociationPending on API 33")
            override fun onDeviceFound(intentSender: IntentSender) = onPending(intentSender)

            override fun onAssociationCreated(info: AssociationInfo) = observe(ctx, address)

            override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "Measure not found")
        }
        if (Build.VERSION.SDK_INT >= 33) manager.associate(request, ctx.mainExecutor, callback)
        else manager.associate(request, callback, Handler(Looper.getMainLooper()))
    }

    /** Starts presence watching; safe to call repeatedly. */
    fun observe(ctx: Context, address: String) {
        val manager = cdm(ctx) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 36) {
                val id = association(ctx, address)?.id ?: return
                manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address.uppercase())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch for the measure", e)
        }
    }

    fun disable(ctx: Context, address: String) {
        val manager = cdm(ctx) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) association(ctx, address)?.let { manager.disassociate(it.id) }
            else @Suppress("DEPRECATION") manager.disassociate(address.uppercase())
        }.onFailure { Log.w(TAG, "Could not remove companion link", it) }
    }

    /** Called when the app itself drops the connection (not when the measure powers off). */
    fun markReleased(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_RELEASED_AT, System.currentTimeMillis()).apply()
    }

    fun inQuietPeriod(ctx: Context): Boolean {
        val at = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_RELEASED_AT, 0)
        return System.currentTimeMillis() - at in 0 until QUIET_MS
    }
}
