#!/bin/bash
# 芭芭农场真机节点树采集脚本
# 用途：dump 当前页面所有可见文本，用于校准 Selectors 中的文案候选
# 前提：设备已授权 adb
#
# 环境变量（可选，避免把本机路径/序列号写进脚本）：
#   ADB          adb 可执行文件路径（默认使用 PATH 里的 adb）
#   ADB_SERIAL   设备序列号（默认不指定；只连一台设备时无需设置）
#
# 用法：
#   bash tools/dump_nodes.sh [序列号]
ADB=${ADB:-adb}
DEV=${ADB_SERIAL:-${1:-}}
SERIAL_ARGS=()
if [ -n "$DEV" ]; then
  SERIAL_ARGS=(-s "$DEV")
fi

echo "=== 设备 ==="
"$ADB" "${SERIAL_ARGS[@]}" shell getprop ro.product.model
echo "=== 当前前台应用 ==="
"$ADB" "${SERIAL_ARGS[@]}" shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -3
echo ""
echo "=== uiautomator 节点树 ==="
"$ADB" "${SERIAL_ARGS[@]}" shell uiautomator dump /sdcard/farm_dump.xml 2>&1 | head -2
"$ADB" "${SERIAL_ARGS[@]}" shell cat /sdcard/farm_dump.xml > /tmp/farm_dump.xml 2>/dev/null
echo "已保存到 /tmp/farm_dump.xml，大小: $(wc -c < /tmp/farm_dump.xml) 字节"
echo ""
echo "=== 全部可见文案（去重）==="
grep -o 'text="[^"]*"' /tmp/farm_dump.xml 2>/dev/null \
  | sed 's/text="//; s/"$//' \
  | grep -v '^$' \
  | sort -u
