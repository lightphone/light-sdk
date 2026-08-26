from __future__ import annotations

import base64
import json
from pathlib import Path

from lightsigner.statement import canonical_bytes, verify_ed25519


VECTORS = Path(__file__).parent / "vectors"


def _statement() -> dict[str, object]:
    return json.loads((VECTORS / "statement.json").read_text(encoding="utf-8"))


def test_canonical_bytes_match_vector() -> None:
    assert canonical_bytes(_statement()) == (VECTORS / "statement.canonical.json").read_bytes()


def test_reordered_keys_canonicalize_identically() -> None:
    statement = _statement()
    reordered = dict(reversed(list(statement.items())))
    assert canonical_bytes(reordered) == canonical_bytes(statement)


def test_unicode_is_utf8_not_ascii_escaped() -> None:
    canonical = canonical_bytes(_statement())
    assert "luz-☀".encode() in canonical
    assert b"\\u2600" not in canonical


def test_vector_signature_verifies() -> None:
    statement = _statement()
    signature = base64.b64decode(statement["attestation"]["sig"], validate=True)
    assert verify_ed25519(
        canonical_bytes(statement), signature, VECTORS / "test-attestation-public.pem"
    )


def test_modified_statement_does_not_verify() -> None:
    statement = _statement()
    signature = base64.b64decode(statement["attestation"]["sig"], validate=True)
    statement["tool"]["versionCode"] = 4
    assert not verify_ed25519(
        canonical_bytes(statement), signature, VECTORS / "test-attestation-public.pem"
    )


def test_floats_are_rejected() -> None:
    statement = _statement()
    statement["schemaVersion"] = 1.0
    try:
        canonical_bytes(statement)
    except ValueError as error:
        assert str(error) == "floats are not allowed in trust documents"
    else:
        raise AssertionError("float was accepted")
