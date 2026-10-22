#!/bin/zsh
# Builds (if needed) and runs the Mac side of Touchpad. Add --new-pin to change the PIN.
cd "${0:A:h}/mac-server"
if [[ ! -x TouchpadServer || main.swift -nt TouchpadServer ]]; then
  echo "Building TouchpadServer…"
  swiftc -O main.swift -o TouchpadServer || exit 1
fi
exec ./TouchpadServer "$@"
