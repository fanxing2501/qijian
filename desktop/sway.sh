#!/bin/sh
set -eu
unset DISPLAY WAYLAND_DISPLAY
export WLR_BACKENDS=drm,libinput LIBSEAT_BACKEND=seatd
# WLR_DRM_DEVICES uses colons as separators; resolve the stable PCI symlink first.
export WLR_DRM_DEVICES="$(readlink -f "$HTPC_DRM_DEVICE")"
exec /usr/bin/sway --config "$HTPC_DESKTOP_RELEASE/sway.conf"
