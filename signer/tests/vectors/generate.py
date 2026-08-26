"""Regenerate test-only trust statement vectors."""

from __future__ import annotations

import base64
import json
from pathlib import Path

from lightsigner.statement import canonical_bytes, sign_ed25519

VECTORS = Path(__file__).resolve().parent


statement: dict[str, object] = {
    "schemaVersion": 1,
    "tool": {
        "id": "com.example.tool",
        "versionCode": 3,
        "versionName": "1.2.0",
        "gitUrl": "https://github.com/example/luz-☀",
        "gitCommit": "a" * 40,
    },
    "sdkGitRef": "v0.1.1",
    "devId": "dev_test_01",
    "signerSha256": "c" * 64,
    "buildId": "build_test_01",
    "unsignedSha256": "d" * 64,
    "issuedAt": "2026-08-25T00:00:00Z",
}
canonical = canonical_bytes(statement)
signature = sign_ed25519(canonical, VECTORS / "test-attestation-private.pem")
statement["attestation"] = {
    "keyId": "test-attestation-1",
    "alg": "Ed25519",
    "sig": base64.b64encode(signature).decode("ascii"),
}

(VECTORS / "statement.canonical.json").write_bytes(canonical)
(VECTORS / "statement.json").write_text(
    json.dumps(statement, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
)
