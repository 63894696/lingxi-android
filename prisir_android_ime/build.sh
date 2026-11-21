#!/usr/bin/env bash
# 灵犀拼音 Android 端构建（引擎 .so → APK）
#
# 用法:
#   ./build.sh                 # 编引擎 .so + 打 debug APK
#   ./build.sh --install       # 打完顺手 adb install -r
#   ./build.sh --push-db       # 顺便把精简词库推到设备（需可 adb）
#   ./build.sh --release       # release（未签名，需要自备 keystore）
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$SDK/ndk/android-ndk-r27d}"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
GRADLE="${GRADLE:-$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle 2>/dev/null | tail -1)}"

DO_INSTALL=0; DO_PUSH=0; TASK=assembleDebug
for a in "$@"; do
  case "$a" in
    --install) DO_INSTALL=1 ;;
    --push-db) DO_PUSH=1 ;;
    --release) TASK=assembleRelease ;;
  esac
done

echo "=== 1/4 编译引擎（arm64-v8a, release LTO）==="
( cd ../prisir_ime && cargo ndk -t arm64-v8a -o ../prisir_android_ime/app/src/main/jniLibs build --release )

echo "=== 2/4 装入全量词库到 assets（装完即用，不二次下载）==="
ASSETS="app/src/main/assets"
mkdir -p "$ASSETS"
# 权威源：Win 安装包同款全量词库；可用 CIKU_DB 覆盖
CIKU_SRC="${CIKU_DB:-}"
if [[ -z "$CIKU_SRC" ]]; then
  for cand in \
    /d/Projects/lingxi-windows/installer/stage/models/ciku.db \
    "$HOME/../Projects/lingxi-windows/installer/stage/models/ciku.db" \
    /d/Temp/ciku_android.db
  do
    [[ -f "$cand" ]] && CIKU_SRC="$cand" && break
  done
fi
[[ -n "$CIKU_SRC" && -f "$CIKU_SRC" ]] || { echo "[x] 找不到 ciku.db（设 CIKU_DB=路径）" >&2; exit 2; }
cp -f "$CIKU_SRC" "$ASSETS/ciku.db"
ls -lh "$ASSETS/ciku.db"
# midx ~340MB，手机包默认不进 APK（引擎无 midx 仍走 SQLite，功能完整）。
# 开发机要测 mmap：INCLUDE_MIDX=1 ./build.sh
rm -f "$ASSETS/ciku.midx"
if [[ "${INCLUDE_MIDX:-0}" == "1" ]]; then
  MIDX_SRC="${CIKU_MIDX:-${CIKU_SRC%.db}.midx}"
  if [[ -f "$MIDX_SRC" ]]; then
    cp -f "$MIDX_SRC" "$ASSETS/ciku.midx"
    ls -lh "$ASSETS/ciku.midx"
  else
    echo "[i] INCLUDE_MIDX=1 但找不到 midx，跳过"
  fi
else
  echo "[i] 不打包 ciku.midx（约省 340MB；设 INCLUDE_MIDX=1 可强制打入）"
fi

echo "=== 3/4 打 APK ($TASK) ==="
echo "sdk.dir=$SDK" > local.properties
"$GRADLE" --no-daemon "$TASK" | tail -8
APK="app/build/outputs/apk/debug/app-debug.apk"
[ "$TASK" = "assembleRelease" ] && APK="app/build/outputs/apk/release/app-release-unsigned.apk"
ls -la "$APK"
# 命名产物
mkdir -p ../dist
VER=$(grep -E 'versionName\s*=' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
OUT="../dist/LingxiIME-Android-arm64-v8a-${VER}-debug.apk"
[[ "$TASK" = "assembleRelease" ]] && OUT="../dist/LingxiIME-Android-arm64-v8a-${VER}-release-unsigned.apk"
cp -f "$APK" "$OUT"
ls -lh "$OUT"

if [ "$DO_INSTALL" = "1" ]; then
  echo "=== 3/3 安装 ==="
  adb install -r "$APK"
fi

if [ "$DO_PUSH" = "1" ]; then
  echo "=== 推送精简词库（56MB, 30 万词）==="
  [ -f /d/Temp/ciku_android.db ] || python ../lexicon_pipeline/trim_db.py --dst /d/Temp/ciku_android.db --top 300000
  MSYS_NO_PATHCONV=1 adb shell mkdir -p /sdcard/Android/data/com.lingxi.ime/files
  MSYS_NO_PATHCONV=1 adb push /d/Temp/ciku_android.db /sdcard/Android/data/com.lingxi.ime/files/ciku.db

  echo "=== 词库落位（app 内部存储）+ 对齐 mtime ==="
  # 词库必须放 app 内部 filesDir：SD 卡随机读 30~120ms，内部存储 <5ms。
  MSYS_NO_PATHCONV=1 adb shell run-as com.lingxi.ime cp /sdcard/Android/data/com.lingxi.ime/files/ciku.db files/ciku.db
  # .midx 指纹 = 词库长度 + mtime → 设备侧 mtime 必须与 PC 侧一致，否则索引失效。
  MTIME=$(date -r /d/Temp/ciku_android.db +%Y%m%d%H%M.%S)
  MSYS_NO_PATHCONV=1 adb shell run-as com.lingxi.ime touch -t "$MTIME" files/ciku.db
  echo "    设备词库 mtime 已对齐为 $MTIME"

  echo "=== 重建并推送 mmap 内存索引（.midx，Android 走内存映射必需）==="
  # .midx 与词库指纹绑定，必须与词库**一起**下发；换词库就一定要重跑这一步。
  ( cd ../prisir_ime && cargo run --release --bin midxtool -- /d/Temp/ciku_android.db )
  MSYS_NO_PATHCONV=1 adb push /d/Temp/ciku_android.midx /data/local/tmp/ciku.midx
  MSYS_NO_PATHCONV=1 adb shell chmod 644 /data/local/tmp/ciku.midx
  MSYS_NO_PATHCONV=1 adb shell run-as com.lingxi.ime cp /data/local/tmp/ciku.midx files/ciku.midx
  MSYS_NO_PATHCONV=1 adb shell run-as com.lingxi.ime ls -l files/
  echo "    冷启后用 'adb logcat -s LingxiE' 确认：引擎已加载，mmap 索引：true"
fi
