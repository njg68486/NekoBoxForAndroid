#!/bin/sh
# kl 本地增量编译（stub libcore + qemu aapt2）
set -e
cd /root/work/kl
export ANDROID_HOME=/root/android-sdk
export ANDROID_NDK_HOME=/root/android-sdk/ndk/25.0.8775105
AAPT2=/root/toolchain/aapt2
./gradlew :app:assembleOssDebug \
  -Pandroid.aapt2FromMavenOverride=$AAPT2 \
  "$@"
