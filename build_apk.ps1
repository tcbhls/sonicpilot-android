# 音枢 AI 音乐 一键编译与真机安装脚本
param(
    [switch]$InstallToDevice,
    [switch]$Release,
    [switch]$OpenSource
)

$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    throw "请先配置环境变量 JAVA_HOME（需要 JDK 17 或 JDK 21）"
}
if (-not $env:ANDROID_HOME) {
    throw "请先配置环境变量 ANDROID_HOME（指向 Android SDK 目录）"
}
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

$gradle = Get-Command gradle -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $gradle) {
    $gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter "gradle.bat" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $gradle) {
    throw "未找到 gradle，请先安装 Gradle 8.14+ 或将其加入 PATH"
}

$task = if ($Release) { "assembleRelease" } else { "assembleDebug" }
$extraArgs = @("--no-daemon")
if ($OpenSource) { $extraArgs += "-POPEN_SOURCE_BUILD=true" }

Write-Host ">>> 开始运行单元测试并编译 $task ..." -ForegroundColor Cyan
& $gradle testDebugUnitTest $task @extraArgs

$apkPath = if ($Release) {
    "app\build\outputs\apk\release\app-release.apk"
} else {
    "app\build\outputs\apk\debug\app-debug.apk"
}

if (Test-Path $apkPath) {
    $info = Get-Item $apkPath
    Write-Host ">>> APK 构建成功: $($info.FullName) ($([math]::Round($info.Length / 1MB, 2)) MB)" -ForegroundColor Green
    if ($InstallToDevice) {
        Write-Host ">>> 正在通过 ADB 安装到已连接的安卓手机..." -ForegroundColor Cyan
        & "$env:ANDROID_HOME\platform-tools\adb.exe" install -r $apkPath
    }
}
