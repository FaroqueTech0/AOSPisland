package com.faroquetech.aospisland

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Lets the SystemUI hook (a different process / uid) read this app's settings:
 *   call("get")   -> the whole config as a Bundle
 *   call("call_number", number) -> {ok: Boolean}, places a call
 *   call("hello") -> SystemUI says it loaded the module
 */
class IslandSettingsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            "get" -> IslandConfig.toBundle(IslandConfig.prefs(ctx).all)
            "hello" -> {
                val sp = ctx.getSharedPreferences(STATUS, 0)
                sp.edit().putLong("lastSeen", System.currentTimeMillis()).apply()
                Bundle.EMPTY
            }
            // SystemUI asks this app to place a call (SystemUI itself has no call permission)
            "call_number" -> Bundle().apply { putBoolean("ok", arg != null && CallHelper.place(ctx, arg)) }
            else -> null
        }
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object { const val STATUS = "island_status" }
}
