package cz.watch

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object Device {
    private fun launchableApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    fun appsJson(ctx: Context): JSONArray {
        val arr = JSONArray()
        launchableApps(ctx).take(400).forEach {
            arr.put(JSONObject().put("p", it.first).put("n", it.second.take(60)))
        }
        return arr
    }

    fun battery(ctx: Context): Pair<Int, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1 to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return (if (level < 0) -1 else level * 100 / scale) to charging
    }
}

object Usage {
    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun today(ctx: Context): JSONArray {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val pm = ctx.packageManager
        val arr = JSONArray()
        usm.queryAndAggregateUsageStats(start, System.currentTimeMillis()).values
            .filter {
                it.totalTimeInForeground > 60_000 && it.packageName != ctx.packageName &&
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .sortedByDescending { it.totalTimeInForeground }
            .take(25)
            .forEach { u ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(u.packageName, 0)).toString()
                } catch (_: Exception) { u.packageName }
                arr.put(
                    JSONObject().put("p", u.packageName).put("n", label.take(60))
                        .put("ms", u.totalTimeInForeground)
                )
            }
        return arr
    }
}

object Locator {
    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun always(ctx: Context) =
        Build.VERSION.SDK_INT < 29 ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    fun fetch(ctx: Context, cb: (Location?) -> Unit) {
        if (!granted(ctx)) { cb(null); return }
        val lm = ctx.getSystemService(LocationManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        val listeners = mutableListOf<LocationListener>()
        var done = false
        fun finish(loc: Location?) {
            if (done) return
            done = true
            listeners.forEach { try { lm.removeUpdates(it) } catch (_: Exception) {} }
            cb(loc ?: lastKnown(lm))
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
        if (providers.isEmpty()) { finish(null); return }
        for (prov in providers) {
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) = finish(location)
            }
            listeners.add(l)
            try {
                lm.requestLocationUpdates(prov, 0L, 0f, l, Looper.getMainLooper())
            } catch (_: Exception) { /* poskytovatel nedostupný */ }
        }
        handler.postDelayed({ finish(null) }, 20_000)
    }
}

object AlarmPlayer {
    private var ringtone: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())

    val isPlaying: Boolean get() = ringtone?.isPlaying == true

    fun start(ctx: Context) {
        stop()
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) { /* režim Nerušit */ }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(ctx.applicationContext, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
        handler.postDelayed({ stop() }, 60_000)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
    }
}
