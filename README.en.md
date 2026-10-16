<img src="docs/icon.png" width="112" alt="HAPanel">

# HAPanel

[中文](README.md)

Repository: [ha-kiosk-browser](https://github.com/YyyQq-C/ha-kiosk-browser)

## What it is

HAPanel is a full-screen Android web app that keeps a Home Assistant page on a tablet. The Chinese display name is HA 智能面板. The English display name is HAPanel.

The minimum system is Android 11 (minSdk 30). The current source version is `versionName` 1.0.25 and `versionCode` 26, as set in `app/build.gradle`.

## Who it is for

It is for a home tablet that should keep a Home Assistant page on screen. The app can run as a local full-screen browser, and it can also connect to Home Assistant on the same network through MQTT or the LAN API.

Development was checked against a Lenovo Xiaoxin Pad 2022 (TB128FU, Android 12). Behavior on other devices still needs to be verified on that hardware.

## Features

- Full-screen WebView, with the status bar and navigation bar hidden and the screen kept on. The first launch opens the placeholder `http://homeassistant.local:8123/`. Change it to your Home Assistant address in settings.
- Swipe right from the left screen edge to open settings. The gesture must start within 24dp of the left edge, move at least 80dp to the right, and move farther horizontally than vertically. Tap outside the panel or swipe left to close it. A tap on the edge is passed through to the page.
- Idle reload clears the page cache and reloads the page after the configured number of hours without a touch. Login cookies and localStorage are kept.
- A failed load retries after the configured number of seconds. A crashed web renderer is recreated.
- Watch brightness and standby brightness are separate and apply only to this app window. The system brightness is not changed. Day and night watch levels can follow the hour in the device local time zone. During the night screen-off window, the screen turns off when nobody is present and settings are closed. It turns back on after that window. Screen off requires device owner.
- The front camera compares brightness on a low-resolution frame (about 320×240). Presence raises the brightness to the watch level. After nobody is detected for the configured number of seconds, brightness drops to standby. Frames are analyzed on the tablet only. They are not saved or uploaded, and there is no live camera view. Detection can continue while the screen is off and can turn it back on. Opening settings pauses the camera. The green dot is the system camera indicator.
- Opening a page does not change the saved home URL. Going home loads the saved home URL and clears the cache. Reloads keep cookies and localStorage. Pinch zoom, the long-press menu, and text selection are disabled. The WebView cancels SSL errors and does not ignore certificate checks.
- A screenshot is the current window as JPEG (long edge at most 1280, quality 70). It is published as raw bytes on the MQTT camera topic, not as base64. An on-screen notice is an in-app banner at the top for about 8 seconds. Speech uses Android `TextToSpeech`. Media volume is 0–100 and is written to the system media stream.
- Status includes CPU, memory, low memory, storage, app memory, and battery temperature. CPU use needs two `/proc/stat` samples. The first read at startup is only a counter. A percentage appears on the second sample about 5 seconds later, then about once a minute.
- MQTT with Home Assistant discovery and the LAN admin API stay online in a foreground service.
- The app starts after boot and after it is updated.
- The settings title offers 中文 / EN. Until a language is chosen, the app follows the system: English when the system language is English, otherwise Simplified Chinese. After a choice, it stays on Simplified Chinese or English and refreshes the MQTT entity names.

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

The first launch asks for the camera permission, then prompts once to ignore battery optimizations. Without device owner, open settings, choose System, then Allow opening at boot, and grant Display over other apps if the page should open after boot.

## Device owner

The app works without device owner. With device owner, it can also use lock task mode, stay as the HOME launcher, receive the camera permission automatically, disable the keyguard, turn the screen off with `DevicePolicyManager.lockNow()`, and reboot from MQTT or the LAN API. Lock task, the fixed HOME launcher, screen off, and remote reboot all require device owner.

The tablet must have no accounts. Remove all of them under Settings, Accounts. It must also have no other device owner.

```powershell
adb shell dpm set-device-owner cn.local.tabletkiosk/.KioskAdminReceiver
```

Notes:

- This app has no button to remove device owner. Removing it requires a **factory reset**.
- System settings cannot be opened while lock task is on. Turn off Lock task first. The switch saves immediately.
- Android has no device-owner API for the battery-optimization allowlist. Allow it once in the system prompt.

## Settings

Swipe in from the left edge to open settings. Most fields save when they lose focus. Switches save immediately. There is no global save button. An invalid value is marked and is not stored.

Percentages use a slider and save when it is released. Hours show only the current value. A tap opens a 0–23 picker, and the hour cannot be typed. Confirm saves the value. Cancel leaves it unchanged. Minutes stay in a text field, from 0 to 59. A settings card that has a title also has a question-mark help button.

The settings PIN is written only by Save password. It is not saved when the field loses focus. It protects only the local settings panel. It is not used for MQTT or the LAN API. It must be 4–8 digits. Leaving both new-password fields empty means there is no gate, so the left-edge swipe opens settings directly. Changing or clearing a saved PIN requires the current PIN first. A saved PIN is not filled back into the fields.

First-run defaults: home `http://homeassistant.local:8123/`; idle reload 12 hours (0–720); failed-load retry 30 seconds (0–3600); page watchdog 0 (off, 0–3600 seconds); daily reload off at 3:00; watch brightness 70, standby brightness 20, dim delay 60 seconds (0–86400); motion detection on, sensitivity 50 (0 turns detection off), presence hold 10 seconds (1–3600); brightness by time off, day start 7, night start 22, brightness 70 and 30; night screen off off, from 23 to 7 (the same start and end hour never turns the screen off); media volume 70; MQTT off, port 1883, base topic `home_kiosk`, discovery prefix `homeassistant`, state interval 60 seconds (0–86400); LAN API off, port 2323 (1024–65535); lock task on by default, with no effect until the app is device owner.

Schedules use the device local hour (`Calendar.getInstance()`).

## MQTT

This connection is to an MQTT broker, not to the Home Assistant web address. Put the web address in the home URL.

Turn MQTT on in settings and enter the broker host, port (default 1883, allowed 1–65535), username, and password. Leave both blank when the broker has no authentication. The base topic defaults to `home_kiosk`. The discovery prefix defaults to `homeassistant` and must match the MQTT integration in Home Assistant. The device name is HA 智能面板 in Chinese and HAPanel in English.

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
