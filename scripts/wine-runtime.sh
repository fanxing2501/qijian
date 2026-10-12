#!/bin/sh
set -eu
# Optional development runtime, extracted locally; does not install system packages.
mkdir -p .tools/wine-debs .tools/wine
if [ ! -x .tools/wine/usr/lib/x86_64-linux-gnu/wine/wine ]; then
  (
    cd .tools/wine-debs
    apt-get download wine64=10.0~repack-12ubuntu1 libwine=10.0~repack-12ubuntu1 wine-common=10.0~repack-12ubuntu1 libz-mingw-w64
  )
  for package in .tools/wine-debs/*.deb; do dpkg-deb -x "$package" .tools/wine; done
fi
# Ubuntu's relocated binary computes DATADIR/wine from /usr/share/wine.
# The normal installation is rescued by its absolute /usr/share fallback;
# supply the equivalent relative layout inside this extracted runtime.
if [ ! -e .tools/wine/usr/share/wine/wine ]; then
  ln -s . .tools/wine/usr/share/wine/wine
fi
# These DLL search-path links are normally supplied by package installation.
for arch in x86_64 i386; do
  triplet=x86_64-w64-mingw32
  [ "$arch" != i386 ] || triplet=i686-w64-mingw32
  target=".tools/wine/usr/lib/x86_64-linux-gnu/wine/$arch-windows/zlib1.dll"
  [ -e "$target" ] || ln -s "../../../../$triplet/lib/zlib1.dll" "$target"
done
printf 'HTPC_WINE=%s/.tools/wine/usr/lib/x86_64-linux-gnu/wine/wine\nHTPC_WINESERVER=%s/.tools/wine/usr/lib/x86_64-linux-gnu/wine/wineserver\n' "$PWD" "$PWD"
