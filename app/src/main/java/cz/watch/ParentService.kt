package cz.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import org.json.JSONObject

class ParentService : Service() {
    private val links = HashMap<String, Link>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("watch_parent", "Watch", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel("watch_alert", getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
        startForeground(
            3,
            Notification.Builder(this, "watch_parent")
                .setContentTitle("Watch")
                .setContentText(getString(R.string.watching_kids))
                .setSmallIcon(R.drawable.ic_stat_watch)
                .build()
        )

        links.values.forEach { it.stop() }
        links.clear()
        ParentLink.links.clear()

        val devs = Prefs.devices(this)
        if (devs.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        for (d in devs) {
            val l = Link(this, d.code, "parent", { o -> onMessage(d, o) })
            links[d.code] = l
            ParentLink.links[d.code] = l
            l.start()
        }
        return START_STICKY
    }

    private fun onMessage(d: Prefs.Dev, o: JSONObject) {
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        when (o.optString("t")) {
            "state" -> {
                val at = o.optLong("adminDisabledAt")
                if (at > Prefs.lastAlert(this, d.code)) {
                    Prefs.setLastAlert(this, d.code, at)
                    nm.notify(
                        d.code, 4,
                        Notification.Builder(this, "watch_alert")
                            .setContentTitle("⚠️ " + getString(R.string.protection_off, d.name))
                            .setContentText(getString(R.string.child_disabled_admin))
                            .setSmallIcon(R.drawable.ic_stat_watch)
                            .setContentIntent(open)
                            .setAutoCancel(true)
                            .build()
                    )
                }
            }
            "request" -> {
                val at = o.optLong("at")
                if (at > Prefs.long(this, "lastReq_${d.code}")) {
                    Prefs.setLong(this, "lastReq_${d.code}", at)
                    val text = Crypto.decrypt(d.secret, o.optString("textEnc")) ?: getString(R.string.decrypt_failed)
                    val b = Notification.Builder(this, "watch_alert")
                        .setContentTitle("🕐 " + getString(R.string.name_requests, d.name))
                        .setContentText(text)
                        .setSmallIcon(R.drawable.ic_stat_watch)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                    for ((i, min) in listOf(10, 30).withIndex()) b.addAction(action(d.code, min, i, getString(R.string.allow_min, min)))
                    b.addAction(action(d.code, 0, 2, getString(R.string.deny)))
                    nm.notify(d.code, 5, b.build())
                }
            }
        }
    }

    private fun action(code: String, min: Int, k: Int, title: String): Notification.Action {
        val intent = Intent(this, ParentActionReceiver::class.java)
            .setAction(if (min > 0) ParentActionReceiver.GRANT else ParentActionReceiver.DENY)
            .putExtra("code", code)
            .putExtra("min", min)
        val pi = PendingIntent.getBroadcast(
            this, code.hashCode() * 3 + k, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_watch), title, pi
        ).build()
    }

    override fun onDestroy() {
        links.values.forEach { it.stop() }
        links.clear()
        ParentLink.links.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
