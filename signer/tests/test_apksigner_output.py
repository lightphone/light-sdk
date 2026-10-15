from pathlib import Path

import pytest

from lightsigner.apk import verify_apk_signature, verify_source_stamp
from lightsigner.errors import SignerError


@pytest.mark.parametrize("prefix", ["Signer #1", "Signer #1:", "V3 Signer:", "V3.1 Signer:"])
def test_apk_signer_certificate_formats(monkeypatch, prefix):
    # Build Tools 36 emits "Signer #1 certificate ..." without a colon.
    output = (f"{prefix} certificate SHA-256 digest: {'AB:' * 31}AB\n"
              f"Source Stamp Signer certificate SHA-256 digest: {'c' * 64}\n")
    monkeypatch.setattr("lightsigner.apk.run_tool", lambda *_: output)
    assert verify_apk_signature(Path("tool.apk"), Path("apksigner")) == "ab" * 32


@pytest.mark.parametrize("prefix", ["Source Stamp Signer", "Source Stamp Signer:"])
def test_source_stamp_certificate_formats(monkeypatch, prefix):
    output = (f"Signer #1 certificate SHA-256 digest: {'b' * 64}\n"
              f"{prefix} certificate SHA-256 digest: {'a' * 64}\n")
    monkeypatch.setattr("lightsigner.apk.run_tool", lambda *_: output)
    assert verify_source_stamp(Path("tool.apk"), Path("apksigner")) == "a" * 64


@pytest.mark.parametrize("verify,prefix,code", [
    (verify_apk_signature, "Signer #1", "invalid_apk_signers"),
    (verify_source_stamp, "Source Stamp Signer", "invalid_source_stamp"),
])
@pytest.mark.parametrize("digests", [[], ["a" * 64, "b" * 64]])
def test_missing_or_multiple_certificates_fail(monkeypatch, verify, prefix, code, digests):
    output = "\n".join(f"{prefix} certificate SHA-256 digest: {digest}" for digest in digests)
    monkeypatch.setattr("lightsigner.apk.run_tool", lambda *_: output)
    with pytest.raises(SignerError) as failure:
        verify(Path("tool.apk"), Path("apksigner"))
    assert failure.value.code == code
