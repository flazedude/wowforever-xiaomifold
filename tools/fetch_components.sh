#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$REPO_DIR/app/src/main/assets"
DEST="$ASSETS/bundled_components"
RELEASE_URL="${COMPONENTS_URL:-https://github.com/jaredgei/wow-forever-android/releases/download/components-v1}"
GAMENATIVE_URL="${GAMENATIVE_COMPONENTS_URL:-https://downloads.gamenative.app}"
FILES=(
    proton-11.0-90624-arm64ec.wcp
    dxvk-2.4.1-wow-aarch64-test.wcp
    turnip-wow-scheduler-test.zip
    turnip-V32-RP6sched.zip
    container_pattern_common_20260821.tzst
    extras.tzst
    wrapper-gamenative-20260724.tzst
    extra_libs.tzst
    imagefs_bionic.txz
)

mkdir -p "$DEST"
for f in "${FILES[@]}"; do
    target="$DEST/$f"
    if [ "$f" = "imagefs_bionic.txz" ]; then target="$ASSETS/$f"; fi
    if [ -s "$target" ]; then
        echo "have $f"
    elif [ -n "${1:-}" ] && [ -s "$1/$f" ]; then
        cp "$1/$f" "$target"
        echo "copied $f"
    else
        case "$f" in
            turnip-V32-RP6sched.zip)
                url="https://raw.githubusercontent.com/arusiasotto/wow-forever-a840/main/driver/turnip-V32-RP6sched.zip" ;;
            container_pattern_common_20260821.tzst|extras.tzst)
                url="$GAMENATIVE_URL/container_files/$f" ;;
            wrapper-gamenative-20260724.tzst|extra_libs.tzst)
                url="$GAMENATIVE_URL/graphics_driver/$f" ;;
            imagefs_bionic.txz)
                url="$GAMENATIVE_URL/$f" ;;
            *) url="$RELEASE_URL/$f" ;;
        esac
        curl -fL --retry 3 --connect-timeout 20 -o "$target.part" "$url"
        mv "$target.part" "$target"
        echo "downloaded $f"
    fi
done

(cd "$ASSETS" && shasum -a 256 -c "$REPO_DIR/tools/components.sha256")
