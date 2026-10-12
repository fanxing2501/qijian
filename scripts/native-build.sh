#!/bin/sh
set -eu
mkdir -p .tools dist/native
ZIG=.tools/zig-x86_64-linux-0.15.2/zig
if [ ! -x "$ZIG" ]; then
  curl --fail --location --retry 3 https://ziglang.org/download/0.15.2/zig-x86_64-linux-0.15.2.tar.xz -o .tools/zig.tar.xz
  printf '%s  %s\n' 02aa270f183da276e5b5920b1dac44a63f1a49e55050ebde3aecc9eb82f93239 .tools/zig.tar.xz | sha256sum -c -
  tar -xJf .tools/zig.tar.xz -C .tools
fi
"$ZIG" cc -target x86_64-windows-gnu -O2 -Wall -Wextra native/dll-host.c -o dist/native/dll-host.exe
"$ZIG" cc -target x86_64-windows-gnu -O2 -Wall -Wextra -Wno-unused-parameter -shared native/example-adapter.c -o dist/native/example-adapter.dll
