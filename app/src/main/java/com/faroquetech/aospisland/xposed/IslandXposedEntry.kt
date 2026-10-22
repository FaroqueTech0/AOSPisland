package com.faroquetech.aospisland.xposed

import android.app.Application
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** LSPosed entry point (declared in assets/xposed_init). Scope: com.android.systemui */
class IslandXposedEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        try {
            XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    if (app.packageName == "com.android.systemui") SystemUiIsland.start(app)
                }
            })
            SystemUiIsland.hookNotificationListener(lpparam.classLoader)
            SystemUiIsland.hookLockscreenNotifications(lpparam.classLoader)
            SystemUiIsland.hookLockscreenMedia(lpparam.classLoader)
            SystemUiIsland.hookNativeCallChip()
            SystemUiIsland.hookComposeChips(lpparam.classLoader)
            SystemUiIsland.hookScreenRecordingChipViewModel(lpparam.classLoader)
            SystemUiIsland.hookScreenRecordingStopLifecycle(lpparam.classLoader)
            SystemUiIsland.hookScreenRecordStop(lpparam.classLoader)
            XposedBridge.log("AOSPIsland: module loaded in SystemUI")
            // Application.onCreate may already have run; start from the live Application in that case.
            try {
                val cur = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Application
                if (cur != null && cur.packageName == "com.android.systemui") SystemUiIsland.start(cur)
            } catch (ignored: Throwable) { }
        } catch (t: Throwable) {
            XposedBridge.log("AOSPIsland: hook failed: $t")
        }
    }
}
