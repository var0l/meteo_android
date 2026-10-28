package org.kawk.meteopolska

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-ensure the periodic capture is scheduled after a device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Scheduler.ensurePeriodic(context)
        }
    }
}
