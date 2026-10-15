# Disclaimer: an LLM was used to generate much of this file (because it wasn't fun to write!)
# syntax=docker/dockerfile:1.6
#
# Builder image for Light SDK tools.
#
# The image bakes in a pinned commit of the Light SDK, a pre-warmed Gradle
# dependency cache and the build-time Maven proxy. At runtime the build
# container reaches only that proxy (see bin/build.sh), which serves
# dependencies missing from the cache from Google, Maven Central, the Gradle
# Plugin Portal and JitPack (com.github.lightphone only). The image digest
# describes the toolchain, the SDK source and the proxy configuration.
#
# Build context: this directory (builder/). The SDK is fetched from git at
# image-build time, not copied from the surrounding tree, so devs and CI
# always build against a published commit.
#
# The warm-up resolves through the proxy at the same URL the runtime build
# uses, since Gradle caches metadata per repository URL; this needs
# --add-host maven-proxy=127.0.0.1.
#
# Example:
#   docker build \
#     --add-host maven-proxy=127.0.0.1 \
#     --build-arg SDK_GIT_URL=https://github.com/lightphone/light-sdk \
#     --build-arg SDK_GIT_REF=<tag-or-commit> \
#     -t lightphone/light-builder:<tag> builder/
#
# Runtime: see bin/build.sh, which CI runs too. Without LIGHT_MAVEN_PROXY
# the build runs strictly --offline against the warmed cache.

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

RUN test -n "${SDK_GIT_REF}" || (echo "SDK_GIT_REF build arg required" >&2; exit 1)

RUN git clone --no-tags "${SDK_GIT_URL}" /opt/light-sdk; \
    git -C /opt/light-sdk checkout -q "${SDK_GIT_REF}"; \
    rm -rf /opt/light-sdk/.git

# ─── Stage 3: pre-warm gradle cache ──────────────────────────────────────────
# Builds the sample tool once, through the Maven proxy and with the runtime
# build's flags, so its dependencies need no proxy round trips at runtime.
FROM --platform=linux/amd64 sdk AS warm

RUN apt-get update && apt-get install -y --no-install-recommends nginx \
    && rm -rf /var/lib/apt/lists/*

COPY proxy /opt/light-builder/proxy
COPY bin/maven-proxy.sh /opt/light-builder/bin/maven-proxy.sh

# An incomplete cache cannot support builds; fail the image build too.
RUN set -eu; \
    if ! getent hosts maven-proxy | grep -q '^127\.0\.0\.1'; then \
        echo "build the image with --add-host maven-proxy=127.0.0.1" >&2; exit 1; \
    fi; \
    LIGHT_BUILDER_HOME=/opt/light-builder bash /opt/light-builder/bin/maven-proxy.sh & \
    proxy_pid=$!; \
    for _ in $(seq 1 30); do \
        curl -fsS -o /dev/null http://maven-proxy:8080/healthz && break; sleep 1; \
    done; \
    cd /opt/light-sdk; \
    LIGHT_MAVEN_PROXY=http://maven-proxy:8080/ ./gradlew :tool:assembleRelease \
        --no-daemon --stacktrace \
        --init-script /opt/light-builder/proxy/init.gradle.kts \
        -DlightSdk.unsigned=true -DlightSdk.abiFilters=arm64-v8a; \
    kill "$proxy_pid"; \
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
COPY proxy /opt/light-builder/proxy

RUN chmod +x /opt/light-builder/bin/build-apk.sh /opt/light-builder/bin/maven-proxy.sh

# Native libraries in the warmed cache are the SDK's own; the signing service
# approves them for every tool built with this image (see lightbuilder/native.py).
RUN python3 -m lightbuilder image-inventory \
        --cache /opt/gradle-cache/caches/modules-2/files-2.1 \
        --output /opt/light-builder/image-native-libraries.json

RUN useradd --create-home --shell /bin/bash builder \
    && chown -R builder:builder /opt/light-sdk /opt/gradle-cache /opt/light-builder /tmp/nginx

USER builder
WORKDIR /home/builder

ENTRYPOINT ["/opt/light-builder/bin/build-apk.sh"]
