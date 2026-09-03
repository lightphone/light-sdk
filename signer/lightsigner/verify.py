from __future__ import annotations

from pathlib import Path

from .apk import inspect_apk, read_trust_statement, verify_apk_signature, verify_source_stamp
from .errors import SignerError


def verify_apk(*, apk: Path, stamp_cert_sha256: str, apksigner: Path,
               apkanalyzer: Path) -> dict[str, object]:
    signer_hash = verify_apk_signature(apk, apksigner)
    actual_stamp_hash = verify_source_stamp(apk, apksigner)
    expected_stamp_hash = stamp_cert_sha256.replace(":", "").lower()
    if actual_stamp_hash != expected_stamp_hash:
        raise SignerError("source_stamp_mismatch", "source stamp certificate is not trusted")
    trust_statement = read_trust_statement(apk)
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
