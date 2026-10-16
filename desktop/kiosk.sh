#!/bin/sh
set -eu
export PULSE_SINK=qijian.audio
profile="$HOME/snap/chromium/common/htpc-next-kiosk"
mkdir -p "$profile"
exec /snap/bin/chromium --ozone-platform=wayland --class=htpc-next \
  --kiosk --no-first-run --no-default-browser-check --disable-session-crashed-bubble \
  --disable-features=Translate,TranslateUI --lang=zh-CN --autoplay-policy=no-user-gesture-required \
  --disable-background-timer-throttling --disable-renderer-backgrounding \
  --user-data-dir="$profile" --remote-debugging-address=127.0.0.1 --remote-debugging-port=9228 \
  "http://127.0.0.1:${HTPC_PORT:-8787}/?display=1"
