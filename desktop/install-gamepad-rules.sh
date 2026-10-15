#!/bin/sh
set -eu
if [ "$(id -u)" != 0 ]; then
  echo 'Run this installer with sudo to configure persistent gamepad access.' >&2
  exit 1
fi
getent group plugdev >/dev/null
dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
udevadm verify "$dir/71-qijian-gamepad.rules"
install -m 0644 "$dir/71-qijian-gamepad.rules" /etc/udev/rules.d/71-qijian-gamepad.rules
udevadm control --reload-rules
udevadm trigger --action=change --subsystem-match=input --property-match=ID_INPUT_JOYSTICK=1
if [ -e /sys/devices/virtual/misc/uinput ]; then
  udevadm trigger --action=change /sys/devices/virtual/misc/uinput
fi
udevadm settle
echo 'Gamepad access configured. Reconnect the controller so Steam and the game rediscover it.'
