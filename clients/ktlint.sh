#!/bin/sh
# ktlint, the only Kotlin formatter in this project, at a pinned version.
# The download is checked against a SHA-256 recorded after verifying the
# release's GPG signature (key ADBC987D1A7B91DB6B0AAA81995EFBF4A3D20BEB).
# Usage: ./ktlint.sh            check
#        ./ktlint.sh --format   fix
set -eu

version=1.8.0
sha256=a3fd620207d5c40da6ca789b95e7f823c54e854b7fade7f613e91096a3706d75
jar="${XDG_CACHE_HOME:-$HOME/.cache}/dekoi/ktlint-$version"

if [ ! -f "$jar" ]; then
    mkdir -p "$(dirname "$jar")"
    curl -fsSL -o "$jar.download" "https://github.com/ktlint/ktlint/releases/download/$version/ktlint"
    mv "$jar.download" "$jar"
fi
echo "$sha256  $jar" | sha256sum -c - >/dev/null

cd "$(dirname "$0")"
exec java -jar "$jar" --relative "$@" "**/*.kt" "**/*.kts" '!**/build/**'
