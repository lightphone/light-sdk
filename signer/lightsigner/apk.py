from __future__ import annotations

import json
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path

from .errors import SignerError
from .tools import run_tool

TRUST_STATEMENT_PATH = "META-INF/light-trust.json"


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
    normalized: set[str] = {digest.replace(":", "").lower() for digest in digests}
    if len(normalized) != 1:
        raise SignerError("invalid_apk_signers", f"expected one APK signer, found {len(normalized)}")
    return normalized.pop()


def read_trust_statement(apk: Path) -> dict[str, object]:
    try:
        with zipfile.ZipFile(apk) as archive:
            matches = [info for info in archive.infolist() if info.filename == TRUST_STATEMENT_PATH]
            if len(matches) != 1:
                raise SignerError(
                    "invalid_statement_count",
                    f"expected one trust statement, found {len(matches)}",
                )
            value = json.loads(archive.read(matches[0]), object_pairs_hook=_unique_object)
    except (OSError, zipfile.BadZipFile, json.JSONDecodeError, UnicodeError) as error:
        raise SignerError("invalid_statement", "trust statement is unreadable") from error
    if not isinstance(value, dict):
        raise SignerError("invalid_statement", "trust statement must be an object")
    return value


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise SignerError("invalid_statement", f"duplicate field: {key}")
        result[key] = value
    return result
