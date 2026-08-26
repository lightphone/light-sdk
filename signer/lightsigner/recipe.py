from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

from .errors import SignerError
from .registry import _load_json_object, validate_tool_id


@dataclass(frozen=True)
class ToolRecipe:
    id: str
    version_code: int
    version_name: str
    git_url: str
    git_commit: str


@dataclass(frozen=True)
class Recipe:
    tool: ToolRecipe
    sdk_git_ref: str
    unsigned_sha256: str


def load_recipe(path: Path) -> Recipe:
    document = _load_json_object(path, "recipe")
    tool = document.get("tool")
    artifact = document.get("artifact")
    sdk_git_ref = document.get("sdkGitRef")
    if not isinstance(tool, dict) or not isinstance(artifact, dict) or not isinstance(sdk_git_ref, str):
        raise SignerError("invalid_recipe", "recipe requires tool, artifact, and sdkGitRef")
    values = (tool.get("id"), tool.get("versionCode"), tool.get("versionName"), tool.get("gitUrl"), tool.get("gitCommit"))
    if not isinstance(values[0], str) or not isinstance(values[1], int) or isinstance(values[1], bool):
        raise SignerError("invalid_recipe", "recipe tool id/versionCode are invalid")
    if not all(isinstance(value, str) for value in values[2:]):
        raise SignerError("invalid_recipe", "recipe tool strings are invalid")
    digest = artifact.get("sha256")
    if not isinstance(digest, str) or len(digest) != 64:
        raise SignerError("invalid_recipe", "recipe artifact.sha256 is invalid")
    validate_tool_id(values[0])
    return Recipe(ToolRecipe(*values), sdk_git_ref, digest.lower())


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1 << 20), b""):
                digest.update(chunk)
    except OSError as error:
        raise SignerError("artifact_unreadable", f"cannot read artifact: {path}") from error
    return digest.hexdigest()
