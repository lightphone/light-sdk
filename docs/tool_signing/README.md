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
3. sign the APK with its per-tool Android signing key.

The trust statement identifies the tool, SDK, developer, build, unsigned APK, and APK signing certificate.
Light attests the statement by signing it with a separate Light attestation key (ed25519 algorithm).

## Verifying signed APKs

The device reads the statement and produces the same canonical JSON bytes used
by the signer. It verifies the Ed25519 signature against a pinned Light public
key and compares `signerSha256` with the APK certificate reported by Android.

The pure-JVM verification foundation lives in `sdk/trust/`:

- `LightTrustStatement` defines the statement fields.
- `LightTrustCanonicalizer` produces deterministic signature bytes.
- `LightAttestationVerifier` verifies Ed25519 signatures.

## Canonicalization

Canonical JSON is the PoC format. A later task will evaluate replacing it with a
standard signing envelope such as DSSE, which carries the exact signed payload
bytes and removes the need for Python and Kotlin to reserialize JSON identically.

Signing is implemented in Python while verification is implemented in Kotlin.
Their JSON libraries may differ in key ordering, escaping, and number handling,
so we need a canonicalization step to generate a stable attestation byte sequence
that can be used to verify the signature.

For the same reason, tests in either language alone could pass even when the two
implementations are incompatible. Both use the fixtures in
`signer/tests/vectors/`, ensuring they agree byte-for-byte and verify the same
signature.
