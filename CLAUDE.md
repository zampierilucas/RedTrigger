# CLAUDE.md - RedTrigger

## What is this

Android app for Nubia Red Magic phones that enables shoulder triggers (SAR capacitive sensors) system-wide without activating full Game Space. The Nubia game framework resets trigger settings on every activity change; RedTrigger watches for those resets and re-applies them instantly via a foreground service.

## Architecture

### Core components

- **TriggerManager** - reads/writes Android Global Settings (`nubia_game_scene`, `nubia_game_mode`, `cc_game_mis_operate`) to enable/disable triggers
- **TriggerService** - foreground service with ContentObservers that watches for system resets and re-applies settings. Also starts the Shizuku input reader
- **InputReader** - manages the Shizuku UserService lifecycle. Runs in the app process, communicates with InputService via AIDL
- **InputService** - Shizuku UserService that runs in Shizuku's process (uid 2000/shell). Reads `/dev/input/eventX` via `getevent`, detects F7/F8 key events, and optionally injects remapped key events
- **TriggerInputMethod** - lightweight IME that intercepts F7/F8 via the input method dispatch chain
- **TriggerAccessibilityService** - accessibility service that intercepts F7/F8 via `onKeyEvent()`
- **BootReceiver** - re-enables triggers on device boot
- **DebugLog** - in-app log buffer with UI display. Errors are prepended to the front of the log

### Key design decisions

- `nubia_game_scene=1` activates SAR sensors without full game mode
- `virtual_game_key` is NOT set - it launches Game Space which hijacks the home launcher
- `cc_game_mis_operate=0` prevents gesture blocking
- The Nubia game service intercepts F7/F8 and converts them to screen taps. Three capture methods exist to work around this: Shizuku (getevent), IME, and Accessibility Service
- Key injection (`input keyevent`) remaps F7/F8 to BUTTON_L1/R1 so apps like KeyMapper can see them
- InputService runs in a separate process (Shizuku's), so state changes must go through AIDL, not local fields

### AIDL interfaces

- `IInputService.aidl` - app -> Shizuku process (start/stop reading, set injection, set key mapping, set grab)
- `ITriggerCallback.aidl` - Shizuku process -> app (trigger events, raw key events, debug messages)

### Preferences

Stored in `RedTriggerPrefs` SharedPreferences:
- `auto_enable_on_boot` - re-enable triggers after reboot
- `capture_shizuku` - enable/disable Shizuku getevent reader
- `capture_inject` - enable/disable key injection
- `capture_ime` - enable/disable IME capture
- `capture_a11y` - enable/disable accessibility capture

## Build

```bash
cd /path/to/RedTrigger
ANDROID_HOME=$ANDROID_HOME \
JAVA_HOME=$JAVA_HOME \
./gradlew assembleDebug --no-daemon --no-configuration-cache
```

Or use justfile:
```bash
just build   # build APK
just ship    # build + copy to /mnt/storage/tmp
just version # show current version
```

APK output: `app/build/outputs/apk/debug/RedTrigger-v{version}.apk`

## Version bumping

**Always bump the version for every build, even for minor changes.** Use the justfile helpers:

```bash
just bump-patch  # 3.0.10 -> 3.0.11 (bug fixes, small changes)
just bump-minor  # 3.0.10 -> 3.1.0 (new features)
```

These update both `versionCode` and `versionName` in `app/build.gradle.kts`.

If editing manually:
- `versionCode` must always increment (integer, used by Android for update detection)
- `versionName` follows semver: `major.minor.patch`

## Device setup

1. Install APK on Red Magic phone
2. Install and start Shizuku app (wireless ADB or root)
3. Grant Shizuku permission to RedTrigger when prompted (WRITE_SECURE_SETTINGS is auto-granted via Shizuku)
4. Optional: enable IME in Settings > Languages & Input > On-screen keyboard
5. Optional: enable Accessibility Service in Settings > Accessibility

## SAR input devices

The shoulder triggers appear as:
- `/dev/input/event4` - typically left trigger (SAR0)
- `/dev/input/event5` - typically right trigger (SAR1)
- Device names contain `nubia_tgk_aw_sar`
- Left trigger: `KEY_F7` (Android keycode 137 / KEYCODE_F7)
- Right trigger: `KEY_F8` (Android keycode 138 / KEYCODE_F8)

## Common issues

- **Watchdog shows X on app open** - normal, it starts when you enable triggers (starts the foreground service)
- **Shizuku unavailable** - make sure Shizuku app is running. It needs to be restarted after every reboot unless using root mode
- **Triggers stop working after switching apps** - this is the core problem the app solves. Nubia's SystemMgr resets `nubia_game_scene` on every `notifyActivityResumed`. The watchdog ContentObserver catches and re-applies it
- **Injected keys not seen by KeyMapper** - check debug log for `[Inject] OK` vs `[Inject] ERROR`. `input keyevent` runs in Shizuku's process
- **GRAB_UNAVAILABLE in log** - expected. Android doesn't ship `evtest`, so exclusive device grab isn't available. Nubia game service still sees F7/F8

## Code conventions

- Kotlin throughout, Jetpack Compose for UI
- Material 3 theming
- All debug output goes through `DebugLog.log(tag, message)` - never plain `Log.d` for important state changes
- Errors containing "ERROR", "FAIL", or "Exception" are auto-prepended to the front of the log buffer
- Cross-process communication only via AIDL (don't set local fields expecting Shizuku process to see them)
- Commit messages: conventional commits (`feat:`, `fix:`, `refactor:`)
