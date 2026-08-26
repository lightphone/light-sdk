from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from .errors import SignerError
from .tools import run_tool


@dataclass(frozen=True)
class ApkMetadata:
    application_id: str
    version_code: int
    version_name: str


def inspect_apk(apk: Path, apkanalyzer: Path) -> ApkMetadata:
    def manifest(field: str) -> str:
        return run_tool([str(apkanalyzer), "manifest", field, str(apk)]).strip()

    try:
        return ApkMetadata(manifest("application-id"), int(manifest("version-code")), manifest("version-name"))
    except ValueError as error:
        raise SignerError("invalid_apk_metadata", "APK version code is not an integer") from error


def verify_apk_signature(apk: Path, apksigner: Path) -> str:
    output = run_tool([str(apksigner), "verify", "--print-certs", str(apk)])
    digests = re.findall(r"(?:Signer #\d+|V\d+(?:\.\d+)? Signer): certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", output)
    normalized = {digest.replace(":", "").lower() for digest in digests}
    if len(normalized) != 1:
        raise SignerError("invalid_apk_signers", f"expected one APK signer, found {len(normalized)}")
    return normalized.pop()
