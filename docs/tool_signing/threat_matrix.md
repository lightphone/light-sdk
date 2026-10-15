# Threat matrix

Attacks the trust pipeline is tested against, and what stops each one.
APK cases are covered by `LightInstallPolicyTest`, and bundle cases by `TrustBundleTest`.

## APK installs

Results are at `AllowLightApprovedApks` unless noted.

| Attack                                                  | What catches it                                  | Result                            |
|---------------------------------------------------------|--------------------------------------------------|-----------------------------------|
| Self-signed developer build                             | no source stamp                                  | `NotLightBuilt` (`Allow` at `AllowAllApks`) |
| Statement edited after signing                          | the stamp covers the whole APK                   | `BadAttestation`                  |
| Statement edited, then APK re-signed by an attacker     | the stamp is not one Light issued                | `BadAttestation`                  |
| Statement transplanted into another APK                 | the stamp is bound to APK content                | `BadAttestation`                  |
| Transplanted statement that survives the stamp          | statement `signerSha256` ≠ APK certificate       | `StatementNotForThisApk`          |
| Repack that changed one `versionCode` but not the other | statement vs. manifest check                     | `VersionCodeMismatch`             |
| Light-built but never approved                          | no `allow` entry                                 | `NotApproved` (`Allow` at `AllowLightSignedApks`) |
| Fresh install of a superseded approved release          | approval `minVersionCode`                        | `BelowMinVersion`                 |
| Second signing key issued for an existing tool ID       | approval `signerSha256`                          | `SignerNotApprovedForTool`        |
| Forged `approved` field in the statement                | approval is only read from the bundle            | no effect                         |
| Unstamped APK claiming an approved tool ID              | stamp checked before approval                    | `NotLightBuilt`                   |
| Tool ID renamed to dodge a block                        | the stamp still fails                            | `BadAttestation`                  |
| Tool both blocked and approved                          | blocks are checked first                         | `Kill`                            |

## Trust bundle

| Attack                                          | What catches it                            | Result                      |
|-------------------------------------------------|--------------------------------------------|-----------------------------|
| Replaying an older bundle                       | version floor                              | `VersionNotNewer`           |
| Lowering an approval floor with an older bundle | version floor                              | `VersionNotNewer`           |
| Bundle signed by a foreign key                  | pinned bundle public keys                  | `InvalidSignature`          |
| Payload or whitespace edited after signing      | signature covers the exact bytes           | `InvalidSignature`          |
| Signature made without the domain separator     | signature covers the separator             | `InvalidSignature`          |
| Same JSON re-encoded as UTF-16, UTF-32, or with a BOM | only UTF-8 without a BOM is accepted | `InvalidJson`               |
| Bundle with a newer schema                      | schema version check                       | `UnsupportedSchema`         |
| Tampered bundle in device storage               | re-verified on boot                        | store refuses to open       |
| Dropping an image pin by leaving it out         | image pins stay unless revoked             | pin kept                    |

In every rejected case, the stored bundle and its version floor are left untouched.
