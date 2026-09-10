from __future__ import annotations

import json
import tempfile
import zipfile
from datetime import UTC, datetime
from pathlib import Path

from .apk import TRUST_STATEMENT_PATH, ApkMetadata, inspect_apk
from .build_recipe import BuildRecipe, load_build_recipe, sha256
from .errors import SignerError
from .keys import certificate_path, certificate_sha256
from .registry import load_registry, require_owner


def inject_trust_statement(*, apk: Path, build_recipe_path: Path, registry_path: Path,
                           dev_id: str, build_id: str, keys_dir: Path, output: Path,
                           apkanalyzer: Path, issued_at: str | None = None) -> dict[str, object]:
    # verify that unsigned apk matches build recipe and stamp parameters
    build_recipe = load_build_recipe(build_recipe_path)
    actual_hash = sha256(apk)
    if actual_hash != build_recipe.unsigned_sha256:
        raise SignerError("unsigned_hash_mismatch", "APK SHA-256 does not match recipe")
    _check_metadata(build_recipe, inspect_apk(apk, apkanalyzer))
    entry = require_owner(load_registry(registry_path), build_recipe.tool.id, dev_id)

    # The source stamp added during APK signing authenticates this statement.
    trust_statement: dict[str, object] = {
        "schemaVersion": 1,
        "tool": {
            "id": build_recipe.tool.id,
            "versionCode": build_recipe.tool.version_code,
            "versionName": build_recipe.tool.version_name,
            "gitUrl": build_recipe.tool.git_url,
            "gitCommit": build_recipe.tool.git_commit,
        },
        "sdkGitRef": build_recipe.sdk_git_ref,
        "devId": dev_id,
        "signerSha256": certificate_sha256(certificate_path(keys_dir, entry.key_id)),
        "buildId": build_id,
        "issuedAt": issued_at or datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
    }

    # inject trust statement in build
    _rewrite_zip(
        apk,
        output,
        json.dumps(trust_statement, indent=2, sort_keys=True).encode() + b"\n",
    )
    return trust_statement


def _check_metadata(build_recipe: BuildRecipe, metadata: ApkMetadata) -> None:
    expected = (
        build_recipe.tool.id,
        build_recipe.tool.version_code,
        build_recipe.tool.version_name,
    )
    actual = (metadata.application_id, metadata.version_code, metadata.version_name)
    if actual != expected:
        raise SignerError("apk_metadata_mismatch", f"APK metadata {actual!r} does not match recipe {expected!r}")


def _rewrite_zip(source: Path, output: Path, trust_statement_bytes: bytes) -> None:
    if source.resolve() == output.resolve():
        raise SignerError("output_overwrites_input", "output must differ from input")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(temporary_path, "w") as rewritten:
            for info in original.infolist():
                if info.filename != TRUST_STATEMENT_PATH:
                    rewritten.writestr(info, original.read(info))
            rewritten.writestr(
                TRUST_STATEMENT_PATH,
                trust_statement_bytes,
                compress_type=zipfile.ZIP_DEFLATED,
            )
        _ = temporary_path.replace(output)
    except Exception:
        temporary_path.unlink(missing_ok=True)
        raise
