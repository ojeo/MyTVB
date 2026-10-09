#!/usr/bin/env bash
#
# 编译 MyTVB debug 版本 + 安装到模拟器（tv35）
# ------------------------------------------------------------------
# 流程：
#   1) 自动定位项目根 / JDK / Android SDK
#   2) 编译 assembleDebug（产物：app/build/outputs/apk/debug/app-debug.apk）
#   3) 启动模拟器（若未运行）
#   4) 等待模拟器 boot 完成
#   5) 安装 app-debug.apk（-r 覆盖安装）
#   6) 启动 App（SplashActivity）
#
# 用法（Git Bash，任意目录下均可，脚本会自动定位项目根）：
#   ./build/scripts/debug-install.sh                         # 默认原版包名 com.mytvb
#   ./build/scripts/debug-install.sh -AppId com.tutu.je.myblbl  # 显式指定 Fork 版包名
#   ./app/build/scripts/debug-install.sh -SkipBuild          # 只安装，不重新编译
#   ./app/build/scripts/debug-install.sh -NoLaunch           # 安装后不自动启动
#   ./app/build/scripts/debug-install.sh -AvdName tv35       # 指定模拟器 AVD
#
# 依赖：
#   - local.properties 中的 sdk.dir，或环境变量 ANDROID_SDK_ROOT / ANDROID_HOME
#   - JAVA_HOME（默认尝试从 D:\Android\jdk17 推断，可环境变量覆盖）
#   - 模拟器 AVD 名称默认 tv35，可 -AvdName 覆盖
#
set -euo pipefail

# ---------------------- 0. 解析参数 ----------------------
APP_ID="com.mytvb"
AVD_NAME="tv35"
SKIP_BUILD=0
NO_LAUNCH=0
while [ $# -gt 0 ]; do
  case "$1" in
    -AppId|-appid|-APP_ID) APP_ID="${2:-}"; shift 2 ;;
    -AvdName|-avd|-AVD)    AVD_NAME="${2:-}"; shift 2 ;;
    -SkipBuild|-skip)      SKIP_BUILD=1; shift ;;
    -NoLaunch|-nolaunch)   NO_LAUNCH=1; shift ;;
    *) echo "[错误] 未知参数：$1" >&2; exit 1 ;;
  esac
done

# 把 Windows 盘符路径（如 D:\Android\Sdk）转成 Git Bash 能识别的 Unix 风格路径（/d/Android/Sdk）。
# 仅当路径形如 "盘符:\..." 时转换，否则原样返回。
win_to_bash() {
  local p="$1"
  case "$p" in
    [A-Za-z]:*) echo "$(printf '%s' "$p" | sed -e 's|^\([A-Za-z]\):|/\L\1|' -e 's|\\|/|g')" ;;
    *)          echo "$p" ;;
  esac
}

# 记录脚本执行前的工作目录，确保脚本结束时恢复（不改变当前终端所在目录）
ORIG_DIR="$(pwd)"

# 定位项目根目录：从脚本所在目录向上查找包含 gradlew.bat 的目录
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
while [ ! -f "$ROOT_DIR/gradlew.bat" ]; do
  PARENT="$(dirname "$ROOT_DIR")"
  [ "$PARENT" = "$ROOT_DIR" ] && { echo "[错误] 未找到项目根目录（缺少 gradlew.bat）" >&2; exit 1; }
  ROOT_DIR="$PARENT"
done
cd "$ROOT_DIR"
# 无论脚本正常结束还是中途报错退出，都恢复到进入脚本前的工作目录
trap 'cd "$ORIG_DIR"' EXIT

# ---------------------- 1. 定位 JDK ----------------------
if [ -z "${JAVA_HOME:-}" ]; then
  # 默认本机路径，可通过环境变量覆盖
  for cand in "/d/Android/jdk17" "/c/Program Files/Java"*/; do
    if [ -x "$cand/bin/javac" ]; then JAVA_HOME="$cand"; break; fi
  done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ]; then
  echo "[错误] 找不到 JDK，请设置 JAVA_HOME，例如：export JAVA_HOME=/d/Android/jdk17"
  exit 1
fi
export JAVA_HOME
export PATH="${JAVA_HOME}/bin:${PATH}"
echo "JAVA_HOME=${JAVA_HOME}"

# 把 Gradle 缓存/依赖/daemon 全部放到 D 盘，不占 C 盘
export GRADLE_USER_HOME="/d/Android/.gradle"
echo "GRADLE_USER_HOME=${GRADLE_USER_HOME}"

# ---------------------- 2. 定位 Android SDK ----------------------
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_DIR" ] && [ -f local.properties ]; then
  # local.properties 中形如：sdk.dir=D\:\\Android\\Sdk  -> 转成 D:\Android\Sdk
  raw="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
  if [ -n "$raw" ]; then
    SDK_DIR="$(printf '%s' "$raw" | sed -e 's/\\\\/\\/g' -e 's/\\:/:/g')"
  fi
fi
if [ -z "${SDK_DIR:-}" ] || [ ! -d "$SDK_DIR" ]; then
  echo "[错误] 找不到 Android SDK，请设置 ANDROID_SDK_ROOT 或 ANDROID_HOME"
  exit 1
fi
# 转为 Git Bash 可识别的路径（处理 Windows 盘符路径）
SDK_DIR="$(win_to_bash "$SDK_DIR")"
if [ ! -d "$SDK_DIR" ]; then
  echo "[错误] Android SDK 路径无效：$SDK_DIR"
  exit 1
fi
echo "SDK_DIR=${SDK_DIR}"

ADB_EXE="${SDK_DIR}/platform-tools/adb.exe"
EMULATOR_EXE="${SDK_DIR}/emulator/emulator.exe"
if [ ! -f "$ADB_EXE" ]; then
  echo "[错误] 找不到 adb：$ADB_EXE"
  exit 1
fi
if [ ! -f "$EMULATOR_EXE" ]; then
  echo "[错误] 找不到 emulator：$EMULATOR_EXE"
  exit 1
fi

# debug 产物
DEBUG_APK="${ROOT_DIR}/app/build/outputs/apk/debug/app-debug.apk"

# ---------------------- 3. 编译 debug（可选跳过） ----------------------
# 包名走环境变量（同 release-fork.sh：避免 -P 值被终端拆词）
if [ "$APP_ID" != "com.mytvb" ]; then
  export ORG_GRADLE_PROJECT_applicationId="${APP_ID}"
else
  unset ORG_GRADLE_PROJECT_applicationId || true
fi
if [ "$SKIP_BUILD" -eq 0 ]; then
  echo
  echo ">>> 构建 debug（applicationId=${APP_ID}）..."
  ./gradlew assembleDebug --console=plain
else
  echo
  echo ">>> 跳过编译（-SkipBuild）"
fi

if [ ! -f "$DEBUG_APK" ]; then
  echo "[错误] 未找到 debug APK：$DEBUG_APK（请先构建或去掉 -SkipBuild）"
  exit 1
fi
echo "APK 产物：$DEBUG_APK"

# ---------------------- 4. 启动模拟器（若未运行） ----------------------
RUNNING_DEVICES="$("$ADB_EXE" devices | grep 'device$' | awk '{print $1}')"
if [ -z "$RUNNING_DEVICES" ]; then
  echo
  echo ">>> 启动模拟器：$AVD_NAME（后台运行）..."
  # 后台启动模拟器，避免占用终端
  "$EMULATOR_EXE" -avd "$AVD_NAME" -gpu swiftshader_indirect -no-snapshot-load &
else
  echo
  echo ">>> 已有在线设备：$(echo "$RUNNING_DEVICES" | tr '\n' ' ')，跳过启动模拟器"
fi

# ---------------------- 5. 等待 boot 完成 ----------------------
echo
echo ">>> 等待设备就绪..."
"$ADB_EXE" wait-for-device
TIMEOUT_SEC=120
ELAPSED=0
BOOTED=0
while [ "$ELAPSED" -lt "$TIMEOUT_SEC" ]; do
  PROP="$("$ADB_EXE" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [ "$PROP" = "1" ]; then BOOTED=1; break; fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
if [ "$BOOTED" -ne 1 ]; then
  echo "[错误] 等待模拟器 boot 超时（${TIMEOUT_SEC}s），请检查模拟器状态"
  exit 1
fi
echo "设备已就绪。"

# ---------------------- 6. 安装 APK ----------------------
echo
echo ">>> 安装 APK 到设备..."
"$ADB_EXE" install -r "$DEBUG_APK"
echo "安装成功。"

# ---------------------- 7. 启动 App ----------------------
if [ "$NO_LAUNCH" -eq 0 ]; then
  ACTIVITY="${APP_ID}/.ui.activity.SplashActivity"
  echo
  echo ">>> 启动 App：$ACTIVITY"
  "$ADB_EXE" shell am start -n "$ACTIVITY" || echo "[提示] 启动命令执行异常"
  echo
  echo ">>> 查看日志：$ADB_EXE logcat | grep MyBLBL"
else
  echo
  echo ">>> 已跳过启动（-NoLaunch），可直接用 adb 手动启动："
  echo "    $ADB_EXE shell am start -n ${APP_ID}/.ui.activity.SplashActivity"
fi

echo
echo "完成。"
