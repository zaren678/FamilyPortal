package com.johnanderson.familyportal.tv.doorbell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the doorbell listener after a reboot or update; it stops itself if not set up. */
class TvBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        TvDoorbellService.start(context)
    }
}
