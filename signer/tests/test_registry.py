from __future__ import annotations

import json

import pytest

from lightsigner.errors import SignerError
from lightsigner.registry import load_registry, require_owner


def test_registry_owner(tmp_path) -> None:
    path = tmp_path / "registry.json"
    path.write_text(json.dumps({"com.example.tool": {"devId": "dev_1", "keyId": "com.example.tool"}}))
    entry = require_owner(load_registry(path), "com.example.tool", "dev_1")
    assert entry.key_id == "com.example.tool"


@pytest.mark.parametrize(
    ("tool_id", "dev_id", "code"),
    [("com.missing.tool", "dev_1", "unregistered_tool"), ("com.example.tool", "dev_2", "wrong_developer")],
)
def test_registry_rejects_invalid_owner(tmp_path, tool_id, dev_id, code) -> None:
    path = tmp_path / "registry.json"
    path.write_text(json.dumps({"com.example.tool": {"devId": "dev_1", "keyId": "com.example.tool"}}))
    with pytest.raises(SignerError) as failure:
        require_owner(load_registry(path), tool_id, dev_id)
    assert failure.value.code == code
