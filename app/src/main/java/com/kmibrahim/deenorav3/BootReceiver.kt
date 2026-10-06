package com.kmibrahim.deenorav3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Phone restart hole KeepAliveService abar chalu korbe,
 * jate reboot er poro incoming call kaj kore.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, starting KeepAliveService")
            KeepAliveService.start(context)
        }
    }
}
