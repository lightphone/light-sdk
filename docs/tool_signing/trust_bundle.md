# Trust bundle and trust store

The trust bundle is a signed JSON document Light publishes to devices. It holds:
- `version`: increases with every published bundle.
- `allow`: approved tools. Each entry names a `toolId`, the only APK signing certificate approved for it,
  a `minVersionCode` floor, and the approved artifacts, by `buildId` or `apkSha256`.
- `block`: tools to block or purge, matched by signing certificate, tool ID, tool version, or APK hash.
- `trustedStampCerts` and `revokedStampCerts`: source-stamp certificates to add or remove.

## Building and signing

The bundle is built with `python -m lightsigner bundle build` (see [`signer/README.md`](../../signer/README.md)).
It is written as `bundle.json` and a detached Ed25519 signature `bundle.sig`.

The signature covers a fixed domain separator, a NUL byte, and then the exact JSON bytes.
The JSON must be UTF-8 without a BOM.
The device checks the signature before it parses anything, and refuses bundles with a newer schema.

## Trust store

`LightTrustStore` keeps the newest bundle the device has accepted. A new bundle is accepted only if:
1. its signature verifies against one of the pinned bundle public keys
2. its `version` is higher than the stored one.

The stored bundle's `version` is also the version floor, so an old bundle can never be replayed to undo a change.
A rejected bundle leaves the state and storage untouched.

On boot, the store re-verifies the stored bundle and compares it with the bundle shipped in the firmware image.
The newer of the two wins, so a firmware update can move the bundle forward, never back.

## Image pins

The firmware image supplies:
- the pinned bundle public keys
- the pinned source-stamp certificates
- the initial signed bundle.

These live in LightOS, outside downloaded bundles and outside `sdk/trust/`.
Test keys prefixed `INSECURE-` must never be configured on a device.

The source-stamp certificates the device trusts are:

```
(image pins + trustedStampCerts) - revokedStampCerts
```

A bundle cannot remove an image pin by leaving it out, only by revoking it explicitly.
If a certificate is both trusted and revoked, it is revoked.
