# INSECURE dev trust-bundle key

A throwaway Ed25519 keypair, generated purely so the emulator can exercise
`LightTrustStore.accept()` locally. `EmulatorApplication` pins the public key
when it opens the store in the emulator build.

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

## Enable HTTP uploads

In a debug emulator build, provision a random 32-byte token as 64 lowercase hex
characters in the app-private `files/trust/upload-token` file through your local
developer connection, then restart the app. Send the token in the
`Authorization: Bearer` header when submitting a bundle. Use a private developer
connection for the upload because the emulator HTTP server uses plaintext HTTP.
