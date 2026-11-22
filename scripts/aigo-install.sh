#!/system/bin/sh
# AiGo 构建产物安装脚本（在宿主 Android 上以 root 执行）
#
# 解决的核心问题：pm install 直接读 /storage/emulated/0 下的 APK 会因 SELinux
# 被拒（system_server 无权读 u:object_r:fuse:s0），必须先把文件复制到
# /data/local/tmp 再装。
#
# 用法：
#   sh aigo-install.sh <apk 路径>
#   sh aigo-install.sh /storage/emulated/0/Download/AiGo/AiGo开发/app-debug.apk

set -e

APK="$1"
if [ -z "$APK" ]; then
  echo "用法: sh $0 <apk 路径>"
  exit 1
fi

if [ ! -f "$APK" ]; then
  echo "找不到 APK: $APK"
  exit 1
fi

NAME=$(basename "$APK")
TMP="/data/local/tmp/$NAME"

echo "==> 复制到 $TMP"
cp "$APK" "$TMP"
chmod 644 "$TMP"

echo "==> 安装（覆盖保留数据）"
pm install -r "$TMP"

echo "==> 清理临时文件"
rm -f "$TMP"

echo "==> 已安装版本"
dumpsys package com.aigo | grep -E "versionName|versionCode" | head -2
