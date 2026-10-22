# AOSPIsland

### A Dynamic Island & Samsung-Inspired Now Bar for AOSP

AOSPIsland transforms the Android status-bar experience into a dynamic, interactive space for notifications, media playback, calls, screen recording, downloads, and ongoing activities.

Built by **Faroque Tech**, AOSPIsland combines a customizable Dynamic Island with a Samsung-inspired Now Bar, smooth transitions, stacked activity bubbles, and optional SystemUI integration through LSPosed.

The goal is simple: bring ongoing activities together in one compact, visually consistent interface that feels at home on Android.

---

## ✨ Features

### 🏝️ Dynamic Island

- Animated pill with compact and expanded states.
- Smooth transitions between notifications and activities.
- Automatic or manual positioning and camera-cutout adjustment.
- Configurable island width, height, and vertical offset.
- Tap-to-expand interaction and optional haptic feedback.
- Support for multiple simultaneous activities.
- Layered activity bubbles for up to three active activities.

### 🎨 Five Island Styles

| Style | Description |
|---|---|
| **Frost Glass** | A frosted-glass appearance with soft translucency and subtle highlights. |
| **Liquid Glass** | A glass-inspired surface with layered transparency, reflections, and a bright rim. |
| **AMOLED** | A deep-black appearance for a clean look on AMOLED displays. |
| **Material You** | A Monet-inspired style using Android wallpaper-derived system colors where supported. |
| **App-Adaptive** | Adapts accent colors to the active app or media when suitable color information is available. |

Island appearance settings are independent of the Now Bar's appearance.

### 📚 Stacked Activity Bubbles

- Supports a layered arrangement for up to three simultaneous activities.
- Secondary bubbles sit partially behind the primary bubble.
- The primary activity remains prominent.
- Transitions respond to activities starting, updating, or finishing.
- Returns to a simpler layout when fewer activities remain active.

### 🌊 Samsung-Inspired Now Bar

- Media information and playback controls.
- Album artwork, track details, and playback progress.
- Notification cards with app icons and content.
- Stacked notification cards and animated transitions.
- Screen-recording and charging activity indicators.
- Automatic handling of notifications from different applications.
- Configurable position and touch-layer behavior.

**Now Bar gestures**
- **Swipe up/down:** Navigate between notification or activity cards.
- **Swipe left/right:** Dismiss the current notification where supported.
- The Now Bar stays anchored in place while its content changes.

### 🎵 Media Controls

- Album artwork and track information.
- Play, pause, previous, and next actions.
- Playback progress and seeking where supported.
- Playback-synchronized waveform animation.
- Media notification integration.

### 📞 Call Integration

- Incoming-call information and island expansion.
- Answer, decline, and end-call actions where supported.
- Ongoing-call information and timer.
- Optional integration with native SystemUI call indicators.

Call behavior depends on the device, dialer, Android version, and ROM implementation.

### 🔴 Screen Recording

- Recording activity indicator and elapsed time where available.
- Stop-recording action where supported.
- Integration with the island and Now Bar.
- Optional coordination with the native SystemUI recording indicator.

### 🔔 Notifications and Ongoing Activities

Depending on the integration mode and information exposed by Android, AOSPIsland can display:

- App icons, titles, text, and available notification actions.
- Downloads and progress notifications.
- Timers and stopwatch notifications.
- Navigation and workout notifications.
- Charging and battery-related information.
- Bluetooth connection events.
- Ringer-mode changes and flashlight activity.
- Other supported ongoing notifications.

Availability depends on the source app, Android APIs, permissions, and ROM implementation.

### ⚙️ Customization

- Island appearance and glass opacity.
- Width, height, and vertical offset.
- Camera-cutout positioning.
- Animation speed, transitions, and haptic feedback.
- Notification and activity visibility.
- Media and call behavior.
- Now Bar positioning and touch layer.
- SystemUI integration settings.
- About and application information.

---

## 🧩 Architecture

AOSPIsland supports two integration approaches.

### Overlay Mode

Runs through the application's overlay service. It can display the island above other applications and process supported notifications using Android's notification-access mechanism.

This mode requires display-over-other-apps permission and notification access for relevant features.

### SystemUI Mode

Uses LSPosed/Xposed-compatible hooks to integrate with Android's SystemUI process. Depending on the target ROM, this can coordinate the island with status-bar elements, native notification presentation, call indicators, screen-recording indicators, and lock-screen media.

SystemUI integration depends on the classes, methods, and UI architecture available in the target ROM.

**Note:** LSPosed integration is not the same as compiling AOSPIsland directly into the Android framework or installing it as a privileged system application.

---

## 📱 Requirements

- A compatible Android device and ROM.
- Notification access for notification-based features in overlay mode.
- Display-over-other-apps permission for overlay mode.
- LSPosed or a compatible Xposed framework for SystemUI mode.
- Root access for optional privileged operations where required.

Some features may require additional permissions or ROM-specific support.

## Installation

### Overlay Mode

1. Install the AOSPIsland APK.
2. Open the application.
3. Grant display-over-other-apps permission.
4. Grant notification access.
5. Enable the island.
6. Configure its position and appearance.

### SystemUI Mode

1. Install AOSPIsland.
2. Open LSPosed.
3. Enable the AOSPIsland module.
4. Add **System UI (`com.android.systemui`)** to its scope.
5. Enable SystemUI mode in AOSPIsland settings.
6. Restart SystemUI or reboot if required.
7. Verify the island and supported activity integrations.

Keep a recovery method available when testing modules that hook SystemUI.

---

## 🔐 Permissions

Permissions depend on the selected mode and enabled features.

| Permission or access | Purpose |
|---|---|
| Display over other apps | Renders the island in overlay mode. |
| Notification access | Reads notifications for activity cards. |
| Phone-related permissions | Supports applicable call features. |
| Bluetooth access | Supports Bluetooth-related information where needed. |
| Notification permission | Allows the app to post notifications where required. |
| Secure settings access | Supports specific system-setting operations where applicable. |
| Root access | Enables optional privileged operations. |

Grant only the permissions required for the features you use.

---

## 🛠️ Troubleshooting

### The island does not appear
- Confirm that the island is enabled.
- Check overlay permission in Overlay mode.
- Verify the LSPosed module and SystemUI scope in SystemUI mode.
- Check LSPosed logs for hook failures.

### The native call or recording indicator remains visible
- Confirm that SystemUI mode is active.
- Enable the corresponding native-indicator integration setting.
- Check whether the target ROM exposes the expected SystemUI methods.

### Notifications are missing
- Verify notification access.
- Check notification visibility settings.
- Confirm that the source app provides a notification or supported activity event.

### The island is misaligned
- Use automatic cutout detection where available.
- Adjust the vertical offset and island dimensions.
- Apply manual positioning if automatic detection is inaccurate.

### Now Bar gestures do not work
- Check the configured touch layer.
- Verify that the displayed card supports the gesture.
- Check whether the ROM's SystemUI window configuration intercepts touch events.

---

## ⚠️ Compatibility Notes

- SystemUI hooks may differ across Android versions and ROMs.
- Some OEM interfaces use custom or Compose-based components.
- Certain activities depend on notification metadata supplied by source apps.
- App-adaptive colors depend on available notification or media color information.
- Call and recording actions depend on the relevant system APIs and permissions.

A feature working on one ROM does not guarantee identical behavior on every device.

---

## 🧑‍💻 Development

AOSPIsland is developed with Kotlin and Android development tools.

Open the project in a compatible Android IDE and use the Gradle wrapper included in the repository.

Build a debug APK:

```bash
./gradlew assembleDebug
```

The output is normally generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Use the project's actual Gradle, JDK, and Android SDK configuration. Release builds should be signed with the appropriate release credentials.

---

## 🐛 Bug Reports

When reporting a bug, include:

- Device model.
- Android version and ROM.
- Integration mode: Overlay or SystemUI.
- LSPosed version, if applicable.
- Steps to reproduce the issue.
- Relevant logs or screenshots.

Remove personal information and sensitive notification content before sharing logs.

---

## 📦 Release

**Current release:** `v1.0.0`

See the repository's Releases section for the APK and version-specific release notes.

---

## 👨‍💻 Developer

**Faroque Tech**

AOSPIsland is an independent Android customization project focused on bringing notifications and ongoing activities into a unified island experience.

---

## 📄 License

Add the project's actual license here before publishing. Until a license is specified, users should not assume that the source code is available for unrestricted reuse.

---

**AOSPIsland — Your activities. One island.**
