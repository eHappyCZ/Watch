package cz.watch

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class WatchAdmin : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warn)

    override fun onDisabled(context: Context, intent: Intent) {
        if (Prefs.role(context) != "child") return
        Prefs.setPendingAdminOff(context, System.currentTimeMillis())
        ChildLink.flushPending(context)
    }
}
