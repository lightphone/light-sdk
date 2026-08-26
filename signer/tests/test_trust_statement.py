from __future__ import annotations

import base64
import json
from pathlib import Path

from lightsigner.trust_statement import canonical_bytes, verify_ed25519

VECTORS = Path(__file__).parent / "vectors"


def _trust_statement() -> dict[str, object]:
    return json.loads((VECTORS / "statement.json").read_text(encoding="utf-8"))


def test_canonical_bytes_match_vector() -> None:
    assert canonical_bytes(_trust_statement()) == (VECTORS / "statement.canonical.json").read_bytes()


def test_reordered_keys_canonicalize_identically() -> None:
    trust_statement = _trust_statement()
    reordered = dict(reversed(list(trust_statement.items())))
    assert canonical_bytes(reordered) == canonical_bytes(trust_statement)


def test_unicode_is_utf8_not_ascii_escaped() -> None:
    canonical = canonical_bytes(_trust_statement())
    assert "luz-☀".encode() in canonical
    assert b"\\u2600" not in canonical


def test_vector_signature_verifies() -> None:
    trust_statement = _trust_statement()
    signature = base64.b64decode(trust_statement["attestation"]["sig"], validate=True)
    assert verify_ed25519(
        canonical_bytes(trust_statement), signature, VECTORS / "test-attestation-public.pem"
    )


def test_modified_trust_statement_does_not_verify() -> None:
    trust_statement = _trust_statement()
    signature = base64.b64decode(trust_statement["attestation"]["sig"], validate=True)
    trust_statement["tool"]["versionCode"] = 4
    assert not verify_ed25519(
        canonical_bytes(trust_statement), signature, VECTORS / "test-attestation-public.pem"
    )


def test_floats_are_rejected() -> None:
    trust_statement = _trust_statement()
    trust_statement["schemaVersion"] = 1.0
    try:
        canonical_bytes(trust_statement)
    except TypeError as error:
        assert str(error) == "floats are not allowed in trust documents"
    else:
        raise AssertionError("float was accepted")
