"""Emit a ``recipe.json`` capturing every input that fed into the unsigned APK.

The recipe is the authoritative description of what was built. Pair it with
the SHA-256 of the unsigned APK to verify a build.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Tool:
    id: str
    version_code: int
    version_name: str
    git_url: str
    git_commit: str


@dataclass(frozen=True)
class Build:
    image_digest: str
    tool_git_ref: str
    gradle_command: tuple[str, ...]
    source_date_epoch: int
    extracted_files: tuple[str, ...] = field(default_factory=tuple)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write(
    *,
    artifact: Path,
    tool: Tool,
    sdk_git_ref: str,
    build: Build,
    dest: Path,
) -> dict[str, Any]:
    artifact_hash = sha256(artifact)
    record: dict[str, Any] = {
        "schemaVersion": 1,
        "artifact": {
            "filename": artifact.name,
            "sizeBytes": artifact.stat().st_size,
            "sha256": artifact_hash,
        },
        "tool": {
            "id": tool.id,
            "versionCode": tool.version_code,
            "versionName": tool.version_name,
            "gitUrl": tool.git_url,
            "gitCommit": tool.git_commit,
        },
        "sdkGitRef": sdk_git_ref,
        "build": {
            "imageDigest": build.image_digest,
            "toolGitRef": build.tool_git_ref,
            "gradleCommand": list(build.gradle_command),
            "sourceDateEpoch": build.source_date_epoch,
            "extractedFiles": list(build.extracted_files),
        },
    }
    # sort_keys for deterministic JSON output — the recipe itself should be
    # byte-stable when the inputs are.
    dest.write_text(
        json.dumps(record, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return record
