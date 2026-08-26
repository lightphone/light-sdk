from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .errors import SignerError


TOOL_ID_PATTERN = re.compile(r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$")


@dataclass(frozen=True)
class RegistryEntry:
    dev_id: str
    key_id: str


def validate_tool_id(tool_id: str) -> str:
    if not TOOL_ID_PATTERN.fullmatch(tool_id):
        raise SignerError("invalid_tool_id", f"invalid tool id: {tool_id}")
    return tool_id


def load_registry(path: Path) -> dict[str, RegistryEntry]:
    raw = _load_json_object(path, "registry")
    registry: dict[str, RegistryEntry] = {}
    for tool_id, value in raw.items():
        validate_tool_id(tool_id)
        if not isinstance(value, dict):
            raise SignerError("invalid_registry", f"registry entry for {tool_id} must be an object")
        if set(value) != {"devId", "keyId"}:
            raise SignerError("invalid_registry", f"registry entry for {tool_id} has invalid fields")
        dev_id = value["devId"]
        key_id = value["keyId"]
        if not isinstance(dev_id, str) or not dev_id:
            raise SignerError("invalid_registry", f"registry devId for {tool_id} must be a string")
        if not isinstance(key_id, str) or not TOOL_ID_PATTERN.fullmatch(key_id):
            raise SignerError("invalid_registry", f"registry keyId for {tool_id} is invalid")
        registry[tool_id] = RegistryEntry(dev_id=dev_id, key_id=key_id)
    return registry


def require_owner(
    registry: dict[str, RegistryEntry], tool_id: str, requesting_dev_id: str
) -> RegistryEntry:
    entry = registry.get(tool_id)
    if entry is None:
        raise SignerError("unregistered_tool", f"tool is not registered: {tool_id}")
    if entry.dev_id != requesting_dev_id:
        raise SignerError("wrong_developer", f"tool {tool_id} is registered to another developer")
    return entry


def _load_json_object(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SignerError(f"invalid_{label}", f"cannot read {label}: {error}") from error
    if not isinstance(value, dict):
        raise SignerError(f"invalid_{label}", f"{label} must be a JSON object")
    return value


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise SignerError("duplicate_json_key", f"duplicate JSON key: {key}")
        result[key] = value
    return result
