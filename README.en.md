# Home Screen (ha-kiosk-browser)

[中文](README.md)

An Android full-screen web app for keeping a Home Assistant page on a tablet. Minimum Android 11 (minSdk 30). It was developed against a Lenovo Xiaoxin Pad 2022 (TB128FU, Android 12). Behavior on a real device still needs to be checked by you.

The interface and the Home Assistant entity names follow the system language. Simplified Chinese and English are included. Any other language falls back to Simplified Chinese.

## License

This project is dual-licensed:

- You may use it under the [GNU Affero General Public License v3.0](LICENSE) at no charge. If you distribute a modified version, or offer it as a network service, you must publish the corresponding source code under AGPL-3.0 as well.
- Commercial distribution or a network service that does not follow the AGPL-3.0 source obligations needs a separate [commercial license](COMMERCIAL-LICENSE.en.md). Use that complies with AGPL-3.0 does not require payment.

Copyright (C) 2026 [YyyQq-C](https://github.com/YyyQq-C).

If contributions from other people are accepted later, those contributors need to sign a contributor license agreement (CLA) before their code can also be included in the commercial license.

## Features

- Full-screen WebView, with the status and navigation bars hidden and the screen kept on. The first launch opens `http://homeassistant.local:8123/`. Change it to your Home Assistant address in settings.
- Swipe right from the left screen edge to open settings. The gesture must start within 24dp of the left edge, move at least 80dp to the right, and move farther horizontally than vertically. Tap outside the panel or swipe left to close it. A tap on the edge is passed through to the page.
- Idle reload: after the configured number of hours without a touch, the page cache is cleared and the page reloads. Login cookies and localStorage are kept.
- Failed loads retry after the configured number of seconds. A crashed web renderer is recreated.
- Watch brightness and standby brightness are separate and apply only to this app window. The system brightness is not changed.
- The front camera compares brightness on a low-resolution frame (about 320x240). Motion raises the brightness to the watch level. After nobody is detected for the configured number of seconds, it drops to standby. Frames are not saved or uploaded.
- MQTT (Home Assistant discovery) and a LAN admin API stay online in a foreground service.
- The app starts after boot or after it is updated.

Every value can be changed in settings. Enter 0 to turn that feature off, except watch brightness.

## Build

JDK 17 or newer (JDK 21 was used here) and Android SDK 36. `sdk.dir` in `local.properties` points at the local SDK. That file is not committed.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

Output: `app\build\outputs\apk\debug\app-debug.apk`.

If the build stops at `Downloading https://services.gradle.org/...`, `GRADLE_USER_HOME` is probably an empty temporary directory. Set `$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle"` to reuse the existing cache.

## Install and first launch

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The first launch asks for the camera permission, then prompts once to ignore battery optimizations. Without device owner, open settings, choose System, then "Allow opening at boot", and grant "Display over other apps" if the page should open after boot.

## Device owner (optional)

The app works without device owner. With it, the app can also use lock task mode, stay as the HOME launcher, receive the camera permission automatically, disable the keyguard, and reboot from MQTT or the LAN API.

The tablet must have no accounts (remove all of them under Settings, Accounts) and no other device owner.

```powershell
adb shell dpm set-device-owner cn.local.tabletkiosk/.KioskAdminReceiver
```

Notes:

- This app has no "remove device owner" button. Removing it requires a **factory reset**.
- System settings cannot be opened while lock task is on. Turn off Lock task in this app and save before opening them.
- Android has no device-owner API for the battery-optimization allowlist. Allow it once in the system prompt.

## MQTT

Turn MQTT on in settings and enter the broker host, port (default 1883), username, and password. Home Assistant shows one device. Its name follows the tablet language ("居家屏幕" or "Home Screen") and includes:

| Entity | Type |
| --- | --- |
| Screen, Presence, Charging | binary_sensor |
| Battery, Charge state, Brightness, Current URL | sensor |
| Set brightness (1-100) | number |
| Reload, Wake screen | button |
| Reboot tablet (device owner only) | button |

Topics (`<base>` defaults to `home_kiosk`; `<id>` is a random `kiosk_xxxxxxxx` stored on the device):

- State: `<base>/<id>/state` (retained; published on change and again at the state interval)
- Availability: `<base>/<id>/availability` (`online` / `offline`; the will message sets `offline` on an unexpected disconnect)
- Commands: `<base>/<id>/cmd/reload`, `wake`, `brightness`, `reboot`

When Home Assistant restarts (`online` on `homeassistant/status`), discovery is published again.

## LAN admin API

Turn it on in settings and set a password of at least 8 characters. The default port is 2323. Only LAN sources are accepted (private, link-local, and IPv6 ULA). Other addresses, including Tailscale, are rejected. The password must be sent in a header, not in the URL.

```bash
# Replace YOUR_PASSWORD with the password from settings
curl -H "Authorization: Bearer YOUR_PASSWORD" http://TABLET_IP:2323/status
curl -X POST -H "X-Kiosk-Password: YOUR_PASSWORD" http://TABLET_IP:2323/reload
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" http://TABLET_IP:2323/wake
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" "http://TABLET_IP:2323/brightness?value=60"
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" http://TABLET_IP:2323/reboot   # device owner only
```

| Path | Method | Meaning |
| --- | --- | --- |
| `/status` | GET | Status JSON |
| `/reload` | POST | Clear the cache and reload |
| `/wake` | POST | Turn the screen on and use watch brightness |
| `/brightness` | POST | Set watch brightness with `?value=60`, a body of `60`, or `{"brightness":60}` |
| `/reboot` | POST | Reboot the tablet; returns 409 when the app is not device owner |

A wrong password returns 401. A non-LAN source returns 403. The API is plain HTTP and should be enabled only on a trusted home LAN.
