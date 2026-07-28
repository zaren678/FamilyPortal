package com.johnanderson.familyportal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.johnanderson.familyportal.MainActivity

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }
}
