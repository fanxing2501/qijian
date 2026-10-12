#!/bin/sh
set -eu
. ./config.env
. "./$RELEASE_MANIFEST"
release="$HOME/.local/share/htpc-next/releases/$ARTIFACT_VERSION"
mkdir -p "$release" "$HOME/.config/systemd/user" "$HOME/.config/htpc-next"
archive="$release/artifact.tar.gz"
curl --fail --silent --show-error --location --retry 3 "$ARTIFACT_URL" -o "$archive"
printf '%s  %s\n' "$ARTIFACT_SHA256" "$archive" | sha256sum -c -
tar -xzf "$archive" -C "$release"
groot_path=$(command -v groot)
unit="$HOME/.config/systemd/user/groot-htpc-next.service"
cat > "$unit" <<EOF
[Unit]
Description=HTPC Next HTTP capability service
StartLimitIntervalSec=60
StartLimitBurst=3

[Service]
Type=simple
WorkingDirectory=$release
Environment=HTPC_HOST=$HTPC_HOST
Environment=HTPC_PORT=$HTPC_PORT
Environment=HTPC_WEB=$release/web
Environment=HTPC_DLL_HOST=$release/native/dll-host.exe
EnvironmentFile=-$HOME/.config/htpc-next/runtime.env
ExecStart=$groot_path use-jdk $JDK_MAJOR -- java -jar $release/htpc-next.jar
Restart=on-failure
RestartSec=3
TimeoutStopSec=90

[Install]
WantedBy=default.target
EOF
chmod 0644 "$unit"
systemd-analyze --user verify "$unit"
systemctl --user daemon-reload
systemctl --user enable groot-htpc-next.service
systemctl --user restart groot-htpc-next.service
curl --fail --silent --show-error --retry 20 --retry-connrefused --retry-delay 1 --max-time 3 "http://127.0.0.1:$HTPC_PORT/api/v1/health"
systemctl --user show groot-htpc-next.service -p ActiveState -p SubState -p MainPID
