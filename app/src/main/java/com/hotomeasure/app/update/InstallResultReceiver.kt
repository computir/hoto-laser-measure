package com.hotomeasure.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.hotomeasure.app.MainActivity

/** Receives the installer's progress for an update session (not exported; only our PendingIntent reaches it). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's own "Do you want to update this app?" screen.
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.set(UpdateState.Idle)
            else -> {
                clearRelaunch(context)
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("HotoMeasure", "Update not installed: status=$status $msg")
                Updater.set(
                    UpdateState.Failed(
                        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled" else "Update not installed${msg?.let { ": $it" } ?: ""}",
                    ),
                )
            }
        }
    }

    companion object {
        fun clearRelaunch(ctx: Context) =
            ctx.getSharedPreferences("update", Context.MODE_PRIVATE).edit().putBoolean("relaunch", false).apply()
    }
}

/** After an in-app update replaces the app, open it again so the person lands back where they were. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("relaunch", false)) return
        prefs.edit().putBoolean("relaunch", false).apply()
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.i("HotoMeasure", "Couldn't reopen after update: ${it.message}") }
    }
}
