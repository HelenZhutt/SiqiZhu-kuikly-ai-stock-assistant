#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEV_ROOT=${DEVECO_STUDIO_HOME:-/Applications/DevEco-Studio.app/Contents}

if [ ! -d "$DEV_ROOT/sdk/default" ] || [ ! -x "$DEV_ROOT/tools/hvigor/bin/hvigorw" ]; then
  echo "error: 未找到完整的 DevEco Studio。可通过 DEVECO_STUDIO_HOME 指定其 Contents 目录。" >&2
  exit 1
fi

export DEVECO_SDK_HOME="$DEV_ROOT/sdk"
export PATH="$DEV_ROOT/jbr/Contents/Home/bin:$DEV_ROOT/tools/node/bin:$DEV_ROOT/tools/ohpm/bin:$DEV_ROOT/tools/hvigor/bin:$PATH"

# Kuikly's Kotlin/Native toolchain queries xcodebuild even for an OHOS target.
# Keep this local to the script instead of changing the user's global xcode-select.
if [ -d /Applications/Xcode.app/Contents/Developer ]; then
  export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

cd "$SCRIPT_DIR"

echo "[1/3] 安装 HarmonyOS 依赖"
ohpm install --all

echo "[2/3] 同步工程"
hvigorw --sync -p product=default --analyze=normal --parallel --incremental

echo "[3/3] 构建 HAP"
hvigorw --mode module \
  -p module=entry@default \
  -p product=default \
  -p requiredDeviceType=phone \
  assembleHap --analyze=normal --parallel --incremental

OUTPUT_DIR="$SCRIPT_DIR/entry/build/default/outputs/default"
SIGNED_HAP="$OUTPUT_DIR/entry-default-signed.hap"
UNSIGNED_HAP="$OUTPUT_DIR/entry-default-unsigned.hap"
HDC="$DEV_ROOT/sdk/default/openharmony/toolchains/hdc"

if [ "${1:-run}" = "build" ]; then
  echo "构建完成：$UNSIGNED_HAP"
  exit 0
fi

TARGETS=$($HDC list targets 2>/dev/null || true)
if [ -z "$TARGETS" ]; then
  echo "构建完成：$UNSIGNED_HAP"
  echo "尚未发现鸿蒙设备/模拟器。请在 DevEco Studio 启动设备后再次运行此脚本。"
  exit 0
fi

if [ ! -f "$SIGNED_HAP" ]; then
  echo "已生成未签名 HAP，但连接设备安装前需要签名。"
  echo "请在 DevEco Studio 中打开 $SCRIPT_DIR，进入 Project Structure > Signing Configs 配置自动签名后重试。"
  exit 2
fi

for TARGET_ID in $TARGETS; do
  echo "安装并启动到 $TARGET_ID"
  $HDC -t "$TARGET_ID" shell aa force-stop com.example.stockaidemo >/dev/null 2>&1 || true
  $HDC -t "$TARGET_ID" install -r "$SIGNED_HAP"
  $HDC -t "$TARGET_ID" shell aa start -a EntryAbility -b com.example.stockaidemo
done

echo "HarmonyOS 应用已启动。"
