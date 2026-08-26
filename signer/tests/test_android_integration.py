from __future__ import annotations

import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest
from lightsigner.build_recipe import sha256
from lightsigner.errors import SignerError
from lightsigner.keys import generate_key
from lightsigner.signing import sign_apk
from lightsigner.stamp import stamp_apk
from lightsigner.tools import resolve_tool, run_tool
from lightsigner.verify import verify_apk


# Runs the complete workflow using real JDK and Android tools.
## In the future this can be extended to do the full build -> stamp -> sign -> verify flow
@pytest.mark.skipif(os.environ.get("LIGHT_SIGNER_ANDROID_INTEGRATION") != "1", reason="opt-in Android integration")
def test_real_android_signing_round_trip(tmp_path, monkeypatch) -> None:
    repository = Path(__file__).resolve().parents[2]
    unsigned = repository / "tool/build/outputs/apk/release/tool-release-unsigned.apk"
    if not unsigned.is_file():
        pytest.skip("build :tool:assembleRelease -DlightSdk.unsigned=true first")

    # Load Android and OpenSSL tooling
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Library/Android/sdk"))
    monkeypatch.setenv("ANDROID_SDK_ROOT", str(sdk))
    monkeypatch.setenv("LIGHT_SIGNER_KEY_PASSWORD", "integration-test-only")
    keytool_name = shutil.which("keytool")
    openssl = shutil.which("openssl")
    if not keytool_name or not openssl:
        pytest.skip("keytool and openssl required")
    keytool = Path(keytool_name)
    apksigner = resolve_tool("apksigner")
    apkanalyzer = resolve_tool("apkanalyzer")
    assert run_tool([str(apkanalyzer), "manifest", "target-sdk", str(unsigned)]).strip() == "34"

    # Generate attestation key
    private_key = tmp_path / "attestation-private.pem"
    public_key = tmp_path / "attestation-public.pem"
    subprocess.run([openssl, "genpkey", "-algorithm", "Ed25519", "-out", private_key], check=True)
    subprocess.run([openssl, "pkey", "-in", private_key, "-pubout", "-out", public_key], check=True)

    # Generate per-tool signing key
    tool_id = "com.thelightphone.app"
    keys_dir = tmp_path / "keys"
    generate_key(tool_id, keys_dir, keytool)
    with pytest.raises(SignerError) as duplicate_key:
        generate_key(tool_id, keys_dir, keytool)
    assert duplicate_key.value.code == "key_exists"

    # Stamp the apk with a trust statement
    ## we'll need to mock the build recipe
    build_recipe = tmp_path / "recipe.json"
    build_recipe.write_text(json.dumps({
        "schemaVersion": 1,
        "artifact": {"sha256": sha256(unsigned)},
        "tool": {"id": tool_id, "versionCode": 1, "versionName": "1.0.0", "gitUrl": "https://example.test/tool", "gitCommit": "a" * 40},
        "sdkGitRef": "integration",
    }))
    ## and mock the key registry entry
    registry = tmp_path / "registry.json"
    registry.write_text(json.dumps({tool_id: {"devId": "dev_integration", "keyId": tool_id}}))
    stamped = tmp_path / "stamped.apk"
    signed = tmp_path / "signed.apk"
    signed_metadata = tmp_path / "signed.json"
    stamp_apk(apk=unsigned, build_recipe_path=build_recipe, registry_path=registry, dev_id="dev_integration",
              build_id="build_integration", attestation_key=private_key, keys_dir=keys_dir,
              output=stamped, apkanalyzer=apkanalyzer)

    # Sign apk after stamped
    result = sign_apk(apk=stamped, build_recipe_path=build_recipe, registry_path=registry,
                      build_id="build_integration", keys_dir=keys_dir, output=signed,
                      metadata_output=signed_metadata, apksigner=apksigner)

    # Verify stamped and signed apk
    trust_statement = verify_apk(apk=signed, attestation_public_key=public_key,
                           apksigner=apksigner, apkanalyzer=apkanalyzer)
    assert trust_statement["signerSha256"] == result["signerSha256"]
    assert result["apkSha256"] == sha256(signed)

    foreign_private = tmp_path / "foreign-private.pem"
    foreign_public = tmp_path / "foreign-public.pem"
    subprocess.run([openssl, "genpkey", "-algorithm", "Ed25519", "-out", foreign_private], check=True)
    subprocess.run([openssl, "pkey", "-in", foreign_private, "-pubout", "-out", foreign_public], check=True)
    with pytest.raises(SignerError) as foreign_key:
        verify_apk(apk=signed, attestation_public_key=foreign_public,
                   apksigner=apksigner, apkanalyzer=apkanalyzer)
    assert foreign_key.value.code == "attestation_failed"
