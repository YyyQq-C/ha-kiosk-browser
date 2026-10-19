# 打已签名的正式 APK。不合并分支，不打 tag，不创建 GitHub Release。
# 不打印 keystore 口令。须在本目录已有 signing/keystore.properties 与对应 keystore。
# 用法：.\release.ps1
# 依赖缓存不够时：.\release.ps1 -Online
param(
    [switch]$Online
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# 本机默认使用 JDK 21。已设置且可执行时不覆盖。
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $jdk = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
    if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin\java.exe'))) {
        throw '找不到 JDK 21。请安装 Eclipse Temurin 21，或先设置 JAVA_HOME。'
    }
    $env:JAVA_HOME = $jdk
}
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'

function Read-Property {
    param([string]$Path, [string]$Name)
    foreach ($line in Get-Content -LiteralPath $Path) {
        $text = $line.Trim()
        if ($text.Length -eq 0 -or $text.StartsWith('#')) { continue }
        $pair = $text.Split('=', 2)
        if ($pair.Length -eq 2 -and $pair[0].Trim() -eq $Name) { return $pair[1].Trim() }
    }
    return $null
}

function ConvertFrom-JavaPath {
    param([string]$Value)
    # Java properties 把冒号和反斜杠写成 \: 与 \\。
    return $Value.Replace('\\', '\').Replace('\:', ':')
}

$propsPath = Join-Path $PSScriptRoot 'signing\keystore.properties'
if (-not (Test-Path -LiteralPath $propsPath)) {
    throw '缺少 signing/keystore.properties，不能打已签名正式包。'
}
foreach ($name in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if (-not (Read-Property $propsPath $name)) {
        throw "signing/keystore.properties 缺少 $name"
    }
}
$storeFile = Read-Property $propsPath 'storeFile'
$storePath = $storeFile
if (-not [System.IO.Path]::IsPathRooted($storePath)) {
    $storePath = Join-Path $PSScriptRoot $storeFile
}
if (-not (Test-Path -LiteralPath $storePath)) {
    throw "找不到 keystore 文件：$storeFile"
}

$gradle = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'app\build.gradle') -Raw
if ($gradle -notmatch "versionName\s+'([^']+)'") { throw 'app/build.gradle 里没有 versionName' }
$versionName = $Matches[1]
if ($gradle -notmatch 'versionCode\s+(\d+)') { throw 'app/build.gradle 里没有 versionCode' }
$versionCode = $Matches[1]

$gradleArgs = @('assembleRelease', '--console=plain')
if (-not $Online) { $gradleArgs += '--offline' }
Write-Host "正在构建正式包 $versionName ($versionCode)"
& .\gradlew.bat @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "assembleRelease 失败，退出码 $LASTEXITCODE" }

$built = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $built)) { throw '没有生成 app-release.apk' }

$sdkDir = $null
$local = Join-Path $PSScriptRoot 'local.properties'
if (Test-Path -LiteralPath $local) {
    $raw = Read-Property $local 'sdk.dir'
    if ($raw) { $sdkDir = ConvertFrom-JavaPath $raw }
}
if (-not $sdkDir) { $sdkDir = $env:ANDROID_HOME }
if (-not $sdkDir -or -not (Test-Path -LiteralPath $sdkDir)) { throw '找不到 Android SDK。请检查 local.properties 的 sdk.dir。' }
$apksigner = Get-ChildItem -LiteralPath (Join-Path $sdkDir 'build-tools') -Filter 'apksigner.bat' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if (-not $apksigner) { throw '找不到 apksigner.bat' }

& $apksigner.FullName verify --print-certs $built
if ($LASTEXITCODE -ne 0) { throw '正式包签名校验失败' }

$dist = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$named = Join-Path $dist "ha-kiosk-browser-$versionName.apk"
Copy-Item -LiteralPath $built -Destination $named -Force
$hash = (Get-FileHash -LiteralPath $named -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $named).Length

Write-Host "正式包已签名"
Write-Host "版本 $versionName (versionCode $versionCode)"
Write-Host "文件 $named"
Write-Host "大小 $size"
Write-Host "SHA-256 $hash"
