# INSECURE dev trust-bundle key

A throwaway Ed25519 keypair, generated purely so the emulator can exercise
`LightTrustStore.accept()` locally. It pins `provideTrustBundleVerifier`'s
default in `EmulatorApplication`, which only ever runs on the local emulator
build — never shipped to a device, never used for anything production.

Never use this for anything but local emulator testing. It carries no real
trust and is safe to delete and regenerate at any time:

```
openssl genpkey -algorithm Ed25519 -out INSECURE-trust-bundle-private.pem
openssl pkey -in INSECURE-trust-bundle-private.pem -pubout -out INSECURE-trust-bundle-public.pem
```

To sign a test bundle with the private key:

```
openssl pkeyutl -sign -rawin -inkey INSECURE-trust-bundle-private.pem -in <payload>
```

where `<payload>` is `"lightos-trust-bundle-v1" || 0x00 || bundle.json bytes`
(see `LightTrustBundleFormat.signedPayloadPrefix`).
