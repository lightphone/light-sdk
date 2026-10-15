#!/usr/bin/env bash
# End-to-end builder check: builds the sample tool and the fixtures in
# tests/fixtures/ through bin/build.sh, then checks every packaged
# native library is approved by the build's native-libraries.json.
#
# usage: tests/integration.sh IMAGE WORKDIR [FIXTURE...]
#
# FIXTURE is "sample" or a directory name under tests/fixtures/; defaults to
# all of them. WORKDIR must not exist and, with Colima/Docker Desktop, must be
# under a directory shared with the Docker VM (e.g. $HOME).

set -Eeuo pipefail

[[ $# -ge 2 ]] || { echo "usage: integration.sh IMAGE WORKDIR [FIXTURE...]" >&2; exit 64; }
IMAGE="$1"
WORKDIR="$2"
shift 2

BUILDER="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="$(cd "$BUILDER/.." && pwd)"

if [[ $# -gt 0 ]]; then
    FIXTURES=("$@")
else
    FIXTURES=(sample)
    for dir in "$BUILDER"/tests/fixtures/*/; do
        FIXTURES+=("$(basename "$dir")")
    done
fi

if [[ -e "$WORKDIR" ]]; then
    echo "workdir already exists: $WORKDIR" >&2
    exit 1
fi
mkdir -p "$WORKDIR/repos"
WORKDIR="$(cd "$WORKDIR" && pwd)"

fixture_repo() {
    local name="$1"
    if [[ "$name" == "sample" ]]; then
        echo "$SDK_ROOT"
        return
    fi
    local repo="$WORKDIR/repos/$name"
    cp -R "$BUILDER/tests/fixtures/$name" "$repo"
    git -C "$repo" init -q
    git -C "$repo" add -A
    git -C "$repo" -c user.name=fixture -c user.email=fixture@example.com commit -qm fixture
    echo "$repo"
}

for name in "${FIXTURES[@]}"; do
    echo "== $name"
    out="$WORKDIR/out/$name"
    "$BUILDER/bin/build.sh" --image "$IMAGE" --dev-repo "$(fixture_repo "$name")" \
        --output-dir "$out" > "$WORKDIR/$name.log" 2>&1 \
        || { tail -50 "$WORKDIR/$name.log"; echo "$name: build failed" >&2; exit 1; }
    requests="$(grep -cE ' (GET|HEAD) ' "$out/proxy-access.log" || true)"
    echo "   proxy requests: $requests"
    python3 "$BUILDER/tests/check_native.py" "$out/build/tool-unsigned.apk" "$out/native-libraries.json"
done

echo "all fixtures passed"
