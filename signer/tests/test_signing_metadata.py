from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

from lightsigner.build_recipe import sha256
from lightsigner.registry import RegistryEntry
from lightsigner.signing import sign_apk


def test_signing_metadata_contract(tmp_path: Path, monkeypatch) -> None:
    from lightsigner import signing

    tool_id = "com.example.tool"
    certificate = "a" * 64
    unsigned_hash = "b" * 64
    output = tmp_path / "signed.apk"
    metadata_output = tmp_path / "signed.json"

    monkeypatch.setattr(signing, "require_password", lambda *_: "password")
    monkeypatch.setattr(
        signing,
        "load_build_recipe",
        lambda *_: SimpleNamespace(tool=SimpleNamespace(id=tool_id), unsigned_sha256=unsigned_hash),
    )
    monkeypatch.setattr(
        signing, "load_registry", lambda *_: {tool_id: RegistryEntry("dev_1", tool_id)}
    )
    monkeypatch.setattr(signing, "keystore_path", lambda *_: tmp_path / "signing.p12")
    monkeypatch.setattr(signing, "run_tool", lambda *_: output.write_bytes(b"signed APK"))
    monkeypatch.setattr(signing, "verify_apk_signature", lambda *_: certificate)
    monkeypatch.setattr(signing, "read_trust_statement", lambda *_: {"signerSha256": certificate})

    result = sign_apk(
        apk=tmp_path / "unsigned.apk",
        build_recipe_path=tmp_path / "recipe.json",
        registry_path=tmp_path / "registry.json",
        build_id="build_1",
        keys_dir=tmp_path / "keys",
        stamp_keystore=tmp_path / "stamp.p12",
        stamp_key_alias="light-stamp",
        output=output,
        metadata_output=metadata_output,
        apksigner=tmp_path / "apksigner",
    )

    assert result == json.loads(metadata_output.read_text()) == {
        "buildId": "build_1",
        "unsignedSha256": unsigned_hash,
        "apkSha256": sha256(output),
        "signerSha256": certificate,
    }
