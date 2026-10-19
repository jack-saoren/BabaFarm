#!/bin/bash
# 芭芭农场真机节点树采集脚本
# 用途：dump 当前页面所有可见文本，用于校准 Selectors 中的文案候选
# 前提：设备已授权 adb
export PATH="$PATH:$ANDROID_SDK/platform-tools"
DEV=${1:-YOUR_DEVICE_SERIAL}

echo "=== 设备 ==="
adb -s $DEV shell getprop ro.product.model
echo "=== 当前前台应用 ==="
adb -s $DEV shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -3
echo ""
echo "=== uiautomator 节点树 ==="
adb -s $DEV shell uiautomator dump /sdcard/farm_dump.xml 2>&1 | head -2
adb -s $DEV shell cat /sdcard/farm_dump.xml > /tmp/farm_dump.xml 2>/dev/null
echo "已保存到 /tmp/farm_dump.xml，大小: $(wc -c < /tmp/farm_dump.xml) 字节"
echo ""
echo "=== 全部可见文案（去重）==="
grep -o 'text="[^"]*"' /tmp/farm_dump.xml 2>/dev/null \
  | sed 's/text="//; s/"$//' \
  | grep -v '^$' \
  | sort -u