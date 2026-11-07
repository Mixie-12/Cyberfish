#!/bin/sh
# Renders the adaptive launcher icon layers in this folder into the app's
# mipmap folders, with the installed Microsoft Edge in headless mode.
# Windows, Git Bash: sh clients/app/icon/render.sh
#
# Each layer is drawn onto a canvas of the exact size and read back as a
# data URL; a screenshot would not do, since headless Edge widens small
# windows.
set -eu

edge="${EDGE:-/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe}"
here="$(cd "$(dirname "$0")" && pwd)"
res="${RES:-$here/../src/main/res}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

render() { # layer, size in pixels, output file
    svg="$(sed "1s/width=\"1536\" height=\"1536\"/width=\"$2\" height=\"$2\"/" "$here/launcher-$1.svg" | base64 -w0)"
    cat >"$work/page.html" <<PAGE
<!doctype html><meta charset="utf-8"><body><script>
const image = new Image();
image.onload = () => {
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = $2;
  canvas.getContext("2d").drawImage(image, 0, 0);
  document.body.textContent = canvas.toDataURL("image/png");
};
image.src = "data:image/svg+xml;base64,$svg";
</script>
PAGE
    "$edge" --headless --disable-gpu --user-data-dir="$(cygpath -w "$work/profile")" \
        --virtual-time-budget=10000 --dump-dom "file:///$(cygpath -m "$work/page.html")" 2>/dev/null |
        sed -n 's/.*data:image\/png;base64,\([A-Za-z0-9+\/=]*\).*/\1/p' | base64 -d >"$3"
    if [ ! -s "$3" ]; then
        echo "Edge did not render $1 at $2 px" >&2
        exit 1
    fi
}

for density in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
    mkdir -p "$res/mipmap-${density%%:*}"
    for layer in background foreground; do
        render "$layer" "${density##*:}" "$res/mipmap-${density%%:*}/ic_launcher_$layer.png"
    done
done
