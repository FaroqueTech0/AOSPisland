package com.faroquetech.aospisland

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable

enum class Kind { CALL, LIVE, MEDIA, CHARGING, BLUETOOTH, RINGER, RECORD, HOTSPOT }

enum class ActionKind { ANSWER, DECLINE, HANGUP, PREV, PLAYPAUSE, NEXT, CUSTOM }

class ActivityAction(val kind: ActionKind, val label: String = "", val run: () -> Unit)

/**
 * One "live activity" shown by the island. Immutable snapshot; sources create a new one on every change.
 */
data class IslandActivity(
    val id: String,
    val kind: Kind,
    val priority: Int,
    val title: String = "",
    val subtitle: String = "",
    val icon: Drawable? = null,
    val art: Bitmap? = null,
    val accent: Int = 0xFFFFFFFF.toInt(),
    /** Source color for the Adaptive island style (notification color or app icon color). 0 = use [accent]. */
    val surfaceColor: Int = 0,
    val playing: Boolean = false,
    // media progress (positionAt is SystemClock.elapsedRealtime when positionMs was sampled)
    val positionMs: Long = -1L,
    val durationMs: Long = -1L,
    val positionAt: Long = 0L,
    val speed: Float = 1f,
    // generic 0..1 progress (live notifications); -1 = none
    val progress: Float = -1f,
    /** True for ongoing transfer notifications whose progress amount is unknown. */
    val indeterminateProgress: Boolean = false,
    // chronometer: base is SystemClock.elapsedRealtime based. countUp: now-base ; countDown: base-now
    val chronoBase: Long = 0L,
    val chronoCountDown: Boolean = false,
    val hasChrono: Boolean = false,
    val trailingText: String? = null,
    val ringing: Boolean = false,
    val actions: List<ActivityAction> = emptyList(),
    val seek: ((Long) -> Unit)? = null,
    val contentIntent: PendingIntent? = null,
    val packageName: String? = null,
    /** Inline reply (messaging notifications with a free-form RemoteInput). Returns true when the reply was handed to the app. */
    val reply: ((String) -> Boolean)? = null,
    val transient: Boolean = false,
    /** Persistent small bubble only: never becomes the main pill (e.g. a connected Bluetooth device). */
    val bubbleOnly: Boolean = false,
) {
    /** Transient events (charger, bluetooth, ringer) never expand. */
    val expandable: Boolean get() = !transient
    val autoExpand: Boolean get() = kind == Kind.CALL && ringing
}
