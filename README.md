# 居家屏幕（ha-kiosk-browser）

[English](README.en.md)

Android 全屏网页展示应用，用于在平板上常驻显示 Home Assistant 页面。最低 Android 11（minSdk 30）。开发时对照的设备是联想小新 Pad 2022（TB128FU，Android 12），真机效果仍需自行验证。

## 许可

本项目采用双许可：

- 可以按 [GNU Affero General Public License v3.0](LICENSE) 免费使用。修改后再分发，或通过网络提供服务，必须同样以 AGPL-3.0 公开对应源代码。
- 不想遵守 AGPL-3.0 开源义务的商业分发或网络服务，需要另行取得 [商业授权](COMMERCIAL-LICENSE.md)。遵守 AGPL-3.0 的使用不需要付费。

版权所有 (C) 2026 [YyyQq-C](https://github.com/YyyQq-C)。

如果以后接受他人贡献的代码，需要贡献者另行签署贡献者许可协议（CLA），这些代码才能同时纳入商业授权。

## 功能

- 全屏 WebView，隐藏状态栏和导航栏，保持屏幕常亮。首次启动打开占位地址 `http://homeassistant.local:8123/`，请在设置里改成自己的 Home Assistant 地址。
- 从屏幕左边缘向右滑出设置（起手在左侧 24dp 内，向右至少 80dp，且横向位移大于纵向）；点设置外区域或向左滑关闭。边缘的轻点会原样交还网页。
- 空闲刷新：无触摸达到设定小时数后清除网页缓存并重载，保留登录 Cookie 和 localStorage。
- 加载失败按设定秒数重试；网页渲染进程崩溃时自动重建。
- 观看亮度与待机亮度分开设置，只作用于本应用窗口，不改系统亮度。
- 前置摄像头低分辨率（约 320x240）亮度差检测：有人时升到观看亮度，无人达到设定秒数后降到待机亮度。画面不保存、不上传。
- MQTT（HA 自动发现）和局域网管理接口，由前台服务保持在线。
- 开机或应用更新后自动启动。
- 界面和 Home Assistant 实体名称跟随系统语言，目前提供简体中文和英语。未翻译的语言使用简体中文。

所有参数在设置中可改，数值填 0 表示关闭对应功能（观看亮度除外）。

## 构建

需要 JDK 17 或更高版本（本机使用 JDK 21）和 Android SDK 36。`local.properties` 中的 `sdk.dir` 指向本机 SDK，该文件不提交。

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

输出：`app\build\outputs\apk\debug\app-debug.apk`。

如果构建停在 `Downloading https://services.gradle.org/...`，多半是 `GRADLE_USER_HOME` 被指向了空的临时目录，可先设置 `$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle"` 复用已有缓存。

## 安装与首次运行

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

首次打开会请求摄像头权限，然后提示一次“忽略电池优化”。不是设备所有者时，如需开机自动打开界面，请在设置“系统”分组中点击“允许开机自动打开”，在系统页面授予“显示在其他应用上层”。

## 设备所有者（可选）

不设置也能正常使用。设置后额外获得：锁定任务模式、固定为主屏幕（HOME）、自动授予摄像头权限、禁用锁屏、通过 MQTT 和局域网接口远程重启。

前提：平板上不能登录任何账号（设置 → 账号中全部移除），也没有其他设备所有者。

```powershell
adb shell dpm set-device-owner cn.local.tabletkiosk/.KioskAdminReceiver
```

注意：

- 本应用没有提供“解除设备所有者”按钮，解除只能**恢复出厂设置**。
- 锁定模式开启时无法打开系统设置界面；需要进入系统设置时，先在本应用设置中关闭“锁定模式”并保存。
- 系统没有为电池优化白名单提供设备所有者接口，仍需手动在提示中允许一次。

## MQTT

在设置中开启 MQTT，填写 broker 地址、端口（默认 1883）、账号和密码。HA 中会出现一台名为“居家屏幕”的设备，包含：

| 实体 | 类型 |
| --- | --- |
| 亮屏、有人靠近、充电中 | binary_sensor |
| 电量、充电状态、当前亮度、当前网址 | sensor |
| 设置亮度（1-100） | number |
| 立即刷新、唤醒屏幕 | button |
| 重启平板（仅设备所有者） | button |

主题（`<base>` 默认 `home_kiosk`，`<id>` 为本机随机生成的 `kiosk_xxxxxxxx`）：

- 状态：`<base>/<id>/state`（保留消息，状态变化时立即发布，另按“上报间隔”定时发布）
- 在线：`<base>/<id>/availability`（`online` / `offline`，断线时由遗嘱消息置为 `offline`）
- 命令：`<base>/<id>/cmd/reload`、`wake`、`brightness`、`reboot`

HA 重启后（`homeassistant/status` 收到 `online`）会自动重新发布注册信息。

## 局域网管理接口

在设置中开启并设置至少 8 位密码，默认端口 2323。只接受局域网来源（私有地址、链路本地、IPv6 ULA），Tailscale 等其他地址会被拒绝。密码只能放在请求头中，不接受 URL 参数。

```bash
# 用法示例，YOUR_PASSWORD 替换为设置中的密码
curl -H "Authorization: Bearer YOUR_PASSWORD" http://平板IP:2323/status
curl -X POST -H "X-Kiosk-Password: YOUR_PASSWORD" http://平板IP:2323/reload
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" http://平板IP:2323/wake
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" "http://平板IP:2323/brightness?value=60"
curl -X POST -H "Authorization: Bearer YOUR_PASSWORD" http://平板IP:2323/reboot   # 仅设备所有者
```

| 路径 | 方法 | 说明 |
| --- | --- | --- |
| `/status` | GET | 返回状态 JSON |
| `/reload` | POST | 清缓存并重载 |
| `/wake` | POST | 点亮屏幕并升到观看亮度 |
| `/brightness` | POST | 设置观看亮度，可用 `?value=60`、正文 `60` 或 `{"brightness":60}` |
| `/reboot` | POST | 重启平板；不是设备所有者时返回 409 |

密码错误返回 401，非局域网来源返回 403。接口使用明文 HTTP，只应在可信的家庭局域网中开启。
