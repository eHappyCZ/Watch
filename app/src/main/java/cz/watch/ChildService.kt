package cz.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject

class ChildService : Service() {
    private var link: Link? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wantAll = false
    private var wantApps: List<String> = emptyList()
    private var msgId = 100

    private val tick = object : Runnable {
        override fun run() {
            pushVpn()
            sendStatus(null)
            handler.postDelayed(this, 300_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        val code = Prefs.code(this)
        if (code == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        link?.stop()
        val l = Link(this, code, "child", ::onMessage, onOpen = { onOpen() })
        link = l
        ChildLink.current = l
        ChildLink.onVpnChanged = { sendStatus(null) }
        l.start()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 300_000L)
        return START_STICKY
    }

    private fun startFg() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (Locator.granted(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(1, n, type)
        } else {
            startForeground(1, n)
        }
    }

    private fun onOpen() {
        ChildLink.flushPending(this)
        val secret = Prefs.childSecret(this)
        link?.send(
            JSONObject().put("t", "apps").put("listEnc", Crypto.encrypt(secret, Device.appsJson(this).toString()))
        )
        sendStatus(null)
    }

    private fun onMessage(o: JSONObject) {
        when (o.optString("t")) {
            "ack" -> if (o.optString("k") == "adminOff") Prefs.setPendingAdminOff(this, 0)
            "state" -> applyState(o)
            "getUsage" -> sendUsage()
            "getLocation" -> Locator.fetch(this) { sendStatus(it) }
            "find" -> if (o.optBoolean("on")) AlarmPlayer.start(this) else AlarmPlayer.stop()
            "volume" -> applyVolume(o)
            "msg" -> {
                val text = Crypto.decrypt(Prefs.childSecret(this), o.optString("textEnc"))
                notifyChild("Zpráva od rodiče", text ?: "(zprávu se nepodařilo dešifrovat)")
            }
            "granted" -> notifyChild("Rodič povolil internet", "Na ${o.optInt("min")} min")
        }
    }

    private fun applyState(o: JSONObject) {
        wantAll = o.optBoolean("netBlocked")
        val arr = o.optJSONArray("appsBlocked")
        wantApps = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
        ChildLink.lastBlocked = wantAll || wantApps.isNotEmpty()
        ChildLink.lastAt = System.currentTimeMillis()
        pushVpn()
        if (o.optBoolean("active") != BlockVpnService.up) {
            link?.send(JSONObject().put("t", "active").put("v", BlockVpnService.up))
        }

        val lockAt = o.optLong("lockAt")
        if (lockAt > Prefs.lastLock(this)) {
            Prefs.setLastLock(this, lockAt)
            if (o.optLong("now") - lockAt < 120_000) {
                val dpm = getSystemService(DevicePolicyManager::class.java)
                if (dpm.isAdminActive(ComponentName(this, WatchAdmin::class.java))) dpm.lockNow()
            }
        }
    }

    private fun applyVolume(o: JSONObject) {
        val mute = o.optBoolean("mute")
        val level = o.optInt("level", 0).coerceIn(0, 100)
        val am = getSystemService(AudioManager::class.java) ?: return
        val streams = intArrayOf(
            AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM, AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION
        )
        for (s in streams) {
            try {
                val max = am.getStreamMaxVolume(s)
                val v = if (mute || level == 0) 0 else (max * level / 100).coerceIn(0, max)
                am.setStreamVolume(s, v, 0)
            } catch (_: SecurityException) {
            }
        }
        sendStatus(null)
    }

    private fun pushVpn() {
        if (wantAll || wantApps.isNotEmpty()) BlockVpnService.apply(this, wantAll, wantApps)
        else if (BlockVpnService.up) BlockVpnService.stop(this)
    }

    private fun sendStatus(loc: Location?) {
        val (pct, charging) = Device.battery(this)
        val am = getSystemService(AudioManager::class.java)
        val volMax = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        val vol = if (volMax > 0) am!!.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / volMax else 0
        val o = JSONObject()
            .put("t", "status")
            .put("bat", pct)
            .put("chg", charging)
            .put("vol", vol)
            .put("vpnErr", ChildLink.vpnErr)
            .put("locOk", Locator.granted(this))
        if (loc != null) {
            val locJson = JSONObject()
                .put("lat", loc.latitude).put("lon", loc.longitude).put("acc", loc.accuracy.toDouble())
            o.put("locEnc", Crypto.encrypt(Prefs.childSecret(this), locJson.toString()))
        }
        link?.send(o)
    }

    private fun sendUsage() {
        val o = JSONObject().put("t", "usage")
        if (Usage.hasAccess(this)) {
            o.put("listEnc", Crypto.encrypt(Prefs.childSecret(this), Usage.today(this).toString()))
        } else o.put("denied", true)
        link?.send(o)
    }

    private fun notifyChild(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("watch_msg", "Zprávy od rodiče", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            msgId++,
            Notification.Builder(this, "watch_msg")
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_stat_watch)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("watch", "Watch", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "watch")
            .setContentTitle("Watch")
            .setContentText("Rodičovská kontrola je aktivní")
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        link?.stop()
        if (ChildLink.current === link) ChildLink.current = null
        ChildLink.onVpnChanged = null
        link = null
        AlarmPlayer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
