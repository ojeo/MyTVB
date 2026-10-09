# ============================================================================
# 编译 MyTVB debug 版本 + 安装到模拟器（tv35）
# ----------------------------------------------------------------------------
# 流程：
#   1) 自动定位项目根 / JDK / Android SDK
#   2) 编译 assembleDebug（产物：app\build\outputs\apk\debug\app-debug.apk）
#   3) 启动模拟器（若未运行）
#   4) 等待模拟器 boot 完成
#   5) 安装 app-debug.apk（-r 覆盖安装）
#   6) 启动 App（SplashActivity）
#
# 用法（PowerShell，任意目录下均可，脚本会自动定位项目根）：
#   .\build\scripts\debug-install.ps1                        # 默认 Fork 版包名 com.tutu.je.myblbl
#   .\build\scripts\debug-install.ps1 -AppId com.mytvb       # 显式指定原版包名
#   .\app\build\scripts\debug-install.ps1 -SkipBuild         # 只安装，不重新编译
#   .\app\build\scripts\debug-install.ps1 -NoLaunch          # 安装后不自动启动
#
# 依赖：
#   - local.properties 中的 sdk.dir，或环境变量 ANDROID_SDK_ROOT / ANDROID_HOME
#   - JAVA_HOME（默认尝试 D:\Android\jdk17，可 -JavaHome 覆盖）
#   - 模拟器 AVD 名称默认 tv35，可 -AvdName 覆盖
# ============================================================================
param(
    [string]$AppId = "com.tutu.je.myblbl",
    [string]$JavaHome = "",
    [string]$AvdName = "tv35",
    [switch]$SkipBuild,
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# 记录脚本执行前的工作目录，确保脚本结束时恢复（不改变当前终端所在目录）
$OrigDir = Get-Location

# 定位项目根目录：从脚本所在目录向上逐层查找包含 gradlew.bat 的目录
$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
while ($RootDir -and -not (Test-Path (Join-Path $RootDir "gradlew.bat"))) {
    $parent = Split-Path -Parent $RootDir
    if ($parent -eq $RootDir) { break }
    $RootDir = $parent
}
if (-not (Test-Path (Join-Path $RootDir "gradlew.bat"))) {
    Write-Host "[错误] 未找到项目根目录（缺少 gradlew.bat），请检查脚本位置" -ForegroundColor Red
    exit 1
}

try {
Set-Location $RootDir

# ---------------------- 1. 定位 JDK ----------------------
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    if ($env:JAVA_HOME) { $JavaHome = $env:JAVA_HOME }
    else { $JavaHome = "D:\Android\jdk17" }
}
if (-not (Test-Path (Join-Path $JavaHome "bin\javac.exe"))) {
    Write-Host "[错误] 找不到 JDK，请设置 -JavaHome 或环境变量 JAVA_HOME，例如 D:\Android\jdk17" -ForegroundColor Red
    exit 1
}
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
Write-Host "JAVA_HOME=$JavaHome"

# 把 Gradle 缓存/依赖/daemon 全部放到 D 盘，不占 C 盘
$env:GRADLE_USER_HOME = "D:\Android\.gradle"
Write-Host "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"

# ---------------------- 2. 定位 Android SDK ----------------------
$SdkDir = ""
if ($env:ANDROID_SDK_ROOT) { $SdkDir = $env:ANDROID_SDK_ROOT }
elseif ($env:ANDROID_HOME) { $SdkDir = $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($SdkDir) -and (Test-Path "local.properties")) {
    $line = (Get-Content "local.properties" | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1)
    if ($line) {
        $SdkDir = $line -replace '^sdk\.dir=', ''
        $SdkDir = $SdkDir -replace '\\\\', '\' -replace '\\:', ':'
    }
}
if ([string]::IsNullOrWhiteSpace($SdkDir) -or -not (Test-Path $SdkDir)) {
    Write-Host "[错误] 找不到 Android SDK，请设置 ANDROID_SDK_ROOT 或 ANDROID_HOME" -ForegroundColor Red
    exit 1
}
Write-Host "SDK_DIR=$SdkDir"

$AdbExe     = Join-Path $SdkDir "platform-tools\adb.exe"
$EmulatorExe = Join-Path $SdkDir "emulator\emulator.exe"
if (-not (Test-Path $AdbExe)) {
    Write-Host "[错误] 找不到 adb：$AdbExe" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $EmulatorExe)) {
    Write-Host "[错误] 找不到 emulator：$EmulatorExe" -ForegroundColor Red
    exit 1
}

# debug 产物
$DebugApk = Join-Path $RootDir "app\build\outputs\apk\debug\app-debug.apk"

# ---------------------- 3. 编译 debug（可选跳过） ----------------------
if (-not $SkipBuild) {
    # 包名走环境变量（同 release-fork.ps1：-P 值在 PowerShell 下会被按 "." 拆词）
    if ($AppId -ne "com.mytvb") {
        $env:ORG_GRADLE_PROJECT_applicationId = $AppId
    } else {
        Remove-Item Env:\ORG_GRADLE_PROJECT_applicationId -ErrorAction SilentlyContinue
    }
    Write-Host ""
    Write-Host ">>> 构建 debug（applicationId=$AppId）..."
    & .\gradlew.bat assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { Write-Host "[错误] 构建失败" -ForegroundColor Red; exit $LASTEXITCODE }
} else {
    Write-Host ""
    Write-Host ">>> 跳过编译（-SkipBuild）"
}

if (-not (Test-Path $DebugApk)) {
    Write-Host "[错误] 未找到 debug APK：$DebugApk（请先构建或去掉 -SkipBuild）" -ForegroundColor Red
    exit 1
}
Write-Host "APK 产物：$DebugApk"

# ---------------------- 4. 启动模拟器（若未运行） ----------------------
function Get-RunningDevices {
    return (& $AdbExe devices | Select-String "device$" | ForEach-Object {
        ($_ -split "\s+")[0]
    })
}

$running = @(Get-RunningDevices)
if ($running.Count -eq 0) {
    Write-Host ""
    Write-Host ">>> 启动模拟器：$AvdName（后台运行）..."
    Start-Process $EmulatorExe -ArgumentList @("-avd", $AvdName, "-gpu", "swiftshader_indirect", "-no-snapshot-load")
} else {
    Write-Host ""
    Write-Host ">>> 已有在线设备：$($running -join ', ')，跳过启动模拟器"
}

# ---------------------- 5. 等待 boot 完成 ----------------------
Write-Host ""
Write-Host ">>> 等待设备就绪..."
& $AdbExe wait-for-device
$TimeoutSec = 120
$Elapsed = 0
$Booted = $false
while ($Elapsed -lt $TimeoutSec) {
    $prop = (& $AdbExe shell getprop sys.boot_completed 2>$null).Trim()
    if ($prop -eq "1") { $Booted = $true; break }
    Start-Sleep -Seconds 2
    $Elapsed += 2
}
if (-not $Booted) {
    Write-Host "[错误] 等待模拟器 boot 超时（${TimeoutSec}s），请检查模拟器状态" -ForegroundColor Red
    exit 1
}
Write-Host "设备已就绪。"

# ---------------------- 6. 安装 APK ----------------------
Write-Host ""
Write-Host ">>> 安装 APK 到设备..."
& $AdbExe install -r $DebugApk
if ($LASTEXITCODE -ne 0) {
    Write-Host "[错误] 安装失败" -ForegroundColor Red
    exit $LASTEXITCODE
}
Write-Host "安装成功。"

# ---------------------- 7. 启动 App ----------------------
if (-not $NoLaunch) {
    $Activity = "${AppId}/.ui.activity.SplashActivity"
    Write-Host ""
    Write-Host ">>> 启动 App：$Activity"
    & $AdbExe shell am start -n $Activity
    if ($LASTEXITCODE -ne 0) { Write-Host "[提示] 启动命令执行异常" -ForegroundColor Yellow }
    Write-Host ""
    Write-Host ">>> 查看日志：$AdbExe logcat | findstr MyBLBL"
} else {
    Write-Host ""
    Write-Host ">>> 已跳过启动（-NoLaunch），可直接用 adb 手动启动："
    Write-Host "    $AdbExe shell am start -n ${AppId}/.ui.activity.SplashActivity"
}

Write-Host ""
Write-Host "完成。"
}
finally {
    # 无论成功还是中途报错（exit），都恢复到进入脚本前的工作目录
    Set-Location $OrigDir
}