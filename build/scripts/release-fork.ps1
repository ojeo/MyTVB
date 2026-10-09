# ============================================================================
# 方案B：构建未签名 release + 用 apksigner 手动补签名 + 可选指定 applicationId
# ----------------------------------------------------------------------------
# 背景：为保持 app/build.gradle.kts 与上游差异最小，Gradle 不再配置
#       release 签名，产物为未签名 APK，签名在构建后用 apksigner 补齐。
#       证书参数从 app/key.properties 读取（该文件被 .gitignore 忽略、
#       不入库），本脚本不含任何密码/别名硬编码，可放心提交。
#
# 用法（PowerShell，任意目录下均可，脚本会自动定位项目根）：
#   .\build\scripts\release-fork.ps1                      # 默认 Fork 版包名 com.tutu.je.myblbl
#   .\build\scripts\release-fork.ps1 -AppId com.mytvb     # 显式指定原版包名
#   .\build\scripts\release-fork.ps1 -SkipBuild           # 只补签名，不重新构建
#
# 依赖：
#   - local.properties 中的 sdk.dir，或环境变量 ANDROID_SDK_ROOT / ANDROID_HOME
#   - JAVA_HOME（默认尝试 D:\Android\jdk17，可 -JavaHome 覆盖）
#   - app/key.properties（storeFile/storePassword/keyAlias/keyPassword）
# ============================================================================
param(
    [string]$AppId = "com.tutu.je.myblbl",
    [string]$JavaHome = "",
    [switch]$SkipBuild
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

# ---------------------- 2. 定位 Android SDK / build-tools ----------------------
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

# build-tools：取存在的最高版本
$BuildTools = ""
Get-ChildItem (Join-Path $SdkDir "build-tools") -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    ForEach-Object {
        if (-not $BuildTools) {
            $hasApksigner = (Test-Path (Join-Path $_.FullName "apksigner.bat")) -or (Test-Path (Join-Path $_.FullName "apksigner"))
            if ($hasApksigner) {
                $BuildTools = $_.FullName
            }
        }
    }
if (-not $BuildTools) {
    Write-Host "[错误] 找不到 build-tools（内含 apksigner），请检查 SDK" -ForegroundColor Red
    exit 1
}
Write-Host "BUILD_TOOLS=$BuildTools"

$Apksigner = Join-Path $BuildTools "apksigner.bat"
$Zipalign  = Join-Path $BuildTools "zipalign.exe"

# ---------------------- 3. 读取签名参数 ----------------------
$KeyFile = "app\key.properties"
$KsFile = ""; $KsPass = ""; $KeyAlias = ""; $KeyPass = ""
if (Test-Path $KeyFile) {
    $prop = @{}
    Get-Content $KeyFile | Where-Object { $_ -match '=' -and $_ -notmatch '^\s*[#;]' } | ForEach-Object {
        $kv = $_ -split '=', 2
        $prop[$kv[0].Trim()] = $kv[1].Trim()
    }
    $KsFile  = $prop['storeFile']
    $KsPass  = $prop['storePassword']
    $KeyAlias = $prop['keyAlias']
    $KeyPass = $prop['keyPassword']
    # storeFile 若为相对路径，基于 key.properties 所在目录（app/）解析
    if (-not [System.IO.Path]::IsPathRooted($KsFile)) {
        $KeyDir = Split-Path -Parent $KeyFile
        $KsFile = Join-Path (Join-Path $RootDir $KeyDir) $KsFile
    }
}
if ([string]::IsNullOrWhiteSpace($KsFile) -or -not (Test-Path $KsFile)) {
    Write-Host "[错误] 找不到 keystore 文件：$KsFile（请检查 app\key.properties 的 storeFile）" -ForegroundColor Red
    exit 1
}

# ---------------------- 4. 读取版本号 + 生成日期时间标签 ----------------------
$VersionName = (Get-Content "app\build.gradle.kts" |
    Where-Object { $_ -match '^\s*versionName\s*=' } |
    Select-Object -First 1) -replace '.*=\s*"([^"]+)".*', '$1'
if ([string]::IsNullOrWhiteSpace($VersionName)) {
    Write-Host "[错误] 无法从 app\build.gradle.kts 解析 versionName" -ForegroundColor Red
    exit 1
}
$DateTimeTag = Get-Date -Format "yyyyMMdd-HHmmss"
Write-Host "VERSION_NAME=$VersionName"
Write-Host "DATETIME_TAG=$DateTimeTag"

# ---------------------- 5. 构建（可选跳过） ----------------------
if (-not $SkipBuild) {
    # 包名走环境变量：PowerShell 向 gradlew.bat 传 "-PapplicationId=com.tutu.je.myblbl"
    # 会被按 "." 拆词，Gradle 报 `Task '.tutu.je.myblbl' not found` 直接构建失败；
    # ORG_GRADLE_PROJECT_applicationId 会被 Gradle 映射为项目属性 applicationId。
    if ($AppId -ne "com.mytvb") {
        $env:ORG_GRADLE_PROJECT_applicationId = $AppId
    } else {
        Remove-Item Env:\ORG_GRADLE_PROJECT_applicationId -ErrorAction SilentlyContinue
    }
    Write-Host ""
    Write-Host ">>> 构建 release（applicationId=$AppId）..."
    & .\gradlew.bat assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { Write-Host "[错误] 构建失败" -ForegroundColor Red; exit $LASTEXITCODE }
}

# ---------------------- 6. 定位未签名 APK ----------------------
$OutDir = "app\build\outputs\apk\release"
$ApkUnsigned = Join-Path $OutDir "app-release-unsigned.apk"
if (-not (Test-Path $ApkUnsigned)) {
    $ApkUnsigned = Get-ChildItem $OutDir -Filter "*.apk" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'unsigned' } | Select-Object -First 1 -ExpandProperty FullName
}
if ([string]::IsNullOrWhiteSpace($ApkUnsigned) -or -not (Test-Path $ApkUnsigned)) {
    Write-Host "[错误] 未找到未签名 APK，请检查 $OutDir" -ForegroundColor Red
    exit 1
}
Write-Host "未签名产物：$ApkUnsigned"

# ---------------------- 7. zipalign（可选） ----------------------
$SignIn = $ApkUnsigned
if (Test-Path $Zipalign) {
    $Aligned = Join-Path $OutDir "aligned.apk"
    & $Zipalign -p -f 4 $ApkUnsigned $Aligned
    if ($LASTEXITCODE -eq 0) {
        $SignIn = $Aligned
        Write-Host "已对齐：$Aligned"
    } else {
        Write-Host "[提示] zipalign 失败，直接使用未对齐产物（仅本地自用不影响）" -ForegroundColor Yellow
    }
} else {
    Write-Host "[提示] 未找到 zipalign，跳过对齐（仅本地自用不影响）。正式分发前请对齐。"
}

# ---------------------- 8. apksigner 签名 ----------------------
if ($AppId -ne "com.mytvb") { $AppName = "MyTVB-fork" }
else { $AppName = "MyTVB" }
# 产物命名：MyTVB-fork-v2.1.3-20261009-1530.apk（含版本号 + 日期时间标签）
$Signed = Join-Path $OutDir "${AppName}-v${VersionName}-${DateTimeTag}.apk"
& $Apksigner sign `
    --ks $KsFile `
    --ks-key-alias $KeyAlias `
    --ks-pass "pass:$KsPass" `
    --key-pass "pass:$KeyPass" `
    --out $Signed `
    $SignIn
if ($LASTEXITCODE -ne 0) { Write-Host "[错误] 签名失败" -ForegroundColor Red; exit $LASTEXITCODE }
Write-Host "已签名：$Signed"

# ---------------------- 9. 验证签名 ----------------------
Write-Host ""
Write-Host ">>> 验证签名："
& $Apksigner verify --print-certs --verbose $Signed

Write-Host ""
Write-Host "构建完成：$Signed"
}
finally {
    # 无论成功还是中途报错（exit），都恢复到进入脚本前的工作目录
    Set-Location $OrigDir
}