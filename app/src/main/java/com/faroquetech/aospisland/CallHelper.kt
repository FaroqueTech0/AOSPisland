package com.faroquetech.aospisland

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager

/**
 * Places a phone call from the app process. SystemUI does not hold CALL_PHONE, so in system mode the island asks this
 * app (through [IslandSettingsProvider]) to dial. Uses the CALL_PHONE permission when granted, otherwise root.
 * Blocking: never call it on the main thread.
 */
object CallHelper {
    fun place(ctx: Context, number: String): Boolean {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.isEmpty()) return false
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).placeCall(Uri.fromParts("tel", digits, null), Bundle())
                return true
            } catch (_: Throwable) { }
        }
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am start -a android.intent.action.CALL -d tel:$digits")).waitFor() == 0
        } catch (_: Throwable) { false }
    }
}
