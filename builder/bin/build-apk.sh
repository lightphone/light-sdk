#!/usr/bin/env bash
# Container entrypoint.
#
# Inputs (via env or flags):
#   --git-url     <https URL of the dev's repo>
#   --git-ref     <branch/tag/commit to build>
#   --output-dir  <path inside the container to write artifacts into>
#   --tool-path   <relative path inside the dev repo where the tool lives;
#                  defaults to "tool". Use "." for repos whose root *is* the
#                  tool directory.>
#
# Optional env:
#   GH_TOKEN      If set, used to authenticate the dev-repo clone. Required
#                 for private GitHub repos. The orchestrator passes a
#                 short-lived token here; for local testing you can pass a
#                 PAT via `docker run -e GH_TOKEN=...`.
#
# This script owns the unsafe-but-necessary parts: cloning untrusted source
# and running gradle. Everything that touches the dev's source for inspection
# lives in the Python module (see lightbuilder/), which never touches the
# network.
#
# On success, ${OUTPUT_DIR} contains:
#   tool-unsigned.apk     — the unsigned APK
#   recipe.json           — sha256 + every input that fed the build
#   extraction.json       — list of files we accepted from the dev's repo
#   extracted-source.zip  — the accepted source files, exactly as staged
#   build.log             — full gradle stdout/stderr
#
# On failure, error.json or a non-zero exit explains why. The orchestrator
# is expected to retain build.log and surface the failure to the dev.

set -Eeuo pipefail

usage() {
    cat <<'USAGE' >&2
usage: build-apk.sh --git-url URL --git-ref REF --output-dir DIR
                    [--tool-path PATH]
USAGE
    exit 64
}

GIT_URL=""
GIT_REF=""
OUTPUT_DIR=""
TOOL_PATH="tool"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --git-url) GIT_URL="$2"; shift 2 ;;
        --git-ref) GIT_REF="$2"; shift 2 ;;
        --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
        --tool-path) TOOL_PATH="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown flag: $1" >&2; usage ;;
    esac
done

[[ -n "$GIT_URL" && -n "$GIT_REF" && -n "$OUTPUT_DIR" ]] || usage

# Image-build-time constants — see Dockerfile.
: "${LIGHT_SDK_HOME:?LIGHT_SDK_HOME must be set in the image}"
: "${LIGHT_BUILDER_HOME:?LIGHT_BUILDER_HOME must be set in the image}"
: "${LIGHT_SDK_GIT_REF:?LIGHT_SDK_GIT_REF must be baked into the image}"
: "${LIGHT_IMAGE_DIGEST:=unknown}"  # filled in by the orchestrator at runtime

mkdir -p "$OUTPUT_DIR"
DEV_REPO="$(mktemp -d -t devrepo.XXXXXX)"
trap 'rm -rf "$DEV_REPO"' EXIT

# --- Clone the dev's repo ----------------------------------------------------
# init/fetch so we can grab arbitrary refs (not just branch tips). --no-tags
# keeps history small; --depth=1 minimises network I/O. Fail closed on a clone
# that doesn't resolve to a real commit.
#
# If GH_TOKEN is set, plumb it through a one-shot credential helper so private
# GitHub repos resolve. The token never lands in git config, process args, or
# any file — it lives only in the env var while this RUN executes.
echo ">> cloning $GIT_URL @ $GIT_REF"
git -C "$DEV_REPO" init -q
git -C "$DEV_REPO" remote add origin "$GIT_URL"
if [[ -n "${GH_TOKEN:-}" ]]; then
    git -C "$DEV_REPO" \
        -c credential.helper="!f() { echo username=x-access-token; echo password=$GH_TOKEN; }; f" \
        fetch --no-tags --depth=1 origin "$GIT_REF" 2>&1 | tee -a "$OUTPUT_DIR/build.log"
else
    git -C "$DEV_REPO" fetch --no-tags --depth=1 origin "$GIT_REF" 2>&1 | tee -a "$OUTPUT_DIR/build.log"
fi
git -C "$DEV_REPO" checkout -q FETCH_HEAD
DEV_GIT_COMMIT="$(git -C "$DEV_REPO" rev-parse HEAD)"
DEV_COMMIT_EPOCH="$(git -C "$DEV_REPO" show -s --format=%ct HEAD)"
echo ">> dev commit: $DEV_GIT_COMMIT (epoch $DEV_COMMIT_EPOCH)"

# --- Stage workspace ---------------------------------------------------------
# We never mutate the baked SDK directly. Copy it to a per-job workspace so
# concurrent builds (if any) cannot collide. The SDK source is the
# image-baked one, NOT anything from the dev's repo.
WORKSPACE="$(mktemp -d -t workspace.XXXXXX)"
trap 'rm -rf "$DEV_REPO" "$WORKSPACE"' EXIT
cp -a "$LIGHT_SDK_HOME/." "$WORKSPACE/"

# Make sure no stale dev artifacts can possibly be present in the workspace.
rm -rf "$WORKSPACE/tool/build" "$WORKSPACE/build"

# --- Prepare phase (Python) --------------------------------------------------
# Reads dev repo, validates lighttool.toml, extracts allowlisted files into
# the workspace's tool/ module, writes manifest + build.gradle.kts.
echo ">> staging tool module from dev source"
python3 -m lightbuilder prepare \
    --dev-repo "$DEV_REPO" \
    --workspace-tool "$WORKSPACE/tool" \
    --tool-path "$TOOL_PATH" \
    --output-dir "$OUTPUT_DIR" \
    2>&1 | tee -a "$OUTPUT_DIR/build.log"

# --- Build phase (gradle) ----------------------------------------------------
# --offline is what makes the network-policy claim meaningful: at runtime the
# build has no permission to fetch anything. Everything it needs was warmed
# into GRADLE_USER_HOME at image build time.
#
# SOURCE_DATE_EPOCH is set from the dev commit so any toolchain that honours
# it produces deterministic timestamps. Note: not all of AGP currently
# honours this; achieving full byte reproducibility is a follow-up.
export SOURCE_DATE_EPOCH="$DEV_COMMIT_EPOCH"
# -DlightSdk.unsigned=true tells the SDK's plugin to clear signingConfig so
# AGP emits an unsigned APK, ready for the signing service to apply the
# per-app key. Locally devs run gradle without this flag and the dev keystore
# is used as normal.
GRADLE_ARGS=(
    ":tool:assembleRelease"
    "--no-daemon"
    "--offline"
    "--no-build-cache"
    "--stacktrace"
    "-DlightSdk.unsigned=true"
)
echo ">> running gradle ${GRADLE_ARGS[*]}"
(cd "$WORKSPACE" && ./gradlew "${GRADLE_ARGS[@]}") 2>&1 | tee -a "$OUTPUT_DIR/build.log"

# --- Collect phase (Python) --------------------------------------------------
# Hashes the artifact, writes recipe.json.
GRADLE_CMD_JSON="$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' "${GRADLE_ARGS[@]}")"
echo ">> collecting artifact"
python3 -m lightbuilder collect \
    --workspace "$WORKSPACE" \
    --output-dir "$OUTPUT_DIR" \
    --image-digest "$LIGHT_IMAGE_DIGEST" \
    --sdk-git-ref "$LIGHT_SDK_GIT_REF" \
    --dev-git-url "$GIT_URL" \
    --dev-git-ref "$GIT_REF" \
    --dev-git-commit "$DEV_GIT_COMMIT" \
    --gradle-command "$GRADLE_CMD_JSON" \
    --source-date-epoch "$DEV_COMMIT_EPOCH"

echo ">> done; artifacts in $OUTPUT_DIR"
