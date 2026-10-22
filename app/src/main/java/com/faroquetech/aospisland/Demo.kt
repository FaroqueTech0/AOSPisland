package com.faroquetech.aospisland

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock

/** Demo activities. Shared by the settings app (preview / overlay) and the SystemUI hook (system mode). */
object Demo {
    private const val GREEN = 0xFF30D158.toInt()
    private const val ORANGE = 0xFFFF9F0A.toInt()

    private fun art(): Bitmap {
        val b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Canvas(b).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 200f, 200f, Color.rgb(255, 94, 98), Color.rgb(255, 195, 113), Shader.TileMode.CLAMP) })
        return b
    }

    private fun media() = IslandActivity("demo:media", Kind.MEDIA, 50, "Midnight Drive", "The Neon Tapes", art = art(), accent = Color.rgb(255, 130, 90), playing = true,
        positionMs = 74_000L, durationMs = 212_000L, positionAt = SystemClock.elapsedRealtime(),
        actions = listOf(ActivityAction(ActionKind.PREV) { }, ActivityAction(ActionKind.PLAYPAUSE) { }, ActivityAction(ActionKind.NEXT) { }), seek = { })

    private fun timer() = IslandActivity("demo:timer", Kind.LIVE, 60, "Timer", "Pasta", accent = ORANGE, hasChrono = true, chronoCountDown = true,
        chronoBase = SystemClock.elapsedRealtime() + 272_000L, progress = 0.45f,
        actions = listOf(ActivityAction(ActionKind.CUSTOM, "Pause") { }, ActivityAction(ActionKind.CUSTOM, "+1 min") { }))

    private fun put(t: List<IslandController>, list: List<IslandActivity>) { t.forEach { it.set("demo", list) } }

    fun play(cmd: String, t: List<IslandController>) {
        when (cmd) {
            "media" -> put(t, listOf(media()))
            "call" -> put(t, listOf(IslandActivity("demo:call", Kind.CALL, 100, "Sara", "Incoming call", accent = GREEN, ringing = true,
                actions = listOf(ActivityAction(ActionKind.ANSWER, "Accept") { play("ongoing", t) }, ActivityAction(ActionKind.DECLINE, "Decline") { play("clear", t) }))))
            "ongoing" -> put(t, listOf(IslandActivity("demo:call", Kind.CALL, 100, "Sara", "", accent = GREEN, hasChrono = true, chronoBase = SystemClock.elapsedRealtime() - 83_000L,
                actions = listOf(ActivityAction(ActionKind.HANGUP, "End") { play("clear", t) }))))
            "timer" -> put(t, listOf(timer()))
            "two" -> put(t, listOf(media(), timer()))
            "many" -> put(t, listOf(media(), timer(),
                IslandActivity("demo:hotspot", Kind.HOTSPOT, 65, "Hotspot", "2 connected devices", trailingText = "2", accent = 0xFF0A84FF.toInt()),
                IslandActivity("demo:lsposed", Kind.LIVE, 66, "LSPosed", "Module scope request", accent = GREEN)))
            "lsposed" -> put(t, listOf(IslandActivity("demo:lsposed", Kind.LIVE, 66, "LSPosed", "Module scope request: AOSP Island", accent = GREEN)))
            "message" -> put(t, listOf(IslandActivity("live:demo:msg", Kind.LIVE, 65, "Sara", "Are we still on for tonight?", accent = 0xFF30D158.toInt(), reply = { true })))
            "clear" -> put(t, emptyList())
            "charge" -> t.forEach { it.pushTransient(IslandActivity("charging", Kind.CHARGING, 80, title = "Charging", trailingText = "78%", accent = chargeColorAt(78f), transient = true), 3500L) }
            "bt" -> t.forEach { it.pushTransient(IslandActivity("bt", Kind.BLUETOOTH, 75, title = "Buds", trailingText = "75%", accent = 0xFF0A84FF.toInt(), transient = true), 3200L) }
            "silent" -> t.forEach { it.pushTransient(IslandActivity("ringer", Kind.RINGER, 70, title = "Silent", trailingText = "Silent", accent = ORANGE, transient = true), 2600L) }
        }
    }
}
