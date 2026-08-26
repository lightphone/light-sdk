# Light signer

Offline PoC tooling that registers tool ownership, stamps an attested trust
statement into an unsigned APK, signs the APK, and verifies the result. Requires
Python 3.11+, OpenSSL, a JDK `keytool`, and Android SDK `apksigner` and
`apkanalyzer`.

Run commands from this directory with `python -m lightsigner`. Use `--help` on
the command or a subcommand for all arguments.

```sh
export LIGHT_SIGNER_KEY_PASSWORD='replace-me'
# Generate the APK signing key.
python -m lightsigner keygen --tool-id com.example.tool --keys-dir keys

# Stamp the APK with a signed trust statement.
python -m lightsigner stamp --apk tool-unsigned.apk --build-recipe recipe.json \
  --registry registry.json --dev-id dev_example --build-id build_example \
  --attestation-key attestation-private.pem --keys-dir keys --out tool-stamped.apk

# Sign and verify the stamped APK.
python -m lightsigner sign --apk tool-stamped.apk --build-recipe recipe.json \
  --registry registry.json --build-id build_example --keys-dir keys \
  --out tool.apk --signed-metadata signed.json

# Perform offline verification of the APK.
python -m lightsigner verify --apk tool.apk \
  --attestation-public-key attestation-public.pem
```

`registry.json` maps a tool ID to its developer and permanent APK signing key:

```json
{
  "com.example.tool": {
    "devId": "dev_example",
    "keyId": "com.example.tool"
  }
}
```

Keys are written below ignored `keys/`. This filesystem keystore is only for
the PoC. Production signing keys must be held by a KMS or HSM. The password is
read only from `LIGHT_SIGNER_KEY_PASSWORD`; commands fail if it is absent.

Android tools resolve from an explicit override, `PATH`, then the latest tool
under `ANDROID_SDK_ROOT` or `ANDROID_HOME`.
