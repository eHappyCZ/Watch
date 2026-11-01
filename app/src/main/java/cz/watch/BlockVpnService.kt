package cz.watch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import org.json.JSONObject

class BlockVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var curAll: Boolean? = null
    private var curApps: List<String> = emptyList()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_APPLY -> apply(
                intent.getBooleanExtra("all", false),
                intent.getStringArrayExtra("apps")?.toList() ?: emptyList()
            )
            else -> { stopVpn(false); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun apply(all: Boolean, apps: List<String>) {
        if (tun != null && curAll == all && curApps == apps) return
        val b = Builder()
            .setSession("Watch")
            .addAddress("10.255.0.2", 32)
            .addAddress("fd00::2", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        if (all) {
            b.addDisallowedApplication(packageName)
        } else {
            var n = 0
            for (p in apps) {
                try { b.addAllowedApplication(p); n++ } catch (_: PackageManager.NameNotFoundException) {}
            }
            if (n == 0) { stopVpn(false); stopSelf(); return }
        }
        val fresh = try { b.establish() } catch (_: Exception) { null }
        val old = tun
        tun = fresh
        try { old?.close() } catch (_: Exception) {}
        if (fresh == null) {
            curAll = null
            curApps = emptyList()
            up = false
            ChildLink.vpnErr = true
        } else {
            curAll = all
            curApps = apps
            up = true
            ChildLink.vpnErr = false
        }
        report()
    }

    private fun stopVpn(err: Boolean) {
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        curAll = null
        curApps = emptyList()
        val changed = up || err != ChildLink.vpnErr
        up = false
        ChildLink.vpnErr = err
        if (changed) report()
    }

    private fun report() {
        ChildLink.current?.send(JSONObject().put("t", "active").put("v", up))
        ChildLink.onVpnChanged?.invoke()
    }

    override fun onRevoke() {
        stopVpn(true)
        super.onRevoke()
    }

    override fun onDestroy() {
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        up = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_APPLY = "cz.watch.APPLY_VPN"
        private const val ACTION_STOP = "cz.watch.STOP_VPN"

        @Volatile var up = false

        fun apply(context: Context, all: Boolean, apps: List<String>) {
            context.startService(
                Intent(context, BlockVpnService::class.java)
                    .setAction(ACTION_APPLY)
                    .putExtra("all", all)
                    .putExtra("apps", apps.toTypedArray())
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BlockVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
