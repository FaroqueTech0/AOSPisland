package com.faroquetech.aospisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager

/** Overlay-app host: shows the island in a TYPE_APPLICATION_OVERLAY window. Not used in LSPosed/system mode. */
class IslandOverlayService : Service() {
    private var runtime: IslandRuntime? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val cfg = IslandConfig.load(this)
        if (!cfg.enabled || cfg.systemMode) stopSelf() else runtime?.applyConfig(cfg)
    }

    override fun onCreate() {
        super.onCreate()
        val cfg = IslandConfig.load(this)
        startAsForeground()
        if (!cfg.enabled || cfg.systemMode || !Settings.canDrawOverlays(this)) { stopSelf(); return }
        val window = IslandWindow(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, false)
        val rt = IslandRuntime(this, window, ComponentName(this, IslandNotificationListener::class.java), cfg)
        runtime = rt; IslandHub.runtime = rt
        try { rt.start() } catch (ignored: Throwable) { stopSelf(); return }
        rt.notifications.reset(IslandHub.snapshot)
        IslandConfig.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Island service", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_island).setContentTitle("AOSP Island is running")
            .setContentText("Tap to open settings").setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); runtime?.window?.onConfigurationChanged(newConfig) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        try { IslandConfig.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener) } catch (ignored: Throwable) { }
        runtime?.stop(); runtime = null; IslandHub.runtime = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { private const val CHANNEL = "island_service" }
}
