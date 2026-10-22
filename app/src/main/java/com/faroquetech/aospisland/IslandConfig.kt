package com.faroquetech.aospisland

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

data class IslandConfig(
    val enabled: Boolean = false,
    /** false = overlay app mode, true = LSPosed / SystemUI mode (the overlay service stays off). */
    val systemMode: Boolean = false,
    val animSpeed: Float = 1f,
    val blur: Boolean = true,
    /** Island surface: 0 = solid black, 1 = liquid glass, 2 = frost glass, 3 = Material You (system dynamic colors), 4 = Adaptive (tinted by the current app / album art color). */
    val islandStyle: Int = 0,
    /** Glass opacity (10..100 %) for the glass styles: how strongly the dark tint covers what is behind the island. */
    val glassTintPct: Int = 55,
    val haptics: Boolean = true,
    val yOffsetDp: Int = 0,
    val widthTrimDp: Int = 0,
    val heightTrimDp: Int = 0,
    /** Width of the expanded card. */
    val cardWidthDp: Int = 330,
    /** Tap on the compact island expands it (long-press / swipe down always do). */
    val tapExpands: Boolean = true,
    val manualCutout: Boolean = false,
    val cutoutWdp: Int = 30,
    val cutoutHdp: Int = 30,
    val cutoutTopDp: Int = 12,
    val showMedia: Boolean = true,
    val showCalls: Boolean = true,
    val showLive: Boolean = true,
    /** System mode only: hide Android's own green call chip in the status bar while the island shows the call. */
    val hideNativeCallChip: Boolean = true,
    /** Screen recording shows in the island (red dot, timer, Stop). */
    val showRecord: Boolean = true,
    /** System mode only: hide Android's own red screen-record chip in the status bar while the island shows the recording. */
    val hideNativeRecordChip: Boolean = true,
    /** Downloads, uploads, installs and any other ongoing notification with a progress bar. */
    val showProgress: Boolean = true,
    /** Every ongoing notification (noisy: includes "app is running" style services). */
    val showAllOngoing: Boolean = false,
    /** Every regular (non-ongoing) app notification from the phone: Phone Link, Gemini, ChatGPT, YouTube, etc. */
    val showAllNotifications: Boolean = true,
    /** System mode: no app (including newly installed ones) gets a notification icon in the status bar; the island/Now Bar carries it. */
    val hideAllStatusIcons: Boolean = true,
    val showCharging: Boolean = true,
    /** Flashlight on: shows an island activity with a one-tap "Turn off". */
    val showFlashlight: Boolean = true,
    val showBluetooth: Boolean = true,
    /** System mode only: hide Android's own Bluetooth status-bar icon; the island's Bluetooth bubble replaces it. */
    val hideNativeBluetoothIcon: Boolean = true,
    val showRinger: Boolean = true,
    val hideInLandscape: Boolean = true,
    val hideInFullscreen: Boolean = false,
    val startOnBoot: Boolean = true,
    /** Lock-screen Now Bar vertical position, 0 (top) .. 100 (bottom) % of the free screen height. */
    val nowBarPositionPct: Int = 94,
    /** System mode: notifications are removed from the real lock screen and only appear in the Now Bar. */
    val nowBarHideNative: Boolean = true,
    /** System mode: the lock screen's own media player is removed, media only shows in the Now Bar. */
    val nowBarHideNativeMedia: Boolean = true,
    /** Window type used in SystemUI mode: 2014 status bar panel, 2041 status bar additional, 2024 nav bar panel. */
    val windowType: Int = 2014,
    /** Window layer of the lock-screen Now Bar. 2024 (navigation bar panel) sits above the keyguard/shade window so it gets touches. */
    val nowBarWindowType: Int = 2024,
    /** Packages allowed to show progress-only live activities (chronometers/navigation are always allowed). */
    val liveApps: Set<String> = emptySet(),
    /** Activities visible at once: the main pill plus (n - 1) small bubbles to its left. */
    val maxActivities: Int = 3,
) {
    companion object {
        /** Shown in the app status and in the SystemUI hook log, to verify which build is really running. */
        const val BUILD = "1.0"
        /** Shown as the app version in About. */
        const val VERSION = "1.0"
        /** "Check for update" opens this link. Paste the real URL here (e.g. the Telegram post / release page); blank = button says "not set yet". */
        const val UPDATE_URL = ""
        /** "Donate with PayPal" opens this link (e.g. https://www.paypal.com/paypalme/yourname). Blank = the button says "coming soon". */
        const val DONATE_URL = "https://www.paypal.com/paypalme/OmarFaroque95"
        const val PREFS = "island_prefs"
        const val AUTHORITY = "com.faroquetech.aospisland.settings"

        fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(ctx: Context): IslandConfig = from(prefs(ctx).all)

        fun from(m: Map<String, Any?>): IslandConfig {
            val d = IslandConfig()
            fun b(k: String, def: Boolean) = (m[k] as? Boolean) ?: def
            fun i(k: String, def: Int) = (m[k] as? Number)?.toInt() ?: def
            fun f(k: String, def: Float) = (m[k] as? Number)?.toFloat() ?: def
            return IslandConfig(
                enabled = b("enabled", d.enabled), systemMode = b("systemMode", d.systemMode),
                animSpeed = f("animSpeed", d.animSpeed).coerceIn(0.5f, 2f),
                blur = b("blur", d.blur), haptics = b("haptics", d.haptics),
                islandStyle = i("islandStyle", d.islandStyle).coerceIn(0, 4), glassTintPct = i("glassTintPct", d.glassTintPct).coerceIn(10, 100),
                yOffsetDp = i("yOffsetDp", d.yOffsetDp), widthTrimDp = i("widthTrimDp", d.widthTrimDp),
                heightTrimDp = i("heightTrimDp", d.heightTrimDp), cardWidthDp = i("cardWidthDp", d.cardWidthDp), tapExpands = b("tapExpands", d.tapExpands),
                manualCutout = b("manualCutout", d.manualCutout),
                cutoutWdp = i("cutoutWdp", d.cutoutWdp), cutoutHdp = i("cutoutHdp", d.cutoutHdp), cutoutTopDp = i("cutoutTopDp", d.cutoutTopDp),
                showMedia = b("showMedia", d.showMedia), showCalls = b("showCalls", d.showCalls), showLive = b("showLive", d.showLive), hideNativeCallChip = b("hideNativeCallChip", d.hideNativeCallChip),
                showRecord = b("showRecord", d.showRecord), hideNativeRecordChip = b("hideNativeRecordChip", d.hideNativeRecordChip),
                showProgress = b("showProgress", d.showProgress), showAllOngoing = b("showAllOngoing", d.showAllOngoing),
                showAllNotifications = b("showAllNotifications", d.showAllNotifications),
                hideAllStatusIcons = b("hideAllStatusIcons", d.hideAllStatusIcons),
                showCharging = b("showCharging", d.showCharging), showFlashlight = b("showFlashlight", d.showFlashlight), showBluetooth = b("showBluetooth", d.showBluetooth), hideNativeBluetoothIcon = b("hideNativeBluetoothIcon", d.hideNativeBluetoothIcon), showRinger = b("showRinger", d.showRinger),
                hideInLandscape = b("hideInLandscape", d.hideInLandscape), hideInFullscreen = b("hideInFullscreen", d.hideInFullscreen),
                startOnBoot = b("startOnBoot", d.startOnBoot),
                nowBarPositionPct = i("nowBarPositionPct", d.nowBarPositionPct).coerceIn(0, 100),
                nowBarHideNative = b("nowBarHideNative", d.nowBarHideNative),
                nowBarHideNativeMedia = b("nowBarHideNativeMedia", d.nowBarHideNativeMedia),
                windowType = i("windowType", d.windowType),
                nowBarWindowType = i("nowBarWindowType", d.nowBarWindowType),
                maxActivities = i("maxActivities", d.maxActivities).coerceIn(2, 5),
                liveApps = (m["liveApps"] as? Collection<*>)?.filterIsInstance<String>()?.toSet() ?: d.liveApps,
            )
        }

        fun toBundle(m: Map<String, Any?>): Bundle = Bundle().apply {
            for ((k, v) in m) when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Float -> putFloat(k, v)
                is String -> putString(k, v)
                is Set<*> -> putStringArrayList(k, ArrayList(v.filterIsInstance<String>()))
            }
        }

        @Suppress("DEPRECATION")
        fun fromBundle(b: Bundle?): IslandConfig {
            if (b == null) return IslandConfig()
            val m = HashMap<String, Any?>()
            for (k in b.keySet()) m[k] = b.get(k)
            return from(m)
        }
    }
}
