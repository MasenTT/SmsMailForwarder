#!/usr/bin/env bash
set -euo pipefail

package_name="cn.smsmail.forwarder"
output_file="${1:-SmsMailForwarder-device-debug.txt}"

if [[ -n "${ADB:-}" ]]; then
  adb_bin="$ADB"
elif command -v adb >/dev/null 2>&1; then
  adb_bin="$(command -v adb)"
elif [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
  adb_bin="$HOME/Library/Android/sdk/platform-tools/adb"
else
  echo "找不到 adb。请安装 Android platform-tools，或先设置 ADB=/path/to/adb。" >&2
  exit 3
fi

device_list=$("$adb_bin" devices | awk '$2 == "device" {print $1}')
device_count=$(printf '%s\n' "$device_list" | awk 'NF {count++} END {print count + 0}')
if [[ "$device_count" -ne 1 ]]; then
  echo "需要恰好连接一台处于 device 状态的 Android 手机。当前设备数：$device_count" >&2
  exit 2
fi

device="$device_list"
adb_args=(-s "$device")

{
  echo "SmsMailForwarder device debug"
  echo "device=$device"
  echo "collected_at=$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo
  echo "[device]"
  "$adb_bin" "${adb_args[@]}" shell getprop ro.product.manufacturer
  "$adb_bin" "${adb_args[@]}" shell getprop ro.product.model
  "$adb_bin" "${adb_args[@]}" shell getprop ro.build.version.release
  "$adb_bin" "${adb_args[@]}" shell getprop ro.build.version.sdk
  "$adb_bin" "${adb_args[@]}" shell getprop ro.build.version.incremental
  "$adb_bin" "${adb_args[@]}" shell getprop ro.miui.ui.version.name
  echo
  echo "[package permissions and state]"
  "$adb_bin" "${adb_args[@]}" shell dumpsys package "$package_name" | grep -E 'versionName=|versionCode=|targetSdk=|RECEIVE_SMS|requested permissions:|User 0:|enabled=|stopped=' || true
  echo
  echo "[appops]"
  "$adb_bin" "${adb_args[@]}" shell cmd appops get "$package_name" | grep -E 'SMS|RUN_IN_BACKGROUND|RUN_ANY_IN_BACKGROUND|START_FOREGROUND|AUTO_START' || true
  echo "RECEIVE_SMS (explicit query)"
  "$adb_bin" "${adb_args[@]}" shell cmd appops get "$package_name" RECEIVE_SMS || true
  echo
  echo "[power and idle exemptions]"
  "$adb_bin" "${adb_args[@]}" shell dumpsys deviceidle whitelist | grep -F "$package_name" || true
  "$adb_bin" "${adb_args[@]}" shell dumpsys battery | grep -E 'AC powered|USB powered|Mobile powered|level=' || true
  echo
  echo "[foreground activity]"
  "$adb_bin" "${adb_args[@]}" shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' || true
  echo
  echo "[application errors only; SMS content is not collected]"
  "$adb_bin" "${adb_args[@]}" logcat -d -v time -s SmsMailForwarder:V AndroidRuntime:E ActivityTaskManager:W | tail -240 || true
} > "$output_file"

echo "已写入：$output_file"
echo "文件中不包含短信正文；发送前请检查是否有邮箱地址或其他个人信息。"
