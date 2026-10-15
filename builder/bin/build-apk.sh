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
#   --dev-repo    <optional; path to an already checked-out dev repo, see
#                  "Source modes" below>
#   --abi-filters <comma-separated ABIs to package native libraries for;
#                  defaults to arm64-v8a, the ABI Light devices run. Pass ""
#                  to keep every ABI.>
#
# Source modes:
#   clone (default)  We fetch --git-url @ --git-ref ourselves, so the
#                    container needs network access to the git host.
#   mounted          --dev-repo points at a checkout a trusted preflight job
#                    already made at the exact commit SHA (--git-ref). We
#                    never clone, so the container can run with
#                    --network=none. This matters because gradle processes
#                    untrusted dev source: docker can't revoke network
#                    mid-run, so the only way to build offline is to fetch
#                    in a separate step. --git-url is still required; it is
#                    recorded in recipe.json.
#
# Optional env:
#   GH_TOKEN      If set, used to authenticate the dev-repo clone. Required
#                 for private GitHub repos. The orchestrator passes a
#                 short-lived token here; for local testing you can pass a
#                 PAT via `docker run -e GH_TOKEN=...`. Unused in mounted
#                 mode.
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
                    [--tool-path PATH] [--dev-repo CHECKED_OUT_REPO]
                    [--abi-filters ABIS]
USAGE
    exit 64
}

GIT_URL=""
GIT_REF=""
MOUNTED_DEV_REPO=""
OUTPUT_DIR=""
TOOL_PATH="tool"
ABI_FILTERS="arm64-v8a"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --git-url) GIT_URL="$2"; shift 2 ;;
        --git-ref) GIT_REF="$2"; shift 2 ;;
        --dev-repo) MOUNTED_DEV_REPO="$2"; shift 2 ;;
        --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
        --tool-path) TOOL_PATH="$2"; shift 2 ;;
        --abi-filters) ABI_FILTERS="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown flag: $1" >&2; usage ;;
    esac
done

[[ -n "$GIT_URL" && -n "$GIT_REF" && -n "$OUTPUT_DIR" ]] || usage
if [[ -n "$MOUNTED_DEV_REPO" && ! "$GIT_REF" =~ ^[0-9a-f]{40}$ ]]; then
    echo "--dev-repo requires --git-ref to be a 40-character commit SHA" >&2
    exit 64
fi

# Image-build-time constants — see Dockerfile.
: "${LIGHT_SDK_HOME:?LIGHT_SDK_HOME must be set in the image}"
: "${LIGHT_BUILDER_HOME:?LIGHT_BUILDER_HOME must be set in the image}"
: "${LIGHT_SDK_GIT_REF:?LIGHT_SDK_GIT_REF must be baked into the image}"
: "${LIGHT_IMAGE_DIGEST:=unknown}"  # filled in by the orchestrator at runtime

mkdir -p "$OUTPUT_DIR"

# Temp dirs we own. A mounted --dev-repo belongs to the caller and is never
# added here.
CLEANUP_DIRS=()
trap 'rm -rf "${CLEANUP_DIRS[@]}"' EXIT

# safe.directory: a mounted checkout is usually owned by a different uid than
# the builder user, which git otherwise refuses to read.
dev_git() { git -c safe.directory="$DEV_REPO" -C "$DEV_REPO" "$@"; }

# --- Acquire the dev's source ------------------------------------------------
# Both modes leave DEV_REPO pointing at a checkout of the dev's commit.

# init/fetch so we can grab arbitrary refs (not just branch tips). --no-tags
# keeps history small; --depth=1 minimises network I/O. Fail closed on a clone
# that doesn't resolve to a real commit.
#
# If GH_TOKEN is set, plumb it through a one-shot credential helper so private
# GitHub repos resolve. The token never lands in git config, process args, or
# any file — it lives only in the env var while this RUN executes.
clone_dev_repo() {
    DEV_REPO="$(mktemp -d -t devrepo.XXXXXX)"
    CLEANUP_DIRS+=("$DEV_REPO")

    echo ">> cloning $GIT_URL @ $GIT_REF"
    dev_git init -q
    dev_git remote add origin "$GIT_URL"
    if [[ -n "${GH_TOKEN:-}" ]]; then
        dev_git \
            -c credential.helper="!f() { echo username=x-access-token; echo password=$GH_TOKEN; }; f" \
            fetch --no-tags --depth=1 origin "$GIT_REF" 2>&1 | tee -a "$OUTPUT_DIR/build.log"
    else
        dev_git fetch --no-tags --depth=1 origin "$GIT_REF" 2>&1 | tee -a "$OUTPUT_DIR/build.log"
    fi
    dev_git checkout -q FETCH_HEAD
}

# The preflight job already resolved and checked out the SHA; we only confirm
# it handed us the commit we were asked to build.
use_mounted_dev_repo() {
    DEV_REPO="$MOUNTED_DEV_REPO"

    echo ">> using mounted checkout $DEV_REPO"
    local head
    head="$(dev_git rev-parse HEAD)"
    if [[ "$head" != "$GIT_REF" ]]; then
        echo "mounted checkout is at $head, expected $GIT_REF" >&2
        exit 1
    fi
}

if [[ -n "$MOUNTED_DEV_REPO" ]]; then
    use_mounted_dev_repo
else
    clone_dev_repo
fi

DEV_GIT_COMMIT="$(dev_git rev-parse HEAD)"
DEV_COMMIT_EPOCH="$(dev_git show -s --format=%ct HEAD)"
echo ">> dev commit: $DEV_GIT_COMMIT (epoch $DEV_COMMIT_EPOCH)"

# --- Stage workspace ---------------------------------------------------------
# We never mutate the baked SDK directly. Copy it to a per-job workspace so
# concurrent builds (if any) cannot collide. The SDK source is the
# image-baked one, NOT anything from the dev's repo.
WORKSPACE="$(mktemp -d -t workspace.XXXXXX)"
CLEANUP_DIRS+=("$WORKSPACE")
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
    "--no-build-cache"
    "--console=plain"
    "-DlightSdk.unsigned=true"
    "-DlightSdk.toolOnly=true"
)
if [[ -n "$ABI_FILTERS" ]]; then
    GRADLE_ARGS+=("-DlightSdk.abiFilters=$ABI_FILTERS")
fi
# With LIGHT_MAVEN_PROXY set, dependencies missing from the warmed cache come
# from the build-time Maven proxy, the only host the container can reach (see
# bin/build.sh). Without it, the build is strictly offline.
if [[ -n "${LIGHT_MAVEN_PROXY:-}" ]]; then
    GRADLE_ARGS+=("--init-script" "$LIGHT_BUILDER_HOME/proxy/init.gradle.kts")
else
    GRADLE_ARGS+=("--offline")
fi
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
    --tool-git-url "$GIT_URL" \
    --tool-git-ref "$GIT_REF" \
    --tool-git-commit "$DEV_GIT_COMMIT" \
    --gradle-command "$GRADLE_CMD_JSON" \
    --source-date-epoch "$DEV_COMMIT_EPOCH"

echo ">> done; artifacts in $OUTPUT_DIR"
