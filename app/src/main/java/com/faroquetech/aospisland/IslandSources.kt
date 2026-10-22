package com.faroquetech.aospisland

import android.app.Notification
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager

private const val GREEN = 0xFF30D158.toInt()
private const val BLUE = 0xFF0A84FF.toInt()
private const val ORANGE = 0xFFFF9F0A.toInt()
private const val RED = 0xFFFF453A.toInt()
private const val YELLOW = 0xFFFFD60A.toInt()

/** Smooth version used by the animation: blends red (15 %) > orange (30 %) > yellow (60 %) > green (75 %) as the level counts up. */
fun chargeColorAt(level: Float): Int {
    val stops = floatArrayOf(15f, 30f, 60f, 75f)
    val cols = intArrayOf(RED, ORANGE, YELLOW, GREEN)
    if (level <= stops[0]) return cols[0]
    for (i in 1 until stops.size) if (level <= stops[i]) {
        val t = (level - stops[i - 1]) / (stops[i] - stops[i - 1])
        fun ch(sh: Int) = (((cols[i - 1] shr sh) and 0xFF) + ((((cols[i] shr sh) and 0xFF) - ((cols[i - 1] shr sh) and 0xFF)) * t)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    return cols.last()
}

/** Charging colour follows the battery level: red up to 15 %, orange up to 30 %, yellow up to 60 %, green above. */
fun chargeColor(level: Int): Int = when {
    level < 0 -> GREEN
    level <= 15 -> RED
    level <= 30 -> ORANGE
    level <= 60 -> YELLOW
    else -> GREEN
}

// =========================================================================================================
// Media
// =========================================================================================================

/**
 * Active media sessions. [listener] is the enabled NotificationListenerService component (overlay app mode);
 * pass null inside SystemUI, which holds MEDIA_CONTENT_CONTROL itself.
 */
class MediaSource(private val ctx: Context, private val listener: ComponentName?, private val hasNotification: (String) -> Boolean = { false }, private val sink: (List<IslandActivity>) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var controllers: List<MediaController> = emptyList()
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private val lastPlaying = HashMap<String, Long>()
    private var started = false
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { setControllers(it ?: emptyList()) }
    private val rebuildRunnable = Runnable { rebuild() }

    fun start() {
        stop()
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
            started = true
            setControllers(msm.getActiveSessions(listener))
        } catch (ignored: SecurityException) {
            // Notification access not granted yet; call start() again after it is.
            sink(emptyList())
        } catch (ignored: Throwable) { sink(emptyList()) }
    }

    /** Re-evaluate now (called when the media notification is posted / removed). */
    fun refresh() { handler.post { if (started) rebuild() } }

    fun stop() {
        handler.removeCallbacks(rebuildRunnable)
        if (started) try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (ignored: Throwable) { }
        started = false
        for ((c, cb) in callbacks) try { c.unregisterCallback(cb) } catch (ignored: Throwable) { }
        callbacks.clear(); controllers = emptyList()
    }

    private fun setControllers(list: List<MediaController>) {
        for ((c, cb) in callbacks) try { c.unregisterCallback(cb) } catch (ignored: Throwable) { }
        callbacks.clear()
        controllers = list
        for (c in list) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = rebuild()
                override fun onMetadataChanged(metadata: MediaMetadata?) = rebuild()
                override fun onSessionDestroyed() = rebuild()
            }
            try { c.registerCallback(cb, handler); callbacks[c] = cb } catch (ignored: Throwable) { }
        }
        rebuild()
    }

    private fun rebuild() {
        val now = SystemClock.elapsedRealtime()
        val out = ArrayList<IslandActivity>()
        var needLater = false
        for (c in controllers) {
            try {
                val st = c.playbackState; val md = c.metadata
                val state = st?.state ?: PlaybackState.STATE_NONE
                val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                val pkg = c.packageName
                if (playing) lastPlaying[pkg] = now
                // Samsung-style: a paused player stays on the Now Bar for as long as its media notification exists
                val recent = now - (lastPlaying[pkg] ?: 0L) < 30_000L || hasNotification(pkg)
                if (!playing && !recent) continue
                if (!playing) needLater = true
                val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
                if (md == null && title.isBlank()) continue
                val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                val icon = if (art == null) try { ctx.packageManager.getApplicationIcon(pkg) } catch (ignored: Throwable) { null } else null
                val tc = c.transportControls
                val acts = st?.actions ?: 0L
                val actions = ArrayList<ActivityAction>()
                if (acts and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) actions.add(ActivityAction(ActionKind.PREV) { tc.skipToPrevious() })
                actions.add(ActivityAction(ActionKind.PLAYPAUSE) { if (playing) tc.pause() else tc.play() })
                if (acts and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) actions.add(ActivityAction(ActionKind.NEXT) { tc.skipToNext() })
                val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
                out.add(IslandActivity(
                    id = "media:$pkg", kind = Kind.MEDIA, priority = if (playing) 50 else 45,
                    title = title, subtitle = artist, art = art, icon = icon,
                    accent = ArtColor.extract(art, 0xFF7CE0D3.toInt()), playing = playing,
                    positionMs = if (st != null && st.position >= 0) st.position else -1L,
                    durationMs = if (dur > 0) dur else -1L,
                    positionAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: now,
                    speed = st?.playbackSpeed ?: 1f,
                    actions = actions,
                    seek = if (acts and PlaybackState.ACTION_SEEK_TO != 0L) ({ ms: Long -> tc.seekTo(ms) }) else null,
                    contentIntent = c.sessionActivity, packageName = pkg,
                ))
            } catch (ignored: Throwable) { }
        }
        sink(out)
        handler.removeCallbacks(rebuildRunnable)
        if (needLater) handler.postDelayed(rebuildRunnable, 31_000L)
    }
}

// =========================================================================================================
// System events: charger, bluetooth, ringer mode
// =========================================================================================================

class SystemEventsSource(private val ctx: Context, private val controller: IslandController, private val config: () -> IslandConfig) {
    private val handler = Handler(Looper.getMainLooper())
    private var hotspotOn: Boolean? = null
    private var hotspotClients = -1
    private var softApCallback: Any? = null
    private var softApWifi: WifiManager? = null
    private var tetherCallback: Any? = null
    private var tetherMgr: Any? = null
    /** Client counts pushed by the system callbacks; -1 = never reported. */
    private var softApClients = -1
    private var tetherClients = -1
    private val hotspotPoll = object : Runnable {
        override fun run() {
            val enabled = isHotspotEnabled()
            if (!enabled) { softApClients = -1; tetherClients = -1 }
            val clients = if (enabled) connectedHotspotClients() else 0
            if (hotspotOn != enabled || (enabled && clients != hotspotClients)) {
                hotspotOn = enabled
                hotspotClients = clients
                publishHotspot(enabled, clients)
            }
            handler.postDelayed(this, 2000L)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                if (i.action == "android.net.wifi.WIFI_AP_STATE_CHANGED" || i.action == "android.net.wifi.WIFI_AP_STATE_CHANGED_ACTION") {
                    val enabled = isHotspotEnabled()
                    val clients = if (enabled) connectedHotspotClients() else 0
                    if (hotspotOn != enabled || (enabled && clients != hotspotClients)) { hotspotOn = enabled; hotspotClients = clients; publishHotspot(enabled, clients) }
                } else handle(i)
            } catch (ignored: Throwable) { }
        }
    }

    // ---- flashlight ----
    private var torchCb: CameraManager.TorchCallback? = null
    private var torchMgr: CameraManager? = null
    private fun startTorch() {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cb = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { publishTorch(cm, cameraId, enabled) }
                override fun onTorchModeUnavailable(cameraId: String) { }
            }
            torchMgr = cm; torchCb = cb
            cm.registerTorchCallback(cb, handler)
        } catch (ignored: Throwable) { }
    }
    private fun stopTorch() {
        try { torchCb?.let { torchMgr?.unregisterTorchCallback(it) } } catch (ignored: Throwable) { }
        torchCb = null; controller.set("torch", emptyList())
    }
    private fun publishTorch(cm: CameraManager, id: String, on: Boolean) {
        if (!on || !config().showFlashlight) { controller.set("torch", emptyList()); return }
        controller.set("torch", listOf(IslandActivity(
            id = "torch", kind = Kind.LIVE, priority = 55, title = "Flashlight", subtitle = "On", accent = 0xFFFFD60A.toInt(),
            actions = listOf(ActivityAction(ActionKind.CUSTOM, "Turn off") { try { cm.setTorchMode(id, false) } catch (ignored: Throwable) { } })
        )))
    }

    fun start() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_BATTERY_LOW)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED); addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"); addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(receiver, f)
        hotspotOn = null
        registerSoftApCallback()
        registerTetheringCallback()
        seedBluetooth()
        startTorch()
        handler.removeCallbacks(hotspotPoll)
        hotspotPoll.run()
    }

    fun stop() {
        handler.removeCallbacks(hotspotPoll)
        unregisterSoftApCallback()
        unregisterTetheringCallback()
        stopTorch()
        try { ctx.unregisterReceiver(receiver) } catch (ignored: Throwable) { }
        controller.set("hotspot", emptyList())
        btDevices.clear(); btLevels.clear(); controller.set("btconn", emptyList())
    }

    /** isWifiApEnabled is hidden on some Android releases, so invoke it reflectively and poll as a fallback. */
    @Suppress("DEPRECATION")
    private fun isHotspotEnabled(): Boolean {
        return try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val m = wifi.javaClass.methods.firstOrNull { it.name == "isWifiApEnabled" && it.parameterTypes.isEmpty() }
                ?: wifi.javaClass.declaredMethods.firstOrNull { it.name == "isWifiApEnabled" && it.parameterTypes.isEmpty() }
            m?.isAccessible = true
            (m?.invoke(wifi) as? Boolean) ?: false
        } catch (ignored: Throwable) { false }
    }

    /**
     * Proxy for a hidden callback interface. hashCode/equals/toString MUST be answered: the framework keeps the callback
     * in a map, and returning null for hashCode() throws, which made registration fail silently before.
     */
    private fun callbackProxy(cls: Class<*>, onEvent: (String, Array<out Any?>?) -> Unit): Any =
        java.lang.reflect.Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "AospIslandCallback"
                else -> { try { onEvent(method.name, args) } catch (_: Throwable) { }; null }
            }
        }

    private fun onClientCount(fromSoftAp: Boolean, count: Int) {
        handler.post {
            if (fromSoftAp) softApClients = count else tetherClients = count
            val n = connectedHotspotClients()
            if (hotspotOn == true && hotspotClients != n) { hotspotClients = n; publishHotspot(true, n) }
        }
    }

    /** SoftAP client callback (Android 10+). Needs the privileged SystemUI process, i.e. system mode. */
    private fun registerSoftApCallback() {
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val callbackClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            if (!callbackClass.isInterface) return
            val proxy = callbackProxy(callbackClass) { name, args ->
                when (name) {
                    // Android 11+: onConnectedClientsChanged(List<WifiClient>) = all clients. Android 12+ also calls a
                    // 2-argument (SoftApInfo, List) variant once per band; ignore that one to avoid partial counts.
                    "onConnectedClientsChanged" -> if (args != null && args.size == 1) {
                        (args[0] as? Collection<*>)?.let { onClientCount(true, it.size) }
                    }
                    "onNumClientsChanged" -> (args?.firstOrNull() as? Number)?.let { onClientCount(true, it.toInt()) }
                }
            }
            val method = wifi.javaClass.methods.firstOrNull { it.name == "registerSoftApCallback" && it.parameterTypes.size == 2 } ?: return
            val executor = java.util.concurrent.Executor { command -> handler.post(command) }
            method.invoke(wifi, executor, proxy)
            softApWifi = wifi; softApCallback = proxy
        } catch (_: Throwable) { }
    }

    private fun unregisterSoftApCallback() {
        val wifi = softApWifi; val callback = softApCallback
        if (wifi != null && callback != null) try {
            val m = wifi.javaClass.methods.firstOrNull { it.name == "unregisterSoftApCallback" && it.parameterTypes.size == 1 }
            m?.invoke(wifi, callback)
        } catch (_: Throwable) { }
        softApWifi = null; softApCallback = null; softApClients = -1
    }

    /** TetheringManager client callback (Android 11+): second source for the connected-device count. */
    private fun registerTetheringCallback() {
        try {
            val mgr = ctx.applicationContext.getSystemService("tethering") ?: return
            val cbClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            if (!cbClass.isInterface) return
            val proxy = callbackProxy(cbClass) { name, args ->
                if (name == "onClientsChanged") {
                    val list = args?.firstOrNull() as? Collection<*> ?: return@callbackProxy
                    // Count only Wi-Fi hotspot clients (TETHERING_WIFI = 0) when the type is readable.
                    val wifiOnly = list.filter { c ->
                        try { (c!!.javaClass.getMethod("getTetheringType").invoke(c) as Int) == 0 } catch (_: Throwable) { true }
                    }
                    onClientCount(false, wifiOnly.size)
                }
            }
            val m = mgr.javaClass.methods.firstOrNull { it.name == "registerTetheringEventCallback" && it.parameterTypes.size == 2 } ?: return
            m.invoke(mgr, java.util.concurrent.Executor { command -> handler.post(command) }, proxy)
            tetherMgr = mgr; tetherCallback = proxy
        } catch (_: Throwable) { }
    }

    private fun unregisterTetheringCallback() {
        val mgr = tetherMgr; val cb = tetherCallback
        if (mgr != null && cb != null) try {
            mgr.javaClass.methods.firstOrNull { it.name == "unregisterTetheringEventCallback" && it.parameterTypes.size == 1 }?.invoke(mgr, cb)
        } catch (_: Throwable) { }
        tetherMgr = null; tetherCallback = null; tetherClients = -1
    }

    /** Last resort: reachable neighbours on the hotspot interface from the kernel ARP table (flag 0x2 = complete). */
    private fun arpClients(): Int = try {
        java.io.File("/proc/net/arp").readLines().drop(1).count { line ->
            val p = line.trim().split(Regex("\\s+"))
            p.size >= 6 && p[2] == "0x2" && p[3] != "00:00:00:00:00:00" &&
                (p[5].startsWith("ap") || p[5].startsWith("swlan") || p[5].startsWith("softap") || p[5].startsWith("wlan1") || p[5].startsWith("wlan2"))
        }
    } catch (_: Throwable) { -1 }

    /** Connected hotspot devices: callbacks first, then hidden getters, then the ARP table. -1 = unknown. */
    private fun connectedHotspotClients(): Int {
        if (softApClients >= 0) return softApClients
        if (tetherClients >= 0) return tetherClients
        val wifi = try { ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager } catch (_: Throwable) { return arpClients() }
        val names = listOf("getSoftApConnectedClients", "getSoftApClients", "getConnectedStations", "getConnectedStationsCount", "getSoftApConnectedClientCount")
        for (name in names) {
            try {
                val m = (wifi.javaClass.methods.asSequence() + wifi.javaClass.declaredMethods.asSequence())
                    .firstOrNull { it.name == name && it.parameterTypes.isEmpty() } ?: continue
                m.isAccessible = true
                when (val value = m.invoke(wifi)) {
                    is Number -> return value.toInt().coerceAtLeast(0)
                    is Collection<*> -> return value.size
                    is Array<*> -> return value.size
                }
            } catch (_: Throwable) { }
        }
        return arpClients()
    }

    /** Stops the Wi-Fi hotspot. Works in system mode (privileged SystemUI); otherwise falls back to root, then Settings. */
    private fun turnOffHotspot() {
        Thread {
            var ok = false
            val app = ctx.applicationContext
            // 1) TetheringManager.stopTethering(TETHERING_WIFI = 0), Android 11+
            if (!ok) try {
                val tm = app.getSystemService("tethering")
                val m = tm?.javaClass?.methods?.firstOrNull { it.name == "stopTethering" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
                if (tm != null && m != null) { m.invoke(tm, 0); ok = true }
            } catch (_: Throwable) { }
            // 2) ConnectivityManager.stopTethering(int), older releases
            if (!ok) try {
                val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE)
                val m = cm?.javaClass?.methods?.firstOrNull { it.name == "stopTethering" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
                if (cm != null && m != null) { m.invoke(cm, 0); ok = true }
            } catch (_: Throwable) { }
            // 3) WifiManager.stopSoftAp()
            if (!ok) try {
                val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val m = wifi.javaClass.methods.firstOrNull { it.name == "stopSoftAp" && it.parameterTypes.isEmpty() }
                if (m != null) { ok = (m.invoke(wifi) as? Boolean) ?: true }
            } catch (_: Throwable) { }
            // 4) root
            if (!ok) try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd wifi stop-softap"))
                ok = p.waitFor() == 0
            } catch (_: Throwable) { }
            if (!ok) handler.post { openHotspotSettings() }
        }.start()
    }

    private fun openHotspotSettings() {
        val candidates = listOf("android.settings.TETHER_SETTINGS", "android.settings.WIFI_SETTINGS", "android.settings.SETTINGS")
        for (action in candidates) {
            try {
                val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(ctx.packageManager) != null) { ctx.startActivity(intent); return }
            } catch (_: Throwable) { }
        }
    }

    private fun publishHotspot(enabled: Boolean, clients: Int = hotspotClients) {
        controller.set("hotspot", if (enabled) listOf(IslandActivity(
            id = "hotspot", kind = Kind.HOTSPOT, priority = 65, title = "Hotspot",
            subtitle = if (clients < 0) "Connected devices unavailable" else "$clients connected device${if (clients == 1) "" else "s"}",
            trailingText = if (clients < 0) "ON" else "$clients", accent = BLUE, transient = false,
            actions = listOf(
                ActivityAction(ActionKind.CUSTOM, "Turn off") { turnOffHotspot() },
                ActivityAction(ActionKind.CUSTOM, "Hotspot settings") { openHotspotSettings() }
            )
        )) else emptyList())
    }

    /** Full device name: user-set alias first, then the advertised name, then the bonded-device entry for the same address. */
    private fun btName(dev: BluetoothDevice?): String? {
        if (dev == null) return null
        if (Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        fun clean(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            (if (Build.VERSION.SDK_INT >= 30) clean(dev.alias) else null)
                ?: clean(dev.name)
                ?: try {
                    val bm = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)
                    bm?.adapter?.bondedDevices?.firstOrNull { it.address == dev.address }?.let { b ->
                        (if (Build.VERSION.SDK_INT >= 30) clean(b.alias) else null) ?: clean(b.name)
                    }
                } catch (ignored: Throwable) { null }
        } catch (ignored: Throwable) { null }
    }

    // ---- connected Bluetooth devices, shown as a persistent bubble next to the island ----
    private val btDevices = LinkedHashMap<String, String?>()   // address -> display name
    private val btLevels = HashMap<String, Int>()               // address -> battery %, when the device reports it

    /** Battery percentage of a connected device (hidden API, works inside SystemUI); -1 when unknown. */
    private fun btBattery(dev: BluetoothDevice?): Int {
        if (dev == null) return -1
        return try { (BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(dev) as? Int)?.takeIf { it in 0..100 } ?: -1 } catch (ignored: Throwable) { -1 }
    }

    /** Short text for the pill: only the battery, never the long device name. */
    private fun btText(addr: String?): String = btLevels[addr]?.let { "$it%" } ?: "Connected"
    private fun btAccent(addr: String?): Int = if ((btLevels[addr] ?: 100) <= 20) 0xFFFF453A.toInt() else BLUE

    /** Devices that were already connected before the island started (needs a privileged process; silently skipped otherwise). */
    private fun seedBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val ad = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return
            if (!ad.isEnabled) return
            for (d in ad.bondedDevices.orEmpty()) {
                val on = try { BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as? Boolean } catch (ignored: Throwable) { null }
                if (on == true) { btDevices[d.address] = btName(d); btBattery(d).let { if (it >= 0) btLevels[d.address] = it } }
            }
            publishBluetoothBubble()
        } catch (ignored: Throwable) { }
    }

    private fun publishBluetoothBubble() {
        if (!config().showBluetooth || btDevices.isEmpty()) { controller.set("btconn", emptyList()); return }
        val last = btDevices.keys.last()   // most recently connected device
        controller.set("btconn", listOf(IslandActivity(
            id = "bt_conn", kind = Kind.BLUETOOTH, priority = 10, title = btDevices[last] ?: "Bluetooth", trailingText = btText(last), accent = btAccent(last), bubbleOnly = true
        )))
    }

    /** The pill shows the whole device name (the view ellipsizes only if it cannot fit on screen). */
    private fun pushBluetooth(name: String?, addr: String? = null) {
        controller.pushTransient(IslandActivity("bt", Kind.BLUETOOTH, 75, title = name ?: "Bluetooth", trailingText = btText(addr), accent = btAccent(addr), transient = true), 3600L)
    }

    private fun chargeTitle(): String {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plug = b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return if ((plug and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) "Wireless charging" else "Charging"
    }

    private fun batteryLevel(): Int {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return if (lvl < 0) -1 else lvl * 100 / scale
    }

    @Suppress("DEPRECATION")
    private fun handle(i: Intent) {
        val cfg = config()
        when (i.action) {
            Intent.ACTION_POWER_CONNECTED -> if (cfg.showCharging) {
                val l = batteryLevel()
                controller.pushTransient(IslandActivity("charging", Kind.CHARGING, 80, title = chargeTitle(), trailingText = if (l >= 0) "$l%" else null, accent = if (l >= 0) chargeColorAt(l.toFloat()) else GREEN, transient = true), 4500L)
            }
            Intent.ACTION_BATTERY_LOW -> if (cfg.showCharging) {
                val l = batteryLevel()
                controller.pushTransient(IslandActivity("battery_low", Kind.CHARGING, 85, title = "Low battery", trailingText = if (l >= 0) "$l%" else "Low", accent = 0xFFFF453A.toInt(), transient = true), 4000L)
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> if (cfg.showBluetooth) {
                val dev: BluetoothDevice? = try {
                    if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } catch (ignored: Throwable) { null }
                val name = btName(dev)
                if (dev != null) { btBattery(dev).let { if (it >= 0) btLevels[dev.address] = it } }
                pushBluetooth(name, dev?.address)
                if (dev != null) { btDevices[dev.address] = name; publishBluetoothBubble() }
                // the battery level usually arrives a moment after the link: refresh the pill and the bubble
                if (dev != null) listOf(1200L, 3000L).forEach { d -> handler.postDelayed({
                    if (btDevices.containsKey(dev.address)) { btBattery(dev).let { if (it >= 0) btLevels[dev.address] = it }; publishBluetoothBubble() }
                }, d) }
                // the name is often not resolved yet when the link comes up: look again and update the same pill
                if (name == null && dev != null) listOf(600L, 1500L, 3000L).forEach { d ->
                    handler.postDelayed({ btName(dev)?.let { pushBluetooth(it, dev.address); if (btDevices.containsKey(dev.address)) { btDevices[dev.address] = it; publishBluetoothBubble() } } }, d)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val dev: BluetoothDevice? = try {
                    if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } catch (ignored: Throwable) { null }
                if (dev != null && btDevices.remove(dev.address) != null) { btLevels.remove(dev.address); publishBluetoothBubble() }
            }
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                val dev: BluetoothDevice? = try {
                    if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } catch (ignored: Throwable) { null }
                val lvl = i.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                if (dev != null && lvl in 0..100) { btLevels[dev.address] = lvl; if (btDevices.containsKey(dev.address)) publishBluetoothBubble() }
            }
            android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val st = i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
                if (st == android.bluetooth.BluetoothAdapter.STATE_OFF || st == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF) { btDevices.clear(); btLevels.clear(); publishBluetoothBubble() }
            }
            AudioManager.RINGER_MODE_CHANGED_ACTION -> if (cfg.showRinger) {
                val mode = i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
                val (title, color) = when (mode) {
                    AudioManager.RINGER_MODE_SILENT -> "Silent" to ORANGE
                    AudioManager.RINGER_MODE_VIBRATE -> "Vibrate" to ORANGE
                    else -> "Ringer" to Color.WHITE
                }
                controller.pushTransient(IslandActivity("ringer", Kind.RINGER, 70, title = title, trailingText = title, accent = color, transient = true), 2600L)
            }
        }
    }
}

// =========================================================================================================
// LSPosed / Vector notification detection
// =========================================================================================================

/**
 * The LSPosed daemon posts its notifications (module scope request, module updated, "framework active") as the
 * "android" package, not as the manager app, so a package check alone never matches them. Match on package, on the
 * notification channel id ("lsposed*") and on the title / text / sub text.
 */
object LsposedNotif {
    private val packages = listOf("org.lsposed.manager", "org.lsposed.lspd", "io.github.lsposed.manager", "org.matrix.vector")
    private val words = listOf("lsposed", "vector")

    fun matches(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName.lowercase()
        if (packages.any { pkg == it || pkg.startsWith("$it.") }) return true
        // do not let an unrelated app that merely mentions "vector" in its text through
        if (pkg != "android" && pkg != "com.android.systemui" && pkg != "root" && pkg != "system") return false
        val n = sbn.notification ?: return false
        val channel = n.channelId.orEmpty().lowercase()
        if (channel.contains("lsposed") || channel.startsWith("vector")) return true
        val ex = n.extras ?: return false
        val head = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
            .joinToString(" ") { ex.getCharSequence(it)?.toString().orEmpty() }.lowercase()
        return words.any { w -> Regex("\\b$w\\b").containsMatchIn(head) }
    }

    /** The permanent "framework is active" status notification: it never ends, so it must not pin the island. */
    fun isPermanentStatus(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        return ongoing && !n.extras.getBoolean("android.requestPromotedOngoing", false)
    }
}

// =========================================================================================================
// Notifications: calls, timers / stopwatch / navigation, Android 16 promoted-ongoing "Live Updates"
// =========================================================================================================

class NotificationSource(private val ctx: Context, private val config: () -> IslandConfig, private val sink: (List<IslandActivity>) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val active = LinkedHashMap<String, StatusBarNotification>()
    // SystemUI can leave the completed recording notification posted until the user clears it.
    // Keep that notification out of the island after the actual stop action has run.
    private val completedRecordKeys = HashSet<String>()
    private val build = Runnable { rebuild() }
    /** True while a call activity is being shown; read by the SystemUI hook to hide the native call chip. */
    @Volatile var hasCall: Boolean = false; private set
    /** True while a screen recording activity is shown; read by the SystemUI hook to hide the native record chip. */
    @Volatile var hasRecord: Boolean = false; private set
    /** Called on the main thread whenever [hasCall] / [hasRecord] changed, so native chips can be re-synced at once. */
    @Volatile var onChipState: (() -> Unit)? = null

    fun reset(arr: Array<StatusBarNotification>?) {
        synchronized(active) {
            active.clear()
            completedRecordKeys.clear()
            arr?.forEach { sbn ->
                active[sbn.key] = sbn
                if (isCompletedRecordingNotification(sbn)) completedRecordKeys.add(sbn.key)
            }
        }
        schedule()
    }
    fun post(sbn: StatusBarNotification) {
        synchronized(active) {
            active[sbn.key] = sbn
            // SystemUI may keep the same notification key after recording has stopped. Treat the
            // terminal/saved notification as completed even when stopRecording() was never called
            // through the chip interactor (for example, when Stop is pressed in the system overlay).
            if (isSystemRecording(sbn)) {
                if (hasStopAction(sbn.notification)) completedRecordKeys.remove(sbn.key)
                else if (isCompletedRecordingNotification(sbn)) completedRecordKeys.add(sbn.key)
            }
        }
        schedule()
    }
    fun remove(key: String) { synchronized(active) { active.remove(key); completedRecordKeys.remove(key) }; schedule() }
    fun refresh() = schedule()

    /** Called from SystemUI's real stopRecording() action; does not touch the recorder service or notification. */
    fun finishRecording() {
        synchronized(active) {
            active.values.filter { isSystemRecording(it) }.forEach { completedRecordKeys.add(it.key) }
        }
        schedule()
    }

    private fun hasStopAction(n: Notification?): Boolean = (n?.actions ?: emptyArray()).any {
        val label = it.title?.toString().orEmpty()
        label.contains("stop", true) || label.contains("finish", true)
    }

    private fun isCompletedRecordingNotification(sbn: StatusBarNotification): Boolean {
        if (!isSystemRecording(sbn)) return false
        val n = sbn.notification ?: return false
        if (hasStopAction(n)) return false
        val ex = n.extras
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val words = "$title $text $big".lowercase()
        // Android commonly changes the active recording notification to a saved/completed notice.
        return listOf("recording saved", "screen recording saved", "saved recording", "recording finished", "recording stopped", "screen recording complete").any { words.contains(it) }
    }

    private fun isSystemRecording(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != "com.android.systemui") return false
        val n = sbn.notification ?: return false
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val words = (title + " " + text + " " + n.category.orEmpty()).lowercase()
        val channel = n.channelId.orEmpty().lowercase()
        return channel.contains("screen_record") || channel.contains("screenrecord") ||
            channel.contains("screen_recording") || channel.contains("recording") ||
            (words.contains("screen") && (words.contains("record") || words.contains("captur")))
    }

    fun isActiveProgressNotification(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        val ex = n.extras ?: return false
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        if (max <= 0 && !indeterminate) return false
        if (max > 0 && !indeterminate && ex.getInt(Notification.EXTRA_PROGRESS, 0) >= max) return false
        return (n.flags and Notification.FLAG_ONGOING_EVENT != 0) || n.category == Notification.CATEGORY_PROGRESS
    }
    private fun schedule() { handler.removeCallbacks(build); handler.postDelayed(build, 120) }

    private fun rebuild() {
        val cfg = config()
        val snapshot = synchronized(active) { active.values.toList() }
        val out = ArrayList<IslandActivity>()
        val live = ArrayList<Pair<Long, IslandActivity>>()
        for (sbn in snapshot) {
            try {
                val completed = synchronized(active) { sbn.key in completedRecordKeys }
                if (completed) continue
                val a = convert(sbn, cfg) ?: continue
                if (a.kind == Kind.CALL) out.add(a) else live.add(sbn.postTime to a)
            } catch (ignored: Throwable) { }
        }
        out.addAll(live.sortedByDescending { it.first }.take(3).map { it.second })
        val oldCall = hasCall; val oldRec = hasRecord
        hasCall = out.any { it.kind == Kind.CALL }
        hasRecord = out.any { it.kind == Kind.RECORD }
        sink(out)
        if (oldCall != hasCall || oldRec != hasRecord) try { onChipState?.invoke() } catch (ignored: Throwable) { }
    }

    @Suppress("DEPRECATION")
    private fun convert(sbn: StatusBarNotification, cfg: IslandConfig): IslandActivity? {
        val n = sbn.notification ?: return null
        val pkg = sbn.packageName
        if (pkg == "com.faroquetech.aospisland") return null
        val ex = n.extras ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val cat = n.category
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val countDown = ex.getBoolean("android.chronometerCountDown", false)
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progressIndeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val progress = if (max > 0 && !progressIndeterminate) ex.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max else -1f
        val promoted = ex.getBoolean("android.requestPromotedOngoing", false)
        val base = SystemClock.elapsedRealtime() + (n.`when` - System.currentTimeMillis())

        if (cat == Notification.CATEGORY_CALL) {
            if (!cfg.showCalls) return null
            val callType = ex.getInt("android.callType", 0)
            val audioMode = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
            val ringing = callType == 1 || audioMode == AudioManager.MODE_RINGTONE || (callType == 0 && !chrono && n.fullScreenIntent != null)
            fun pi(key: String): PendingIntent? = try { ex.getParcelable<PendingIntent>(key) } catch (ignored: Throwable) { null }
            val answerPi = pi("android.answerIntent") ?: findAction(n, listOf("answer", "accept", "pick up"))
            val endPi = (if (ringing) pi("android.declineIntent") else pi("android.hangUpIntent")) ?: pi("android.declineIntent") ?: pi("android.hangUpIntent")
                ?: findAction(n, listOf("decline", "reject", "hang", "end", "disconnect", "dismiss"))
            val acts = ArrayList<ActivityAction>()
            if (ringing) acts.add(ActivityAction(ActionKind.ANSWER, "Accept") { if (answerPi != null) answerPi.send() else telecom { it.acceptRingingCall() } })
            acts.add(ActivityAction(if (ringing) ActionKind.DECLINE else ActionKind.HANGUP, if (ringing) "Decline" else "End") { if (endPi != null) endPi.send() else telecom { it.endCall() } })
            return IslandActivity(
                id = "call:" + sbn.key, kind = Kind.CALL, priority = 100,
                title = title.ifBlank { text.ifBlank { "Phone" } }, subtitle = if (ringing) text.ifBlank { "Incoming call" } else text,
                accent = GREEN, ringing = ringing, hasChrono = !ringing && (chrono || (n.`when` in 1..System.currentTimeMillis())), chronoBase = base, chronoCountDown = false,
                actions = acts, contentIntent = n.contentIntent, packageName = pkg,
            )
        }

        // Missed call: a red "Missed call" activity with a one-tap "Call back" (and the dialer's own extra actions)
        if (isMissedCall(sbn, title, text)) {
            if (!cfg.showCalls) return null
            return missedCallActivity(sbn, n, ex, title, text)
        }

        // Screen recording is generated by SystemUI. Channel IDs vary across Android/OEM builds,
        // so use the channel plus notification text/title as fallbacks instead of requiring one exact ID.
        val isSystemRecording = isSystemRecording(sbn)
        if (isSystemRecording) {
            if (!cfg.showRecord) return null
            val actions = n.actions ?: emptyArray()
            val stopAction = actions.firstOrNull {
                val label = it.title?.toString().orEmpty()
                label.contains("stop", ignoreCase = true) || label.contains("finish", ignoreCase = true)
            }
            val stop = stopAction?.actionIntent
            val acts = if (stop != null) listOf(ActivityAction(ActionKind.CUSTOM, "Stop") { stop.send() }) else emptyList()
            val shownText = text.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
            return IslandActivity(
                id = "record:" + sbn.key, kind = Kind.RECORD, priority = 90,
                title = "Screen recording", subtitle = shownText ?: "Recording",
                accent = RED, hasChrono = true,
                chronoBase = if (chrono) base else SystemClock.elapsedRealtime() - (System.currentTimeMillis() - sbn.postTime).coerceAtLeast(0L),
                actions = acts, contentIntent = n.contentIntent, packageName = pkg,
            )
        }
        if (ex.containsKey("android.mediaSession")) return null          // handled by MediaSource
        if (!cfg.showLive) return null
        // LSPosed / Vector alerts (module scope request, module updated, ...). Posted by "android", so they never
        // reached the generic path below. The permanent status notification is skipped.
        if (LsposedNotif.matches(sbn)) {
            if (LsposedNotif.isPermanentStatus(sbn)) return null
            if (title.isBlank() && text.isBlank()) return null
            val managerPkg = listOf("org.lsposed.manager", "io.github.lsposed.manager", "org.matrix.vector.manager", "org.matrix.vector")
                .firstOrNull { try { ctx.packageManager.getApplicationInfo(it, 0); true } catch (ignored: Throwable) { false } }
            val lsIcon = try { managerPkg?.let { ctx.packageManager.getApplicationIcon(it) } } catch (ignored: Throwable) { null }
            val lsActions = (n.actions ?: emptyArray()).take(3).map { act -> ActivityAction(ActionKind.CUSTOM, act.title?.toString() ?: "") { sendPi(act.actionIntent) } }
            return IslandActivity(
                id = "live:" + sbn.key, kind = Kind.LIVE, priority = 66,
                title = title.ifBlank { "LSPosed" }, subtitle = text.ifBlank { ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: "LSPosed" },
                icon = lsIcon, accent = if (n.color != 0) brighten(n.color) else GREEN,
                actions = lsActions, contentIntent = n.contentIntent, packageName = managerPkg ?: pkg,
            )
        }
        val special = cat == "navigation" || cat == "stopwatch" || cat == "workout"
        val allowed = cfg.liveApps.contains(pkg)
        val pm = ctx.packageManager
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (ignored: Throwable) { pkg }
        // Social and messaging alerts are not necessarily ongoing notifications. Determine this
        // before applying the ongoing-only gate, otherwise ordinary WhatsApp/Telegram/etc. alerts
        // are rejected before they can reach the island.
        // "All app notifications": any regular alert from any app (except SystemUI/system noise and
        // low-priority/silent ones) is shown, so Phone Link, Gemini, ChatGPT, etc. always reach the island + Now Bar.
        val anyAlert = cfg.showAllNotifications && !ongoing && pkg != "com.android.systemui" && pkg != "android" &&
            n.priority > Notification.PRIORITY_MIN && (title.isNotBlank() || text.isNotBlank())
        val social = anyAlert || isSocialNotification(pkg, label, cat)
        // the LSPosed / Vector "module active" status notification is permanent: only their real alerts belong in the island
        if (ongoing && !promoted && LsposedNotif.matches(sbn)) return null
        if (!ongoing && !promoted && !social) return null
        // downloads / uploads / installs: ongoing notification carrying a progress bar (determinate or not)
        val hasProgress = max > 0 || progressIndeterminate
        val transfer = cfg.showProgress && hasProgress && (ongoing || cat == Notification.CATEGORY_PROGRESS)
        // A completed transfer can leave its notification posted; do not keep its island activity alive.
        if (transfer && progress >= 1f) return null
        val accepted = social || promoted || chrono || special || allowed || transfer || cfg.showAllOngoing
        if (!accepted) return null
        if (!social && cfg.liveApps.isNotEmpty() && !allowed && !promoted && !chrono && !special && !transfer && !cfg.showAllOngoing) return null

        val icon = try { pm.getApplicationIcon(pkg) } catch (ignored: Throwable) { null }
        val accent = if (n.color != 0) brighten(n.color) else ORANGE
        // the notification's own "Reply" action (free-form RemoteInput) becomes an inline text field in the expanded card,
        // so it is not also shown as a bare button
        val replyAct = (n.actions ?: emptyArray()).firstOrNull { a -> a.actionIntent != null && a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val replyFn: ((String) -> Boolean)? = replyAct?.let { ra ->
            val f: (String) -> Boolean = { text -> sendReply(ra, text) }
            f
        }
        val actions = (n.actions ?: emptyArray()).filter { it !== replyAct }.take(3).map { act -> ActivityAction(ActionKind.CUSTOM, act.title?.toString() ?: "") { sendPi(act.actionIntent) } }
        return IslandActivity(
            id = "live:" + sbn.key, kind = Kind.LIVE, priority = if (social) 65 else 60, reply = replyFn,
            title = title.ifBlank { label }, subtitle = if (title.isBlank()) text else text.ifBlank { label }, icon = icon, accent = accent,
            surfaceColor = if (n.color != 0) n.color else ArtColor.fromDrawable(icon),
            progress = progress, indeterminateProgress = transfer && progressIndeterminate, hasChrono = chrono, chronoBase = base, chronoCountDown = countDown,
            trailingText = ex.getCharSequence("android.shortCriticalText")?.toString()
                ?: if (progress >= 0f && !chrono) "${(progress * 100).toInt()}%" else null,
            actions = actions, contentIntent = n.contentIntent, packageName = pkg,
        )
    }

    // ---- missed calls ----
    private fun isMissedCall(sbn: StatusBarNotification, title: String, text: String): Boolean {
        val n = sbn.notification ?: return false
        val pkg = sbn.packageName.lowercase()
        if (n.category == "missed_call" || n.channelId.orEmpty().lowercase().contains("missed")) return true
        val dialer = pkg.contains("dialer") || pkg.contains("telecom") || pkg.contains("incallui") || pkg == "com.android.phone" || pkg.contains("contacts")
        return dialer && "$title $text".lowercase().contains("missed")
    }

    /** Caller's number from the notification's people list or from digits in its text; null if not available. */
    @Suppress("DEPRECATION")
    private fun missedCallNumber(ex: android.os.Bundle, title: String, text: String): String? {
        try {
            ex.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)?.forEach { p ->
                val u = p.uri
                if (u != null && u.startsWith("tel:", true)) return android.net.Uri.decode(u.substring(4))
            }
        } catch (_: Throwable) { }
        try { ex.getStringArray(Notification.EXTRA_PEOPLE)?.forEach { if (it.startsWith("tel:", true)) return android.net.Uri.decode(it.substring(4)) } } catch (_: Throwable) { }
        try { ex.getStringArrayList(Notification.EXTRA_PEOPLE)?.forEach { if (it.startsWith("tel:", true)) return android.net.Uri.decode(it.substring(4)) } } catch (_: Throwable) { }
        val re = Regex("""\+?\d[\d\s\-().]{5,}\d""")
        val m = re.find(title) ?: re.find(text) ?: return null
        return m.value.filter { it.isDigit() || it == '+' }
    }

    /**
     * Sends a notification's PendingIntent. Since Android 14 a background sender must opt in to let the target start an
     * activity, otherwise "Call back", "Message", etc. silently do nothing. Returns false if it could not be sent.
     */
    /** Sends [text] through the notification's reply action, exactly like typing in the shade's inline reply. */
    private fun sendReply(act: Notification.Action, text: String): Boolean {
        val pi = act.actionIntent ?: return false
        val inputs = act.remoteInputs ?: return false
        val ri = inputs.firstOrNull { it.allowFreeFormInput } ?: return false
        return try {
            val fill = Intent()
            val results = android.os.Bundle().apply { putCharSequence(ri.resultKey, text) }
            android.app.RemoteInput.addResultsToIntent(inputs, fill, results)
            if (Build.VERSION.SDK_INT >= 28) android.app.RemoteInput.setResultsSource(fill, android.app.RemoteInput.SOURCE_FREE_FORM_INPUT)
            if (Build.VERSION.SDK_INT >= 34) {
                val o = android.app.ActivityOptions.makeBasic()
                o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, fill, null, null, null, o.toBundle())
            } else pi.send(ctx, 0, fill)
            true
        } catch (t: Throwable) { false }
    }

    private fun sendPi(pi: PendingIntent?): Boolean {
        if (pi == null) return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val o = android.app.ActivityOptions.makeBasic()
                o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, o.toBundle())
            } else pi.send()
            true
        } catch (_: Throwable) { false }
    }

    private fun placeCall(number: String) {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.isEmpty()) return
        Thread {
            // Overlay mode runs inside this app; system mode runs in SystemUI, which asks the app to dial for it.
            val ok = try {
                if (ctx.packageName == "com.faroquetech.aospisland") CallHelper.place(ctx, digits)
                else ctx.contentResolver.call(android.net.Uri.parse("content://${IslandConfig.AUTHORITY}"), "call_number", digits, null)?.getBoolean("ok") == true
            } catch (_: Throwable) { false }
            // Could not call directly (no call permission and no root): open the dialer with the number filled in.
            if (!ok) handler.post {
                try { ctx.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", digits, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
            }
        }.start()
    }

    private fun missedCallActivity(sbn: StatusBarNotification, n: Notification, ex: android.os.Bundle, title: String, text: String): IslandActivity {
        val pkg = sbn.packageName
        val caller = (if (title.contains("missed", true)) text else title).ifBlank { text.ifBlank { title } }
        val number = missedCallNumber(ex, title, text)
        val callBackPi = findAction(n, listOf("call back", "callback", "call"))
        val callBack = ActivityAction(ActionKind.CUSTOM, "Call back") {
            // With a number: dial it (app process / root); the dialer's own action is the fallback when none was found.
            if (number != null) placeCall(number)
            else if (!sendPi(callBackPi)) sendPi(n.contentIntent)
        }
        val others = (n.actions ?: emptyArray())
            .filter { a -> val t = a.title?.toString().orEmpty().lowercase(); !(t.contains("call back") || t == "call" || t.contains("callback")) }
            .take(2).map { act -> ActivityAction(ActionKind.CUSTOM, act.title?.toString() ?: "") { sendPi(act.actionIntent) } }
        val icon = try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { null }
        return IslandActivity(
            id = "live:" + sbn.key, kind = Kind.LIVE, priority = 70,
            title = "Missed call", subtitle = caller.ifBlank { "Unknown" }, icon = icon, accent = RED,
            actions = listOf(callBack) + others, contentIntent = n.contentIntent, packageName = pkg,
        )
    }

    private fun isSocialNotification(pkg: String, label: String, category: String?): Boolean {
        val p = pkg.lowercase()
        val knownPackages = listOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram",
            "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "com.facebook.mlite",
            "com.snapchat.android", "com.discord", "org.thoughtcrime.securesms", "com.signal.org",
            "com.viber.voip", "com.tencent.mm", "com.twitter.android", "com.x.android", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "com.threads.android", "com.linkedin.android", "com.reddit.frontpage",
            "com.skype.raider", "jp.naver.line.android", "kik.android", "com.google.android.apps.messaging",
            // YouTube and YouTube Studio (comments, subscribers, uploads, milestones)
            "com.google.android.youtube", "com.google.android.apps.youtube.creator",
            // Phone Link / Link to Windows, Gemini, ChatGPT
            "com.microsoft.appmanager", "com.microsoft.yourphonecompanion",
            "com.google.android.apps.bard", "com.openai.chatgpt", "com.anthropic.claude",
            // LSPosed / Vector manager
            "org.lsposed.manager", "org.matrix.vector", "org.matrix.vector.manager"
        )
        if (knownPackages.any { p == it || p.startsWith("$it.") }) return true
        // Fallback for forks / regional variants. Require a messaging/social category or a
        // recognizable app label to avoid pulling arbitrary ongoing service notifications in.
        val l = label.lowercase()
        val socialLabel = listOf("whatsapp", "telegram", "instagram", "facebook", "messenger", "snapchat", "discord", "signal", "viber", "wechat", "twitter", " x", "tiktok", "threads", "linkedin", "reddit", "skype", "line", "kik", "messages", "youtube", "youtube studio", "phone link", "link to windows", "your phone", "gemini", "chatgpt", "claude", "lsposed", "vector")
        return (category == Notification.CATEGORY_MESSAGE || category == "social") && socialLabel.any { l.contains(it) }
    }

    private fun findAction(n: Notification, words: List<String>): PendingIntent? {
        for (a in n.actions ?: return null) {
            val t = a.title?.toString()?.lowercase() ?: continue
            if (words.any { t.contains(it) }) return a.actionIntent
        }
        return null
    }

    private fun telecom(block: (TelecomManager) -> Unit) {
        try { block(ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager) } catch (ignored: Throwable) { }
    }

    private fun brighten(c: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        hsv[2] = hsv[2].coerceAtLeast(0.85f); hsv[1] = hsv[1].coerceAtLeast(0.4f)
        return Color.HSVToColor(hsv)
    }
}
