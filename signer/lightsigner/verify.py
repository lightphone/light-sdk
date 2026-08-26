from __future__ import annotations

import base64
import binascii
from pathlib import Path

from .apk import inspect_apk, read_trust_statement, verify_apk_signature
from .errors import SignerError
from .stamp import ATTESTATION_KEY_ID
from .trust_statement import canonical_bytes, verify_ed25519


def verify_apk(*, apk: Path, attestation_public_key: Path, apksigner: Path,
               apkanalyzer: Path, expected_key_id: str = ATTESTATION_KEY_ID) -> dict[str, object]:
    signer_hash = verify_apk_signature(apk, apksigner)
    trust_statement = read_trust_statement(apk)
    attestation = trust_statement.get("attestation")

    if not isinstance(attestation, dict):
        raise SignerError("invalid_attestation", "attestation is missing")

    if attestation.get("alg") != "Ed25519" or attestation.get("keyId") != expected_key_id:
        raise SignerError("invalid_attestation", "attestation algorithm or key ID is invalid")

    encoded = attestation.get("sig")
    if not isinstance(encoded, str):
        raise SignerError("invalid_attestation", "attestation signature is invalid")

    try:
        signature = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise SignerError("invalid_attestation", "attestation signature is not base64") from error

    try:
        verified = verify_ed25519(canonical_bytes(trust_statement), signature, attestation_public_key)
    except TypeError as error:
        raise SignerError("invalid_statement", str(error)) from error
    if not verified:
        raise SignerError("attestation_failed", "trust statement signature is invalid")
    if trust_statement.get("signerSha256") != signer_hash:
        raise SignerError("signer_mismatch", "trust statement signer does not match APK signer")

    tool = trust_statement.get("tool")
    if not isinstance(tool, dict):
        raise SignerError("invalid_statement", "trust statement tool is missing")

    metadata = inspect_apk(apk, apkanalyzer)
    if (tool.get("id"), tool.get("versionCode"), tool.get("versionName")) != (
        metadata.application_id, metadata.version_code, metadata.version_name
    ):
        raise SignerError("statement_apk_mismatch", "statement tool metadata does not match APK")
    return trust_statement
