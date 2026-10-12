#!/bin/sh
set -eu
# Keep development profiles under the worktree. This path also works with the
# Ubuntu Chromium snap's home-directory permissions.
HTPC_DATA=${HTPC_DATA:-"$PWD/.runtime/dev"}
export HTPC_DATA
local_wine="$PWD/.tools/wine/usr/lib/x86_64-linux-gnu/wine"
if [ -x "$local_wine/wine" ] && [ -x "$local_wine/wineserver" ]; then
  HTPC_WINE=${HTPC_WINE:-"$local_wine/wine"}
  HTPC_WINESERVER=${HTPC_WINESERVER:-"$local_wine/wineserver"}
  export HTPC_WINE HTPC_WINESERVER
fi
exec sh scripts/scala.sh run shared server/project.scala server/src/main --main-class htpc.server.Main
