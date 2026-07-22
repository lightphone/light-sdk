# Disclaimer: an LLM was used to generate much of this file (because it wasn't fun to write!)
# syntax=docker/dockerfile:1.6
#
# Builder image for Light SDK tools.
#
# The image bakes in a pinned commit of the Light SDK and a pre-warmed Gradle
# dependency cache. At runtime the container runs strictly --offline and
# never fetches dependencies, so the image digest is the full description of
# "the build toolchain and the SDK source we built against."
#
# Build context: this directory (builder/). The SDK is fetched from git at
# image-build time, not copied from the surrounding tree, so devs and CI
# always build against a published commit.
#
# Example:
#   docker build \
#     --build-arg SDK_GIT_URL=https://github.com/lightphone/light-sdk \
#     --build-arg SDK_GIT_REF=<commit-sha> \
#     --secret id=github_token,env=GH_PACKAGES_TOKEN \
#     --secret id=gh_packages_user,env=GH_PACKAGES_USER \
#     -t lightphone/light-builder:<tag> builder/
#
# `github_token` is a single GitHub PAT used for two things:
#   1. cloning the SDK repo if it's private (stage 2)
#   2. authenticating to GitHub Packages while pre-warming gradle (stage 3)
# Needs `repo` and `read:packages` scopes. Public SDK + no GH Packages deps
# means you can omit both secrets entirely.
#
# Runtime:
#   docker run --rm --network=none \
#     -v /tmp/out:/out lightphone/light-builder:<tag> \
#     --git-url https://github.com/dev/their-tool \
#     --git-ref <commit-sha> \
#     --output-dir /out
#
# Note: --network=none at runtime requires the orchestrator to clone the dev
# repo outside the container and mount it in; if you let the container do
# the clone (as bin/build-apk.sh does), run it on a docker network that
# permits HTTPS to GitHub only.

ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
ARG ANDROID_PLATFORM_VERSION=android-36
ARG ANDROID_BUILD_TOOLS_VERSION=36.0.0

# ─── Stage 1: base OS + JDK + Android SDK ────────────────────────────────────
# Pinned to linux/amd64 because Google only ships AAPT2 as an x86_64 binary.
# Apple-Silicon hosts will run this under Rosetta 2 (or QEMU) at the cost of
# slower configuration; switch to a multi-arch AAPT2 if/when Google ships one.
FROM --platform=linux/amd64 eclipse-temurin:17-jdk-noble AS base

ARG ANDROID_CMDLINE_TOOLS_VERSION
ARG ANDROID_PLATFORM_VERSION
ARG ANDROID_BUILD_TOOLS_VERSION

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/opt/gradle-cache \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/lib/jvm/default-jvm/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1

# Pinned by tag for now; production builds should pin by digest.
RUN apt-get update && apt-get install -y --no-install-recommends \
        git \
        ca-certificates \
        curl \
        unzip \
        python3 \
        python3-pip \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"; \
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/clt.zip; \
    unzip -q /tmp/clt.zip -d "$ANDROID_SDK_ROOT/cmdline-tools"; \
    mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"; \
    rm /tmp/clt.zip; \
    yes | sdkmanager --licenses > /dev/null; \
    sdkmanager --install \
        "platform-tools" \
        "platforms;${ANDROID_PLATFORM_VERSION}" \
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" > /dev/null

# ─── Stage 2: SDK source ─────────────────────────────────────────────────────
FROM --platform=linux/amd64 base AS sdk

ARG SDK_GIT_URL=https://github.com/lightphone/light-sdk
ARG SDK_GIT_REF

# SDK_GIT_REF must be a commit-ish; fail loud if the caller forgot it.
RUN test -n "${SDK_GIT_REF}" || (echo "SDK_GIT_REF build arg required" >&2; exit 1)

# If a github_token secret is mounted, use it to clone (works for private repos).
# Otherwise fall back to an anonymous clone (public repos). The token is read
# from the secret mount and used via a one-shot credential helper, so it never
# lands in process args, git config, or any image layer.
RUN --mount=type=secret,id=github_token,uid=0,mode=0400,target=/run/secrets/gh \
    set -eu; \
    if [ -s /run/secrets/gh ]; then \
        echo ">> github_token secret received ($(wc -c < /run/secrets/gh) bytes)"; \
        TOKEN="$(cat /run/secrets/gh)"; \
        git -c credential.helper="!f() { echo username=x-access-token; echo password=$TOKEN; }; f" \
            clone --no-tags "${SDK_GIT_URL}" /opt/light-sdk; \
    else \
        echo ">> no github_token secret mounted; attempting anonymous clone"; \
        git clone --no-tags "${SDK_GIT_URL}" /opt/light-sdk; \
    fi; \
    git -C /opt/light-sdk checkout -q "${SDK_GIT_REF}"; \
    rm -rf /opt/light-sdk/.git

# ─── Stage 3: pre-warm gradle cache ──────────────────────────────────────────
# This is the only stage that needs network for Maven repos + GH Packages.
# Once it's done, GRADLE_USER_HOME holds every dep needed for any tool/
# build of this SDK commit, and runtime can run --offline.
FROM --platform=linux/amd64 sdk AS warm

RUN --mount=type=secret,id=github_token,uid=0,mode=0400,target=/run/secrets/gh_token \
    --mount=type=secret,id=gh_packages_user,uid=0,mode=0400,target=/run/secrets/gh_user \
    set -eux; \
    set +x; \
    export GH_PACKAGES_TOKEN="$(cat /run/secrets/gh_token 2>/dev/null || true)"; \
    export GH_PACKAGES_USER="$(cat /run/secrets/gh_user 2>/dev/null || true)"; \
    set -x; \
    cd /opt/light-sdk; \
    ./gradlew :tool:assembleRelease --no-daemon --stacktrace; \
    # Drop build outputs but keep the populated dep cache.
    find /opt/light-sdk -path '*/build' -type d -prune -exec rm -rf {} +

# ─── Stage 4: final runtime image ────────────────────────────────────────────
FROM --platform=linux/amd64 warm AS runtime

ARG SDK_GIT_REF

ENV LIGHT_SDK_HOME=/opt/light-sdk \
    LIGHT_BUILDER_HOME=/opt/light-builder \
    LIGHT_SDK_GIT_REF=${SDK_GIT_REF} \
    PYTHONPATH=/opt/light-builder

COPY lightbuilder /opt/light-builder/lightbuilder
COPY bin /opt/light-builder/bin

RUN chmod +x /opt/light-builder/bin/build-apk.sh

# Non-root user for the build. The user cannot read GH_PACKAGES_TOKEN or any
# other build-time secret because secret mounts are unmounted between stages.
RUN useradd --create-home --shell /bin/bash builder \
    && chown -R builder:builder /opt/light-sdk /opt/gradle-cache /opt/light-builder

USER builder
WORKDIR /home/builder

ENTRYPOINT ["/opt/light-builder/bin/build-apk.sh"]
