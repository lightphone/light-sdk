from __future__ import annotations

import hashlib
import json
import zipfile

import pytest

from lightsigner.apk import ApkMetadata
from lightsigner.errors import SignerError
from lightsigner.stamp import STATEMENT_PATH, read_statement, stamp_apk


def _inputs(tmp_path):
    apk = tmp_path / "unsigned.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("classes.dex", b"dex", compress_type=zipfile.ZIP_STORED)
        archive.writestr("assets/data", b"payload", compress_type=zipfile.ZIP_DEFLATED)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    recipe = tmp_path / "recipe.json"
    recipe.write_text(json.dumps({
        "schemaVersion": 1,
        "artifact": {"sha256": digest},
        "tool": {"id": "com.example.tool", "versionCode": 3, "versionName": "1.2", "gitUrl": "https://example.test/tool", "gitCommit": "a" * 40},
        "sdkGitRef": "v1.0",
    }))
    registry = tmp_path / "registry.json"
    registry.write_text(json.dumps({"com.example.tool": {"devId": "dev_1", "keyId": "com.example.tool"}}))
    keys = tmp_path / "keys" / "com.example.tool"
    keys.mkdir(parents=True)
    (keys / "certificate.der").write_bytes(b"certificate")
    return apk, recipe, registry


def test_stamp_preserves_entries_and_attests(tmp_path, monkeypatch) -> None:
    apk, recipe, registry = _inputs(tmp_path)
    output = tmp_path / "stamped.apk"
    monkeypatch.setattr("lightsigner.stamp.inspect_apk", lambda *_: ApkMetadata("com.example.tool", 3, "1.2"))
    monkeypatch.setattr("lightsigner.stamp.sign_ed25519", lambda *_: b"signature")
    stamp_apk(apk=apk, recipe_path=recipe, registry_path=registry, dev_id="dev_1", build_id="build_1",
              attestation_key=tmp_path / "unused", keys_dir=tmp_path / "keys", output=output,
              apkanalyzer=tmp_path / "unused", issued_at="2026-08-25T00:00:00Z")
    with zipfile.ZipFile(apk) as before, zipfile.ZipFile(output) as after:
        for name in ("classes.dex", "assets/data"):
            assert after.read(name) == before.read(name)
            assert after.getinfo(name).compress_type == before.getinfo(name).compress_type
        assert [info.filename for info in after.infolist()].count(STATEMENT_PATH) == 1
    assert read_statement(output)["unsignedSha256"] == hashlib.sha256(apk.read_bytes()).hexdigest()


def test_stamp_checks_apk_metadata_before_registry(tmp_path, monkeypatch) -> None:
    apk, recipe, registry = _inputs(tmp_path)
    registry.write_text("{}")
    monkeypatch.setattr("lightsigner.stamp.inspect_apk", lambda *_: ApkMetadata("com.other.tool", 3, "1.2"))
    with pytest.raises(SignerError) as failure:
        stamp_apk(apk=apk, recipe_path=recipe, registry_path=registry, dev_id="dev_1", build_id="build_1",
                  attestation_key=tmp_path / "unused", keys_dir=tmp_path / "keys", output=tmp_path / "out.apk",
                  apkanalyzer=tmp_path / "unused")
    assert failure.value.code == "apk_metadata_mismatch"
