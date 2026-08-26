from __future__ import annotations

import base64
import json
import tempfile
import zipfile
from datetime import UTC, datetime
from pathlib import Path

from .apk import inspect_apk
from .errors import SignerError
from .keys import certificate_path, certificate_sha256
from .recipe import Recipe, load_recipe, sha256
from .registry import load_registry, require_owner
from .statement import canonical_bytes, sign_ed25519


STATEMENT_PATH = "META-INF/light-trust.json"
ATTESTATION_KEY_ID = "light-attest-1"


def stamp_apk(*, apk: Path, recipe_path: Path, registry_path: Path, dev_id: str,
              build_id: str, attestation_key: Path, keys_dir: Path, output: Path,
              apkanalyzer: Path, issued_at: str | None = None) -> dict[str, object]:
    # verify that unsigned apk matches build recipe and stamp parameters
    recipe = load_recipe(recipe_path)
    actual_hash = sha256(apk)
    if actual_hash != recipe.unsigned_sha256:
        raise SignerError("unsigned_hash_mismatch", "APK SHA-256 does not match recipe")
    _check_metadata(recipe, inspect_apk(apk, apkanalyzer))
    entry = require_owner(load_registry(registry_path), recipe.tool.id, dev_id)

    # build and sign trust statement
    statement: dict[str, object] = {
        "schemaVersion": 1,
        "tool": {
            "id": recipe.tool.id,
            "versionCode": recipe.tool.version_code,
            "versionName": recipe.tool.version_name,
            "gitUrl": recipe.tool.git_url,
            "gitCommit": recipe.tool.git_commit,
        },
        "sdkGitRef": recipe.sdk_git_ref,
        "devId": dev_id,
        "signerSha256": certificate_sha256(certificate_path(keys_dir, entry.key_id)),
        "buildId": build_id,
        "unsignedSha256": actual_hash,
        "issuedAt": issued_at or datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
    }
    signature = sign_ed25519(canonical_bytes(statement), attestation_key)
    statement["attestation"] = {
        "keyId": ATTESTATION_KEY_ID,
        "alg": "Ed25519",
        "sig": base64.b64encode(signature).decode("ascii"),
    }

    # inject trust statement in build
    _rewrite_zip(apk, output, json.dumps(statement, indent=2, sort_keys=True).encode() + b"\n")
    return statement


def read_statement(apk: Path) -> dict[str, object]:
    try:
        with zipfile.ZipFile(apk) as archive:
            matches = [info for info in archive.infolist() if info.filename == STATEMENT_PATH]
            if len(matches) != 1:
                raise SignerError("invalid_statement_count", f"expected one trust statement, found {len(matches)}")
            value = json.loads(archive.read(matches[0]), object_pairs_hook=_unique_object)
    except (OSError, zipfile.BadZipFile, json.JSONDecodeError, UnicodeError) as error:
        raise SignerError("invalid_statement", "trust statement is unreadable") from error
    if not isinstance(value, dict):
        raise SignerError("invalid_statement", "trust statement must be an object")
    return value


def _check_metadata(recipe: Recipe, metadata: object) -> None:
    expected = (recipe.tool.id, recipe.tool.version_code, recipe.tool.version_name)
    actual = (metadata.application_id, metadata.version_code, metadata.version_name)
    if actual != expected:
        raise SignerError("apk_metadata_mismatch", f"APK metadata {actual!r} does not match recipe {expected!r}")


def _rewrite_zip(source: Path, output: Path, statement: bytes) -> None:
    if source.resolve() == output.resolve():
        raise SignerError("output_overwrites_input", "output must differ from input")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(temporary_path, "w") as rewritten:
            for info in original.infolist():
                if info.filename != STATEMENT_PATH:
                    rewritten.writestr(info, original.read(info))
            rewritten.writestr(STATEMENT_PATH, statement, compress_type=zipfile.ZIP_DEFLATED)
        _ = temporary_path.replace(output)
    except Exception:
        temporary_path.unlink(missing_ok=True)
        raise


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise SignerError("invalid_statement", f"duplicate field: {key}")
        result[key] = value
    return result
