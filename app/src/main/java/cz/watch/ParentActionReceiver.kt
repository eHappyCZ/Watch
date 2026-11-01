package cz.watch

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class ParentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra("code") ?: return
        context.getSystemService(NotificationManager::class.java).cancel(code, 5)
        if (intent.action == GRANT) {
            ParentLink.links[code]?.send(
                JSONObject().put("t", "grant").put("min", intent.getIntExtra("min", 10))
            )
        }
    }

    companion object {
        const val GRANT = "cz.watch.GRANT"
        const val DENY = "cz.watch.DENY"
    }
}
