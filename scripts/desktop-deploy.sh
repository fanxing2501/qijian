#!/bin/sh
set -eu
. ./config.env
. "./$DESKTOP_RELEASE_MANIFEST"
release="$HOME/.local/share/htpc-next/desktop/$ARTIFACT_VERSION"
mkdir -p "$release" "$HOME/.config/systemd/user" "$HOME/.config/htpc-next"
curl --fail --silent --show-error --location --retry 3 "$ARTIFACT_URL" -o "$release/artifact.tar.gz"
printf '%s  %s\n' "$ARTIFACT_SHA256" "$release/artifact.tar.gz" | sha256sum -c -
tar -xzf "$release/artifact.tar.gz" -C "$release"
cat > "$HOME/.config/systemd/user/groot-htpc-desktop.service" <<EOF
[Unit]
Description=Qijian TV desktop
StartLimitIntervalSec=60
StartLimitBurst=3

[Service]
Type=notify
NotifyAccess=all
WorkingDirectory=$release
Environment=HTPC_DRM_DEVICE=$HTPC_DRM_DEVICE
Environment=SWAYSOCK=%t/htpc-sway.sock
Environment=HTPC_DESKTOP_RELEASE=$release
ExecStart=/bin/sh $release/sway.sh
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF
cat > "$HOME/.config/systemd/user/groot-htpc-steam.service" <<EOF
[Unit]
Description=Qijian background game client
Requires=groot-htpc-desktop.service
After=groot-htpc-desktop.service
PartOf=groot-htpc-desktop.service
StartLimitIntervalSec=120
StartLimitBurst=3

[Service]
Type=simple
WorkingDirectory=$HOME
EnvironmentFile=$HOME/.config/htpc-next/desktop.env
ExecStart=/bin/sh $release/steam.sh
Restart=on-failure
RestartSec=5
TimeoutStopSec=90

[Install]
WantedBy=default.target
EOF
cat > "$HOME/.config/systemd/user/groot-htpc-kiosk.service" <<EOF
[Unit]
Description=Qijian TV browser
Requires=groot-htpc-desktop.service
Wants=groot-htpc-next.service
After=groot-htpc-desktop.service groot-htpc-next.service
PartOf=groot-htpc-desktop.service
StartLimitIntervalSec=60
StartLimitBurst=3

[Service]
Type=simple
WorkingDirectory=$HOME
EnvironmentFile=$HOME/.config/htpc-next/desktop.env
Environment=HTPC_PORT=$HTPC_PORT
ExecStart=/bin/sh $release/kiosk.sh
Restart=on-failure
RestartSec=3
TimeoutStopSec=30

[Install]
WantedBy=default.target
EOF
chmod 0644 "$HOME/.config/systemd/user/groot-htpc-desktop.service" "$HOME/.config/systemd/user/groot-htpc-steam.service" "$HOME/.config/systemd/user/groot-htpc-kiosk.service"
if [ ! -e "$HOME/.config/htpc-next/host-services.json" ]; then
  printf '%s\n' '{"steam":{"unit":"groot-htpc-steam.service","cdp":"http://127.0.0.1:8080"}}' > "$HOME/.config/htpc-next/host-services.json"
  chmod 0600 "$HOME/.config/htpc-next/host-services.json"
fi
systemd-analyze --user verify "$HOME/.config/systemd/user/groot-htpc-desktop.service" "$HOME/.config/systemd/user/groot-htpc-steam.service" "$HOME/.config/systemd/user/groot-htpc-kiosk.service"
systemctl --user daemon-reload
systemctl --user enable groot-htpc-desktop.service groot-htpc-steam.service groot-htpc-kiosk.service
# This desktop now owns the TV. Stop consumers before releasing the old DRM seat.
systemctl --user stop groot-htpc-steam.service groot-htpc-kiosk.service groot-htpc-desktop.service
systemctl --user disable --now groot-hearth-display.service groot-hearth-compositor.service
rm -f "$HOME/.config/htpc-next/desktop.env"
systemctl --user reset-failed groot-htpc-desktop.service groot-htpc-steam.service groot-htpc-kiosk.service
systemctl --user start groot-htpc-desktop.service
test -s "$HOME/.config/htpc-next/desktop.env"
test -S "$XDG_RUNTIME_DIR/htpc-sway.sock"
systemctl --user try-restart groot-htpc-next.service
systemctl --user start groot-htpc-steam.service
systemctl --user start groot-htpc-kiosk.service
systemctl --user show groot-htpc-desktop.service groot-htpc-steam.service groot-htpc-kiosk.service -p ActiveState -p SubState
