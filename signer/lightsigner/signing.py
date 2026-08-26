from __future__ import annotations

import json
from pathlib import Path

from .apk import read_trust_statement, verify_apk_signature
from .build_recipe import load_build_recipe, sha256
from .errors import SignerError
from .keys import KEY_ALIAS, PASSWORD_ENV, keystore_path, require_password
from .registry import load_registry
from .tools import run_tool


# sign apk using android sdk apksigner
def sign_apk(*, apk: Path, build_recipe_path: Path, registry_path: Path, build_id: str,
             keys_dir: Path, output: Path, metadata_output: Path, apksigner: Path) -> dict[str, object]:
    if apk.resolve() == output.resolve():
        raise SignerError("output_overwrites_input", "output must differ from input")
    require_password()

    build_recipe = load_build_recipe(build_recipe_path)
    entry = load_registry(registry_path).get(build_recipe.tool.id)
    if entry is None:
        raise SignerError("unregistered_tool", f"tool is not registered: {build_recipe.tool.id}")
    output.parent.mkdir(parents=True, exist_ok=True)

    run_tool([
        str(apksigner), "sign", "--ks", str(keystore_path(keys_dir, entry.key_id)),
        "--ks-key-alias", KEY_ALIAS, "--ks-pass", f"env:{PASSWORD_ENV}",
        "--key-pass", f"env:{PASSWORD_ENV}", "--out", str(output), str(apk),
    ])

    signer_hash = verify_apk_signature(output, apksigner)
    if read_trust_statement(output).get("signerSha256") != signer_hash:
        output.unlink(missing_ok=True)
        raise SignerError("signer_mismatch", "trust statement signer does not match APK signing key")

    metadata: dict[str, object] = {
        "buildId": build_id,
        "unsignedSha256": build_recipe.unsigned_sha256,
        "apkSha256": sha256(output),
        "signerSha256": signer_hash,
    }
    metadata_output.parent.mkdir(parents=True, exist_ok=True)
    metadata_output.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    return metadata
