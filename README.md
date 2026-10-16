<img src="docs/icon.png" width="112" alt="HA 智能面板">

# HA 智能面板

[English](README.en.md)

仓库：[ha-kiosk-browser](https://github.com/YyyQq-C/ha-kiosk-browser)

## 项目是什么

HA 智能面板是面向 Android 平板的全屏网页应用，用于常驻显示 Home Assistant 页面。中文界面显示名为「HA 智能面板」，英文界面显示名为 HAPanel。

最低系统为 Android 11（minSdk 30）。当前源码版本为 `versionName` 1.0.29、`versionCode` 30，以 `app/build.gradle` 为准。

## 适合谁

适合需要在平板上固定显示 Home Assistant 的家庭场景。应用可以只在本机全屏浏览，也可以通过 MQTT 或局域网接口接入同一网络中的 Home Assistant。

开发时对照的设备是联想小新 Pad 2022（TB128FU，Android 12）。其他设备上的实际效果需要自行验证。

## 功能

- 全屏 WebView，隐藏状态栏和导航栏，并保持屏幕常亮。首次启动打开占位地址 `http://homeassistant.local:8123/`，需在设置中改为自己的 Home Assistant 地址。
- 从屏幕左边缘向右滑出设置。起手须在左侧 24dp 内，向右至少 80dp，且横向位移大于纵向。点按设置外区域或向左滑动可关闭。边缘上的轻点会原样交给网页。
- 空闲刷新：无触摸达到设定小时数后清除网页缓存并重新加载，保留登录 Cookie 和 localStorage。
- 加载失败时按设定秒数重试。网页渲染进程崩溃时自动重建。
- 观看亮度与待机亮度分开设置，只作用于本应用窗口，不修改系统亮度。可按设备本地时区的小时切换白天与夜间观看亮度。夜间熄屏时段内，无人且设置已关闭时熄屏；离开该时段后重新点亮。熄屏依赖设备所有者。
- 前置摄像头以低分辨率（约 320×240）做亮度差检测。有人时升至观看亮度；无人达到设定秒数后降至待机亮度。画面只在本机分析，不保存、不上传，也没有实时摄像头画面。熄屏后仍可检测，有人时再点亮。打开设置会暂停摄像头。左上角绿色点是系统摄像头指示。
- 打开指定页面不修改已保存的首页。回到首页会加载已保存首页并清除缓存。刷新保留 Cookie 和 localStorage。禁止双指缩放、长按菜单和文字选择。WebView 收到 SSL 错误时拒绝加载，不忽略证书校验。
- 截屏将当前窗口压成 JPEG（长边不超过 1280，质量 70），以原始字节发布到 MQTT camera 主题，不是 base64。画面通知是应用内顶部横幅，约 8 秒后消失。语音使用 Android `TextToSpeech`。媒体音量范围为 0–100，写入系统媒体音量。
- 状态包含 CPU、内存、内存不足、存储、应用内存和电池温度。CPU 占用需要两次 `/proc/stat` 采样。启动后第一次只有计数，约 5 秒后的第二次采样才有百分比，之后约每分钟采样一次。
- MQTT（Home Assistant 自动发现）和局域网管理接口由前台服务保持在线。
- 开机或应用更新后自动启动。
- 设置标题旁只有一个语言按钮。中文界面显示「中文」，英文界面显示「EN」，点一下切到另一种语言。尚未选择时跟随系统：系统语言为英语时使用英语，否则使用简体中文。选定后写入配置，只切换已经打开的设置面板文案。不重建界面，不重新加载网页，也不刷新前台通知或 MQTT 发现。主界面和语音仍用本次启动时的语言，下次启动后再使用已保存的选择。

本应用不提供远程点击注入、条码、打印或多标签页。计时类数值填 0 表示关闭，包括空闲刷新、失败重试、无人后降亮度、页面看门狗和状态定时上报。观看亮度、白天亮度和夜间亮度的范围是 1–100。

## 安装

构建需要 JDK 17 或更高版本（本机使用 JDK 21）和 Android SDK 36。`local.properties` 中的 `sdk.dir` 指向本机 SDK，该文件不提交。

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

输出路径：`app\build\outputs\apk\debug\app-debug.apk`。

若构建停在 `Downloading https://services.gradle.org/...`，通常是 `GRADLE_USER_HOME` 指向了空的临时目录。可先设置 `$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle"`，以复用已有缓存。

安装命令：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 开发与发布

`main` 只保留已经发布的版本。日常开发在 `develop`。发布时把 `develop` 合并到 `main`，打上 `v` 加 `versionName` 的 tag，并在 GitHub Release 附上已签名的 release APK。

本机签名放在 `signing/keystore.properties` 和对应的 keystore 文件中，目录已被忽略，不会提交。没有这份文件时，`assembleRelease` 生成未签名包。已发布的安装包以 GitHub Release 上的 APK 为准。

首次打开会请求摄像头权限，然后提示一次忽略电池优化。未成为设备所有者时，如需开机自动打开界面，请在设置的「系统」分组中点击「允许开机自动打开」，并在系统页面授予「显示在其他应用上层」。

## 设备所有者

未设置为设备所有者时，应用仍可正常使用。设置后额外获得：锁定任务模式、固定为主屏幕（HOME）、自动授予摄像头权限、禁用锁屏、使用 `DevicePolicyManager.lockNow()` 熄屏，以及通过 MQTT 和局域网接口远程重启。锁定任务、固定 HOME、熄屏和远程重启都依赖设备所有者。

前提：平板上不能登录任何账号（设置 → 账号中全部移除），且没有其他设备所有者。

```powershell
adb shell dpm set-device-owner cn.local.tabletkiosk/.KioskAdminReceiver
```

注意：

- 本应用没有「解除设备所有者」按钮。解除只能**恢复出厂设置**。
- 锁定模式开启时无法打开系统设置。需要进入系统设置时，先关闭「锁定模式」。该开关会立即保存。
- 系统没有为电池优化白名单提供设备所有者接口，仍需在提示中手动允许一次。

## 设置

从左边缘滑出设置。大部分字段在离开输入框时保存，开关拨动后立即保存。没有全局保存按钮。无效数值会被标出，且不会写入。

百分比使用拖动条，松手后保存。小时只显示当前值，点击后弹出 0–23 的选择器，不能手工输入。确认后保存，取消则不修改。分钟仍为输入框，范围 0–59。带标题的设置卡片右上角有问号，点击后显示该卡片的说明。

设置密码须单独点击「保存密码」才会写入，不会在失焦时保存。它只保护本机设置面板，不用于 MQTT 或局域网接口。密码必须是 4–8 位数字。两个新密码框都留空表示不设门禁，左边缘滑出后直接进入设置。已经设置过密码时，修改或清除都要先验证旧密码。已保存的密码不会回填到输入框。

首次启动的默认值：首页 `http://homeassistant.local:8123/`；空闲刷新 12 小时（0–720）；失败重试 30 秒（0–3600）；页面看门狗 0（关闭，0–3600 秒）；每天固定刷新关闭，时刻 3:00；观看亮度 70，待机亮度 20，无人后降亮度 60 秒（0–86400）；人体检测开启，灵敏度 50（0 表示关闭），有人状态保持 10 秒（1–3600）；按时间切换亮度关闭，白天 7 点、夜间 22 点，亮度 70 和 30；夜间熄屏关闭，23 点到 7 点（开始和结束为同一小时时不熄屏）；媒体音量 70；MQTT 关闭，端口 1883，基础主题 `home_kiosk`，发现前缀 `homeassistant`，上报间隔 60 秒（0–86400）；局域网接口关闭，端口 2323（1024–65535）；锁定模式默认开启，但不是设备所有者时不会生效。

时间表使用设备本地时区的小时（`Calendar.getInstance()`）。

## MQTT

此处连接的是 MQTT 代理，不是 Home Assistant 网页地址。网页地址填写在设置的首页网址中。

在设置中开启 MQTT，并填写代理主机、端口（默认 1883，允许 1–65535）、账号和密码。代理未启用认证时，账号和密码留空。基础主题默认 `home_kiosk`。发现前缀默认 `homeassistant`，须与 Home Assistant MQTT 集成的发现前缀一致。中文界面下设备名是「HA 智能面板」，英文界面下是 HAPanel。

主题（`<base>` 默认 `home_kiosk`，`<prefix>` 默认 `homeassistant`，`<id>` 为本机首次生成并保存的 `kiosk_` 加 8 位十六进制）：

- 状态：`<base>/<id>/state`（保留消息。状态变化时立即发布。上报间隔为 0 时只在变化时发布；大于 0 时再按该秒数定时发布，默认 60）
- 在线：`<base>/<id>/availability`（`online` / `offline`。断线时由遗嘱消息置为 `offline`）
- 截屏：`<base>/<id>/camera/image`（保留的 JPEG 原始字节，QoS 0，不是 base64）
- 命令：`<base>/<id>/cmd/<name>`
- 发现：`<prefix>/<component>/<id>/<object_id>/config`
- Home Assistant 上线：`<prefix>/status` 收到 `online` 时重新发布注册信息

开关接受 `ON` / `OFF` / `true` / `false` / `1` / `0`。按钮在对应主题收到消息时触发。发现配置中的 `payload_press` 为 `PRESS`。文本命令内容为空或超长时会被丢弃。观看亮度、白天亮度、夜间亮度收到 0 时按 1 保存。

| 显示名 | 类型 | object_id | 命令主题 |
| --- | --- | --- | --- |
| 亮屏 | binary_sensor | `screen_on` | |
| 有人靠近 | binary_sensor | `presence` | |
| 充电中 | binary_sensor | `charging` | |
| 当前待机 | binary_sensor | `standby` | |
| 内存不足 | binary_sensor | `memory_low` | |
| 电量 | sensor | `battery` | |
| 充电状态 | sensor | `charge_state` | 取值 `charging`、`discharging`、`not_charging`、`full`、`unknown` |
| 当前亮度 | sensor | `brightness` | |
| 当前网址 | sensor | `url` | |
| 本机地址 | sensor | `ip` | |
| 应用版本 | sensor | `app_version` | |
| 设备型号 | sensor | `model` | |
| 系统版本 | sensor | `android_version` | |
| Wi-Fi 名称 | sensor | `wifi_ssid` | 需要本机允许读取 Wi-Fi 名称 |
| Wi-Fi 信号 | sensor | `wifi_signal` | 状态字段 `wifi_rssi`，单位 dBm |
| CPU | sensor | `cpu` | 百分比；第一次采样前为未知 |
| 内存 | sensor | `memory` | 已用百分比 |
| 存储 | sensor | `storage` | 数据分区已用百分比 |
| 应用内存 | sensor | `app_memory` | 状态字段 `app_memory_mb`，单位 MB |
| 电池温度 | sensor | `temperature` | 摄氏度 |
| 设置亮度 | number | `watch_brightness` | `brightness`，1–100 |
| 待机亮度 | number | `standby_brightness` | `standby_brightness`，0–100 |
| 无人后降亮度 | number | `dim_delay` | `dim_delay`，0–86400 秒 |
| 白天亮度 | number | `day_brightness` | `day_brightness`，1–100 |
| 夜间亮度 | number | `night_brightness` | `night_brightness`，1–100 |
| 媒体音量 | number | `volume` | `volume`，0–100 |
| 每天刷新的小时 | number | `daily_hour` | `daily_hour`，0–23 |
| 每天刷新的分钟 | number | `daily_minute` | `daily_minute`，0–59 |
| 熄屏开始 | number | `sleep_start` | `sleep_start`，0–23 |
| 熄屏结束 | number | `sleep_end` | `sleep_end`，0–23 |
| 页面看门狗 | number | `watchdog` | `watchdog`，0–3600 秒 |
| 人体检测 | switch | `motion` | `motion` |
| 锁定模式 | switch | `lock_task` | `lock_task` |
| 按时间切换亮度 | switch | `schedule` | `schedule` |
| 每天刷新 | switch | `daily_reload` | `daily_reload` |
| 夜间熄屏 | switch | `sleep` | `sleep` |
| 立即刷新 | button | `reload` | `reload` |
| 唤醒屏幕 | button | `wake` | `wake` |
| 熄屏 | button | `screen_off` | `screen_off`；不是设备所有者时不会熄灭 |
| 回到首页 | button | `home` | `home` |
| 屏幕截图 | button | `screenshot` | `screenshot` |
| 屏幕截图 | camera | `screen` | 图片主题见上面的 camera |
| 重启平板 | button | `reboot` | `reboot`；不是设备所有者时不注册，并删除已有发现配置 |
| 打开页面 | text | `url_command` | `url`；`http://` 或 `https://`，最长 2000 |
| 画面通知 | text | `notify` | `notify`；最长 200 |
| 语音播报 | text | `tts` | `tts`；最长 200 |
| 播放音频 | text | `play` | `play`；`http://` 或 `https://`，最长 2000 |

## 局域网

在设置中开启局域网管理接口，并设置至少 8 位密码。默认端口 2323，可改为 1024–65535。密码短于 8 位时，监听不会启动。只接受本机、私有地址、链路本地地址和 IPv6 ULA。Tailscale 等其他地址会被拒绝。密码放在请求头 `Authorization: Bearer` 之后，也可以使用 `X-Kiosk-Password`。不接受 URL 参数中的密码。

```bash
# 用法示例。YOUR_PASSWORD 换成设置里的管理密码，不要写成真实密码
curl -H "Authorization: Bearer YOUR_PASSWORD" http://平板IP:2323/status
curl -X POST -H "X-Kiosk-Password: YOUR_PASSWORD" http://平板IP:2323/reload
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" http://平板IP:2323/wake
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" "http://平板IP:2323/brightness?value=60"
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" http://平板IP:2323/reboot
```

| 路径 | 方法 | 说明 |
| --- | --- | --- |
| `/status` | GET | 状态 JSON，比 MQTT 状态多一个 `mqtt_connected` |
| `/reload` | POST | 清缓存并重载当前页 |
| `/wake` | POST | 点亮屏幕并升到观看亮度 |
| `/home` | POST | 清缓存并打开已保存首页 |
| `/url` | POST | 打开页面，不改首页；正文或 `?value=` 必须是 `http://` 或 `https://` |
| `/brightness` | POST | 设置观看亮度；`?value=60`、正文里的数字，或 `{"brightness":60}`。0 按 1 保存 |
| `/standby-brightness` | POST | 0–100 |
| `/dim-delay` | POST | 0–86400 |
| `/day-brightness` | POST | 1–100 |
| `/night-brightness` | POST | 1–100 |
| `/motion` | POST | 开关，正文或 `value` 为 on/off、1/0 |
| `/lock` | POST | 锁定模式开关 |
| `/schedule` | POST | 按时间切换亮度开关 |
| `/screen-off` | POST | 熄屏；不是设备所有者时返回 409 |
| `/screenshot` | POST | 截取窗口并发布到 MQTT camera 主题 |
| `/notify` | POST | 画面通知，正文最长 200 |
| `/say` | POST | 语音播报，正文最长 200 |
| `/volume` | POST | 媒体音量 0–100 |
| `/play` | POST | 播放 `http://` 或 `https://` 音频 |
| `/daily` | POST | 每天刷新开关 |
| `/daily-hour` | POST | 0–23 |
| `/daily-minute` | POST | 0–59 |
| `/sleep` | POST | 夜间熄屏开关 |
| `/sleep-start` | POST | 0–23 |
| `/sleep-end` | POST | 0–23 |
| `/watchdog` | POST | 0–3600 |
| `/reboot` | POST | 重启平板；先返回响应再重启。不是设备所有者时返回 409 |

整数和文本没有正文时，可以使用查询参数 `value`。密码错误返回 401。非局域网来源返回 403。路径不存在返回 404。方法不对返回 405。参数不对返回 400。接口使用明文 HTTP，只应在可信的家庭局域网中开启。

## 许可与联系

本项目采用双许可：

- 可以按 [GNU Affero General Public License v3.0](LICENSE) 免费使用。修改后再分发，或通过网络提供服务，必须同样以 AGPL-3.0 公开对应源代码。
- 不想遵守 AGPL-3.0 开源义务的商业分发或网络服务，需要另行取得 [商业授权](COMMERCIAL-LICENSE.md)。遵守 AGPL-3.0 的使用不需要付费。

版权所有 (C) 2026 [YyyQq-C](https://github.com/YyyQq-C)。

联系：yongqc.o@gmail.com

如果以后接受他人贡献的代码，需要贡献者另行签署贡献者许可协议（CLA），这些代码才能同时纳入商业授权。
