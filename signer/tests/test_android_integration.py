from __future__ import annotations

import json
import os
import shutil
import zipfile
from pathlib import Path

import pytest
from lightsigner.build_recipe import sha256
from lightsigner.errors import SignerError
from lightsigner.keys import generate_key, generate_stamp_key
from lightsigner.signing import sign_apk
from lightsigner.tools import resolve_tool, run_tool
from lightsigner.trust_statement import inject_trust_statement
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
    if not keytool_name:
        pytest.skip("keytool required")
    keytool = Path(keytool_name)
    apksigner = resolve_tool("apksigner")
    apkanalyzer = resolve_tool("apkanalyzer")
    assert run_tool([str(apkanalyzer), "manifest", "target-sdk", str(unsigned)]).strip() == "34"

    # Generate per-tool signing key
    tool_id = "com.thelightphone.app"
    keys_dir = tmp_path / "keys"
    generate_key(tool_id, keys_dir, keytool)
    with pytest.raises(SignerError) as duplicate_key:
        generate_key(tool_id, keys_dir, keytool)
    assert duplicate_key.value.code == "key_exists"
    monkeypatch.setenv("LIGHT_SIGNER_STAMP_KEY_PASSWORD", "integration-test-only")
    stamp_keys_dir = tmp_path / "stamp-keys"
    stamp_hash = generate_stamp_key(stamp_keys_dir, keytool)

    # Inject the trust statement into the APK.
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
    statement_apk = tmp_path / "with-statement.apk"
    signed = tmp_path / "signed.apk"
    signed_metadata = tmp_path / "signed.json"
    inject_trust_statement(
        apk=unsigned, build_recipe_path=build_recipe, registry_path=registry,
        dev_id="dev_integration", build_id="build_integration", keys_dir=keys_dir,
        output=statement_apk, apkanalyzer=apkanalyzer,
    )

    # Sign and stamp APK with trust statement inside
    result = sign_apk(apk=statement_apk, build_recipe_path=build_recipe, registry_path=registry,
                      build_id="build_integration", keys_dir=keys_dir, output=signed,
                      stamp_keystore=stamp_keys_dir / "signing.p12",
                      stamp_key_alias="light-stamp",
                      metadata_output=signed_metadata, apksigner=apksigner)

    # Verify source-stamped and signed APK.
    trust_statement = verify_apk(apk=signed, stamp_cert_sha256=stamp_hash,
                           apksigner=apksigner, apkanalyzer=apkanalyzer)
    assert trust_statement["signerSha256"] == result["signerSha256"]
    assert result["apkSha256"] == sha256(signed)

    with pytest.raises(SignerError) as foreign_key:
        verify_apk(apk=signed, stamp_cert_sha256="0" * 64,
                   apksigner=apksigner, apkanalyzer=apkanalyzer)
    assert foreign_key.value.code == "source_stamp_mismatch"

    tampered = tmp_path / "tampered.apk"
    shutil.copyfile(signed, tampered)
    with zipfile.ZipFile(tampered, "a") as archive:
        archive.writestr("tampered", b"tampered")
    with pytest.raises(SignerError) as tampered_apk:
        verify_apk(apk=tampered, stamp_cert_sha256=stamp_hash,
                   apksigner=apksigner, apkanalyzer=apkanalyzer)
    assert tampered_apk.value.code == "external_tool_failed"
