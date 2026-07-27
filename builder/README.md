# light-builder

Containerised APK builder for Light SDK tools. **Note that you do NOT need to use this to develop your tool locally! This is how Light will build your source into a Light-signed APK for sharing/distrobution.**

## What it does

Given a git URL and a commit, the container produces an **unsigned** APK from
a developer's tool, plus a recipe describing every input that fed the build.
Signing is a separate concern, intentionally handled by a different process
running on a different trust zone, with the keys.

## Dev

Most Light SDK tools will be forks of this repo with edits to the `tool/`
module. They will look like:

| File                                      | Purpose                                                |
|-------------------------------------------|--------------------------------------------------------|
| `tool/lighttool.toml`                     | Tool id, label, versionCode/Name, declared permissions |
| `tool/build.gradle.kts`                   | The dev's allowed dependencies (Compose, Ktor, etc.)   |
| `tool/src/main/kotlin/**/*.kt`            | Tool source                                            |
| `tool/src/main/res/**`, `assets/**`       | Resources and assets                                   |

Reminder that you should **not** write/change `AndroidManifest.xml` — the plugin generates it from
`lighttool.toml` at every Gradle build (locally and on the server). The
plugin also rejects setting `applicationId`, `versionCode`, `versionName`,
or `namespace` in `build.gradle.kts`.

## Architecture

```
   ┌────────────────────────┐         ┌──────────────────────────┐
   │ your tool repo         │         │  baked-in SDK source     │
   │  tool/lighttool.toml   │         │  (pinned commit, built   │
   │  tool/build.gradle.kts │         │   at image build time)   │
   │  tool/src/main/...     │         └─────────────┬────────────┘
   └───────────┬────────────┘                       │
               │                                    │
               │   allowlist extraction             │
               │   + pre-flight build-script scan   │
               ▼                                    ▼
            ┌─────────────────────────────────────────┐
            │   workspace = SDK ⊕ extracted files     │
            └─────────────────────┬───────────────────┘
                                  │
                                  │  gradle :tool:assembleRelease
                                  │    --offline -DlightSdk.unsigned=true
                                  │
                                  │  (the plugin, inside gradle:
                                  │   reads lighttool.toml,
                                  │   sets applicationId / versionCode /
                                  │       versionName / namespace on AGP,
                                  │   writes AndroidManifest.xml into
                                  │       build/generated/light-sdk/,
                                  │   clears signingConfig for an unsigned
                                  │       artifact, validates banlist)
                                  ▼
                          ┌──────────────────────┐
                          │ unsigned APK         │
                          │ recipe.json          │
                          │ extraction.json      │
                          │ extracted-source.zip │
                          │ build.log            │
                          └──────────────────────┘
```

The Gradle build runs `--offline`. All dependencies are warmed into the
image's `GRADLE_USER_HOME` at image build time. The image digest captures
the toolchain, the SDK source, and the dependency set; the runtime
container cannot pull anything from the network.

`-DlightSdk.unsigned=true` is the toggle that tells the plugin to clear any
`signingConfig` the dev wired up locally. Locally devs build without that
flag and AGP signs with the shared dev keystore as usual.

## Building the image

```sh
GH_PACKAGES_USER=<your-gh-user> \
GH_PACKAGES_TOKEN=<PAT with repo + read:packages scopes> \
DOCKER_BUILDKIT=1 docker build \
  -f builder/Dockerfile \
  --build-arg SDK_GIT_URL=https://github.com/lightphone/light-sdk \
  --build-arg SDK_GIT_REF=<commit-sha-or-tag> \
  --secret id=github_token,env=GH_PACKAGES_TOKEN \
  --secret id=gh_packages_user,env=GH_PACKAGES_USER \
  -t lightphone/light-builder:<tag> \
  builder/
```

A single GitHub PAT does two jobs at image-build time:

1. **Stage 2** clones the (currently private) SDK repo — needs `repo` scope.
2. **Stage 3** pre-warms the gradle dependency cache, which includes
   `com.thelightphone.lp3keyboard` from GitHub Packages — needs `read:packages`.

Both stages read the same token via BuildKit secret mounts (`id=github_token`
for the clone, `id=github_token` + `id=gh_packages_user` for the warm-up).
The secrets are mounted only for the steps that need them and never end up
in any image layer. When both the SDK repo and the Packages deps become
public, we will omit the secrets entirely.

### Apple Silicon

The Dockerfile pins `--platform=linux/amd64` on every stage because Google
only ships AAPT2 as a Linux x86_64 binary. On Apple Silicon Docker Desktop
will run the image under Rosetta 2 — make sure **Settings → General → "Use
Rosetta for x86_64/amd64 emulation"** is enabled. It's still quite slow, and 
we've had mixed results getting it to actually complete.

## Running a build

```sh
docker run --rm \
  --platform=linux/amd64 \
  --network=lightbuilder-egress \
  --read-only \
  --tmpfs /tmp \
  --tmpfs /home/builder \
  --security-opt=no-new-privileges \
  --cap-drop=ALL \
  -e GH_TOKEN="$DEV_REPO_TOKEN" \
  -v /var/run/lightbuilder/out/<build-id>:/out \
  lightphone/light-builder:<tag> \
  --git-url https://github.com/dev/their-tool \
  --git-ref <commit-sha> \
  --tool-path tool \
  --output-dir /out
```

### Flags

| Flag            | Meaning                                                                            |
|-----------------|------------------------------------------------------------------------------------|
| `--git-url`     | HTTPS URL of the dev's repo.                                                       |
| `--git-ref`     | Branch, tag, or commit SHA to build.                                               |
| `--tool-path`   | Relative path inside the dev's repo where their tool lives. Defaults to `tool`. Use `.` if the repo root *is* the tool dir. Validated to stay inside the repo. |
| `--output-dir`  | Where to write artifacts inside the container. Bind-mount this from the host.      |

### Env

| Variable   | Required? | Purpose                                                                                                                                           |
|------------|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| `GH_TOKEN` | If the dev's repo is private | Used as the password (`x-access-token` username) for the dev-repo clone. **This is temporary, we will eventually expect all repos to be public.** |

### Network

`lightbuilder-egress` should be a docker network configured to permit HTTPS
to `github.com` only — that's the only host the runtime touches, for the
dev-repo clone. If the orchestrator clones outside the container and
bind-mounts the working tree in, you can run with `--network=none`.

### Bind-mount permissions

The container runs as a non-root `builder` user (uid 1001 — the temurin base
image already occupies 1000). The `--output-dir` mount point must be writable
by uid 1001. In production the orchestrator
creates the per-build output dir with that ownership; for local testing the
simplest answer is `chmod 777` on the host dir before `docker run`.

### Container outputs

Inside `--output-dir`:

| File             | Purpose                                                                |
|------------------|------------------------------------------------------------------------|
| `tool-unsigned.apk` | The build artifact.                                                 |
| `recipe.json`    | SHA-256 + every input that fed the build. The signing job must verify the dev-commit hash against this before signing. |
| `extraction.json`| List of files the extractor accepted from the dev's repo.              |
| `extracted-source.zip` | The accepted source files themselves, zipped exactly as staged into the tool module (`build.gradle.kts`, `lighttool.toml`, `src/main/**`). Deterministic archive — same commit produces a byte-identical zip. |
| `build.log`      | Gradle stdout/stderr, plus the extractor's log.                        |
| `error.json`     | Present only on policy-violation failure; describes why.               |

`recipe.json` is the source-of-truth for what was actually built. Pass its
`sha256` into the signing queue alongside the build ID, and have the signer
refuse to sign if the artifact's hash doesn't match.

## `lighttool.toml` schema

```toml
[tool]
id            = "com.example.mytool"  # Java package id, dotted, lowercase
label         = "My Tool"             # 1–50 printable chars, no <, >, control chars
versionCode   = 1                     # positive integer
versionName   = "1.0.0"               # ^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$
permissions   = []                    # array of allowlisted permissions
```

Schema enforcement and the permission allowlist live in
[`plugin/src/main/kotlin/com/thelightphone/plugin/LightToolMetadata.kt`](../plugin/src/main/kotlin/com/thelightphone/plugin/LightToolMetadata.kt).
To loosen any rule, edit that file and ship a new SDK release; the builder
picks up the change the next time the image is rebuilt against the new SDK
commit.

## TODO

- **Full bit-reproducibility.** AGP, R8, ZIP packaging, and signed-block
  layout each introduce non-determinism. The extraction-and-build pipeline here is deterministic, 
  but the gradle output is only "reproducible enough that diffs are inspectable".
- **Everything should be public.** Eventually, we won't require any GitHub creds here. The SDK will be public,
  tools will only be buildable if they are public, and we'll (likely) host our build artifacts on Maven Central.

## Tests

```sh
# Python (extraction policy)
cd builder
python3 -m venv .venv
.venv/bin/pip install pytest
.venv/bin/python -m pytest tests/

# Kotlin (metadata parser + manifest generator)
cd plugin
../gradlew test
```
