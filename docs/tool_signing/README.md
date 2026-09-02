# Tool signing and trust statements

Tools are built and signed using Light CI infrastructure, and then verified in the phone by LightOS.
Signing lets LightOS confirm that an APK came through Light's build pipeline and was not modified afterward.
Android also uses the APK signing key as the app's identity, allowing updates only when they are signed by the same per-tool key.

## Building tools

The builder runs developer source in an isolated environment with no signing keys. It produces:
- `tool-unsigned.apk`: the unsigned Android package.
- `recipe.json`: a record of the artifact, tool source, SDK git ref, and build inputs.

The `tool` object and `sdkGitRef` in the recipe are later copied into the trust statement.
Builder code lives in `builder/`.

## Signing APKs

Signing happens separately from building. The signer will:
1. verify the build recipe and unsigned APK
2. add the trust statement file to the APK at `META-INF/light-trust.json`
3. sign the APK with its per-tool Android signing key and Light source-stamp key.

The trust statement identifies the tool, SDK, developer, build, and APK signing certificate.
Android's APK source stamp authenticates the complete signed APK, including the statement.

## Verifying signed APKs

The device verifies the APK source stamp against a pinned certificate before it
reads the statement, then compares `signerSha256` with the APK certificate
reported by Android.

The pure-JVM verification foundation lives in `sdk/trust/`:

- `LightTrustStatement` defines the statement fields.
- `StampVerifier` isolates platform source-stamp verification from pure policy.
- `StampResult` represents verified, absent, failed, and unavailable outcomes.

The statement carries no independent signature. It must never be trusted unless
the containing APK's source stamp has already verified.
