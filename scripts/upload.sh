#!/bin/sh
set -eu
. ./config.env
. "./$RELEASE_MANIFEST"
for file in "$ARTIFACT_FILE" "$ARTIFACT_FILE.sha256"; do
  curl --fail --silent --show-error --retry 3 --upload-file "artifacts/$file" "$S3_UPLOAD_BASE/$file"
done
printf 'Artifact: %s\nSHA-256: %s\n' "$ARTIFACT_URL" "$ARTIFACT_SHA256"
