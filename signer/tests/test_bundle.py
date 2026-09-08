from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any

import pytest

from lightsigner.bundle import build_bundle, load_json, validate_bundle, verify_bundle
from lightsigner.__main__ import main
from lightsigner.errors import SignerError
from lightsigner.tools import resolve_tool


OPENSSL = resolve_tool("openssl")
HASH_A = "a" * 64
HASH_B = "b" * 64


@pytest.fixture
def keypair(tmp_path: Path) -> tuple[Path, Path]:
    private = tmp_path / "private.pem"
    public = tmp_path / "public.pem"
    subprocess.run([OPENSSL, "genpkey", "-algorithm", "Ed25519", "-out", private], check=True)
    subprocess.run([OPENSSL, "pkey", "-in", private, "-pubout", "-out", public], check=True)
    return private, public


def portal(version: int = 1) -> dict[str, Any]:
    return {
        "version": version,
        "issuedAt": "2026-08-25T00:00:00Z",
        "allow": [{
            "toolId": "com.example.tool",
            "signerSha256": HASH_A,
            "minVersionCode": 1,
            "approvedArtifacts": [{"buildId": "build_01"}, {"apkSha256": HASH_B}],
        }],
        "block": [{"match": {"toolId": "com.bad.tool"}, "action": "block", "reason": "blocked"}],
        "trustedStampCerts": [HASH_A],
        "revokedStampCerts": [HASH_B],
    }


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value), encoding="utf-8")


def build(tmp_path: Path, private: Path, value: dict[str, Any] | None = None) -> tuple[Path, Path]:
    portal_path = tmp_path / "portal.json"
    write_json(portal_path, value or portal())
    output = tmp_path / "out"
    build_bundle(portal=portal_path, output_dir=output, private_key=private, openssl=OPENSSL)
    return output / "bundle.json", output / "bundle.sig"


def assert_code(code: str, action: Any) -> None:
    with pytest.raises(SignerError) as failure:
        action()
    assert failure.value.code == code


def test_build_and_verify_round_trip(tmp_path: Path, keypair: tuple[Path, Path]) -> None:
    private, public = keypair
    bundle, signature = build(tmp_path, private)
    result = verify_bundle(bundle=bundle, signature=signature, public_keys=[public], openssl=OPENSSL)
    assert result["version"] == 1


def test_build_reads_all_portal_fields(tmp_path: Path, keypair: tuple[Path, Path]) -> None:
    private, _ = keypair
    bundle, _ = build(tmp_path, private)
    result = load_json(bundle)
    for field in ("allow", "block", "trustedStampCerts", "revokedStampCerts"):
        assert result[field] == portal()[field]


def test_build_requires_increasing_version(tmp_path: Path, keypair: tuple[Path, Path]) -> None:
    private, _ = keypair
    build(tmp_path, private, portal(2))
    assert_code("bundle_version_not_newer", lambda: build(tmp_path, private, portal(2)))
    assert_code("bundle_version_not_newer", lambda: build(tmp_path, private, portal(1)))


@pytest.mark.parametrize("edit", [lambda value: value + b" ", lambda value: value.replace(b"build_01", b"build_02")])
def test_verify_rejects_edited_bytes(tmp_path: Path, keypair: tuple[Path, Path], edit: Any) -> None:
    private, public = keypair
    bundle, signature = build(tmp_path, private)
    bundle.write_bytes(edit(bundle.read_bytes()))
    assert_code(
        "invalid_bundle_signature",
        lambda: verify_bundle(bundle=bundle, signature=signature, public_keys=[public], openssl=OPENSSL),
    )


def test_verify_rejects_foreign_key(tmp_path: Path, keypair: tuple[Path, Path]) -> None:
    private, _ = keypair
    bundle, signature = build(tmp_path, private)
    foreign_private = tmp_path / "foreign-private.pem"
    foreign_public = tmp_path / "foreign-public.pem"
    subprocess.run([OPENSSL, "genpkey", "-algorithm", "Ed25519", "-out", foreign_private], check=True)
    subprocess.run([OPENSSL, "pkey", "-in", foreign_private, "-pubout", "-out", foreign_public], check=True)
    assert_code(
        "invalid_bundle_signature",
        lambda: verify_bundle(bundle=bundle, signature=signature, public_keys=[foreign_public], openssl=OPENSSL),
    )


def test_verify_rejects_signature_without_domain_separator(tmp_path: Path, keypair: tuple[Path, Path]) -> None:
    private, public = keypair
    bundle, signature = build(tmp_path, private)
    result = subprocess.run(
        [OPENSSL, "pkeyutl", "-sign", "-rawin", "-inkey", private, "-in", bundle],
        capture_output=True,
        check=True,
    )
    signature.write_bytes(result.stdout)
    assert_code(
        "invalid_bundle_signature",
        lambda: verify_bundle(bundle=bundle, signature=signature, public_keys=[public], openssl=OPENSSL),
    )


@pytest.mark.parametrize("match", [{}, {"toolId": "x", "apkSha256": HASH_A}, {"versionCode": 1}])
def test_rejects_invalid_block_match(match: dict[str, Any]) -> None:
    value = {"schemaVersion": 1, **portal()}
    value["block"] = [{"match": match, "action": "block", "reason": "bad"}]
    assert_code("invalid_block_match", lambda: validate_bundle(value))


@pytest.mark.parametrize("missing", ["minVersionCode", "approvedArtifacts"])
def test_rejects_missing_allow_field(missing: str) -> None:
    value = {"schemaVersion": 1, **portal()}
    del value["allow"][0][missing]
    assert_code("missing_bundle_field", lambda: validate_bundle(value))


@pytest.mark.parametrize("artifact", [{}, {"other": "x"}, {"buildId": "x", "apkSha256": HASH_A}])
def test_rejects_invalid_approved_artifact(artifact: dict[str, Any]) -> None:
    value = {"schemaVersion": 1, **portal()}
    value["allow"][0]["approvedArtifacts"] = [artifact]
    assert_code("invalid_approved_artifact", lambda: validate_bundle(value))


def test_rejects_duplicate_json_key(tmp_path: Path) -> None:
    duplicate = tmp_path / "duplicate.json"
    duplicate.write_text('{"version": 1, "nested": {"x": 1, "x": 2}}', encoding="utf-8")
    assert_code("duplicate_json_key", lambda: load_json(duplicate))


def test_rejects_newer_schema() -> None:
    value = {"schemaVersion": 2, **portal()}
    assert_code("unsupported_schema_version", lambda: validate_bundle(value))


def test_checked_in_vectors() -> None:
    root = Path(__file__).parent / "vectors" / "bundle"
    public = root / "INSECURE-bundle-public.pem"
    valid = verify_bundle(
        bundle=root / "valid" / "bundle.json",
        signature=root / "valid" / "bundle.sig",
        public_keys=[public],
        openssl=OPENSSL,
    )
    assert valid["version"] == 42
    for name in ("edited-payload", "whitespace-edit", "foreign-key", "no-domain-separator"):
        assert_code(
            "invalid_bundle_signature",
            lambda name=name: verify_bundle(
                bundle=root / name / "bundle.json",
                signature=root / name / "bundle.sig",
                public_keys=[public],
                openssl=OPENSSL,
            ),
        )
    assert_code(
        "unsupported_schema_version",
        lambda: verify_bundle(
            bundle=root / "newer-schema" / "bundle.json",
            signature=root / "newer-schema" / "bundle.sig",
            public_keys=[public],
            openssl=OPENSSL,
        ),
    )


def test_cli_verify_returns_reason_for_structural_failure(tmp_path: Path, keypair: tuple[Path, Path], capsys: Any) -> None:
    private, public = keypair
    bundle, signature = build(tmp_path, private)
    document = load_json(bundle)
    document["block"][0]["match"] = {}
    bundle.write_bytes(json.dumps(document).encode())
    signature.write_bytes(_sign_for_test(bundle.read_bytes(), private, tmp_path))
    result = main([
        "bundle", "verify",
        "--bundle-json", str(bundle),
        "--bundle-sig", str(signature),
        "--public-key", str(public),
        "--openssl", str(OPENSSL),
    ])
    assert result == 1
    assert "invalid_block_match" in capsys.readouterr().err


def _sign_for_test(bundle_bytes: bytes, private: Path, tmp_path: Path) -> bytes:
    from lightsigner.bundle_format import SIGNED_PAYLOAD_PREFIX

    payload = tmp_path / "payload"
    payload.write_bytes(SIGNED_PAYLOAD_PREFIX + bundle_bytes)
    return subprocess.run(
        [OPENSSL, "pkeyutl", "-sign", "-rawin", "-inkey", private, "-in", payload],
        capture_output=True,
        check=True,
    ).stdout
