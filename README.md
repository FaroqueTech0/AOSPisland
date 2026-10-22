# AOSP Island v1.0 — dynamic-island style live activities for AOSP

One code base, two ways to run it:

| | Overlay mode (default) | System mode (LSPosed) |
|---|---|---|
| Needs | "Display over other apps" + notification access | LSPosed (or any Xposed-compatible framework), module enabled for **System UI** |
| Window | App overlay window | Privileged status-bar-panel window created inside SystemUI |
| Notifications | via notification-access service | read straight from SystemUI's own listener, no permission |
| Over lockscreen | no | yes (best effort, see window layer option) |

## What it does
- **Morphing island**: black shape anchored on the camera cutout; width/height/corner radius are driven by springs (slight overshoot out, firmer on the way back), content cross-fades with scale + blur (Android 12+).
- **States**: hidden (collapsed into the cutout) · compact (icon left, live value right) · expanded card · detached bubble for a second simultaneous activity.
- **Activities**: media (album art, tinted waveform, seek bar, prev/play/next), calls (auto-expands when ringing, accept / decline / end, call timer), timers / stopwatch / navigation / workout / Android 16 promoted-ongoing "Live Updates" (chronometer, progress, notification actions), charger connected, low battery, Bluetooth connected, silent / vibrate / ringer.
- **Style** (Settings → STYLE): Black · Liquid glass (clear, specular highlight, bright rim) · Frost glass (milky, grainy, diffuse), with a glass-opacity slider. Applies to the pill, expanded card and bubble. Glass is a translucent tint; Android gives overlay windows no per-shape backdrop blur, so frost is simulated.
- **Material You** (style 4 in Settings → STYLE): flat tonal surface built from the system dynamic colors (wallpaper palette, Android 12+); media / live / Bluetooth accents switch to the system primary color. Calls, recording and charging keep their semantic colors.
- **Adaptive** (style 5): the island surface is a deep tone of the current app's color: album art for media, the notification color (or the app icon's dominant color) for notifications. It cross-fades when the activity changes; neutral icons give a neutral near-black.
- **Gestures**: tap = open the app · long-press or swipe down = expand · swipe up or tap outside = collapse · swipe sideways = next activity · tap bubble = swap · haptics.
- **Settings screen** with a live preview and demo buttons (works without any real activity), manual cutout calibration, vertical offset, width trim, animation speed.

## Build
Open the `AOSPIsland` folder in Android Studio (AGP 8.6.1, Kotlin 2.0.21, JDK 17) and run `assembleDebug`. No third-party dependencies; the `xposed-stub` module is compile-only.

## Overlay mode
1. Install, open the app, tap **Allow display over other apps** and **Allow notification access**, then optionally **Grant optional permissions**.
2. Turn on **Island enabled**. Use the demo buttons to check position; if the island is not centred on the camera, switch on **Manual camera cutout**.

## System mode (LSPosed)
1. Install the APK, enable the module in LSPosed and tick **System UI** in its scope.
2. In the app turn on **System mode**, then **Island enabled**.
3. Restart SystemUI (`su -c killall com.android.systemui`) or reboot. The settings screen shows "LSPosed module in SystemUI: last seen …" once it has loaded.
4. If the island is hidden behind something, or shows over places you do not want, change **System-mode window layer** (status bar panel / additional / navigation bar panel) and restart SystemUI.

Settings reach SystemUI through the app's content provider (`com.faroquetech.aospisland.settings`), so changes apply live.

## Known limits
- Not compiled or run on a device by the author of this change: first build may need small fixes.
- System-mode notification feed hooks `com.android.systemui.statusbar.NotificationListener`. On ROMs that renamed it, media/charger/Bluetooth/ringer still work but calls/timers/live activities do not (logged to the LSPosed log).
- No access to audio frequencies: the waveform is a playback-synced animation.
- Fullscreen hiding is experimental (depends on the window insets your ROM reports); landscape hiding is reliable.
- Navigation/live data only comes from apps that post ongoing notifications with a chronometer, progress, navigation/stopwatch/workout category or the Android 16 promoted-ongoing flag.

## 2.0.13 change
- Downloads, uploads, installs and any ongoing notification with a progress bar now appear in the island (ring in compact, bar + percent in the expanded card). Toggle: **Downloads & other progress**.
- Optional **All ongoing notifications** switch (off by default) sends every ongoing notification to the island.

## 2.0.14 change
- Calls: the island timer now also works when the dialer does not set a chronometer (falls back to the notification time).
- System mode: the status-bar call chip is hidden while the island shows the call (toggle: **Hide system call chip**). Matched by view id (`ongoing_call_chip`, `ongoing_activity_chip`); the hook logs "hid native call chip" to the LSPosed log when it fires. Overlay mode cannot hide it.

## 2.0.15 change
- Screen recording now appears in the island (red record glyph, running timer, **Stop** button in the expanded card). It is read from SystemUI's `screen_record` notification; previously every SystemUI notification was dropped because the "ignore my own notifications" filter compared against SystemUI's package in system mode.
- System mode: the native call chip **and** screen-record chip are hidden while the island shows that activity (same toggle, now **Hide system call & screen-record chips**). Chip views are remembered, so a chip that was already visible when the call started is hidden right away, and restored when the call / recording ends. Also matches `ongoing_activity_chip_secondary` and chip classes when the view id is not found. The privacy (camera/mic) chip is never touched.
- Limit: chips drawn with Compose (no Android View id) cannot be hidden by this hook; check the LSPosed log for "hid native chip".

## 2.0.37 change
- Fix: the pill no longer jumps sideways when a second activity appears or the card expands. The visible island window is now full screen width, never moves or changes width (only its height follows the card) and is not touchable; the pill is drawn at the camera cutout's x inside it. A small invisible input window follows the pill's bounds and forwards touches, so any lag in the window manager is never visible.

## 2.0.38 change
- Tapping anywhere on the expanded island (not just the app / contact name lines) now opens the app; buttons and the seek bar keep working. Launching the app's PendingIntent is allowed to start the activity from the background on Android 14+.

## 2.0.42 change
- Lock-screen Now Bar media is now linked to the media notification, like Samsung: the player stays on the Now Bar while its notification exists (even paused > 30 s), disappears when the notification is swiped away or removed, and falls back to the notification's title / text / large icon when the session has no metadata.
- A new track, a different player or pressing play brings the media page to the front of the Now Bar and collapses any open card.
- Media pill shows a live progress line (ticks once a second while playing).
- Every MediaStyle notification (not only the transport category) is kept out of the notification pages.

## 2.0.43 change
- System mode: notifications no longer appear on the real lock screen, only in the Now Bar. The SystemUI hook overrides the keyguard notification visibility decision (`shouldHideNotification` / `shouldShowOnKeyguard`) while the device is locked; nothing is cancelled, so the shade after unlock is unchanged. Media, calls and SystemUI's own notifications are exempt. Needs no extra permission (the old WRITE_SECURE_SETTINGS route is now only a bonus). Switch: **Hide Android's own lock-screen notifications** (now on by default). Restart SystemUI after enabling.
- The LSPosed log prints `lock-screen notification hide hooks=N`; N = 0 means this ROM uses different class names.
- Overlay mode cannot do this (an app has no way to remove SystemUI's lock-screen list except the secure setting).

## 2.0.44 change
- The island is now fully hidden while the keyguard is showing (previously a running animation could re-show it on the lock screen). It reappears when the phone is unlocked. The lock screen only shows the Now Bar.

## 2.0.45 change
- Now Bar touches: the lock-screen Now Bar now lives in its own window layer (default **navigation bar panel**, above the keyguard/shade window that was swallowing taps and swipes on the lock screen), with an automatic fallback to the island's layer. Picker: **Now Bar touch layer**. Restart SystemUI after changing.
- Gestures (Samsung style): swipe up / down / left / right on the collapsed pill pages through media + notifications; tap opens; on an expanded card swipe up dismisses and swipe down collapses.

## 2.0.46 change
- Now Bar cards are a stack: the next cards peek out behind the front one, and switching plays a layered transition (next = the front card lifts away over the one behind it, previous = the new card drops in on top).
- System mode: the lock screen's own media player is removed so media only appears in the Now Bar (**Hide the lock screen's own media player**, on by default; restart SystemUI). The log prints `lock-screen media hide hooks=N`.

## 2.0.47 change
- Lock-screen media hiding no longer depends on one class name: while the keyguard is showing, a sweeper (every 0.4 s) finds the media views in SystemUI's window trees (MediaContainerView, or view ids containing keyguard_media / media_carousel / media_controls …) and hides them, then restores them after unlock.
- The app's status box now shows `lockscreen media views: …` and `media classes: …` (what the sweeper saw, and media-related classes in your SystemUI). If the player is Compose-drawn it can't be hidden from the view tree - send those two lines and the hook can be targeted.

## 2.0.48 change
- Lock-screen Now Bar now also carries the island's **charger** and **screen recording** activities, drawn as cards in the same stack:
  - Charger connected / low battery: pulsing bolt badge, battery %, and a charge bar that fills to the level with a moving shine. Stays about 5.5 s.
  - Screen recording: pulsing red dot, running timer, **Stop** button (tap Stop on the Now Bar). Stays until recording ends.
  A new one jumps to the front of the stack with the card transition.
- System mode: while locked, SystemUI's own screen-recording notification and the native record chip are hidden (the Now Bar replaces them).

## 2.0.49 change
- Downloads, uploads and installs (any notification carrying a progress bar) now show in the lock-screen Now Bar with a live progress bar and percent (a sweeping bar when the size is unknown), in both the pill and the expanded card (which keeps the app's Cancel / Pause buttons). A new download jumps to the front of the stack; finished downloads drop their progress bar.

## 2.0.50 change
- Lock-screen media player: now switched off through Android's own **show media on lock screen** setting (`Settings.Secure media_controls_lock_screen`), which works for both the classic and the new Compose player where hiding views cannot. The app writes the setting (directly, or through `su`) when **Hide the lock screen's own media player** is on in system mode, and the SystemUI hook also reports it as off to SystemUI. Turning the switch off restores it.

## 2.0.59 change
- LSPosed / Vector alerts (module scope request, module updated, …) now appear in the island and Now Bar. The LSPosed daemon posts them as the `android` package, which the island ignored; they are now matched by package, by notification channel (`lsposed*`) and by title / text. The permanent "framework active" status notification is still kept out so it does not pin the island.
- System mode: the status-bar icon of an LSPosed alert is hidden while the island carries it (the permanent status icon stays).
- New **LSPosed** demo button in the settings screen.

## 2.0.60 change
- Charging card (Now Bar): removed the diagonal light band that swept across the card.

## 2.0.61 change
- Incoming call: Android's own heads-up header ("Incoming call" card with Decline / Answer) is now always suppressed in system mode while Calls are enabled, so only the island is shown. Previously it was suppressed only after the island had already registered the call, which usually happened too late. The call notification itself stays in the shade.

## 2.0.62 change
- Island gestures: swipe right on the compact pill hides it into the cutout; swipe left (or tap) on the cutout brings it back. A new activity or a ringing call also brings it back. Swipe left on a visible pill still goes to the next activity (swipe right no longer goes to the previous one; tap the bubble to swap).

## 2.0.63 change
- System mode: the LSPosed / Vector status-bar icon (including the permanent "framework active" one) is hidden. The notification itself stays in the shade.

## 2.0.64 change
- Charging card (Now Bar): removed the horizontal charge bar; title and percentage are centred. The ring around the bolt still shows the level.

## 2.0.65 change
- Charging colour depends on the battery level: red ≤ 15 %, orange ≤ 30 %, yellow ≤ 60 %, green above. Applies to the island pill and the Now Bar charging card.

## 2.0.66 change
- Now Bar charging card: the colour animates while the percentage counts up, blending red (15 %) > orange (30 %) > yellow (60 %) > green (75 %) and ending on the colour for the real level. Badge, ring glow, sparks and fill all follow it.

## 2.0.67 change
- Hidden pill: the swipe zone on the cutout is now as wide as the pill was, and any sideways swipe (either direction) or a tap shows the pill again.

## 2.0.68 change
- Checked against a PixelOS Android 17 SystemUI.apk. Android 17 takes the heads-up decision in `VisualInterruptionDecisionProviderImpl.makeAndLogHeadsUpDecision / makeUnloggedHeadsUpDecision`, a class the earlier hooks did not know. New hook there returns a "do not interrupt" decision for incoming calls (Calls on) and app notifications carried by the island, so Android's own call header and heads-up cards no longer appear. The LSPosed log prints `heads-up decision-provider hooks=N` (2 expected on Android 17).
- Verified present on that build: NotificationListener, HeadsUpCoordinator, HeadsUpManagerImpl, KeyguardNotificationVisibilityProviderImpl.shouldHideNotification, KeyguardMediaController (`media.controls.ui.controller`), StatusBarIconView, ExpandableNotificationRow, MediaContainerView, ScreenRecordChipInteractor and the Compose ongoing-chip classes.

## 2.0.69 change
- Island swipe: a sideways swipe in EITHER direction hides the compact pill, and a sideways swipe (either direction) or tap on the cutout shows it again. Swiping to the next activity is replaced by tapping the bubble.
