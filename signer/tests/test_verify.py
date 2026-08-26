from __future__ import annotations

import json
import warnings
import zipfile
from pathlib import Path

import pytest
from lightsigner.apk import TRUST_STATEMENT_PATH, ApkMetadata, read_trust_statement
from lightsigner.errors import SignerError
from lightsigner.verify import verify_apk

VECTORS = Path(__file__).parent / "vectors"


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


def test_verify_rejects_edited_trust_statement(tmp_path, monkeypatch) -> None:
    trust_statement = json.loads((VECTORS / "statement.json").read_text())
    trust_statement["buildId"] = "edited"
    apk = _apk(tmp_path, trust_statement)
    monkeypatch.setattr("lightsigner.verify.verify_apk_signature", lambda *_: "c" * 64)
    monkeypatch.setattr("lightsigner.verify.inspect_apk", lambda *_: ApkMetadata("com.example.tool", 3, "1.2.0"))
    with pytest.raises(SignerError) as failure:
        verify_apk(apk=apk, attestation_public_key=VECTORS / "test-attestation-public.pem",
                   apksigner=tmp_path / "unused", apkanalyzer=tmp_path / "unused",
                   expected_key_id="test-attestation-1")
    assert failure.value.code == "attestation_failed"


def test_verify_rejects_trust_statement_transplanted_to_another_apk(tmp_path, monkeypatch) -> None:
    trust_statement = json.loads((VECTORS / "statement.json").read_text())
    apk = _apk(tmp_path, trust_statement)
    monkeypatch.setattr("lightsigner.verify.verify_apk_signature", lambda *_: "c" * 64)
    monkeypatch.setattr("lightsigner.verify.inspect_apk", lambda *_: ApkMetadata("com.other.tool", 3, "1.2.0"))
    with pytest.raises(SignerError) as failure:
        verify_apk(apk=apk, attestation_public_key=VECTORS / "test-attestation-public.pem",
                   apksigner=tmp_path / "unused", apkanalyzer=tmp_path / "unused",
                   expected_key_id="test-attestation-1")
    assert failure.value.code == "statement_apk_mismatch"
