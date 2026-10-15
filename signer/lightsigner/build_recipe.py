from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

from .errors import SignerError
from .registry import load_json_object, validate_tool_id


@dataclass(frozen=True)
class ToolBuildRecipe:
    id: str
    version_code: int
    version_name: str
    git_url: str
    git_commit: str


@dataclass(frozen=True)
class BuildRecipe:
    tool: ToolBuildRecipe
    sdk_git_ref: str
    unsigned_sha256: str


def load_build_recipe(path: Path) -> BuildRecipe:
    document = load_json_object(path, "build_recipe")
    tool = document.get("tool")
    artifact = document.get("artifact")
    sdk_git_ref = document.get("sdkGitRef")
    if not isinstance(tool, dict) or not isinstance(artifact, dict) or not isinstance(sdk_git_ref, str):
        raise SignerError("invalid_recipe", "recipe requires tool, artifact, and sdkGitRef")
    tool_id = tool.get("id")
    version_code = tool.get("versionCode")
    version_name = tool.get("versionName")
    git_url = tool.get("gitUrl")
    git_commit = tool.get("gitCommit")
    if not isinstance(tool_id, str) or not isinstance(version_code, int) or isinstance(version_code, bool):
        raise SignerError("invalid_recipe", "recipe tool id/versionCode are invalid")
    if not isinstance(version_name, str) or not isinstance(git_url, str) or not isinstance(git_commit, str):
        raise SignerError("invalid_recipe", "recipe tool strings are invalid")
    digest = artifact.get("sha256")
    if not isinstance(digest, str) or len(digest) != 64:
        raise SignerError("invalid_recipe", "recipe artifact.sha256 is invalid")
    validate_tool_id(tool_id)
    return BuildRecipe(
        ToolBuildRecipe(tool_id, version_code, version_name, git_url, git_commit),
        sdk_git_ref,
        digest.lower(),
    )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1 << 20), b""):
                digest.update(chunk)
    except OSError as error:
        raise SignerError("artifact_unreadable", f"cannot read artifact: {path}") from error
    return digest.hexdigest()
