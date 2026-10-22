package com.faroquetech.aospisland

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.WindowManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private val bg = Color.rgb(10, 13, 18)
    private val panel = Color.rgb(17, 22, 30)
    private val mint = Color.rgb(114, 229, 213)
    private val muted = Color.rgb(158, 169, 185)
    private val prefs by lazy { IslandConfig.prefs(this) }
    private lateinit var preview: IslandView
    private lateinit var previewController: IslandController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        mainView = scroller(buildScreen())
        setContentView(mainView)
    }

    private var mainView: View? = null
    private var inAbout = false

    private fun scroller(content: View) = ScrollView(this).apply {
        setBackgroundColor(bg); isFillViewport = true; clipToPadding = false
        addView(content)
    }

    private fun showAbout() { inAbout = true; setContentView(scroller(buildAbout())) }
    private fun closeAbout() { inAbout = false; mainView?.let { setContentView(it) } }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() { if (inAbout) closeAbout() else super.onBackPressed() }

    override fun onResume() {
        super.onResume(); IslandHub.runtime?.restartMedia()
        if (prefs.getBoolean("systemMode", false) && prefs.getBoolean("nowBarHideNativeMedia", true)) applyNativeMediaSetting(true, false)
    }

    /**
     * The lock-screen media player obeys Android's own "show media on lock screen" setting (Settings.Secure
     * media_controls_lock_screen), which SystemUI watches live. Turning it off removes the native player whatever
     * UI toolkit draws it. Written directly if we hold WRITE_SECURE_SETTINGS, otherwise through root.
     */
    private fun applyNativeMediaSetting(hide: Boolean, toast: Boolean) {
        val v = if (hide) 0 else 1
        Thread {
            val cur = try { Settings.Secure.getInt(contentResolver, "media_controls_lock_screen", 1) } catch (_: Throwable) { 1 }
            if (cur != v) {
                val direct = try { Settings.Secure.putInt(contentResolver, "media_controls_lock_screen", v) } catch (_: Throwable) { false }
                val ok = direct || runSu("settings put secure media_controls_lock_screen $v")
                if (toast && !ok) runOnUiThread { Toast.makeText(this, "Could not change the setting. Run: su -c settings put secure media_controls_lock_screen $v", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ------------------------------------------------------------------------------------------ UI

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(36)); clipToPadding = false }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(label("AOSP Island", 34f, Color.WHITE, bold = true).apply { letterSpacing = -.02f })
        titleCol.addView(label("Live activities for your status bar", 15f, muted).apply { setPadding(0, dp(6), 0, 0) })
        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("About", 14f, mint, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10)); isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xFF161D27.toInt()); setStroke(dp(1), 0xFF263140.toInt()) }
            setOnClickListener { showAbout() }
        })
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        // live preview
        val box = FrameLayout(this).apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFC9B8A6.toInt(), 0xFF8A8F96.toInt(), 0xFF3E454D.toInt())).apply { cornerRadius = dp(28).toFloat() } }
        preview = IslandView(this)
        previewController = IslandController(this, preview)
        val sw = resources.displayMetrics.widthPixels
        preview.config = IslandConfig(enabled = true, islandStyle = prefs.getInt("islandStyle", 0), glassTintPct = prefs.getInt("glassTintPct", 55), blur = prefs.getBoolean("blur", true), haptics = prefs.getBoolean("haptics", true), animSpeed = prefs.getFloat("animSpeed", 1f),
            widthTrimDp = prefs.getInt("widthTrimDp", 0), cardWidthDp = prefs.getInt("cardWidthDp", 330), tapExpands = prefs.getBoolean("tapExpands", true), maxActivities = prefs.getInt("maxActivities", 3))
        preview.setGeometry(dp(30), dp(36), sw)
        box.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)).apply { topMargin = dp(12) })
        root.addView(box, LinearLayout.LayoutParams(-1, dp(240)))
        root.addView(label("Try it: tap = open app · hold or swipe down = expand · swipe up = collapse · swipe sideways = next activity", 12f, muted).apply { setPadding(0, dp(8), 0, dp(8)) })
        root.addView(demoRow("Media" to { demo("media") }, "Call" to { demo("call") }, "On call" to { demo("ongoing") }))
        root.addView(demoRow("Timer" to { demo("timer") }, "Charging" to { demo("charge") }, "Bluetooth" to { demo("bt") }, "Message" to { demo("message") }))
        root.addView(demoRow("Silent" to { demo("silent") }, "LSPosed" to { demo("lsposed") }, "4 activities" to { demo("many") }, "Clear" to { demo("clear") }))

        // mode + status
        root.addView(section("MODE"))
        root.addView(switchRow("System mode (LSPosed, runs inside SystemUI)", "systemMode", false) { if (it) stopService(Intent(this, IslandOverlayService::class.java)); applyService() })
        root.addView(switchRow("Island enabled", "enabled", false) { applyService() })
        root.addView(button("Apply & restart SystemUI (root)", primary = true) { applyAndRestart() })
        root.addView(button("Allow display over other apps (overlay mode)") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
        root.addView(button("Allow notification access (media, calls, timers)") { IslandNotificationListener.requestAccess(this) })
        root.addView(button("Grant optional permissions (Bluetooth name, answer & call back)") { requestOptional() })

        root.addView(section("ACTIVITIES"))
        root.addView(switchRow("Media", "showMedia", true)); root.addView(switchRow("Calls", "showCalls", true)); root.addView(switchRow("Hide system call chip (system mode)", "hideNativeCallChip", true))
        root.addView(switchRow("Screen recording", "showRecord", true)); root.addView(switchRow("Hide system red screen-record chip (system mode)", "hideNativeRecordChip", true))
        root.addView(switchRow("Timers, navigation, live updates", "showLive", true)); root.addView(switchRow("Downloads & other progress", "showProgress", true)); root.addView(switchRow("All ongoing notifications (noisy)", "showAllOngoing", false)); root.addView(switchRow("All app notifications (Phone Link, Gemini, ChatGPT, YouTube...)", "showAllNotifications", true)); root.addView(switchRow("Hide status-bar icons for all apps, incl. new ones (system mode)", "hideAllStatusIcons", true)); root.addView(switchRow("Charging", "showCharging", true)); root.addView(switchRow("Flashlight", "showFlashlight", true))
        root.addView(switchRow("Bluetooth connected (bubble)", "showBluetooth", true)); root.addView(switchRow("Hide status-bar Bluetooth icon (system mode)", "hideNativeBluetoothIcon", true)); root.addView(switchRow("Silent / ringer mode", "showRinger", true))

        root.addView(sliderRow("Activities shown at once", "maxActivities", 2, 5, 3, "") { preview.config = preview.config.copy(maxActivities = it) })
        root.addView(section("STYLE"))
        root.addView(styleRow())
        root.addView(sliderRow("Glass opacity (Liquid / Frost only)", "glassTintPct", 10, 100, 55, "%"))

        root.addView(section("FEEL"))
        root.addView(sliderRow("Animation speed", "animSpeed", 50, 200, 100, "%", float = true))
        root.addView(switchRow("Blur transitions (Android 12+)", "blur", true)); root.addView(switchRow("Haptic feedback", "haptics", true)); root.addView(switchRow("Tap expands the island (instead of opening the app)", "tapExpands", true))
        root.addView(switchRow("Hide in landscape", "hideInLandscape", true)); root.addView(switchRow("Hide in fullscreen apps (experimental)", "hideInFullscreen", false))
        root.addView(switchRow("Start on boot (overlay mode)", "startOnBoot", true))

        root.addView(section("LOCK SCREEN NOW BAR"))
        root.addView(label("The Now Bar is a pill in the lock-screen shortcut row: media controls while music plays, and every notification is shown in it. Preview it on a full-screen lock screen and adjust its vertical position.", 13f, muted).apply { setPadding(0, dp(4), 0, dp(8)) })
        root.addView(button("Open full-screen Now Bar preview") { openNowBarPreview() })
        root.addView(sliderRow("Now Bar vertical position", "nowBarPositionPct", 0, 100, 94, "%"))
        root.addView(switchRow("Hide Android's own lock-screen notifications (they only show in the Now Bar; system mode, restart SystemUI after changing)", "nowBarHideNative", true) { hideNativeLockNotifications(it) })
        root.addView(label("Needs a one-time grant: adb shell pm grant com.faroquetech.aospisland android.permission.WRITE_SECURE_SETTINGS", 12f, muted).apply { setPadding(dp(4), dp(2), dp(4), dp(8)) })

        root.addView(section("POSITION"))
        root.addView(sliderRow("Vertical offset", "yOffsetDp", -20, 40, 0, "dp"))
        root.addView(sliderRow("Extra width", "widthTrimDp", -150, 60, 0, "dp"))
        root.addView(sliderRow("Extra height", "heightTrimDp", -60, 30, 0, "dp"))
        root.addView(sliderRow("Expanded card width", "cardWidthDp", 160, 500, 330, "dp"))
        root.addView(switchRow("Manual camera cutout (if auto-detect is wrong)", "manualCutout", false))
        root.addView(sliderRow("Cutout width", "cutoutWdp", 10, 90, 30, "dp")); root.addView(sliderRow("Cutout height", "cutoutHdp", 10, 90, 30, "dp"))
        root.addView(sliderRow("Cutout top distance", "cutoutTopDp", 0, 70, 12, "dp"))
        root.addView(switchRow("Hide the lock screen's own media player (media only shows in the Now Bar; system mode, restart SystemUI after changing)", "nowBarHideNativeMedia", true) { applyNativeMediaSetting(it, true) })
        root.addView(button("Now Bar touch layer: ${windowTypeName(prefs.getInt("nowBarWindowType", 2024))} (tap to change; restart SystemUI)") { b ->
            val order = intArrayOf(2024, 2041, 2040, 2017, 2014); val cur = prefs.getInt("nowBarWindowType", 2024)
            val next = order[(order.indexOf(cur).coerceAtLeast(0) + 1) % order.size]
            put("nowBarWindowType", next); (b as Button).text = "Now Bar touch layer: ${windowTypeName(next)} (tap to change; restart SystemUI)"
        })
        root.addView(button("System-mode window layer: ${windowTypeName(prefs.getInt("windowType", 2014))} (tap to change)") { b ->
            val order = intArrayOf(2014, 2017, 2040, 2041, 2024); val cur = prefs.getInt("windowType", 2014)
            val next = order[(order.indexOf(cur).coerceAtLeast(0) + 1) % order.size]
            put("windowType", next); (b as Button).text = "System-mode window layer: ${windowTypeName(next)} (tap to change)"
        })

        groupRows(root)
        return root
    }

    // ------------------------------------------------------------------------------------------ About screen

    private fun buildAbout(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(36)); clipToPadding = false }
        root.addView(label("‹  Back", 15f, mint, bold = true).apply {
            setPadding(dp(4), dp(8), dp(16), dp(8)); isClickable = true; setOnClickListener { closeAbout() }
        })
        root.addView(label("About", 34f, Color.WHITE, bold = true).apply { letterSpacing = -.02f; setPadding(0, dp(14), 0, 0) })
        root.addView(label("AOSP Island · version ${IslandConfig.VERSION}", 15f, muted).apply { setPadding(0, dp(6), 0, dp(10)) })

        root.addView(aboutRow("AOSP Island", "Dynamic-island style live activities for your AOSP ROM: media, calls, timers, downloads, notifications with inline reply, charging, Bluetooth, hotspot and a lock-screen Now Bar."))
        root.addView(aboutRow("Version", IslandConfig.VERSION))
        root.addView(aboutRow("Package", packageName))
        root.addView(aboutRow("Device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        root.addView(aboutRow("Mode", if (prefs.getBoolean("systemMode", false)) "System mode (LSPosed, inside SystemUI)" else "Overlay mode (app window)"))
        root.addView(aboutRow("Developer", "Faroque Tech"))

        root.addView(section("UPDATES & COMMUNITY"))
        root.addView(button("Check for update", primary = true) {
            val url = IslandConfig.UPDATE_URL.trim()
            if (url.isEmpty()) Toast.makeText(this, "Update link not set yet. You are on version ${IslandConfig.VERSION}.", Toast.LENGTH_LONG).show()
            else openLink(url, "No app can open the update link")
        })
        root.addView(button("Join Faroque Tech on Telegram") { openLink("https://t.me/faroquetech0", "No app can open t.me/faroquetech0") })
        root.addView(button("Copy build info (for bug reports)") {
            val info = "AOSP Island v${IslandConfig.VERSION}\n$packageName\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "mode=${if (prefs.getBoolean("systemMode", false)) "system" else "overlay"} style=${prefs.getInt("islandStyle", 0)}"
            (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("AOSP Island build info", info))
            Toast.makeText(this, "Build info copied", Toast.LENGTH_SHORT).show()
        })

        root.addView(section("SUPPORT THE PROJECT"))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); tag = "row"
            addView(label("Made with love ❤", 17f, Color.WHITE, bold = true))
            addView(label("AOSP Island is free, and it is built and polished in my spare time. If it makes your phone feel a little more alive and you would like to say thanks, a small donation helps me keep improving it and shipping new updates. It is completely optional, the app stays free for everyone. Thank you for being here!", 14f, muted).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) })
        })
        root.addView(button("Donate with PayPal", primary = true) {
            val url = IslandConfig.DONATE_URL.trim()
            if (url.isEmpty()) Toast.makeText(this, "Donation link coming soon. Thank you for the support!", Toast.LENGTH_LONG).show()
            else openLink(url, "No app can open the donation link")
        })
        root.addView(label("Not affiliated with Apple or Samsung; \"Dynamic Island\" and \"Now Bar\" are names of their respective features.", 12f, muted).apply { setPadding(dp(6), dp(18), dp(6), dp(24)) })
        groupRows(root)
        return root
    }

    private fun openLink(url: String, failMsg: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (ignored: Throwable) { Toast.makeText(this, failMsg, Toast.LENGTH_SHORT).show() }
    }

    /** One title + value line in the About card. */
    private fun aboutRow(title: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14)); tag = "row"
        addView(label(title, 12f, muted))
        addView(label(value, 15f, Color.WHITE).apply { setPadding(0, dp(4), 0, 0); setLineSpacing(0f, 1.1f) })
    }

    /** Collapses consecutive setting rows into one rounded card with hairline dividers. */
    private fun groupRows(root: LinearLayout) {
        val kids = (0 until root.childCount).map { root.getChildAt(it) }
        root.removeAllViews()
        var card: LinearLayout? = null
        for (v in kids) {
            if (v.tag == "row") {
                val c = card ?: LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0xFF1E2733.toInt()) }
                    clipToOutline = true
                    root.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(2) })
                }.also { card = it }
                if (c.childCount > 0) c.addView(View(this).apply { setBackgroundColor(0xFF1F2833.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16) })
                c.addView(v, LinearLayout.LayoutParams(-1, -2))
            } else { card = null; root.addView(v) }
        }
    }

    /** Turns Android's own lock-screen notification list off/on, so the Now Bar is the only place they appear. */
    private fun hideNativeLockNotifications(hide: Boolean) {
        // Best effort only (needs WRITE_SECURE_SETTINGS). The real work is done by the SystemUI hook, which needs no permission.
        try { Settings.Secure.putInt(contentResolver, "lock_screen_show_notifications", if (hide) 0 else 1) } catch (_: Throwable) { }
    }

    private fun openNowBarPreview() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF42464B.toInt()) }
        val clock = TextView(this).apply {
            text = "12:47\nThu, Oct 22"; setTextColor(Color.WHITE); textSize = 42f
            gravity = Gravity.CENTER; setShadowLayer(dp(3).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
        }
        frame.addView(clock, FrameLayout.LayoutParams(-1, dp(190), Gravity.TOP))
        val fingerprint = TextView(this).apply {
            text = "◎\nFingerprint"; setTextColor(0xFFE7ECF4.toInt()); textSize = 20f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x334F91E8); setStroke(dp(2), 0xFFB9D9FF.toInt()) }
        }
        val fpSize = dp(92)
        frame.addView(fingerprint, FrameLayout.LayoutParams(fpSize, fpSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(95) })
        val card = NowBarView(this); card.showDemo()
        val pct = prefs.getInt("nowBarPositionPct", 94).coerceIn(0,100)
        // Same height and same screen-height basis as the real lock-screen window (IslandWindow.nowBarY).
        val cardH = card.contentHeightPx
        @Suppress("DEPRECATION")
        val screenH = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }.y
        val cardLp = FrameLayout.LayoutParams(NowBarView.widthFor(resources.displayMetrics.density, resources.displayMetrics.widthPixels), cardH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ((screenH-cardH).coerceAtLeast(0)*pct/100f).toInt() }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(16)); background = rounded(0xEE11151C.toInt(),20) }
        bottom.addView(label("NOW BAR POSITION", 12f, mint, true))
        val value = label("$pct%", 14f, Color.WHITE, true)
        bottom.addView(value)
        bottom.addView(SeekBar(this).apply {
            max = 100; progress = pct; progressTintList = ColorStateList.valueOf(mint); thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "$progress%"
                    cardLp.topMargin = ((screenH-cardH).coerceAtLeast(0)*progress/100f).toInt()
                    card.layoutParams = cardLp
                    if (fromUser) put("nowBarPositionPct", progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        bottom.addView(button("Done") { dialog.dismiss() })
        frame.addView(bottom, FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_VERTICAL))
        frame.addView(card, cardLp)   // drawn last so the pill stays visible above the control panel
        dialog.setContentView(frame)
        // Span the real display (incl. nav-bar area) like the lock-screen window does, so percentages line up.
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        dialog.show()
    }

    private fun windowTypeName(t: Int) = when (t) { 2017 -> "status bar sub-panel"; 2040 -> "notification shade"; 2041 -> "status bar additional"; 2024 -> "navigation bar panel"; else -> "status bar panel" }

    // ------------------------------------------------------------------------------------------ settings plumbing

    private fun put(key: String, v: Any) {
        prefs.edit().apply { when (v) { is Boolean -> putBoolean(key, v); is Int -> putInt(key, v); is Float -> putFloat(key, v) } }.apply()
        if (key == "nowBarPositionPct" && v is Int) {
            sendBroadcast(Intent(IslandWindow.ACTION_NOWBAR_POSITION).putExtra(IslandWindow.EXTRA_NOWBAR_POSITION, v.coerceIn(0, 100)))
        }
        contentResolver.notifyChange(Uri.parse("content://${IslandConfig.AUTHORITY}/config"), null)   // wakes the SystemUI module
        val cfg = IslandConfig.load(this)
        preview.config = IslandConfig(enabled = true, islandStyle = cfg.islandStyle, glassTintPct = cfg.glassTintPct, blur = cfg.blur, haptics = cfg.haptics, animSpeed = cfg.animSpeed, widthTrimDp = cfg.widthTrimDp, cardWidthDp = cfg.cardWidthDp, tapExpands = cfg.tapExpands)
        preview.setGeometry(dp(30), dp(36), resources.displayMetrics.widthPixels)
    }

    private fun applyService() {
        val cfg = IslandConfig.load(this)
        if (cfg.enabled && !cfg.systemMode) {
            if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "Allow display over other apps first", Toast.LENGTH_LONG).show(); return }
            try { startForegroundService(Intent(this, IslandOverlayService::class.java)) } catch (ignored: Throwable) { }
        } else stopService(Intent(this, IslandOverlayService::class.java))
    }

    /** Pushes the current settings to the SystemUI module, then restarts SystemUI so the hook reloads. */
    private fun applyAndRestart() {
        contentResolver.notifyChange(Uri.parse("content://${IslandConfig.AUTHORITY}/config"), null)
        applyService()
        restartSystemUi()
    }

    private fun restartSystemUi() {
        val go = {
            Thread {
                val ok = runSu("killall com.android.systemui") || runSu("pkill -f com.android.systemui")
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Restarting SystemUI…" else "Root (su) denied or unavailable. Run: su -c killall com.android.systemui", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Restart SystemUI?")
            .setMessage("The status bar and screen will flicker for a few seconds.")
            .setPositiveButton("Restart") { _, _ -> go() }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(0xFF171E27.toInt(), 24))
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply { setTextColor(mint); isAllCaps = false }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(muted); isAllCaps = false }
        }
        dialog.show()
    }

    private fun runSu(cmd: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)); p.waitFor() == 0
    } catch (ignored: Throwable) { false }

    private fun requestOptional() {
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
        perms.add(Manifest.permission.CALL_PHONE)
        requestPermissions(perms.toTypedArray(), 1)
    }

    // ------------------------------------------------------------------------------------------ demo data

    private fun targets() = listOfNotNull(previewController, IslandHub.runtime?.controller)

    private fun demoRow(vararg items: Pair<String, () -> Unit>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEach { (t, f) -> addView(TextView(this@MainActivity).apply {
            text = t; gravity = Gravity.CENTER; textSize = 12f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply { setColor(0xFF161D27.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0xFF222C39.toInt()) }; setPadding(dp(4), dp(12), dp(4), dp(12)); setOnClickListener { f() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(3); marginEnd = dp(3); topMargin = dp(3); bottomMargin = dp(3) }) }
    }

    /** Runs a demo on the preview + overlay island, and forwards it to the SystemUI island (system mode). */
    private fun demo(cmd: String) {
        Demo.play(cmd, targets())
        prefs.edit().putString("demoCmd", cmd + ":" + System.nanoTime()).apply()
        contentResolver.notifyChange(Uri.parse("content://${IslandConfig.AUTHORITY}/config"), null)
    }

    // ------------------------------------------------------------------------------------------ view helpers

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        includeFontPadding = false
    }

    private fun section(t: String) = label(t, 12f, mint, bold = true).apply {
        letterSpacing = .14f; setPadding(dp(6), dp(30), dp(6), dp(10))
    }

    private fun button(t: String, primary: Boolean = false, onClick: (View) -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) 0xFF06201C.toInt() else Color.WHITE); gravity = Gravity.CENTER
        backgroundTintList = null
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (primary) setColor(mint) else { setColor(0xFF161D27.toInt()); setStroke(dp(1), 0xFF263140.toInt()) }
        }
        minHeight = dp(54); minimumHeight = dp(54); stateListAnimator = null; elevation = dp(0).toFloat()
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    private fun switchRow(t: String, key: String, def: Boolean, after: ((Boolean) -> Unit)? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(13), dp(14), dp(13)); tag = "row"
        val text = TextView(this@MainActivity).apply { this.text = t; setTextColor(Color.WHITE); textSize = 14f; includeFontPadding = false; setLineSpacing(dp(2).toFloat(), 1f) }
        addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Switch(this@MainActivity).apply {
            isChecked = prefs.getBoolean(key, def)
            showText = false
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFF071513.toInt(), 0xFFB8C1CA.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(mint, 0xFF46515D.toInt()))
            setOnCheckedChangeListener { _, on -> put(key, on); after?.invoke(on) }
        }, LinearLayout.LayoutParams(dp(54), dp(42)).apply { marginStart = dp(10) })
    }

    private fun sliderRow(t: String, key: String, min: Int, max: Int, def: Int, unit: String, float: Boolean = false, onChange: ((Int) -> Unit)? = null): View {
        val cur = if (float) (prefs.getFloat(key, def / 100f) * 100).toInt() else prefs.getInt(key, def)
        val title = label(t, 14f, Color.WHITE)
        val value = label("$cur$unit", 12f, mint, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF1B2D2B.toInt(), 12)
            setPadding(dp(11), dp(5), dp(11), dp(5))
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(title, LinearLayout.LayoutParams(0, -2, 1f)); addView(value) }
        val bar = SeekBar(this).apply {
            this.max = max - min; progress = (cur - min).coerceIn(0, max - min)
            // thick rounded track with a mint gradient fill and a white thumb with a soft mint halo
            val track = GradientDrawable().apply { setColor(0xFF242D39.toInt()); cornerRadius = dp(4).toFloat() }
            val fill = android.graphics.drawable.ClipDrawable(
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF3FBFAE.toInt(), mint)).apply { cornerRadius = dp(4).toFloat() },
                Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(track, fill)).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
                for (i in 0..1) { setLayerHeight(i, dp(8)); setLayerGravity(i, Gravity.CENTER_VERTICAL) }
            }
            val halo = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x3372E5D5); setSize(dp(32), dp(32)) }
            val knob = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(20), dp(20)) }
            thumb = android.graphics.drawable.LayerDrawable(arrayOf(halo, knob)).apply {
                setLayerGravity(0, Gravity.CENTER); setLayerGravity(1, Gravity.CENTER)
                setLayerSize(0, dp(32), dp(32)); setLayerSize(1, dp(20), dp(20))
            }
            splitTrack = false
            thumbOffset = 0
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min; value.text = "$v$unit"
                    if (fromUser) { if (float) put(key, v / 100f) else put(key, v); onChange?.invoke(v) }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(8)); tag = "row"
            addView(heading); addView(bar, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })
        }
    }

    /** Modern pill selector for island material styles (two rows so the longer names fit on a phone). */
    private fun styleRow(): View {
        val names = listOf("AMOLED", "Liquid", "Frost", "Material You", "Adaptive")
        val btns = ArrayList<TextView>()
        fun refresh(sel: Int) = btns.forEachIndexed { i, b ->
            b.background = rounded(if (i == sel) 0xFF31534F.toInt() else 0xFF1C2430.toInt(), 14)
            b.setTextColor(if (i == sel) 0xFFB8FFF2.toInt() else muted)
            b.typeface = Typeface.create("sans-serif-medium", if (i == sel) Typeface.BOLD else Typeface.NORMAL)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); tag = "row" }
        val rows = listOf(0..2, 3..4)
        rows.forEachIndexed { ri, range ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in range) {
                val b = TextView(this).apply {
                    text = names[i]; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(6), dp(12), dp(6), dp(12)); includeFontPadding = false
                    setOnClickListener { put("islandStyle", i); refresh(i); preview.invalidate() }
                }
                btns.add(b); line.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
            col.addView(line, LinearLayout.LayoutParams(-1, -2).apply { if (ri > 0) topMargin = dp(6) })
        }
        val hint = TextView(this).apply {
            text = "Material You uses your wallpaper's system colors. Adaptive tints the island with the current app, album art or notification color."
            textSize = 11f; setTextColor(muted); setPadding(dp(4), dp(10), dp(4), 0)
        }
        col.addView(hint)
        refresh(prefs.getInt("islandStyle", 0).coerceIn(0, names.lastIndex))
        return col
    }

    private fun rounded(color: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
