package cz.watch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

private class Rule(var d: MutableSet<Int>, var s: String, var e: String, var lock: Boolean)

class MainActivity : Activity() {
    private var link: Link? = null
    private var linkCode: String? = null
    private var screen = "main"
    private var onData: ((JSONObject) -> Unit)? = null
    private var state: JSONObject? = null
    private var appsMsg: JSONObject? = null
    private var usageMsg: JSONObject? = null
    private var statusMsg: JSONObject? = null
    private var requestMsg: JSONObject? = null
    private var serverError: String? = null
    private var skew = 0L
    private var finding = false

    private companion object {
        val BG = Color.parseColor("#0A0F1E")
        val CARD = Color.parseColor("#141B2E")
        val CARD2 = Color.parseColor("#1B2437")
        val BORDER = Color.parseColor("#14FFFFFF")
        val TEXT = Color.parseColor("#F5F7FA")
        val MUTED = Color.parseColor("#8B96AA")
        val MUTED2 = Color.parseColor("#6B7A94")
        val LIGHT = Color.parseColor("#D7DEEB")
        val RED = Color.parseColor("#EF4444")
        val GREEN = Color.parseColor("#22C55E")
        val BLUE = Color.parseColor("#5B6CFF")
        val AMBER = Color.parseColor("#F59E0B")
        val MINT = Color.parseColor("#7CFFB2")
        val GOLD = Color.parseColor("#FFC24B")
        val PINK = Color.parseColor("#FF7A86")
        val ALERT_BG = Color.parseColor("#2B1420")
        val DAYS = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        if (Prefs.role(this) == "parent" && Prefs.devices(this).isNotEmpty()) {
            startForegroundService(Intent(this, ParentService::class.java))
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.role(this) == "child") render()
    }

    override fun onDestroy() {
        stopParentLink()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (Prefs.role(this) == "parent" && screen != "main") go("main") else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1) {
            if (resultCode == RESULT_OK) startChild() else render()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 || requestCode == 3) {
            if (Prefs.role(this) == "child") startForegroundService(Intent(this, ChildService::class.java))
            render()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun tint(color: Int, alphaPct: Int) =
        Color.argb(255 * alphaPct / 100, Color.red(color), Color.green(color), Color.blue(color))

    private fun root(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG); isFillViewport = true; addView(col)
        })
        return col
    }

    private fun lp(topDp: Int = 0, heightDp: Int = -2) =
        LinearLayout.LayoutParams(-1, if (heightDp > 0) dp(heightDp) else heightDp)
            .apply { topMargin = dp(topDp) }

    private fun label(
        l: LinearLayout, s: String, size: Float = 16f, color: Int = TEXT,
        bold: Boolean = false, topDp: Int = 0
    ) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }.also { l.addView(it, lp(topDp)) }

    private fun lineLabel(l: LinearLayout, s: String, size: Float = 16f, color: Int = TEXT, topDp: Int = 0) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color); gravity = Gravity.START
        }.also { l.addView(it, lp(topDp)) }

    private fun card(l: LinearLayout, topDp: Int = 16): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg(CARD, 22, BORDER)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }.also { l.addView(it, lp(topDp)) }

    private fun bigButton(
        l: LinearLayout, icon: String, s: String, color: Int, topDp: Int = 16, onClick: () -> Unit
    ) = Button(this).apply {
        text = "$icon  $s"; textSize = 19f; isAllCaps = false
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = bg(color, 24); stateListAnimator = null
        setOnClickListener { onClick() }
    }.also { l.addView(it, lp(topDp, 96)) }

    private fun textButton(l: LinearLayout, s: String, onClick: () -> Unit) =
        Button(this).apply {
            text = s; isAllCaps = false; setTextColor(MUTED); textSize = 14f
            setBackgroundColor(Color.TRANSPARENT); stateListAnimator = null
            setOnClickListener { onClick() }
        }.also { l.addView(it, lp(16)) }

    private fun row(l: LinearLayout, topDp: Int = 12): LinearLayout =
        LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            .also { l.addView(it, lp(topDp)) }

    private fun smallButton(r: LinearLayout, s: String, color: Int, onClick: () -> Unit) =
        Button(this).apply {
            text = s; textSize = 14f; isAllCaps = false
            setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            background = bg(color, 18); stateListAnimator = null
            setOnClickListener { onClick() }
        }.also {
            r.addView(it, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }

    private fun badge(sizeDp: Int, radiusDp: Int, emoji: String, tintColor: Int) = TextView(this).apply {
        text = emoji; textSize = if (sizeDp >= 48) 22f else 18f; gravity = Gravity.CENTER
        background = bg(tint(tintColor, 16), radiusDp)
    }

    private fun setBadge(b: TextView, emoji: String, tintColor: Int, radiusDp: Int = 16) {
        b.text = emoji
        b.background = bg(tint(tintColor, 16), radiusDp)
    }

    private fun dotRow(l: LinearLayout, ok: Boolean, text: String, topDp: Int = 10) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        View(this).apply { background = bg(if (ok) MINT else AMBER, 4) }
            .also { r.addView(it, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }) }
        TextView(this).apply { this.text = text; textSize = 14.5f; setTextColor(TEXT) }
            .also { r.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        l.addView(r, lp(topDp))
    }

    private fun tileRow(l: LinearLayout, topDp: Int = 10): LinearLayout =
        LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { l.addView(it, lp(topDp)) }

    private fun iconTile(
        row: LinearLayout, emoji: String, label: String, tintColor: Int, onClick: () -> Unit
    ): Triple<LinearLayout, TextView, TextView> {
        val b = badge(42, 12, emoji, tintColor)
        val t = TextView(this).apply {
            text = label; textSize = 11.5f; setTextColor(LIGHT)
            gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = bg(CARD, 18, BORDER)
            setPadding(dp(4), dp(16), dp(4), dp(16))
            setOnClickListener { onClick() }
            addView(b, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5); marginEnd = dp(5) })
        return Triple(tile, b, t)
    }

    private fun pillButton(
        row: LinearLayout, emoji: String, label: String, onClick: () -> Unit
    ): Triple<LinearLayout, TextView, TextView> {
        val icon = TextView(this).apply { text = emoji; textSize = 15f }
        val txt = TextView(this).apply {
            text = "  $label"; textSize = 13.5f; setTextColor(LIGHT); setTypeface(typeface, Typeface.BOLD)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = bg(CARD, 16, BORDER)
            setPadding(0, dp(15), 0, dp(15))
            setOnClickListener { onClick() }
            addView(icon); addView(txt)
        }
        row.addView(container, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5); marginEnd = dp(5) })
        return Triple(container, icon, txt)
    }

    private fun sectionLabel(l: LinearLayout, s: String, topDp: Int = 20) =
        TextView(this).apply {
            text = s.uppercase(); textSize = 12f; setTextColor(MUTED2); setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.05f
        }.also { l.addView(it, lp(topDp)) }

    private fun field(hint: String, size: Float = 20f) = EditText(this).apply {
        this.hint = hint; setHintTextColor(MUTED); setTextColor(TEXT)
        textSize = size; gravity = Gravity.CENTER
        background = bg(BG, 16); setPadding(dp(12), dp(14), dp(12), dp(14))
    }

    private fun pinField() = field("PIN", 24f).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun askPin(onOk: () -> Unit) {
        val input = pinField()
        AlertDialog.Builder(this)
            .setTitle("Zadej rodičovský PIN")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (Prefs.checkPin(this, input.text.toString())) onOk()
                else Toast.makeText(this, "Špatný PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    private fun header(l: LinearLayout, sub: String) {
        label(l, "Watch", 34f, TEXT, true)
        label(l, sub, 14f, MUTED, topDp = 4)
    }

    private fun subHeader(l: LinearLayout, title: String, sub: String = "") {
        textButton(l, "← Zpět") { go("main") }
        label(l, title, 26f, TEXT, true)
        if (sub.isNotEmpty()) label(l, sub, 13f, MUTED, topDp = 4)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun setLauncherIcon(visible: Boolean) {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, "cz.watch.Launcher"),
                if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    private fun clock(serverMs: Long): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
            .format(java.util.Date(serverMs - skew))

    private fun ago(serverMs: Long): String {
        val min = ((System.currentTimeMillis() + skew - serverMs) / 60000).coerceAtLeast(0)
        return when {
            min < 1 -> "právě teď"
            min < 60 -> "před $min min"
            else -> "před ${min / 60} h"
        }
    }

    private fun fmtMs(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms % 3_600_000 / 60_000
        return if (h > 0) "$h h $m min" else "$m min"
    }

    private fun go(s: String) { screen = s; render() }

    private fun render() {
        onData = null
        when (Prefs.role(this)) {
            "parent" -> parentRoute()
            "child" -> { stopParentLink(); childScreen() }
            else -> { stopParentLink(); chooseScreen() }
        }
    }

    private fun chooseScreen() {
        val l = root()
        header(l, "Kdo používá tento telefon?")
        l.addView(View(this), LinearLayout.LayoutParams(-1, dp(24)))
        bigButton(l, "👨‍👩‍👧", "Jsem rodič", BLUE) {
            Prefs.save(this, "parent", Prefs.code(this)); screen = "main"; render()
        }
        bigButton(l, "📱", "Telefon dítěte", AMBER) { pairChild() }
    }

    private fun pairChild() {
        if (Prefs.code(this)?.length != 8) {
            val full = newCode()
            Prefs.save(this, "child", full.take(8))
            Prefs.setChildSecret(this, full.drop(8))
        } else {
            Prefs.save(this, "child", Prefs.code(this))
        }
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, 1) else startChild()
    }

    private fun newCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..16).map { chars.random() }.joinToString("")
    }

    private fun startChild() {
        startForegroundService(Intent(this, ChildService::class.java))
        render()
    }

    private fun childScreen() {
        val l = root()
        header(l, "Telefon dítěte")

        val fullCode = (Prefs.code(this) ?: "") + Prefs.childSecret(this)
        val c = card(l, 28)
        label(c, "PÁROVACÍ KÓD PRO RODIČE", 12f, MUTED2, true).letterSpacing = 0.05f
        label(c, fullCode.chunked(4).joinToString(" "), 24f, TEXT, true, 10)
            .typeface = Typeface.MONOSPACE
        val copyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Watch kód", fullCode))
                toast("Kód zkopírován")
            }
        }
        copyRow.addView(TextView(this).apply { text = "📋"; textSize = 13f })
        copyRow.addView(TextView(this).apply {
            text = "  Kopírovat"; textSize = 12.5f; setTextColor(BLUE); setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(copyRow, lp(14))

        val admin = ComponentName(this, WatchAdmin::class.java)
        val adminOk = getSystemService(DevicePolicyManager::class.java).isAdminActive(admin)
        val needBattery = !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        val s = card(l)
        dotRow(s, true, "Ochrana běží", 0)
        val connected = ChildLink.current?.isOpen == true
        dotRow(s, connected, if (connected) "Spojení se serverem" else "Bez spojení se serverem")
        dotRow(s, adminOk, if (adminOk) "Zamykání povoleno" else "Zamykání není povoleno")
        dotRow(s, Prefs.hasPin(this), if (Prefs.hasPin(this)) "PIN nastaven" else "PIN není nastaven")
        dotRow(s, !needBattery, if (!needBattery) "Běh na pozadí povolen" else "Běh na pozadí není povolen")

        val lastCmd = when (ChildLink.lastBlocked) {
            null -> "žádný"
            true -> "VYPNOUT internet"
            false -> "ZAPNOUT internet"
        } + if (ChildLink.lastAt > 0) " (před ${(System.currentTimeMillis() - ChildLink.lastAt) / 1000} s)" else ""
        val lastRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = bg(tint(Color.WHITE, 3), 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }.also { l.addView(it, lp(14)) }
        lastRow.addView(
            TextView(this).apply { text = "🕐"; textSize = 13f },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        )
        lastRow.addView(TextView(this).apply {
            text = "Poslední příkaz: $lastCmd"; textSize = 12.5f; setTextColor(MUTED)
        })

        val needVpn = VpnService.prepare(this) != null
        val needUsage = !Usage.hasAccess(this)
        val needLoc = !Locator.granted(this)
        val needAlways = !needLoc && !Locator.always(this)

        val setupDone = adminOk && !needVpn && !needUsage && !needLoc && !needAlways && !needBattery &&
            Prefs.hasPin(this)
        if (setupDone && !Prefs.iconHidden(this)) {
            setLauncherIcon(false)
            Prefs.setIconHidden(this, true)
            toast("Ikona je skrytá. Appku najdeš přes oznámení „Watch“.")
        } else if (!setupDone && Prefs.iconHidden(this)) {
            setLauncherIcon(true)
            Prefs.setIconHidden(this, false)
        }

        if (!adminOk || needVpn || needUsage || needLoc || needAlways || needBattery) {
            val p = card(l)
            label(p, "Dokonči nastavení", 16f, TEXT, true)
            if (needVpn) bigButton(p, "🔑", "Obnovit oprávnění VPN", BLUE, 12) {
                VpnService.prepare(this)?.let { startActivityForResult(it, 1) }
            }
            if (!adminOk) bigButton(p, "🔒", "Povolit zamykání", BLUE, 12) {
                startActivity(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                )
            }
            if (needUsage) bigButton(p, "📊", "Povolit přehled používání", BLUE, 12) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            if (needLoc) bigButton(p, "📍", "Povolit polohu", BLUE, 12) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2
                )
            }
            if (needAlways) bigButton(p, "📍", "Povolit polohu „vždy“", BLUE, 12) {
                if (Build.VERSION.SDK_INT >= 30) {
                    toast("Oprávnění → Poloha → Vždy povolit")
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                } else {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 3)
                }
            }
            if (needBattery) bigButton(p, "🔋", "Povolit běh na pozadí", BLUE, 12) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        if (!Prefs.hasPin(this)) {
            val p = card(l)
            label(p, "Nastav rodičovský PIN", 16f, TEXT, true)
            label(p, "Bude potřeba ke zrušení párování (4–8 číslic)", 13f, MUTED, topDp = 4)
            val input = pinField()
            p.addView(input, lp(12))
            bigButton(p, "🔑", "Uložit PIN", BLUE, 12) {
                val v = input.text.toString()
                if (v.length in 4..8) { Prefs.setPin(this, v); render() }
                else toast("PIN má mít 4–8 číslic")
            }
        }

        bigButton(l, "🕐", "Požádat o čas", AMBER, 16) {
            val labels = arrayOf("10 minut", "30 minut", "1 hodinu")
            AlertDialog.Builder(this)
                .setTitle("Kolik času navíc?")
                .setItems(labels) { _, i ->
                    val textEnc = Crypto.encrypt(Prefs.childSecret(this), "Chci ještě ${labels[i]}")
                    val ok = ChildLink.current?.send(
                        JSONObject().put("t", "request").put("textEnc", textEnc)
                    ) == true
                    toast(if (ok) "Žádost odeslána rodiči" else "Není spojení se serverem")
                }
                .show()
        }
        if (AlarmPlayer.isPlaying) {
            bigButton(l, "🔕", "Ztlumit zvonění", RED, 12) { AlarmPlayer.stop(); render() }
        }

        label(l, "Tuto aplikaci nevypínej.", 13f, MUTED, topDp = 16)
        if (Prefs.hasPin(this)) {
            textButton(l, "Zrušit párování") {
                askPin {
                    stopService(Intent(this, ChildService::class.java))
                    BlockVpnService.stop(this)
                    setLauncherIcon(true)
                    Prefs.setIconHidden(this, false)
                    Prefs.clearPin(this)
                    Prefs.save(this, null, null); render()
                }
            }
        }
    }

    private fun parentRoute() {
        val code = Prefs.code(this)
        if (code == null || screen == "add") {
            stopParentLink()
            addChildScreen()
            return
        }
        ensureLink(code)
        when (screen) {
            "apps" -> appsScreen()
            "usage" -> usageScreen()
            "schedule" -> scheduleScreen()
            "location" -> locationScreen()
            else -> parentMain(code)
        }
    }

    private fun ensureLink(code: String) {
        if (link != null && linkCode == code) return
        stopParentLink()
        linkCode = code
        link = Link(this, code, "parent", ::onServer).also { it.start() }
    }

    private fun stopParentLink() {
        link?.stop()
        link = null
        linkCode = null
        state = null; appsMsg = null; usageMsg = null; statusMsg = null; requestMsg = null
        serverError = null
        finding = false
    }

    private fun onServer(o: JSONObject) {
        when (o.optString("t")) {
            "state" -> {
                state = o
                skew = o.optLong("now") - System.currentTimeMillis()
                serverError = null
            }
            "apps" -> appsMsg = o
            "usage" -> usageMsg = o
            "status" -> statusMsg = o
            "request" -> requestMsg = o
            "error" -> serverError = o.optString("m")
        }
        onData?.invoke(o)
    }

    private fun cmd(o: JSONObject): Boolean {
        val ok = link?.send(o) == true
        if (!ok) toast("Není spojení se serverem")
        return ok
    }

    private fun restartParentService() {
        if (Prefs.devices(this).isEmpty()) stopService(Intent(this, ParentService::class.java))
        else startForegroundService(Intent(this, ParentService::class.java))
    }

    private fun pickMinutes(title: String, options: List<Int>, onPick: (Int) -> Unit) {
        val labels = options.map { m ->
            when {
                m == 0 -> "Dokud nezapnu"
                m < 60 -> "$m min"
                m == 60 -> "1 hodinu"
                else -> "${m / 60} hodiny"
            }
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title)
            .setItems(labels) { _, i -> onPick(options[i]) }.show()
    }

    private fun pickVolume(onPick: (Int) -> Unit) {
        val levels = listOf(0, 25, 50, 75, 100)
        val labels = levels.map { if (it == 0) "Vypnuto" else "$it %" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Hlasitost telefonu")
            .setItems(labels) { _, i -> onPick(levels[i]) }.show()
    }

    private fun addChildScreen() {
        val l = root()
        header(l, "Přidat dítě")
        val c = card(l, 28)
        label(c, "Jméno dítěte", 15f, MUTED)
        val name = field("např. Honza", 20f)
        c.addView(name, lp(12))
        label(c, "Kód z dětského telefonu", 15f, MUTED, topDp = 20)
        val input = field("XXXX XXXX XXXX XXXX", 20f).apply {
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        c.addView(input, lp(12))
        bigButton(l, "🔗", "Spárovat", BLUE, 24) {
            val v = input.text.toString().filter { it.isLetterOrDigit() }.uppercase()
            if (v.length != 16) { toast("Kód má 16 znaků"); return@bigButton }
            val n = name.text.toString().trim().ifEmpty { "Dítě ${Prefs.devices(this).size + 1}" }
            Prefs.addDevice(this, v.take(8), v.drop(8), n)
            stopParentLink()
            screen = "main"
            restartParentService()
            render()
        }
        textButton(l, "Zpět") {
            if (Prefs.devices(this).isNotEmpty()) go("main")
            else { Prefs.save(this, null, null); screen = "main"; render() }
        }
    }

    private fun parentMain(code: String) {
        val devs = Prefs.devices(this)
        val name = devs.firstOrNull { it.code == code }?.name ?: "Dítě"
        val l = root()
        header(l, "Ovládání dětských telefonů")

        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(chips)
        l.addView(hs, lp(16))
        fun chip(text: String, selected: Boolean, onClick: () -> Unit) {
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = bg(if (selected) BLUE else CARD, 22, if (selected) null else BORDER)
                setPadding(dp(18), 0, dp(18), 0)
                isClickable = true
                setOnClickListener { onClick() }
            }
            if (selected) {
                View(this).apply { background = bg(MINT, 4) }
                    .also { chipRow.addView(it, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) }) }
            }
            TextView(this).apply {
                this.text = text; textSize = 14.5f
                setTextColor(if (selected) Color.WHITE else MUTED); setTypeface(typeface, Typeface.BOLD)
            }.also { chipRow.addView(it) }
            chips.addView(chipRow, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) })
        }
        devs.forEach { d ->
            chip(d.name, d.code == code) {
                if (d.code != code) { Prefs.select(this, d.code); render() }
            }
        }
        chip("＋ Přidat dítě", false) { screen = "add"; render() }

        val statusCard = card(l, 16)
        val headRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusCard.addView(headRow, lp(0))
        val statusBadge = badge(52, 16, "🌐", MUTED)
        headRow.addView(statusBadge, LinearLayout.LayoutParams(dp(52), dp(52)))
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headRow.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        val status = TextView(this).apply {
            text = "Připojuji…"; textSize = 17f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD)
        }
        textCol.addView(status, LinearLayout.LayoutParams(-1, -2))
        val hint = TextView(this).apply { textSize = 13f; setTextColor(MUTED); setTypeface(typeface, Typeface.BOLD) }
        textCol.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        statusCard.addView(
            View(this).apply { background = bg(tint(Color.WHITE, 7), 0) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(14) }
        )
        val info = TextView(this).apply { textSize = 13f; setTextColor(MUTED); gravity = Gravity.START }
        statusCard.addView(info, LinearLayout.LayoutParams(-1, -2))

        var pendingAlert = 0L
        val alertCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(ALERT_BG, 20, tint(PINK, 30))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }.also { l.addView(it, lp(12)) }
        alertCard.addView(
            TextView(this).apply { text = "⚠️"; textSize = 18f },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10); topMargin = dp(1) }
        )
        val alertCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        alertCard.addView(alertCol, LinearLayout.LayoutParams(0, -2, 1f))
        val alertText = TextView(this).apply {
            textSize = 14.5f; setTextColor(Color.parseColor("#FFD3D7")); setTypeface(typeface, Typeface.BOLD)
        }
        alertCol.addView(alertText, LinearLayout.LayoutParams(-1, -2))
        TextView(this).apply {
            text = "Rozumím"; textSize = 12.5f; setTextColor(PINK); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { Prefs.setAck(this@MainActivity, code, pendingAlert); alertCard.visibility = View.GONE }
        }.also { alertCol.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        alertCard.visibility = View.GONE

        val reqCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Color.parseColor("#2A1F10"), 20, tint(GOLD, 30))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }.also { l.addView(it, lp(12)) }
        val reqHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        reqCard.addView(reqHead, lp(0))
        reqHead.addView(
            TextView(this).apply { text = "🕐"; textSize = 16f },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        )
        val reqText = TextView(this).apply {
            textSize = 14.5f; setTextColor(Color.parseColor("#FFE3A8")); setTypeface(typeface, Typeface.BOLD)
        }
        reqHead.addView(reqText, LinearLayout.LayoutParams(0, -2, 1f))
        fun ackRequest() {
            Prefs.setLong(this, "reqAck_$code", requestMsg?.optLong("at") ?: 0L)
            reqCard.visibility = View.GONE
        }
        val reqRow = row(reqCard, 12)
        smallButton(reqRow, "Povolit 10 min", GREEN) {
            if (cmd(JSONObject().put("t", "grant").put("min", 10))) ackRequest()
        }
        smallButton(reqRow, "Povolit 30 min", GREEN) {
            if (cmd(JSONObject().put("t", "grant").put("min", 30))) ackRequest()
        }
        smallButton(reqRow, "Zamítnout", CARD2) { ackRequest() }
        reqCard.visibility = View.GONE

        sectionLabel(l, "Rychlé akce", 20)
        val tr1 = tileRow(l)
        val (toggleTile, toggleBadge, toggleLabel) = iconTile(tr1, "🌐", "Odpojit\ninternet", RED) { }
        iconTile(tr1, "🔒", "Zamknout\ntelefon", BLUE) {
            if (cmd(JSONObject().put("t", "lock"))) toast("Příkaz k zamknutí odeslán")
        }
        iconTile(tr1, "🔊", "Hlasitost…", MINT) {
            pickVolume { lvl ->
                if (cmd(JSONObject().put("t", "volume").put("level", lvl))) toast("Příkaz odeslán")
            }
        }
        val tr2 = tileRow(l)
        iconTile(tr2, "📱", "Aplikace", MUTED) { go("apps") }
        iconTile(tr2, "🗓", "Rozvrh", MUTED) { go("schedule") }
        iconTile(tr2, "📍", "Poloha", MUTED) { go("location") }
        val tr3 = tileRow(l)
        iconTile(tr3, "📊", "Používání", MUTED) { go("usage") }
        View(this).apply { visibility = View.INVISIBLE }
            .also { tr3.addView(it, LinearLayout.LayoutParams(0, -2, 2f)) }

        sectionLabel(l, "Komunikace", 22)
        val cr = tileRow(l)
        pillButton(cr, "💬", "Zpráva") { messageDialog() }
        val (findTile, findIcon, findLabel) = pillButton(cr, "🔔", "Najít telefon") { }
        findTile.setOnClickListener {
            if (cmd(JSONObject().put("t", "find").put("on", !finding))) {
                finding = !finding
                findIcon.text = if (finding) "🔕" else "🔔"
                findLabel.text = "  " + if (finding) "Ztlumit" else "Najít telefon"
            }
        }
        findIcon.text = if (finding) "🔕" else "🔔"
        findLabel.text = "  " + if (finding) "Ztlumit" else "Najít telefon"

        textButton(l, "Upravit nebo odebrat dítě") { manageChild(code, name) }

        fun refresh() {
            if (serverError != null) {
                setBadge(statusBadge, "❓", MUTED); status.text = "Neznámý kód"
                hint.text = "Odeber dítě a spáruj znovu s kódem z jeho telefonu"; hint.setTextColor(MUTED)
                return
            }
            val s = state ?: return
            val netBlocked = s.optBoolean("netBlocked")
            val appsN = s.optJSONArray("appsBlocked")?.length() ?: 0
            val wanted = netBlocked || appsN > 0
            val active = s.optBoolean("active")
            val grantUntil = s.optLong("grantUntil")
            val until = s.optLong("blockedUntil")
            val blocked = s.optBoolean("blocked")
            when {
                wanted != active -> {
                    setBadge(statusBadge, "⏳", AMBER); status.text = "Odesílám příkaz…"
                    hint.text = "Čekám na potvrzení z telefonu"; hint.setTextColor(AMBER)
                }
                netBlocked -> {
                    setBadge(statusBadge, "🌐", RED); status.text = "Internet je vypnutý"
                    hint.text = when {
                        until > 0 -> "Zapne se v ${clock(until)}"
                        s.optBoolean("scheduleActive") && !blocked -> "Podle rozvrhu"
                        else -> "Web i aplikace jsou odpojené"
                    }
                    hint.setTextColor(RED)
                }
                appsN > 0 -> {
                    setBadge(statusBadge, "📵", AMBER); status.text = "Blokované aplikace: $appsN"
                    hint.text = "Ostatní internet funguje"; hint.setTextColor(AMBER)
                }
                grantUntil > 0 -> {
                    setBadge(statusBadge, "🌐", MINT); status.text = "Internet povolen do ${clock(grantUntil)}"
                    hint.text = "Rodič povolil dočasný přístup"; hint.setTextColor(MINT)
                }
                else -> {
                    setBadge(statusBadge, "🌐", MINT); status.text = "Internet je zapnutý"
                    hint.text = "$name má normální přístup"; hint.setTextColor(MINT)
                }
            }
            val st = statusMsg
            if (!s.optBoolean("childOnline")) {
                hint.text = "Dětský telefon je offline – příkaz se provede po připojení"; hint.setTextColor(AMBER)
            } else if (st?.optBoolean("vpnErr") == true) {
                hint.text = "VPN selhala – dítě musí v aplikaci obnovit oprávnění VPN"; hint.setTextColor(AMBER)
            }
            info.text = if (st != null && st.has("bat")) {
                "🔋 ${st.optInt("bat")} %" + (if (st.optBoolean("chg")) " (nabíjí se)" else "") +
                    (if (st.has("vol")) " · 🔊 ${st.optInt("vol")} %" else "") +
                    " · " + ago(st.optLong("at"))
            } else ""

            when {
                blocked -> { toggleLabel.text = "Zapnout\ninternet"; setBadge(toggleBadge, "🌐", MINT, 12) }
                grantUntil > 0 -> { toggleLabel.text = "Zrušit\npovolení"; setBadge(toggleBadge, "🌐", AMBER, 12) }
                netBlocked -> { toggleLabel.text = "Povolit\nna chvíli"; setBadge(toggleBadge, "🌐", AMBER, 12) }
                else -> { toggleLabel.text = "Odpojit\ninternet"; setBadge(toggleBadge, "🌐", RED, 12) }
            }
            toggleTile.setOnClickListener {
                when {
                    blocked -> cmd(JSONObject().put("t", "set").put("blocked", false))
                    grantUntil > 0 -> cmd(JSONObject().put("t", "grant").put("min", 0))
                    netBlocked -> pickMinutes("Povolit internet na…", listOf(10, 30, 60, 120)) { m ->
                        cmd(JSONObject().put("t", "grant").put("min", m))
                    }
                    else -> pickMinutes("Odpojit internet na…", listOf(0, 15, 30, 60, 120)) { m ->
                        cmd(JSONObject().put("t", "set").put("blocked", true).put("minutes", m))
                    }
                }
            }

            val off = s.optLong("adminDisabledAt")
            if (off > Prefs.ack(this, code)) {
                pendingAlert = off
                alertText.text = "$name vypnul(a) ochranu\n" +
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(off - skew))
                alertCard.visibility = View.VISIBLE
            } else alertCard.visibility = View.GONE

            val rq = requestMsg
            if (rq != null && rq.optLong("at") > Prefs.long(this, "reqAck_$code")) {
                val txt = Crypto.decrypt(Prefs.secretFor(this, code), rq.optString("textEnc"))
                reqText.text = "$name žádá o čas\n${txt ?: "(nepodařilo se dešifrovat)"}"
                reqCard.visibility = View.VISIBLE
            } else reqCard.visibility = View.GONE
        }
        onData = { refresh() }
        refresh()
    }

    private fun messageDialog() {
        val input = EditText(this).apply {
            hint = "Např. Večeře je hotová"
            setPadding(dp(20), dp(16), dp(20), dp(16))
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        AlertDialog.Builder(this)
            .setTitle("Zpráva dítěti")
            .setView(input)
            .setPositiveButton("Odeslat") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                val secret = Prefs.secretFor(this, Prefs.code(this) ?: "")
                if (state?.optBoolean("childOnline") == false) toast("Dítě je offline – zpráva se nedoručí")
                else if (cmd(JSONObject().put("t", "msg").put("textEnc", Crypto.encrypt(secret, t)))) {
                    toast("Zpráva odeslána")
                }
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    private fun manageChild(code: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Přejmenovat", "Odebrat")) { _, i ->
                if (i == 0) {
                    val input = EditText(this).apply {
                        setText(name); setPadding(dp(20), dp(16), dp(20), dp(16))
                    }
                    AlertDialog.Builder(this).setTitle("Nové jméno").setView(input)
                        .setPositiveButton("Uložit") { _, _ ->
                            val n = input.text.toString().trim()
                            if (n.isNotEmpty()) {
                                Prefs.renameDevice(this, code, n)
                                restartParentService(); render()
                            }
                        }
                        .setNegativeButton("Zrušit", null).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Odebrat $name?")
                        .setMessage("Telefon dítěte tím nepřestane být chráněný, jen ho přestaneš ovládat z této aplikace.")
                        .setPositiveButton("Odebrat") { _, _ ->
                            Prefs.removeDevice(this, code)
                            stopParentLink()
                            screen = "main"
                            restartParentService()
                            render()
                        }
                        .setNegativeButton("Zrušit", null).show()
                }
            }
            .show()
    }

    private fun appsScreen() {
        val l = root()
        subHeader(l, "Blokované aplikace", "Označené aplikace nebudou mít internet")
        val checks = LinkedHashMap<String, CheckBox>()
        bigButton(l, "💾", "Uložit", BLUE, 16) {
            val arr = JSONArray()
            checks.forEach { (p, cb) -> if (cb.isChecked) arr.put(p) }
            if (cmd(JSONObject().put("t", "setApps").put("apps", arr))) toast("Uloženo")
        }
        val box = card(l, 16)
        var built = 0
        fun build() {
            val secret = Prefs.secretFor(this, Prefs.code(this) ?: "")
            val listEnc = appsMsg?.optString("listEnc")
            val list = listEnc?.let { Crypto.decrypt(secret, it) }?.let { JSONArray(it) }
            if (list == null || list.length() == 0) {
                if (built != -1) {
                    box.removeAllViews()
                    label(box, "Seznam aplikací zatím nemám.\nDětský telefon ho pošle po připojení.", 14f, MUTED)
                    built = -1
                }
                return
            }
            if (built == list.length()) return
            built = list.length()
            box.removeAllViews(); checks.clear()
            val blockedSet = HashSet<String>()
            state?.optJSONArray("blockedApps")?.let { a ->
                for (i in 0 until a.length()) blockedSet.add(a.getString(i))
            }
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val pkg = o.optString("p")
                val cb = CheckBox(this).apply {
                    text = o.optString("n"); setTextColor(TEXT); textSize = 16f
                    isChecked = pkg in blockedSet
                    buttonTintList = ColorStateList.valueOf(BLUE)
                }
                checks[pkg] = cb
                box.addView(cb, LinearLayout.LayoutParams(-1, -2))
            }
        }
        onData = { build() }
        build()
    }

    private fun usageScreen() {
        val l = root()
        subHeader(l, "Používání dnes", "Které aplikace dítě dnes používalo")
        bigButton(l, "🔄", "Obnovit", BLUE, 16) {
            if (cmd(JSONObject().put("t", "getUsage"))) toast("Žádám o data…")
        }
        val box = card(l, 16)
        fun refresh() {
            box.removeAllViews()
            val u = usageMsg
            if (u == null) {
                label(box, "Zatím žádná data.\nKlepni na Obnovit.", 14f, MUTED); return
            }
            if (u.optBoolean("denied")) {
                label(
                    box, "Dítě nepovolilo přehled používání.\nMusí ho povolit v aplikaci Watch na svém telefonu.",
                    14f, MUTED
                ); return
            }
            val secret = Prefs.secretFor(this, Prefs.code(this) ?: "")
            val listEnc = u.optString("listEnc")
            val list = Crypto.decrypt(secret, listEnc)?.let { JSONArray(it) }
            if (list == null || list.length() == 0) {
                label(box, "Dnes zatím nic nepoužívalo.", 14f, MUTED); return
            }
            var max = 1L
            for (i in 0 until list.length()) max = maxOf(max, list.getJSONObject(i).optLong("ms"))
            lineLabel(box, "Aktualizováno ${ago(u.optLong("at"))}", 12f, MUTED)
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val ms = o.optLong("ms")
                lineLabel(box, "${o.optString("n")}  ·  ${fmtMs(ms)}", 15f, TEXT, 12)
                val bar = View(this).apply { background = bg(BLUE, 4) }
                box.addView(
                    bar,
                    LinearLayout.LayoutParams((dp(260) * ms / max).toInt().coerceAtLeast(dp(4)), dp(6))
                        .apply { topMargin = dp(4) }
                )
            }
        }
        onData = { o -> if (o.optString("t") == "usage") refresh() }
        refresh()
        link?.send(JSONObject().put("t", "getUsage"))
    }

    private fun scheduleScreen() {
        val l = root()
        subHeader(l, "Rozvrh", "V zadaných časech se internet sám odpojí. Výjimku můžeš kdykoli povolit.")
        val rules = mutableListOf<Rule>()
        state?.optJSONArray("schedule")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val days = o.optJSONArray("d")
                val set = mutableSetOf<Int>()
                if (days != null) for (j in 0 until days.length()) set.add(days.getInt(j))
                rules.add(Rule(set, o.optString("s", "21:00"), o.optString("e", "07:00"), o.optBoolean("lock")))
            }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        l.addView(box, lp(0))

        fun pickTime(cur: String, onPick: (String) -> Unit) {
            val h = cur.substringBefore(":").toIntOrNull() ?: 0
            val m = cur.substringAfter(":").toIntOrNull() ?: 0
            TimePickerDialog(this, { _, hh, mm -> onPick("%02d:%02d".format(hh, mm)) }, h, m, true).show()
        }

        fun draw() {
            box.removeAllViews()
            if (rules.isEmpty()) {
                val c = card(box, 16)
                label(c, "Zatím žádné pravidlo.", 14f, MUTED)
            }
            rules.forEachIndexed { idx, r ->
                val c = card(box, 16)
                val times = row(c, 0)
                smallButton(times, "Od  ${r.s}", CARD2) { pickTime(r.s) { r.s = it; draw() } }
                smallButton(times, "Do  ${r.e}", CARD2) { pickTime(r.e) { r.e = it; draw() } }
                val days = row(c, 12)
                DAYS.forEachIndexed { i, dn ->
                    val sel = (i + 1) in r.d
                    Button(this).apply {
                        text = dn; textSize = 13f; isAllCaps = false; minWidth = 0; minimumWidth = 0
                        setPadding(0, 0, 0, 0)
                        setTextColor(if (sel) Color.WHITE else MUTED)
                        background = bg(if (sel) BLUE else CARD2, 12); stateListAnimator = null
                        setOnClickListener {
                            if (sel) r.d.remove(i + 1) else r.d.add(i + 1)
                            draw()
                        }
                    }.also {
                        days.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                            marginStart = dp(2); marginEnd = dp(2)
                        })
                    }
                }
                val lockCb = CheckBox(this).apply {
                    text = "Zamknout telefon na začátku"; setTextColor(TEXT); textSize = 14f
                    isChecked = r.lock; buttonTintList = ColorStateList.valueOf(BLUE)
                    setOnCheckedChangeListener { _, v -> r.lock = v }
                }
                c.addView(lockCb, lp(12))
                textButton(c, "Smazat pravidlo") { rules.removeAt(idx); draw() }
            }
        }
        draw()

        if (true) {
            bigButton(l, "＋", "Přidat pravidlo", CARD2, 16) {
                if (rules.size >= 6) { toast("Nejvýše 6 pravidel"); return@bigButton }
                rules.add(Rule((1..7).toMutableSet(), "21:00", "07:00", false))
                draw()
            }
        }
        bigButton(l, "💾", "Uložit rozvrh", BLUE, 12) {
            if (rules.any { it.d.isEmpty() }) { toast("Vyber u pravidla aspoň jeden den"); return@bigButton }
            val arr = JSONArray()
            rules.forEach { r ->
                arr.put(
                    JSONObject().put("d", JSONArray(r.d.sorted())).put("s", r.s).put("e", r.e).put("lock", r.lock)
                )
            }
            if (cmd(JSONObject().put("t", "setSchedule").put("rules", arr))) toast("Rozvrh uložen")
        }
    }

    private fun locationScreen() {
        val l = root()
        subHeader(l, "Poloha a baterie")
        val c = card(l, 16)
        val bat = label(c, "", 18f, TEXT, true)
        val loc = label(c, "", 14f, MUTED, topDp = 12)
        val mapBtn = bigButton(l, "🗺", "Zobrazit na mapě", BLUE, 16) { }
        bigButton(l, "📍", "Aktualizovat polohu", CARD2, 12) {
            if (cmd(JSONObject().put("t", "getLocation"))) toast("Zjišťuji polohu…")
        }
        fun refresh() {
            val s = statusMsg
            if (s == null) {
                bat.text = "Zatím žádná data"; loc.text = "Klepni na Aktualizovat polohu."
                mapBtn.visibility = View.GONE; return
            }
            bat.text = if (s.has("bat")) {
                "🔋 ${s.optInt("bat")} %" + if (s.optBoolean("chg")) " (nabíjí se)" else ""
            } else "🔋 neznámá"
            val secret = Prefs.secretFor(this, Prefs.code(this) ?: "")
            val locJson = Crypto.decrypt(secret, s.optString("locEnc"))?.let { JSONObject(it) }
            if (locJson != null) {
                val lat = locJson.optDouble("lat"); val lon = locJson.optDouble("lon")
                loc.text = "📍 %.5f, %.5f\nPřesnost asi %d m · %s".format(
                    lat, lon, locJson.optDouble("acc").toInt(), ago(s.optLong("locAt"))
                )
                mapBtn.visibility = View.VISIBLE
                mapBtn.setOnClickListener {
                    val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { toast("Nelze otevřít mapu") }
                }
            } else {
                loc.text = if (!s.optBoolean("locOk", true)) {
                    "Dítě nepovolilo polohu.\nMusí ji povolit v aplikaci Watch na svém telefonu."
                } else "Poloha zatím není známá.\nKlepni na Aktualizovat polohu."
                mapBtn.visibility = View.GONE
            }
        }
        onData = { o -> if (o.optString("t") == "status") refresh() }
        refresh()
    }
}
