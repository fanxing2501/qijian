#!/bin/sh
set -eu
. ./config.env
mkdir -p artifacts
stage=$(mktemp -d artifacts/package.XXXXXX)
trap 'rm -rf "$stage"' EXIT HUP INT TERM
cp dist/htpc-next.jar "$stage/"
cp -R dist/web dist/native "$stage/"
cp README.md "$stage/"
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf - -C "$stage" . | gzip -n > artifacts/package.tar.gz
hash=$(sha256sum artifacts/package.tar.gz | cut -d ' ' -f 1)
name="$PROJECT_NAME-$hash.tar.gz"
mv artifacts/package.tar.gz "artifacts/$name"
(cd artifacts && sha256sum "$name" > "$name.sha256")
printf 'ARTIFACT_VERSION=%s\nARTIFACT_FILE=%s\nARTIFACT_SHA256=%s\nARTIFACT_URL=%s/%s\n' "$hash" "$name" "$hash" "$S3_DOWNLOAD_BASE" "$name" > "$RELEASE_MANIFEST"
printf '%s\n' "artifacts/$name"
