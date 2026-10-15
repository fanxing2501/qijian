#!/bin/sh
set -eu
. ./config.env
. "./$DESKTOP_RELEASE_MANIFEST"
release="$HOME/.local/share/htpc-next/desktop/$ARTIFACT_VERSION"
mkdir -p "$release"
curl --fail --silent --show-error --location --retry 3 "$ARTIFACT_URL" -o "$release/artifact.tar.gz"
printf '%s  %s\n' "$ARTIFACT_SHA256" "$release/artifact.tar.gz" | sha256sum -c -
tar -xzf "$release/artifact.tar.gz" -C "$release"
udevadm verify "$release/71-qijian-gamepad.rules"
printf 'Install the verified device rules with:\nsudo /bin/sh "%s/install-gamepad-rules.sh"\n' "$release"
