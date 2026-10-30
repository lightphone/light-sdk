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
#
# The same build also yields the SDK's Maven bundle (sdk-shared, sdk-server,
# sdk-ui) published with each release: --target maven-bundle-out
# --output type=local,dest=DIR (see stage 5).

ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
ARG ANDROID_PLATFORM_VERSION=android-36
ARG ANDROID_BUILD_TOOLS_VERSION=36.0.0
# AGP's default build-tools, used by every module (and dev tool) that sets no
# buildToolsVersion. Must follow the AGP version in gradle/libs.versions.toml.
ARG AGP_DEFAULT_BUILD_TOOLS_VERSION=35.0.0

# ─── Stage 1: base OS + JDK + Android SDK ────────────────────────────────────
# Pinned to linux/amd64 because Google only ships AAPT2 as an x86_64 binary.
# Apple-Silicon hosts will run this under Rosetta 2 (or QEMU) at the cost of
# slower configuration; switch to a multi-arch AAPT2 if/when Google ships one.
FROM --platform=linux/amd64 eclipse-temurin:17-jdk-noble AS base

ARG ANDROID_CMDLINE_TOOLS_VERSION
ARG ANDROID_PLATFORM_VERSION
ARG ANDROID_BUILD_TOOLS_VERSION
ARG AGP_DEFAULT_BUILD_TOOLS_VERSION

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
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" \
        "build-tools;${AGP_DEFAULT_BUILD_TOOLS_VERSION}" > /dev/null

# ─── Stage 2: SDK source ─────────────────────────────────────────────────────
FROM --platform=linux/amd64 base AS sdk

ARG SDK_GIT_URL=https://github.com/lightphone/light-sdk
ARG SDK_GIT_REF

RUN test -n "${SDK_GIT_REF}" || (echo "SDK_GIT_REF build arg required" >&2; exit 1)

RUN git clone --no-tags "${SDK_GIT_URL}" /opt/light-sdk; \
    git -C /opt/light-sdk checkout -q "${SDK_GIT_REF}"; \
    rm -rf /opt/light-sdk/.git

# ─── Stage 3: Maven proxy ────────────────────────────────────────────────────
# nginx and the proxy scripts, shared by the warm-up and the runtime image.
FROM --platform=linux/amd64 sdk AS proxy-base

RUN apt-get update && apt-get install -y --no-install-recommends nginx \
    && rm -rf /var/lib/apt/lists/*

COPY proxy /opt/light-builder/proxy
COPY bin/maven-proxy.sh bin/with-proxy.sh /opt/light-builder/bin/

# nginx's working directory (see proxy/nginx.conf); handed to the builder user
# in the runtime stage.
RUN mkdir -p /tmp/nginx

# ─── Stage 4: pre-warm gradle cache ──────────────────────────────────────────
# Builds the sample tool once, through the Maven proxy and with the runtime
# build's flags, so its dependencies need no proxy round trips at runtime.
# Build outputs stay in this stage for maven-bundle; the runtime image only
# takes the Gradle cache (see runtime-base).
FROM --platform=linux/amd64 proxy-base AS gradle-warmup

# Only /opt/gradle-cache leaves this stage, so packages AGP installs on its own
# would be missing at runtime; they belong in the base stage's sdkmanager list.
RUN set -eu; \
    find "$ANDROID_SDK_ROOT" -name package.xml | sort > /tmp/android-sdk-before; \
    cd /opt/light-sdk; \
    LIGHT_MAVEN_PROXY=http://maven-proxy:8080/ /opt/light-builder/bin/with-proxy.sh \
        ./gradlew :tool:assembleRelease \
        --no-daemon --stacktrace \
        --init-script /opt/light-builder/proxy/init.gradle.kts \
        -DlightSdk.unsigned=true -DlightSdk.abiFilters=arm64-v8a; \
    find "$ANDROID_SDK_ROOT" -name package.xml | sort > /tmp/android-sdk-after; \
    if ! diff -u /tmp/android-sdk-before /tmp/android-sdk-after; then \
        echo "the warm-up installed Android SDK packages; add them to the base stage" >&2; \
        exit 1; \
    fi

# ─── Stage 5: Maven bundle ───────────────────────────────────────────────────
# Publishes sdk-shared, sdk-server and sdk-ui as a Maven repository directory
# for the GitHub release. Reuses gradle-warmup's compiled outputs; sdk-server's
# extra dependencies stay in this stage and never reach the runtime image,
# whose cache defines the image's approved native libraries.
FROM --platform=linux/amd64 gradle-warmup AS maven-bundle

COPY release/bundle.init.gradle.kts /opt/light-builder/release/bundle.init.gradle.kts

RUN cd /opt/light-sdk && \
    LIGHT_MAVEN_PROXY=http://maven-proxy:8080/ \
    LIGHT_RELEASE_BUNDLE_DIR=/opt/maven-release \
    /opt/light-builder/bin/with-proxy.sh \
        ./gradlew \
        :sdk:shared:publishAllPublicationsToReleaseBundleRepository \
        :sdk:server:publishAllPublicationsToReleaseBundleRepository \
        :sdk:ui:publishAllPublicationsToReleaseBundleRepository \
        --no-daemon --stacktrace \
        --init-script /opt/light-builder/proxy/init.gradle.kts \
        --init-script /opt/light-builder/release/bundle.init.gradle.kts \
        -DlightSdk.unsigned=true -DlightSdk.abiFilters=arm64-v8a

# Resolves the bundle from a consumer project before it can be exported.
FROM --platform=linux/amd64 maven-bundle AS bundle-smoke

COPY release/smoke /opt/light-builder/release/smoke

RUN cd /opt/light-builder/release/smoke && \
    sdk_version="$(sed -n 's/^sdkVersion=//p' /opt/light-sdk/gradle.properties)" && \
    /opt/light-builder/bin/with-proxy.sh \
        /opt/light-sdk/gradlew -p . resolveBundle \
        --no-daemon --stacktrace \
        -PsdkVersion="$sdk_version" \
        -PlightReleaseBundle=/opt/maven-release \
        -PlightMavenProxy=http://maven-proxy:8080/ \
        -PlightSdkCatalog=/opt/light-sdk/gradle/libs.versions.toml

# Export with: docker build --target maven-bundle-out --output type=local,dest=DIR
FROM scratch AS maven-bundle-out
COPY --from=bundle-smoke /opt/maven-release /

# ─── Stage 6: runtime base ───────────────────────────────────────────────────
# Clean SDK source plus the warmed Gradle cache, without build outputs.
FROM --platform=linux/amd64 proxy-base AS runtime-base

COPY --from=gradle-warmup /opt/gradle-cache /opt/gradle-cache

# ─── Stage 7: final runtime image ────────────────────────────────────────────
FROM --platform=linux/amd64 runtime-base AS runtime

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
