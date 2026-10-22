<img src="docs/icon.png" width="112" alt="HAPanel">

# HAPanel

[中文](README.md)

Repository: [ha-kiosk-browser](https://github.com/YyyQq-C/ha-kiosk-browser)

## What it is

HAPanel is a full-screen Android web app that keeps a Home Assistant page on a tablet. The Chinese display name is HA 智能面板. The English display name is HAPanel.

The minimum system is Android 11 (minSdk 30). The current source version is `versionName` 1.0.2 and `versionCode` 38, as set in `app/build.gradle`. The first launch opens settings and does not load a page until the Home Assistant address is confirmed. Next to the home URL, the app can scan the LAN on port 8123 and fill the address from a result. The waiting page distinguishes no network, an address that is not open, and a certificate error, and it retries as soon as the network returns. Settings can check the latest GitHub release. The settings title is followed by how to open the panel again from the left edge. Day-to-day work is on `develop`. Public releases start at 1.0.0. The current release on `main` is 1.0.1.

## Who it is for

It is for a home tablet that should keep a Home Assistant page on screen. The app can run as a local full-screen browser, and it can also connect to Home Assistant on the same network through MQTT or the LAN API.

Development was checked against a Lenovo Xiaoxin Pad 2022 (TB128FU, Android 12). Behavior on other devices still needs to be verified on that hardware.

## Features

- Full-screen WebView, with the status bar and navigation bar hidden and the screen kept on. The first launch opens the placeholder `http://homeassistant.local:8123/`. Change it to your Home Assistant address in settings.
- Swipe right from the left screen edge to open settings. The gesture must start within 24dp of the left edge, move at least 80dp to the right, and move farther horizontally than vertically. Tap outside the panel or swipe left to close it. A tap on the edge is passed through to the page.
- Idle reload clears the page cache and reloads the page after the configured number of hours without a touch. Login cookies and localStorage are kept.
- A failed load retries after the configured number of seconds. A crashed web renderer is recreated.
- Watch brightness and standby brightness are separate and apply only to this app window. The system brightness is not changed. Day and night watch levels can follow the hour in the device local time zone. When standby brightness starts, a screensaver covers the page with the time, date, lunar date, and the weather already in Home Assistant. A tap only leaves the screensaver. During the night screen-off window, the screen turns off when nobody is present and settings are closed. It turns back on after that window. Screen off requires device owner.
- The front camera compares brightness on a low-resolution frame (about 320×240). Presence raises the brightness to the watch level. After nobody is detected for the configured number of seconds, brightness drops to standby. Frames are analyzed on the tablet only. They are not saved or uploaded, and there is no live camera view. Detection can continue while the screen is off and can turn it back on. Opening settings pauses the camera. The green dot is the system camera indicator.
- Opening a page does not change the saved home URL. Going home loads the saved home URL and clears the cache. Reloads keep cookies and localStorage. Pulling down from within about 96dp of the top of the screen moves the page down with the finger and shows a mark in the gap. Releasing after a long enough pull clears the cache and reloads the current page without changing the saved home URL. A short pull springs back. Pull-to-refresh is disabled while settings or the PIN gate is open. Pages lay out at the device width, so a fixed-size page can scale into the screen. If the page cannot be opened, a local waiting screen stays up and retries until the same page loads. Login fields and text boxes can still be edited. Elsewhere, pinch zoom, the long-press menu, and text selection are disabled. The home page can be chosen from Lovelace dashboards listed with a Home Assistant long-lived access token. Settings can be exported to JSON and imported again. The file contains passwords and the token. The WebView cancels SSL errors and does not ignore certificate checks.
- A screenshot is the current window as JPEG (long edge at most 1280, quality 70). It is published as raw bytes on the MQTT camera topic, not as base64. An on-screen notice is an in-app banner at the top for about 8 seconds. Speak uses Android `TextToSpeech`. Media volume is 0–100 and is written to the system media stream.
- Voice assist is off by default. After it is turned on and the microphone is allowed, the tablet detects the wake phrase okay nabu on device. The lower-right button also starts a sentence. Only that sentence is sent to Home Assistant Assist through `/api/websocket` on the home URL. The reply is downloaded with the long-lived access token and played locally. The screen wakes and standby waits during the conversation. Sensitivity 0 keeps tap-to-talk only.
- Status includes CPU, memory, low memory, storage, app memory, and battery temperature. CPU use needs two `/proc/stat` samples. The first read at startup is only a counter. A percentage appears on the second sample about 5 seconds later, then about once a minute.
- MQTT with Home Assistant discovery and the LAN admin API stay online in a foreground service.
- The app starts after boot and after it is updated.
- The settings title has one language button. It shows 中文 on the Chinese screen and EN on the English screen. A tap switches to the other language. Until a language is chosen, the app follows the system: English when the system language is English, otherwise Simplified Chinese. A choice is saved and updates only the open settings panel. It does not recreate the activity, reload the page, or refresh the foreground notification or MQTT discovery. The main screen and speech keep the language from this launch until the next start.

The app does not provide remote click injection, barcode scanning, printing, or multiple tabs. Enter 0 to turn off a timer. This applies to idle reload, failed-load retry, dim delay, the page watchdog, and the periodic state publish. Watch, day, and night brightness are 1–100.

## Install

The build needs JDK 17 or newer (JDK 21 was used here) and Android SDK 36. `sdk.dir` in `local.properties` points at the local SDK. That file is not committed.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

Output: `app\build\outputs\apk\debug\app-debug.apk`.

If the build stops at `Downloading https://services.gradle.org/...`, `GRADLE_USER_HOME` is usually an empty temporary directory. Set `$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle"` to reuse the existing cache.

Install the APK:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Development and releases

`main` keeps released versions only. Day-to-day work happens on `develop`. A release merges `develop` into `main`, tags `v` plus `versionName`, and attaches the signed release APK to a GitHub Release.

Local signing uses `signing/keystore.properties` and the keystore file named there. That directory is ignored and is not committed. Without it, `assembleRelease` produces an unsigned package. The installable package for a release is the APK on the GitHub Release.

Build a signed release APK without merging `main` or creating a tag:

```powershell
.\release.ps1
```

Use `.\release.ps1 -Online` when the dependency cache is incomplete. The script verifies the APK signature and copies the package to `dist/ha-kiosk-browser-<versionName>.apk`. `dist/` is not committed.

The first launch asks for the camera permission, then prompts once to ignore battery optimizations. Without device owner, open settings, choose System, then Allow opening at boot, and grant Display over other apps if the page should open after boot.

## Device owner

The app works without device owner. With device owner, it can also use lock task mode, stay as the HOME launcher, receive the camera permission automatically, disable the keyguard, turn the screen off with `DevicePolicyManager.lockNow()`, and reboot from MQTT or the LAN API. Lock task, the fixed HOME launcher, screen off, and remote reboot all require device owner.

The tablet must have no accounts. Remove all of them under Settings, Accounts. It must also have no other device owner.

```powershell
adb shell dpm set-device-owner cn.local.tabletkiosk/.KioskAdminReceiver
```

Notes:

- The device owner area has Release lock. It leaves lock task, unpins this home screen, and tries to clear device owner. If Android refuses to clear device owner, other apps can still open. Turning lock task on again pins this app as home.
- System settings cannot be opened while lock task is on. Turn off Lock task first. The switch saves immediately.
- Android has no device-owner API for the battery-optimization allowlist. Allow it once in the system prompt.

## Settings

Swipe in from the left edge to open settings. Most fields save when they lose focus. Switches save immediately. The MQTT card is written only when Save and sync is tapped, which then connects and publishes with the new settings. There is no global save button. An invalid value is marked and is not stored.

Percentages use a slider and save when it is released. Hours show only the current value. A tap opens a 0–23 picker, and the hour cannot be typed. Confirm saves the value. Cancel leaves it unchanged. Minutes stay in a text field, from 0 to 59. A settings card that has a title also has a question-mark help button.

The settings PIN is written only by Save password. It is not saved when the field loses focus. It protects only the local settings panel. It is not used for MQTT or the LAN API. It must be 4–8 digits. Leaving both new-password fields empty means there is no gate, so the left-edge swipe opens settings directly. Changing or clearing a saved PIN requires the current PIN first. A saved PIN is not filled back into the fields.

First-run defaults: home `http://homeassistant.local:8123/`; idle reload 12 hours (0–720); failed-load retry 30 seconds (0–3600); page watchdog 0 (off, 0–3600 seconds); daily reload off at 3:00; watch brightness 70, standby brightness 20, dim delay 60 seconds (0–86400); motion detection on, sensitivity 50 (0 turns detection off), presence hold 10 seconds (1–3600); brightness by time off, day start 7, night start 22, brightness 70 and 30; night screen off off, from 23 to 7 (the same start and end hour never turns the screen off); media volume 70; voice assist off, wake sensitivity 50; MQTT off, port 1883, base topic `home_kiosk`, discovery prefix `homeassistant`, state interval 60 seconds (0–86400); LAN API off, port 2323 (1024–65535); lock task on by default, with no effect until the app is device owner.

Schedules use the device local hour (`Calendar.getInstance()`).

## MQTT

This connection is to an MQTT broker, not to the Home Assistant web address. Put the web address in the home URL.

Enter the broker host, port (default 1883, allowed 1–65535), username, and password, turn MQTT on, then tap Save and sync. The settings are written and the tablet connects and publishes only then. The line under the button shows the address while connecting, then success or the failure reason. The base topic defaults to `home_kiosk`. The discovery prefix defaults to `homeassistant` and must match the MQTT integration in Home Assistant. The device name is HA 智能面板 in Chinese and HAPanel in English. After it connects, open Settings → Devices & services → MQTT in Home Assistant.

The official Home Assistant Mosquitto broker requires a login. Leave neither the username nor the password blank. Configure it in this order:

1. Install and start Mosquitto broker under Settings → Apps. Then confirm the discovered MQTT integration under Settings → Devices & services.
2. Open the Mosquitto broker Options page and add one Logins row with your own username and password. Do not use `homeassistant`; that account belongs to the integration. Leave “password already hashed” off (`false` on the form).
3. Scroll to the bottom of that page and tap Save. Add alone does not store the row. Switching to another page discards it. Saving restarts the app. The username should still be listed when you return.
4. On the tablet, set the host to the Home Assistant machine’s LAN address, the port to `1883`, and the same username and password. Turn MQTT on and tap Save and sync.

States keep updating after the broker log shows `New client connected` with that username. `not authorised` means the login was not saved, or the tablet username and password do not match Mosquitto.

Topics (`<base>` defaults to `home_kiosk`, `<prefix>` defaults to `homeassistant`, and `<id>` is `kiosk_` plus 8 hex digits, created once and stored on the device):

- State: `<base>/<id>/state` (retained; published when something changes. An interval of 0 publishes only on change. Above 0 it also publishes on that many seconds. The default is 60.)
- Availability: `<base>/<id>/availability` (`online` / `offline`; the will message sets `offline` on an unexpected disconnect)
- Screenshot: `<base>/<id>/camera/image` (retained raw JPEG bytes, QoS 0, not base64)
- Commands: `<base>/<id>/cmd/<name>`
- Discovery: `<prefix>/<component>/<id>/<object_id>/config`
- Home Assistant online: discovery is published again when `<prefix>/status` receives `online`

Switches accept `ON` / `OFF` / `true` / `false` / `1` / `0`. A button runs when any message arrives on its command topic. Discovery uses `PRESS` as `payload_press`. Empty or over-long text is dropped. A watch, day, or night brightness of 0 is stored as 1.

| Name | Type | object_id | Command topic |
| --- | --- | --- | --- |
| Screen | binary_sensor | `screen_on` | |
| Presence | binary_sensor | `presence` | |
| Charging | binary_sensor | `charging` | |
| Standby | binary_sensor | `standby` | |
| Low memory | binary_sensor | `memory_low` | |
| Battery | sensor | `battery` | |
| Charge state | sensor | `charge_state` | `charging`, `discharging`, `not_charging`, `full`, `unknown` |
| Brightness | sensor | `brightness` | |
| Current URL | sensor | `url` | |
| IP address | sensor | `ip` | |
| App version | sensor | `app_version` | |
| Device model | sensor | `model` | |
| Android version | sensor | `android_version` | |
| Wi-Fi name | sensor | `wifi_ssid` | Requires the local Wi-Fi name permission |
| Wi-Fi signal | sensor | `wifi_signal` | State field `wifi_rssi`, dBm |
| CPU | sensor | `cpu` | Percent; unknown until the second sample |
| Memory | sensor | `memory` | Used percent |
| Storage | sensor | `storage` | Data-partition used percent |
| App memory | sensor | `app_memory` | State field `app_memory_mb`, MB |
| Battery temperature | sensor | `temperature` | Celsius |
| Set brightness | number | `watch_brightness` | `brightness`, 1–100 |
| Standby brightness | number | `standby_brightness` | `standby_brightness`, 0–100 |
| Dim delay | number | `dim_delay` | `dim_delay`, 0–86400 seconds |
| Day brightness | number | `day_brightness` | `day_brightness`, 1–100 |
| Night brightness | number | `night_brightness` | `night_brightness`, 1–100 |
| Media volume | number | `volume` | `volume`, 0–100 |
| Daily hour | number | `daily_hour` | `daily_hour`, 0–23 |
| Daily minute | number | `daily_minute` | `daily_minute`, 0–59 |
| Screen-off start | number | `sleep_start` | `sleep_start`, 0–23 |
| Screen-off end | number | `sleep_end` | `sleep_end`, 0–23 |
| Page watchdog | number | `watchdog` | `watchdog`, 0–3600 seconds |
| Motion detection | switch | `motion` | `motion` |
| Lock task | switch | `lock_task` | `lock_task` |
| Brightness by time | switch | `schedule` | `schedule` |
| Daily reload | switch | `daily_reload` | `daily_reload` |
| Night screen off | switch | `sleep` | `sleep` |
| Reload | button | `reload` | `reload` |
| Wake screen | button | `wake` | `wake` |
| Screen off | button | `screen_off` | `screen_off`; no effect without device owner |
| Go home | button | `home` | `home` |
| Screenshot | button | `screenshot` | `screenshot` |
| Screenshot | camera | `screen` | Image topic listed above |
| Reboot tablet | button | `reboot` | `reboot`; omitted when the app is not device owner, and any old discovery config is removed |
| Open page | text | `url_command` | `url`; `http://` or `https://`, at most 2000 characters |
| On-screen notice | text | `notify` | `notify`; at most 200 characters |
| Speak | text | `tts` | `tts`; at most 200 characters |
| Play audio | text | `play` | `play`; `http://` or `https://`, at most 2000 characters |

## LAN

Turn the LAN admin API on in settings and set a password of at least 8 characters. The default port is 2323. Allowed ports are 1024–65535. The listener does not start when the password is shorter than 8 characters. Only this device, private addresses, link-local addresses, and IPv6 ULA are accepted. Other addresses, including Tailscale, are rejected. Send the password after `Authorization: Bearer`, or in the `X-Kiosk-Password` header. A password in the URL is not accepted.

```bash
# Replace YOUR_PASSWORD with the admin password from settings. Do not put a real password here.
curl -H "Authorization: Bearer YOUR_PASSWORD" http://TABLET_IP:2323/status
curl -X POST -H "X-Kiosk-Password: YOUR_PASSWORD" http://TABLET_IP:2323/reload
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" http://TABLET_IP:2323/wake
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" "http://TABLET_IP:2323/brightness?value=60"
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" http://TABLET_IP:2323/reboot
```

| Path | Method | Meaning |
| --- | --- | --- |
| `/status` | GET | Status JSON, plus `mqtt_connected` |
| `/reload` | POST | Clear the cache and reload the current page |
| `/wake` | POST | Turn the screen on and use watch brightness |
| `/home` | POST | Clear the cache and open the saved home URL |
| `/url` | POST | Open a page without changing home. The body or `?value=` must start with `http://` or `https://` |
| `/brightness` | POST | Set watch brightness with `?value=60`, a number in the body, or `{"brightness":60}`. 0 is stored as 1 |
| `/standby-brightness` | POST | 0–100 |
| `/dim-delay` | POST | 0–86400 |
| `/day-brightness` | POST | 1–100 |
| `/night-brightness` | POST | 1–100 |
| `/motion` | POST | Switch. Body or `value` is on/off or 1/0 |
| `/lock` | POST | Lock-task switch |
| `/schedule` | POST | Brightness-by-time switch |
| `/screen-off` | POST | Turn the screen off; returns 409 without device owner |
| `/screenshot` | POST | Capture the window and publish it on the MQTT camera topic |
| `/notify` | POST | On-screen notice, body at most 200 characters |
| `/say` | POST | Speak, body at most 200 characters |
| `/volume` | POST | Media volume 0–100 |
| `/play` | POST | Play an `http://` or `https://` audio URL |
| `/daily` | POST | Daily-reload switch |
| `/daily-hour` | POST | 0–23 |
| `/daily-minute` | POST | 0–59 |
| `/sleep` | POST | Night screen-off switch |
| `/sleep-start` | POST | 0–23 |
| `/sleep-end` | POST | 0–23 |
| `/watchdog` | POST | 0–3600 |
| `/reboot` | POST | Reboot after the response is sent. Returns 409 without device owner |

Integers and text can use the `value` query parameter when the body is empty. A wrong password returns 401. A non-LAN source returns 403. An unknown path returns 404. The wrong method returns 405. A bad value returns 400. The API is plain HTTP and should be enabled only on a trusted home LAN.

## License and contact

This project is dual-licensed:

- You may use it under the [GNU Affero General Public License v3.0](LICENSE) at no charge. If you distribute a modified version, or offer it as a network service, you must publish the corresponding source code under AGPL-3.0 as well.
- Commercial distribution or a network service that does not follow the AGPL-3.0 source obligations needs a separate [commercial license](COMMERCIAL-LICENSE.en.md). Use that complies with AGPL-3.0 does not require payment.

Copyright (C) 2026 [YyyQq-C](https://github.com/YyyQq-C).

Contact: yongqc.o@gmail.com

If contributions from other people are accepted later, those contributors need to sign a contributor license agreement (CLA) before their code can also be included in the commercial license.

## Web file uploads (1.0.3)

WebView now opens Android's document picker for HTML file inputs, supports single or multiple selection, and completes cancellation callbacks. MIME filters normalize image extensions. Only selected `content://` results are returned; broad storage permissions are not added, and `file://` access stays disabled. File picking pauses automatic idle reload, watchdog and app sleep checks. In lock task mode, the app asks the user to disable locking first instead of silently doing nothing.

## Voice and tablet interaction updates in v1.0.3

- Adds a listening panel with cyan-to-violet waves driven by local microphone volume, plus thinking and speaking states.
- Tap the microphone to start and tap again, or close the panel, to cancel. Stale network and playback callbacks cannot revive a cancelled session.
- Long-press and drag the microphone to reposition it. The position survives app restarts. The icon shares the panel palette and hides during the screensaver, without disabling wake-word listening.
- Adds a configurable wait-for-speech timeout, defaulting to 5 seconds (1–120 seconds). Once speech starts, the existing pause detection remains in use.
- Uses the HA WebSocket dashboard-list command and distinguishes authentication, permission, URL and connection failures.
- Retires superseded dashboard Activity instances so background timers cannot overwrite the active window brightness status.
- Falls back to Android's device CPU interface when `/proc/stat` is inaccessible. The fallback requires device-owner privileges and vendor support; unavailable device CPU remains unknown.

Validation: 99 unit tests and debug/release builds passed. Full Lint still reports 11 existing errors. Tap-to-cancel, the panel animation, timeout, icon dragging/restoration and screensaver visibility were checked on a Lenovo TB128FU. Wake-word recognition and a complete spoken conversation still need testing in the target environment. Release-signed APKs cannot overwrite an installation signed with the Android Debug certificate; do not uninstall or clear user data just to bypass this mismatch.
