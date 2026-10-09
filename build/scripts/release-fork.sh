#!/usr/bin/env bash
#
# 方案B：构建未签名 release + 用 apksigner 手动补签名 + 可选指定 applicationId
# ------------------------------------------------------------------
# 背景：为保持 app/build.gradle.kts 与上游差异最小，Gradle 不再配置
#       release 签名，产物为未签名 APK，签名在构建后用 apksigner 补齐。
#       证书参数从 app/key.properties 读取（该文件被 .gitignore
#       忽略、不入库），本脚本不含任何密码/别名硬编码，可放心提交。
#
# 用法（Git Bash，任意目录下均可，脚本会自动定位项目根）：
#   ./build/scripts/release-fork.sh                 # 默认 Fork 版包名 com.tutu.je.myblbl
#   ./build/scripts/release-fork.sh com.mytvb       # 显式指定原版包名
#
# 依赖：
#   - local.properties 中的 sdk.dir，或环境变量 ANDROID_SDK_ROOT / ANDROID_HOME
#   - JAVA_HOME（默认尝试从 D:\Android\jdk17 推断，可环境变量覆盖）
#   - app/key.properties（storeFile/storePassword/keyAlias/keyPassword）
#
set -euo pipefail

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

# ---------------------- 1. 解析参数 ----------------------
APP_ID="${1:-com.tutu.je.myblbl}"
# 包名走环境变量：ORG_GRADLE_PROJECT_applicationId 会被 Gradle 映射为项目属性
# applicationId，避免 -P 值在不同终端被拆词（PowerShell 调 gradlew.bat 传
# "-PapplicationId=com.tutu.je.myblbl" 会按 "." 拆开，构建直接失败）。
if [ "$APP_ID" != "com.mytvb" ]; then
  export ORG_GRADLE_PROJECT_applicationId="${APP_ID}"
else
  unset ORG_GRADLE_PROJECT_applicationId || true
fi

# 从 app/build.gradle.kts 读取 versionName（形如 versionName = "1.6.10"）
VERSION_NAME="$(grep -E '^\s*versionName\s*=\s*' app/build.gradle.kts | head -1 | sed -E 's/.*=\s*"([^"]+)".*/\1/')"
if [ -z "$VERSION_NAME" ]; then
  echo "[错误] 无法从 app/build.gradle.kts 解析 versionName" >&2
  exit 1
fi

# 生成日期时间标签，如 20260807-1530
DATETIME_TAG="$(date +%Y%m%d-%H%M%S)"
echo "VERSION_NAME=${VERSION_NAME}"
echo "DATETIME_TAG=${DATETIME_TAG}"

# ---------------------- 2. 定位 JDK / SDK ----------------------
# JDK
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

# Android SDK
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

# build-tools：取存在的最高版本，并定位 apksigner（Unix 版 apksigner，或 Windows 版 apksigner.bat）
BT=""
APKSIGNER=""
for d in "$SDK_DIR"/build-tools/*/; do
  if [ -f "$d/apksigner" ]; then
    BT="${d%/}"; APKSIGNER="${BT}/apksigner"
  elif [ -f "$d/apksigner.bat" ]; then
    BT="${d%/}"; APKSIGNER="${BT}/apksigner.bat"
  fi
done
if [ -z "$BT" ] || [ -z "$APKSIGNER" ]; then
  echo "[错误] 找不到 build-tools（内含 apksigner/apksigner.bat），请检查 SDK"
  exit 1
fi
echo "BUILD_TOOLS=${BT}"
echo "APKSIGNER=${APKSIGNER}"

# ---------------------- 3. 读取签名参数 ----------------------
KS_FILE=""
if [ -f app/key.properties ]; then
  # 兼容带引号 / 带空格的值
  KS_FILE="$(grep -E '^storeFile=' app/key.properties | head -1 | cut -d= -f2- || true)"
  KS_PASS="$(grep -E '^storePassword=' app/key.properties | head -1 | cut -d= -f2- || true)"
  KEY_ALIAS="$(grep -E '^keyAlias=' app/key.properties | head -1 | cut -d= -f2- || true)"
  KEY_PASS="$(grep -E '^keyPassword=' app/key.properties | head -1 | cut -d= -f2- || true)"
  # storeFile 可能是相对路径，统一基于 key.properties 所在目录（app/）解析
  case "$KS_FILE" in
    /*) : ;;                       # 绝对路径（Unix）
    [A-Za-z]:*) KS_FILE="$(win_to_bash "$KS_FILE")" ;;  # 绝对路径（Windows，如 D:\...）
    *) KS_FILE="${ROOT_DIR}/app/${KS_FILE}" ;;  # 相对路径，相对 app/
  esac
fi
if [ -z "${KS_FILE:-}" ] || [ ! -f "$KS_FILE" ]; then
  echo "[错误] 找不到 keystore 文件：${KS_FILE:-<未设置>}（请检查 app/key.properties 的 storeFile）"
  exit 1
fi

# ---------------------- 4. 构建 ----------------------
# 先停掉残留的 Gradle daemon 再构建。
# 原因：--no-daemon 会让 Gradle 额外 fork 一个 single-use daemon，
# 若此时还有常驻 daemon / Kotlin daemon 存活，多个 JVM 并存会显著抬高
# Windows 提交内存占用。提交上限 = 物理内存 + 页面文件，一旦打满，
# JVM 会在 native malloc 上直接崩（hs_err_pid*.log，表现为
# "Gradle build daemon disappeared unexpectedly"），而不是 Java 堆 OOM。
echo
echo ">>> 停止残留 Gradle daemon..."
./gradlew --stop >/dev/null 2>&1 || true

echo ">>> 构建 release（applicationId=${APP_ID}）..."
./gradlew assembleRelease --no-daemon

APK_UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$APK_UNSIGNED" ]; then
  # 若文件名不同，尝试自动查找
  APK_UNSIGNED="$(find app/build/outputs/apk/release -name '*.apk' | grep -i unsigned | head -1 || true)"
fi
if [ -z "${APK_UNSIGNED:-}" ] || [ ! -f "$APK_UNSIGNED" ]; then
  echo "[错误] 未找到未签名 APK，请检查 app/build/outputs/apk/release/"
  exit 1
fi
echo "未签名产物：${APK_UNSIGNED}"

# ---------------------- 5. zipalign（可选） ----------------------
OUT_DIR="app/build/outputs/apk/release"
ALIGNED=""
if command -v zipalign >/dev/null 2>&1 || [ -x "$BT/zipalign" ]; then
  ALIGNED="${OUT_DIR}/aligned.apk"
  "$BT/zipalign" -p -f 4 "$APK_UNSIGNED" "$ALIGNED"
  echo "已对齐：${ALIGNED}"
else
  echo "[提示] 未找到 zipalign，跳过对齐（仅本地自用不影响）。正式分发前请对齐。"
fi

SIGN_IN="${ALIGNED:-$APK_UNSIGNED}"

# ---------------------- 6. apksigner 签名 ----------------------
APP_NAME="MyTVB"
if [ "$APP_ID" != "com.mytvb" ]; then
  APP_NAME="MyTVB-fork"
fi
# 产物命名：MyTVB-fork-v2.1.3-20261009-1530.apk（含版本号 + 日期时间标签）
SIGNED="${OUT_DIR}/${APP_NAME}-v${VERSION_NAME}-${DATETIME_TAG}.apk"
"$APKSIGNER" sign \
  --ks "$KS_FILE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:${KS_PASS}" \
  --key-pass "pass:${KEY_PASS}" \
  --out "$SIGNED" \
  "$SIGN_IN"
echo "已签名：${SIGNED}"

# ---------------------- 7. 验证签名 ----------------------
echo
echo ">>> 验证签名："
"$APKSIGNER" verify --print-certs --verbose "$SIGNED"

echo
echo "构建完成：${SIGNED}"
