from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


VECTOR_ROOT = Path(__file__).resolve().parent
SIGNER_ROOT = VECTOR_ROOT.parents[2]
sys.path.insert(0, str(SIGNER_ROOT))

from lightsigner.bundle_format import SIGNED_PAYLOAD_PREFIX  # noqa: E402


OPENSSL = "openssl"
PRIVATE_KEY = VECTOR_ROOT / "INSECURE-bundle-private.pem"
FOREIGN_PRIVATE_KEY = VECTOR_ROOT / "INSECURE-foreign-private.pem"
A = "a" * 64
B = "b" * 64
C = "c" * 64
D = "d" * 64


def encode(document: dict[str, object]) -> bytes:
    return json.dumps(document, indent=2, sort_keys=True).encode() + b"\n"


def sign(payload: bytes, key: Path) -> bytes:
    payload_path = VECTOR_ROOT / ".payload.tmp"
    payload_path.write_bytes(payload)
    try:
        result = subprocess.run(
            [OPENSSL, "pkeyutl", "-sign", "-rawin", "-inkey", key, "-in", payload_path],
            capture_output=True,
            check=True,
        )
    finally:
        payload_path.unlink(missing_ok=True)
    return result.stdout


def write_pair(name: str, payload: bytes, signature: bytes) -> None:
    directory = VECTOR_ROOT / name
    directory.mkdir(exist_ok=True)
    (directory / "bundle.json").write_bytes(payload)
    (directory / "bundle.sig").write_bytes(signature)


bundle: dict[str, object] = {
    "schemaVersion": 1,
    "version": 42,
    "issuedAt": "2026-08-25T00:00:00Z",
    "allow": [{
        "toolId": "com.example.tool",
        "signerSha256": A,
        "minVersionCode": 7,
        "approvedArtifacts": [{"buildId": "build_01H"}, {"apkSha256": B}],
    }],
    "block": [{"match": {"toolId": "com.bad.tool"}, "action": "purge", "reason": "compromised"}],
    "trustedStampCerts": [A, C],
    "revokedStampCerts": [A, B],
}
valid = encode(bundle)
valid_signature = sign(SIGNED_PAYLOAD_PREFIX + valid, PRIVATE_KEY)
write_pair("valid", valid, valid_signature)

edited = valid.replace(b"build_01H", b"build_02H")
write_pair("edited-payload", edited, valid_signature)
write_pair("whitespace-edit", valid + b" ", valid_signature)
write_pair("foreign-key", valid, sign(SIGNED_PAYLOAD_PREFIX + valid, FOREIGN_PRIVATE_KEY))
write_pair("no-domain-separator", valid, sign(valid, PRIVATE_KEY))

newer = encode({**bundle, "schemaVersion": 2, "version": 43})
write_pair("newer-schema", newer, sign(SIGNED_PAYLOAD_PREFIX + newer, PRIVATE_KEY))

(VECTOR_ROOT / "expected-trust-set.json").write_text(
    json.dumps({"imagePins": [A, B, D], "trusted": [C, D]}, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
