package com.faroquetech.aospisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val cfg = IslandConfig.load(ctx)
        if (cfg.enabled && !cfg.systemMode && cfg.startOnBoot && Settings.canDrawOverlays(ctx)) {
            try { ctx.startForegroundService(Intent(ctx, IslandOverlayService::class.java)) } catch (ignored: Throwable) { }
        }
    }
}
