# Test-only attestation key

The private key in this directory exists only to regenerate deterministic test
vectors. It is public, provides no trust, and must never be configured as a
production Light attestation key.

Regenerate from `signer/`:

```sh
python3 -m tests.vectors.generate
```
