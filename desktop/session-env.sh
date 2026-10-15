#!/bin/sh
set -eu
umask 077
target="$HOME/.config/htpc-next/desktop.env"
mkdir -p "$(dirname "$target")"
printf 'DISPLAY=%s\nWAYLAND_DISPLAY=%s\nSWAYSOCK=%s\n' "$DISPLAY" "$WAYLAND_DISPLAY" "$SWAYSOCK" > "$target.tmp"
printf 'XDG_CURRENT_DESKTOP=sway\nXDG_SESSION_TYPE=wayland\n' >> "$target.tmp"
mv "$target.tmp" "$target"
/usr/bin/systemd-notify --ready
