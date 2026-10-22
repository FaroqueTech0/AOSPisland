package com.faroquetech.aospisland

import android.content.Context
import android.graphics.Color
import android.os.Build

/** Shared appearance palette for the Dynamic Island and lock-screen Now Bar. */
object IslandTheme {
    const val CLASSIC = 0
    const val MONET = 1
    const val APP_ADAPTIVE = 2
    fun mode(context: Context): Int = IslandConfig.prefs(context).getInt("islandThemeMode", CLASSIC).coerceIn(0, 2)
    fun surface(context: Context, appAccent: Int = Color.BLACK): Int = when (mode(context)) {
        MONET -> monetSurface(context)
        APP_ADAPTIVE -> blend(appAccent.takeIf { it != Color.TRANSPARENT && it != Color.WHITE } ?: 0xFF333333.toInt(), 0.30f)
        else -> Color.BLACK
    }
    fun accent(context: Context, fallback: Int = 0xFF72E5D5.toInt()): Int = when (mode(context)) {
        MONET -> resolve(context, "system_accent1_200") ?: resolve(context, "system_accent1_300") ?: fallback
        APP_ADAPTIVE -> fallback
        else -> fallback
    }
    private fun monetSurface(context: Context): Int = (resolve(context, "system_neutral1_900") ?: resolve(context, "system_neutral2_900") ?: Color.BLACK)
    private fun resolve(context: Context, name: String): Int? {
        if (Build.VERSION.SDK_INT < 31) return null
        val id = context.resources.getIdentifier(name, "color", "android")
        return if (id != 0) try { context.getColor(id) } catch (_: Throwable) { null } else null
    }
    private fun blend(color: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb((Color.red(color)*a).toInt(), (Color.green(color)*a).toInt(), (Color.blue(color)*a).toInt())
    }
}
