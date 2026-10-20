package com.johnanderson.familyportal.tv.doorbell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Debug-only trigger for a test doorbell popup, so it can be tried over other apps via adb. */
class DebugDoorbellReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { TvDoorbellService.showTestPopup(context) }
            .onFailure { Log.e("FamilyPortalTvDoorbell", "Unable to start the test popup", it) }
    }
}
