#!/bin/sh
set -eu
. ./config.env
# Same archive and checksum as Ubuntu's steam-installer package. This performs
# its first-time bootstrap without displaying the installer's Zenity question.
root="$HOME/.steam/debian-installation"
if [ -x "$root/steam.sh" ]; then exit 0; fi
systemctl --user stop groot-htpc-steam.service 2>/dev/null || true
mkdir -p "$root/deb-installer" artifacts
archive="artifacts/steam-$STEAM_BOOTSTRAP_VERSION.tar.gz"
curl --fail --location --retry 3 "https://repo.steampowered.com/steam/archive/beta/steam_$STEAM_BOOTSTRAP_VERSION.tar.gz" -o "$archive"
printf '%s  %s\n' "$STEAM_BOOTSTRAP_SHA256" "$archive" | sha256sum -c -
tar -xzf "$archive" -C "$root/deb-installer" steam-launcher/bootstraplinux_ubuntu12_32.tar.xz
cp "$root/deb-installer/steam-launcher/bootstraplinux_ubuntu12_32.tar.xz" "$root/bootstrap.tar.xz"
tar -xf "$root/bootstrap.tar.xz" -C "$root"
printf '%s\n' "$STEAM_BOOTSTRAP_VERSION" > "$root/deb-installer/version"
for link in root steam; do
  if [ ! -e "$HOME/.steam/$link" ]; then ln -s "$root" "$HOME/.steam/$link"; fi
done
