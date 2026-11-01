package cz.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (Prefs.role(context)) {
            "child" -> context.startForegroundService(Intent(context, ChildService::class.java))
            "parent" -> if (Prefs.devices(context).isNotEmpty())
                context.startForegroundService(Intent(context, ParentService::class.java))
        }
    }
}
