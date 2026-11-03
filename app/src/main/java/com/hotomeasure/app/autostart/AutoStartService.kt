package com.hotomeasure.app.autostart

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Intent
import android.os.Build
import android.util.Log
import com.hotomeasure.app.MainActivity

/** Bound by Android when the linked measure starts advertising, i.e. when it's switched on. */
class AutoStartService : CompanionDeviceService() {

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) appeared()
    }

    @Deprecated("Used below API 36")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT < 36) appeared()
    }

    @Deprecated("Used below API 33")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < 33) appeared()
    }

    private fun appeared() {
        if (AutoStart.inQuietPeriod(this)) {
            Log.i("HotoMeasure", "Measure appeared, but the app only just let go of it; not reopening")
            return
        }
        Log.i("HotoMeasure", "Measure switched on; opening the app")
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (e: Exception) {
            Log.w("HotoMeasure", "Could not open the app", e)
        }
    }
}
