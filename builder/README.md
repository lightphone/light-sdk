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
                                  │    -DlightSdk.unsigned=true
                                  │    -DlightSdk.abiFilters=arm64-v8a
                                  │    (missing deps via the Maven proxy)
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

The sample tool's dependencies are warmed into the image's
`GRADLE_USER_HOME` at image build time. Anything else a tool declares comes
from the [build-time Maven proxy](#build-time-maven-proxy), the only host
the build container can reach. The image digest captures the toolchain, the
SDK source and the proxy configuration. Without `LIGHT_MAVEN_PROXY` the build
runs strictly `--offline` against the warmed cache.

`-DlightSdk.unsigned=true` is the toggle that tells the plugin to clear any
`signingConfig` the dev wired up locally. Locally devs build without that
flag and AGP signs with the shared dev keystore as usual.

The builder never passes `-DlightSdk.allowAltServerPackage=true`, so any
`tool.serverPackage` other than `com.lightos` fails the build.

## Building the image

```sh
DOCKER_BUILDKIT=1 docker build \
  -f builder/Dockerfile \
  --add-host maven-proxy=127.0.0.1 \
  --build-arg SDK_GIT_URL=https://github.com/lightphone/light-sdk \
  --build-arg SDK_GIT_REF=<tag-or-commit> \
  -t lightphone/light-builder:<tag> \
  builder/
```

`--add-host` is required: the warm-up resolves through the proxy at the same
URL the runtime build uses, because Gradle caches metadata per repository URL.

### Apple Silicon

The Dockerfile pins `--platform=linux/amd64` on every stage because Google
only ships AAPT2 as a Linux x86_64 binary. On Apple Silicon Docker Desktop
will run the image under Rosetta 2 — make sure **Settings → General → "Use
Rosetta for x86_64/amd64 emulation"** is enabled. With Colima, use
`colima start --vm-type vz --vz-rosetta --cpu 8 --memory 16`; the image build
then completes in about 40 minutes. QEMU emulation with 8 GB runs out of
memory during the warm-up.

## Running a build

`bin/build.sh` is the host-side driver CI uses (it extracts the script from
the pinned image) and the way to build locally: proxy, isolated build
network, build, then the trusted native-library inventory.
`bin/build-apk.sh` is the entrypoint inside the build container.

```sh
builder/bin/build.sh \
  --image lightphone/light-builder:<tag> \
  --dev-repo /path/to/checkout \
  --output-dir ~/light-build-out
```

The output dir must not exist; with Colima or Docker Desktop it must be under
a directory shared with the VM (e.g. `$HOME`). It ends up holding `build/`
(the container outputs below), `proxy-access.log` and
`native-libraries.json`.

## Build-time Maven proxy

```
build container ──(--internal network)──> proxy container ──> Google /
  (untrusted)                               (nginx, same image)   Maven Central /
                                                                  Gradle Plugin Portal /
                                                                  JitPack
```

- The build container is attached only to an `--internal` Docker network
  whose one other member is the proxy, started from the same image with
  `--entrypoint /opt/light-builder/bin/maven-proxy.sh`. The build reaches it
  as `http://maven-proxy:8080/` via `LIGHT_MAVEN_PROXY`, and
  `proxy/init.gradle.kts` makes it every build's only repository.
- [`proxy/nginx.conf`](proxy/nginx.conf) tries Google, then Maven Central,
  then the Plugin Portal on 404, and serves JitPack only for
  `com.github.lightphone` (anyone can publish to JitPack, so it is never a
  fallback). GET/HEAD only, no query strings, upstream TLS verified.
- The link between the build and the proxy is plain http: it never leaves
  the Docker host, and nothing relies on its integrity (see below).
- The proxy does not enforce the dependency allowlist: transitive
  dependencies come from arbitrary groups. The plugin enforces it inside
  Gradle.

### Native libraries

The signing service only signs APKs whose `lib/**.so` entries are approved by
the build's `native-libraries.json`. A library is approved if its bytes match
a `jni/**.so` in:

- an AAR in the image's warmed cache (`/opt/light-builder/image-native-libraries.json`,
  generated at image build), or
- an AAR the proxy served whose coordinates directly match the plugin's
  [dependency allowlist](../plugin/src/main/resources/com/thelightphone/plugin/allowed-dependencies.txt).

The second set is computed by `python3 -m lightbuilder native-inventory` in a
trusted container after the build. It reads the proxy log only to learn
which allowlisted AARs were served, and fetches them directly from upstream
over verified HTTPS, so a compromised proxy cannot approve its own bytes. The
plugin never strips `.so` files, so packaged bytes match the AAR's.

## Running the container directly

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
| `--dev-repo`    | Optional. Path to an already checked-out dev repo; skips the clone. Requires `--git-ref` to be a 40-character commit SHA. See [Source modes](#source-modes). |
| `--abi-filters` | Comma-separated ABIs to package native libraries for. Defaults to `arm64-v8a`, the ABI Light devices run; `""` keeps every ABI. Local dev builds are unaffected. |

### Source modes

- **Clone (default)** — the builder fetches `--git-url` @ `--git-ref`
  itself, so the container needs egress to the git host.
- **Mounted (`--dev-repo`)** — a trusted preflight job clones and checks out
  the exact SHA, then mounts it in. The builder verifies the checkout's HEAD
  matches `--git-ref` and never clones, so it runs with `--network=none`.
  Gradle processes untrusted dev source and docker can't revoke network
  mid-run, so fetching in a separate step is the only way to build offline.
  `--git-url` is still required; it's recorded in `recipe.json`.

```sh
docker run --rm \
  --platform=linux/amd64 \
  --network=none \
  --read-only \
  --tmpfs /tmp \
  --tmpfs /home/builder \
  --security-opt=no-new-privileges \
  --cap-drop=ALL \
  -v /path/to/checkout:/src:ro \
  -v /var/run/lightbuilder/out/<build-id>:/out \
  lightphone/light-builder:<tag> \
  --git-url https://github.com/dev/their-tool \
  --git-ref <40-char-commit-sha> \
  --dev-repo /src \
  --tool-path tool \
  --output-dir /out
```

Mounting read-only (`:ro`) is recommended.

### Env

| Variable   | Required? | Purpose                                                                                                                                           |
|------------|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| `GH_TOKEN` | If the dev's repo is private | Used as the password (`x-access-token` username) for the dev-repo clone. **This is temporary, we will eventually expect all repos to be public.** |
| `LIGHT_MAVEN_PROXY` | No | URL of the [build-time Maven proxy](#build-time-maven-proxy). Unset means `--offline`. |

### Network

`lightbuilder-egress` should be a docker network configured to permit HTTPS
to `github.com` only — that's the only host the runtime touches, for the
dev-repo clone. In mounted mode (`--dev-repo`) run with `--network=none`.

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
| `recipe.json`    | SHA-256 + every input that fed the build. The signing job must verify the tool commit against this before signing. |
| `extraction.json`| List of files the extractor accepted from the dev's repo.              |
| `extracted-source.zip` | The accepted source files themselves, zipped exactly as staged into the tool module (`build.gradle.kts`, `lighttool.toml`, `src/main/**`). Deterministic archive — same commit produces a byte-identical zip. |
| `build.log`      | Gradle stdout/stderr, plus the extractor's log.                        |
| `error.json`     | Present only on policy-violation failure; describes why.               |

`recipe.json` is the source-of-truth for what was actually built. Pass its
`sha256` into the signing queue alongside the build ID, and have the signer
refuse to sign if the artifact's hash doesn't match.

Its `tool` object and `sdkGitRef` are copied unchanged into the trust statement:

```json
{
  "tool": {
    "id": "com.example.mytool",
    "versionCode": 1,
    "versionName": "1.0.0",
    "gitUrl": "https://github.com/example/mytool",
    "gitCommit": "<full commit SHA>"
  },
  "sdkGitRef": "v0.1.1"
}
```

Artifact metadata and builder-specific inputs remain in the recipe's `artifact`
and `build` objects.

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
- **Tools should be public.** Eventually, tools will only be buildable if they are public.

## Tests

```sh
# Python (extraction policy)
cd builder
# Python 3.11 or newer is required (`tomllib` is part of the standard library).
python3 -m venv .venv
.venv/bin/pip install pytest
.venv/bin/python -m pytest tests/

# Kotlin (metadata parser + manifest generator)
cd plugin
../gradlew test

# End to end: sample tool + tests/fixtures/ through the proxy (needs a built image)
builder/tests/integration.sh lightphone/light-builder:<tag> ~/light-builder-integration
```

CircleCI runs all three on builder branches (`verify-light-builder`) without
publishing the image.
