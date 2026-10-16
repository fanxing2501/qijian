#!/bin/sh
set -eu
export PULSE_SINK=qijian.audio
# The official client keeps credentials/runtime data in its normal user profile.
# A display is required even though all management windows stay out of view.
exec /usr/games/steam -silent -cef-enable-debugging -cef-debugging-port=8080 "$@"
