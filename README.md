# Mi Fitness Enhanced (`mifitness-enhanced`)

An Xposed/LSPosed/Vector module for Xiaomi Mi Fitness (`com.xiaomi.wearable`) that:

1. **Blocks telemetry** — neutralizes Xiaomi's `OneTrack` analytics framework and third-party trackers (formerly `mifitness-notrack`).
2. **Rings the band for Google Clock alarms and finished timers** — Mi Fitness only forwards phone alarms from Xiaomi's own clock. This bridges Google Clock (`com.google.android.deskclock`) into Mi Fitness's built-in phone-alarm feature, including snooze/dismiss in both directions.
3. **Skips silent notifications** — notifications Android shows as *Silent* (channel importance Low/Min) are not mirrored to the band.

## Google Clock alarm bridge
Mi Fitness has a native "phone alarm alerts on band" feature (`AlarmConsoleService`), but it is only triggered when Xiaomi's clock binds to it. Google Clock never does, and Mi Fitness drops ongoing notifications such as a ringing alarm.

The module watches Mi Fitness's notification listener for Google Clock's full-screen alarm notification and calls `AlarmConsoleService` directly:
* Alarm rings on phone → band rings with the alarm label.
* Snooze/dismiss on the band → the matching action on Google Clock's notification is fired (for timers, snooze = "Add 1 min").
* Stop/snooze on the phone (notification removed) → band stops ringing.

Requires the *Alarm → phone alarm alert* switch to be enabled in Mi Fitness's device settings (it is stored as `KEY_SYNC_MIUI_ALARM`).

## Telemetry blocking

### Why This Exists
While hosts-based blockers (e.g. AdAway, NextDNS, Re-Malwack) prevent telemetry packets from leaving the device over the wire, the app continues to run heavy tracking routines in the background:
* Compiling hardware identifiers and app usage patterns.
* Encrypting payloads with AES-ECB.
* Storing events in local SQLite databases (`onetrack.db`).
* Waking CPU locks to retry failed network connections.

`mifitness-enhanced` stops tracking **at the code level**, saving CPU and battery while keeping all watch-syncing, health monitoring, and notification mirroring functionality 100% operational.

### Update-Resilient Architecture
The telemetry hooks do not rely on fragile, obfuscated internal methods that break with every app update:
1. **Public SDK Kill Switch:** On initialization, calls Xiaomi's built-in `OneTrack.setDisable(true)` switch and forces `OneTrack.isDisable()` to return `true`.
2. **In-Process Drop:** Replaces `OneTrack.track(...)` and `EventManager` serialization methods with `DO_NOTHING`.
3. **Android Framework Choke Point (100% Version-Agnostic):** Hooks `android.database.sqlite.SQLiteDatabase.insertWithOnConflict` to drop any inserts into `events` or `monitor` tables. Because this is an Android OS framework class, app updates cannot rename or bypass it.
4. **Network Host Choke Point:** Hooks `java.net.InetAddress.getAllByName` to immediately throw `UnknownHostException` on Xiaomi tracking domains, aborting connection threads before socket creation.
5. **Third-Party Telemetry:** Stubs Google Firebase Analytics and Facebook AppEventsLogger.

## Installation
1. Download the latest APK from the [Releases](https://github.com/bgwastu/mifitness-enhanced/releases) or GitHub Actions build artifacts.
2. Install the APK on your device:
   ```bash
   adb install -r mifitness-enhanced.apk
   ```
3. Open your Xposed manager (Vector, LSPosed, etc.).
4. Enable the module. The scope is automatically set to `com.xiaomi.wearable`. If you used `mifitness-notrack`, disable or uninstall it — this module replaces it.
5. Force-stop Mi Fitness (`am force-stop com.xiaomi.wearable`) and relaunch.

## Scope
* Target package: `com.xiaomi.wearable` (Mi Fitness / Xiaomi Wear)

## License
MIT
