from __future__ import annotations

import json
import subprocess
import tempfile
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .bundle_format import SIGNED_PAYLOAD_PREFIX, SUPPORTED_SCHEMA_VERSION
from .errors import SignerError


REQUIRED_BUNDLE_FIELDS = {
    "schemaVersion",
    "version",
    "issuedAt",
    "allow",
    "block",
    "trustedStampCerts",
    "revokedStampCerts",
}
SHA256_FIELDS = {"signerSha256", "apkSha256"}


def load_json(path: Path) -> Any:
    try:
        with path.open("r", encoding="utf-8") as source:
            return json.load(source, object_pairs_hook=_reject_duplicate_keys)
    except SignerError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SignerError("invalid_json", f"could not read {path}: {error}") from error


def build_bundle(*, portal: Path, output_dir: Path, private_key: Path, openssl: Path) -> bytes:
    source = _object(load_json(portal), "portal input")
    bundle: dict[str, Any] = {
        "schemaVersion": SUPPORTED_SCHEMA_VERSION,
        "version": source.get("version"),
        "issuedAt": source.get("issuedAt") or datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "allow": source.get("allow"),
        "block": source.get("block"),
        "trustedStampCerts": source.get("trustedStampCerts"),
        "revokedStampCerts": source.get("revokedStampCerts"),
    }
    validate_bundle(bundle)
    output_dir.mkdir(parents=True, exist_ok=True)
    bundle_path = output_dir / "bundle.json"
    _require_newer_version(bundle["version"], bundle_path)
    bundle_bytes = json.dumps(bundle, indent=2, sort_keys=True).encode("utf-8") + b"\n"
    bundle_path.write_bytes(bundle_bytes)
    signature = _sign(openssl, private_key, SIGNED_PAYLOAD_PREFIX + bundle_path.read_bytes())
    (output_dir / "bundle.sig").write_bytes(signature)
    return bundle_bytes


def verify_bundle(*, bundle: Path, signature: Path, public_keys: list[Path], openssl: Path) -> dict[str, Any]:
    try:
        bundle_bytes = bundle.read_bytes()
        signature_bytes = signature.read_bytes()
    except OSError as error:
        raise SignerError("bundle_io_error", str(error)) from error
    if not public_keys:
        raise SignerError("no_bundle_keys", "at least one bundle public key is required")
    payload = SIGNED_PAYLOAD_PREFIX + bundle_bytes
    if not any(_verify(openssl, key, payload, signature_bytes) for key in public_keys):
        raise SignerError("invalid_bundle_signature", "bundle signature did not match a pinned key")
    try:
        document = json.loads(bundle_bytes, object_pairs_hook=_reject_duplicate_keys)
    except SignerError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise SignerError("invalid_json", f"bundle is not valid JSON: {error}") from error
    return validate_bundle(document)


def validate_bundle(value: Any) -> dict[str, Any]:
    bundle = _object(value, "bundle")
    _exact_fields(bundle, REQUIRED_BUNDLE_FIELDS, "bundle")
    schema_version = _integer(bundle["schemaVersion"], "schemaVersion")
    if schema_version > SUPPORTED_SCHEMA_VERSION:
        raise SignerError("unsupported_schema_version", f"schemaVersion {schema_version} is newer than supported version 1")
    if schema_version != SUPPORTED_SCHEMA_VERSION:
        raise SignerError("invalid_schema_version", "schemaVersion must be 1")
    _nonnegative_integer(bundle["version"], "version")
    _nonempty_string(bundle["issuedAt"], "issuedAt")
    _validate_cert_list(bundle["trustedStampCerts"], "trustedStampCerts")
    _validate_cert_list(bundle["revokedStampCerts"], "revokedStampCerts")
    allow = _list(bundle["allow"], "allow")
    tool_ids: set[str] = set()
    for index, raw_entry in enumerate(allow):
        entry = _object(raw_entry, f"allow[{index}]")
        required = {"toolId", "signerSha256", "minVersionCode", "approvedArtifacts"}
        _exact_fields(entry, required, f"allow[{index}]")
        tool_id = _nonempty_string(entry["toolId"], f"allow[{index}].toolId")
        if tool_id in tool_ids:
            raise SignerError("duplicate_allow_tool", f"allow contains duplicate toolId {tool_id}")
        tool_ids.add(tool_id)
        _sha256(entry["signerSha256"], f"allow[{index}].signerSha256")
        _nonnegative_integer(entry["minVersionCode"], f"allow[{index}].minVersionCode")
        artifacts = _list(entry["approvedArtifacts"], f"allow[{index}].approvedArtifacts")
        if not artifacts:
            raise SignerError("invalid_approved_artifacts", f"allow[{index}].approvedArtifacts must not be empty")
        for artifact_index, raw_artifact in enumerate(artifacts):
            artifact = _object(raw_artifact, f"allow[{index}].approvedArtifacts[{artifact_index}]")
            if set(artifact) not in ({"buildId"}, {"apkSha256"}):
                raise SignerError("invalid_approved_artifact", "approvedArtifacts entry must contain exactly buildId or apkSha256")
            field = next(iter(artifact))
            if field == "apkSha256":
                _sha256(artifact[field], f"approvedArtifacts.{field}")
            else:
                _nonempty_string(artifact[field], f"approvedArtifacts.{field}")
    for index, raw_entry in enumerate(_list(bundle["block"], "block")):
        entry = _object(raw_entry, f"block[{index}]")
        _exact_fields(entry, {"match", "action", "reason"}, f"block[{index}]")
        match = _object(entry["match"], f"block[{index}].match")
        if set(match) not in ({"signerSha256"}, {"toolId"}, {"toolId", "versionCode"}, {"apkSha256"}):
            raise SignerError("invalid_block_match", "block.match must select exactly one supported match form")
        for field, field_value in match.items():
            if field in SHA256_FIELDS:
                _sha256(field_value, f"block[{index}].match.{field}")
            elif field == "versionCode":
                _nonnegative_integer(field_value, f"block[{index}].match.versionCode")
            else:
                _nonempty_string(field_value, f"block[{index}].match.toolId")
        if entry["action"] not in {"block", "purge"}:
            raise SignerError("invalid_block_action", f"block[{index}].action must be block or purge")
        _nonempty_string(entry["reason"], f"block[{index}].reason")
    return bundle


def _require_newer_version(version: int, bundle_path: Path) -> None:
    if not bundle_path.exists():
        return
    previous = validate_bundle(load_json(bundle_path))
    if version <= previous["version"]:
        raise SignerError("bundle_version_not_newer", f"version {version} must exceed previous version {previous['version']}")


def _sign(openssl: Path, private_key: Path, payload: bytes) -> bytes:
    with tempfile.NamedTemporaryFile() as payload_file:
        payload_file.write(payload)
        payload_file.flush()
        command = [
            str(openssl), "pkeyutl", "-sign", "-rawin", "-inkey", str(private_key), "-in", payload_file.name,
        ]
        result = subprocess.run(command, capture_output=True, check=False)
    if result.returncode != 0:
        raise SignerError("external_tool_failed", result.stderr.decode(errors="replace").strip())
    return result.stdout


def _verify(openssl: Path, public_key: Path, payload: bytes, signature: bytes) -> bool:
    command = [str(openssl), "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(public_key)]
    with tempfile.NamedTemporaryFile() as payload_file, tempfile.NamedTemporaryFile() as signature_file:
        payload_file.write(payload)
        payload_file.flush()
        signature_file.write(signature)
        signature_file.flush()
        verified = subprocess.run(
            command + ["-in", payload_file.name, "-sigfile", signature_file.name],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    return verified.returncode == 0


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise SignerError("duplicate_json_key", f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _object(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise SignerError("invalid_bundle_structure", f"{field} must be an object")
    return value


def _list(value: Any, field: str) -> list[Any]:
    if not isinstance(value, list):
        raise SignerError("invalid_bundle_structure", f"{field} must be an array")
    return value


def _integer(value: Any, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise SignerError("invalid_bundle_structure", f"{field} must be an integer")
    return value


def _nonnegative_integer(value: Any, field: str) -> int:
    result = _integer(value, field)
    if result < 0:
        raise SignerError("invalid_bundle_structure", f"{field} must be non-negative")
    return result


def _nonempty_string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise SignerError("invalid_bundle_structure", f"{field} must be a non-empty string")
    return value


def _sha256(value: Any, field: str) -> str:
    result = _nonempty_string(value, field)
    if len(result) != 64 or any(character not in "0123456789abcdef" for character in result):
        raise SignerError("invalid_sha256", f"{field} must be 64 lowercase hexadecimal characters")
    return result


def _exact_fields(value: dict[str, Any], expected: set[str], field: str) -> None:
    missing = expected - set(value)
    unknown = set(value) - expected
    if missing:
        raise SignerError("missing_bundle_field", f"{field} missing required field: {sorted(missing)[0]}")
    if unknown:
        raise SignerError("unknown_bundle_field", f"{field} has unknown field: {sorted(unknown)[0]}")


def _validate_cert_list(value: Any, field: str) -> None:
    certificates = _list(value, field)
    for index, certificate in enumerate(certificates):
        _sha256(certificate, f"{field}[{index}]")
    if len(set(certificates)) != len(certificates):
        raise SignerError("duplicate_stamp_cert", f"{field} contains a duplicate certificate")
