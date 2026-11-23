#!/usr/bin/env bash
# Optional: drive Pixel Halo's LED helper from a computer over USB/adb.
# The app can do all of this itself through Wireless debugging; this is the fallback.
#
#   scripts/halo-adb.sh start            start (or restart) the LED helper
#   scripts/halo-adb.sh stop             stop it and hand HiLight back to Android
#   scripts/halo-adb.sh status           what the helper reports
#   scripts/halo-adb.sh log              helper log
#   scripts/halo-adb.sh color '#ff00aa' [effect] [seconds]   needs "Automation" enabled in the app
#   scripts/halo-adb.sh off              clear an automation colour
set -euo pipefail
PKG=com.shashankanil.halo
MAIN=com.shashankanil.halo.core.HelperMain
LOG=/data/local/tmp/pixel-halo.log
CP='CLASSPATH=$(pm path '"$PKG"' | head -1 | cut -d: -f2)'

case "${1:-}" in
  start)  adb shell "$CP setsid nohup app_process /system/bin $MAIN > $LOG 2>&1 < /dev/null &"
          sleep 2; adb shell cat "$LOG" ;;
  stop)   adb shell "$CP app_process /system/bin $MAIN --stop" ;;
  status) adb shell cat "/sdcard/Android/data/$PKG/files/status.json"; echo ;;
  log)    adb shell cat "$LOG" ;;
  color)  adb shell am broadcast -n "$PKG/.AutomationReceiver" -a "$PKG.SET" \
            --es color "'${2:?colour like #ff00aa}'" --es effect "${3:-solid}" --ei seconds "${4:-30}" ;;
  off)    adb shell am broadcast -n "$PKG/.AutomationReceiver" -a "$PKG.OFF" ;;
  *)      sed -n '2,12p' "$0"; exit 1 ;;
esac
