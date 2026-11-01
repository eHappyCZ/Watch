package cz.watch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun role(c: Context): String? = p(c).getString("role", null)

    fun code(c: Context): String? = p(c).getString("code", null)

    fun save(c: Context, role: String?, code: String?) =
        p(c).edit().putString("role", role).putString("code", code).apply()

    fun long(c: Context, key: String): Long = p(c).getLong(key, 0L)
    fun setLong(c: Context, key: String, v: Long) = p(c).edit().putLong(key, v).apply()

    fun childSecret(c: Context): String = p(c).getString("secret", "") ?: ""
    fun setChildSecret(c: Context, secret: String) = p(c).edit().putString("secret", secret).apply()

    class Dev(val code: String, val name: String, val secret: String = "")

    fun devices(c: Context): List<Dev> {
        val raw = p(c).getString("devices", null)
        if (raw == null) {
            val code = code(c)
            return if (role(c) == "parent" && code != null) listOf(Dev(code, c.getString(R.string.child_default))) else emptyList()
        }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Dev(o.getString("code"), o.optString("name", c.getString(R.string.child_default)), o.optString("secret", ""))
        }
    }

    fun secretFor(c: Context, code: String): String = devices(c).firstOrNull { it.code == code }?.secret ?: ""

    private fun putDevices(c: Context, list: List<Dev>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("code", it.code).put("name", it.name).put("secret", it.secret)) }
        p(c).edit().putString("devices", arr.toString()).apply()
    }

    fun addDevice(c: Context, code: String, secret: String, name: String) {
        putDevices(c, devices(c).filter { it.code != code } + Dev(code, name, secret))
        p(c).edit().putString("role", "parent").putString("code", code).apply()
    }

    fun renameDevice(c: Context, code: String, name: String) =
        putDevices(c, devices(c).map { if (it.code == code) Dev(code, name, it.secret) else it })

    fun select(c: Context, code: String) = p(c).edit().putString("code", code).apply()

    fun removeDevice(c: Context, code: String) {
        val rest = devices(c).filter { it.code != code }
        putDevices(c, rest)
        p(c).edit().putString("code", rest.firstOrNull()?.code).apply()
    }

    fun lastAlert(c: Context, code: String) = long(c, "lastAlert_$code")
    fun setLastAlert(c: Context, code: String, v: Long) = setLong(c, "lastAlert_$code", v)
    fun ack(c: Context, code: String) = long(c, "ack_$code")
    fun setAck(c: Context, code: String, v: Long) = setLong(c, "ack_$code", v)

    fun lastLock(c: Context): Long = long(c, "lastLock")
    fun setLastLock(c: Context, v: Long) = setLong(c, "lastLock", v)

    fun pendingAdminOff(c: Context): Long = long(c, "pendingOff")
    fun setPendingAdminOff(c: Context, v: Long) = setLong(c, "pendingOff", v)

    private fun hash(pin: String) =
        MessageDigest.getInstance("SHA-256").digest("watch:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun hasPin(c: Context) = p(c).getString("pin", null) != null
    fun setPin(c: Context, pin: String) = p(c).edit().putString("pin", hash(pin)).apply()
    fun checkPin(c: Context, pin: String) = p(c).getString("pin", null) == hash(pin)
    fun clearPin(c: Context) = p(c).edit().remove("pin").apply()

    fun iconHidden(c: Context) = p(c).getBoolean("iconHidden", false)
    fun setIconHidden(c: Context, v: Boolean) = p(c).edit().putBoolean("iconHidden", v).apply()
}
