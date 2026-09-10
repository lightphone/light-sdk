from __future__ import annotations

import json
import warnings
import zipfile
from pathlib import Path

import pytest
from lightsigner.apk import TRUST_STATEMENT_PATH, ApkMetadata, read_trust_statement
from lightsigner.errors import SignerError
from lightsigner.verify import verify_apk

def _apk(tmp_path, trust_statement=None, copies=1):
    apk = tmp_path / "tool.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("classes.dex", b"dex")
        for _ in range(copies):
            if trust_statement is not None:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    archive.writestr(TRUST_STATEMENT_PATH, json.dumps(trust_statement))
    return apk


@pytest.mark.parametrize(("trust_statement", "copies"), [(None, 1), ({}, 2)])
def test_read_trust_statement_requires_exactly_one(tmp_path, trust_statement, copies) -> None:
    with pytest.raises(SignerError) as failure:
        read_trust_statement(_apk(tmp_path, trust_statement, copies))
    assert failure.value.code == "invalid_statement_count"


def test_verify_rejects_untrusted_source_stamp(tmp_path, monkeypatch) -> None:
    apk = _apk(tmp_path, {})
    monkeypatch.setattr("lightsigner.verify.verify_apk_signature", lambda *_: "c" * 64)
    monkeypatch.setattr("lightsigner.verify.verify_source_stamp", lambda *_: "a" * 64)
    with pytest.raises(SignerError) as failure:
        verify_apk(apk=apk, stamp_cert_sha256="b" * 64,
                   apksigner=tmp_path / "unused", apkanalyzer=tmp_path / "unused")
    assert failure.value.code == "source_stamp_mismatch"


def test_verify_rejects_trust_statement_transplanted_to_another_apk(tmp_path, monkeypatch) -> None:
    trust_statement = {
        "tool": {"id": "com.example.tool", "versionCode": 3, "versionName": "1.2.0"},
        "signerSha256": "c" * 64,
    }
    apk = _apk(tmp_path, trust_statement)
    monkeypatch.setattr("lightsigner.verify.verify_apk_signature", lambda *_: "c" * 64)
    monkeypatch.setattr("lightsigner.verify.verify_source_stamp", lambda *_: "a" * 64)
    monkeypatch.setattr("lightsigner.verify.inspect_apk", lambda *_: ApkMetadata("com.other.tool", 3, "1.2.0"))
    with pytest.raises(SignerError) as failure:
        verify_apk(apk=apk, stamp_cert_sha256="a" * 64,
                   apksigner=tmp_path / "unused", apkanalyzer=tmp_path / "unused")
    assert failure.value.code == "statement_apk_mismatch"
