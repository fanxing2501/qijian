#!/bin/sh
set -eu
. ./config.env
mkdir -p artifacts
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf - -C desktop . | gzip -n > artifacts/desktop.tar.gz
hash=$(sha256sum artifacts/desktop.tar.gz | cut -d ' ' -f 1)
name="$PROJECT_NAME-desktop-$hash.tar.gz"
mv artifacts/desktop.tar.gz "artifacts/$name"
(cd artifacts && sha256sum "$name" > "$name.sha256")
printf 'ARTIFACT_VERSION=%s\nARTIFACT_FILE=%s\nARTIFACT_SHA256=%s\nARTIFACT_URL=%s/%s\n' "$hash" "$name" "$hash" "$S3_DOWNLOAD_BASE" "$name" > "$DESKTOP_RELEASE_MANIFEST"
