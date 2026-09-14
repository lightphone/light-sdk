# Trust bundle v1 contract

`bundle-schema-v1.json` describes the signed document. Python performs equivalent
structural checks without a runtime JSON Schema dependency, plus unique allow
`toolId` enforcement and duplicate JSON-key rejection. All counters fit a
nonnegative signed 64-bit integer. `issuedAt` is a valid calendar timestamp in
UTC, exactly `YYYY-MM-DDTHH:MM:SSZ`; it does not control expiry. JSON nesting is
limited to 64 levels. The schema version is checked before other root fields so
newer formats are refused with a schema-specific reason.

The portal input has exactly the signed document's fields except
`schemaVersion`. Every field, including `issuedAt`, is required; unknown fields
are errors. The builder supplies schema version 1.

`bundle-domain-separator.txt` is the shared signature prefix definition. Its
trailing CR/LF bytes are excluded identically in both languages, and a NUL byte
precedes the exact JSON bytes. Git attributes enforce LF for `trust-format/**`;
both language suites independently assert the expected prefix bytes.
Bundle keys must be Ed25519, with unencrypted PEM private keys for this offline
PoC. Public keys use SubjectPublicKeyInfo PEM encoding.
Every configured public key is validated before signature attempts. Any missing,
unreadable, malformed, or non-Ed25519 pin rejects the configuration regardless of
list order. A valid Ed25519 pin that does not match the signature permits trying
the remaining validated pins.

## Image pins and ownership

The firmware integration owns image-pinned source-stamp SHA-256 digests and
bundle verification public keys. Keep them in image-owned resources in LightOS,
outside downloaded bundles and outside this reusable trust library. The 05b
store will receive image stamp pins as an explicit immutable constructor input;
09a/14 supply those pins from the image. The bundle verifier independently
receives pinned bundle public keys.

The image must also supply the initial signed bundle and its version floor.
Production key values and the concrete LightOS resource path belong to firmware
integration; no insecure fixture key is a production default. JVM fixtures use
`signer/tests/vectors/bundle/expected-trust-set.json` for image-pin inputs.

The effective install trust set is `(imagePins | trustedStampCerts) -
revokedStampCerts`. Omitting an image pin from `trustedStampCerts` cannot remove
it; explicitly revoking it can. Executable store guarantees belong to 05b.

## Builder publication

Builds stage the JSON and sign those written bytes before replacing published
files. Signing failure preserves the previous pair and permits retrying the
same version. Publication replaces the signature before the payload, leaving
the previous version floor until the final rename. A crash or rename error
between replacements may leave a mismatched pair; verification refuses it and
the same new version can be retried. This is not a two-file atomic transaction.
Run only one builder per output directory.
