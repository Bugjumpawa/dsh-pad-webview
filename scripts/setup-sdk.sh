#!/usr/bin/env bash
# 安装最小 Android 构建链：cmdline-tools + platform 34 + build-tools 34
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Android/sdk}"
VER=13114758
mkdir -p "$SDK/cmdline-tools"
cd "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "[setup] downloading cmdline-tools $VER"
  curl -fL --retry 3 --connect-timeout 15 -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-${VER}_latest.zip"
  rm -rf tmp latest
  mkdir -p tmp
  unzip -q cmdline-tools.zip -d tmp
  mv tmp/cmdline-tools latest
  rm -rf tmp cmdline-tools.zip
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
echo "[setup] licenses"
yes | "$SM" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
echo "[setup] packages"
"$SM" --sdk_root="$SDK" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
echo "[setup] installed:"
ls "$SDK/build-tools" "$SDK/platforms"
"$SDK/build-tools/34.0.0/aapt2" version || true
echo "[setup] DONE"
