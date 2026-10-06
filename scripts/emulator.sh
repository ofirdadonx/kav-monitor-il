#!/usr/bin/env bash
# Headless Android emulator for testing kav-monitor-app on this machine.
#   scripts/emulator.sh start     # boot (if not running) and wait until ready; sets TZ Asia/Jerusalem
#   scripts/emulator.sh stop      # graceful shutdown
#   scripts/emulator.sh status
#   scripts/emulator.sh install   # adb install -r the debug APK
#   scripts/emulator.sh shot NAME # screenshot to docs/emulator/NAME.png
# Env overrides: AVD_NAME (default kav_pixel_api36), ANDROID_HOME (default ~/android-sdk), EMU_EXTRA_ARGS
#
# WARNING (2026-10-06): on the current agent box this does NOT work. /dev/kvm opens, but creating a vCPU hits a
# kernel BUG (kvm_spurious_fault in vmx_vcpu_create; see `sudo dmesg`). The box is a snapshot-restored microVM
# ("virtual machine fork") with kvm.enable_virt_at_load=Y, so the VMX state set up at boot is stale. Each attempt
# oopses the kernel and leaves a hung qemu process, so `start` refuses to run when that oops is in dmesg (FORCE=1 overrides).
# It should work unchanged on a machine with working (nested) KVM.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
AVD_NAME="${AVD_NAME:-kav_pixel_api36}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="/tmp/emulator-$AVD_NAME.log"
TZ_ID="Asia/Jerusalem"

# /dev/kvm is root:kvm(103) 0660. `box` is a member of group kvm (/etc/group), but shells started before that
# change don't carry the group, and a user ACL on /dev/kvm was observed to get reset. So the emulator is always
# launched through `sg kvm`, which gives the process the kvm group regardless of the parent shell.
ensure_kvm() {
  local kmsg; kmsg="$(sudo -n dmesg 2>/dev/null || true)"
  if [ "${FORCE:-0}" != 1 ] && [[ "$kmsg" == *kvm_spurious_fault* ]]; then
    echo "KVM is broken on this machine (kernel BUG kvm_spurious_fault in dmesg); refusing to start. FORCE=1 to override." >&2
    exit 3
  fi
  getent group kvm | grep -qE "[:,]$(id -un)(,|$)" || sudo -n usermod -aG kvm "$(id -un)"
  [ "$(stat -c %G /dev/kvm)" = "kvm" ] || sudo -n chgrp kvm /dev/kvm
  sg kvm -c 'test -r /dev/kvm -a -w /dev/kvm' || { echo "no KVM access" >&2; exit 1; }
}

running() { "$ADB" devices 2>/dev/null | grep -q '^emulator-.*device$'; }

wait_boot() {
  "$ADB" wait-for-device
  for _ in $(seq 1 180); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && return 0
    sleep 2
  done
  echo "boot timeout; see $LOG" >&2; return 1
}

post_boot() {
  "$ADB" shell settings put global auto_time_zone 0 >/dev/null 2>&1 || true
  "$ADB" shell cmd alarm set-timezone "$TZ_ID" >/dev/null 2>&1 || "$ADB" shell setprop persist.sys.timezone "$TZ_ID"
  # keep the screen on while plugged in, no lock screen, fast animations for screenshots
  "$ADB" shell settings put global stay_on_while_plugged_in 7 >/dev/null 2>&1 || true
  "$ADB" shell locksettings set-disabled true >/dev/null 2>&1 || true
  "$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
}

case "${1:-start}" in
  start)
    ensure_kvm
    if running; then echo "emulator already running"; else
      rm -f "$HOME/.android/avd/$AVD_NAME.avd/"*.lock
      setsid nohup sg kvm -c "exec '$EMU' -avd '$AVD_NAME' -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
        -timezone '$TZ_ID' -no-snapshot-save ${EMU_EXTRA_ARGS:-}" >"$LOG" 2>&1 < /dev/null &
      echo "started emulator (log $LOG)"
    fi
    wait_boot
    post_boot
    echo "ready: $("$ADB" shell getprop ro.product.model | tr -d '\r'), API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r'), TZ $("$ADB" shell getprop persist.sys.timezone | tr -d '\r'), $("$ADB" shell date | tr -d '\r')"
    ;;
  stop)
    running && "$ADB" emu kill || echo "not running" ;;
  status)
    if running; then "$ADB" devices -l; "$ADB" shell getprop sys.boot_completed; else echo "not running"; fi ;;
  install)
    "$ADB" install -r "$ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk" ;;
  shot)
    mkdir -p "$ROOT/docs/emulator"
    "$ADB" exec-out screencap -p > "$ROOT/docs/emulator/${2:?name}.png"
    echo "$ROOT/docs/emulator/$2.png" ;;
  *) echo "usage: $0 {start|stop|status|install|shot NAME}" >&2; exit 2 ;;
esac
