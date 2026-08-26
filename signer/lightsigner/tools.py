from __future__ import annotations

import os
import re
import shutil
import subprocess
from pathlib import Path

from .errors import SignerError


def resolve_tool(name: str, explicit: Path | None = None) -> Path:
    if explicit is not None:
        if explicit.is_file():
            return explicit.resolve()
        raise SignerError("tool_not_found", f"{name} not found at {explicit}")

    found = shutil.which(name)
    if found:
        return Path(found).resolve()

    if name in {"apksigner", "apkanalyzer"}:
        for sdk_env in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
            sdk_value = os.environ.get(sdk_env)
            if not sdk_value:
                continue
            candidate = _find_android_tool(Path(sdk_value), name)
            if candidate is not None:
                return candidate

    raise SignerError("tool_not_found", f"{name} was not found")


def run_tool(command: list[str], *, env: dict[str, str] | None = None) -> str:
    result = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise SignerError("external_tool_failed", f"{Path(command[0]).name}: {detail}")
    return result.stdout


def _find_android_tool(sdk: Path, name: str) -> Path | None:
    if name == "apkanalyzer":
        candidates = list((sdk / "cmdline-tools").glob("*/bin/apkanalyzer"))
    else:
        candidates = list((sdk / "build-tools").glob(f"*/{name}"))
    existing = [candidate for candidate in candidates if candidate.is_file()]
    return max(existing, key=_android_version_key, default=None)


def _android_version_key(path: Path) -> tuple[int, ...]:
    version = path.parent.parent.name if path.parent.name == "bin" else path.parent.name
    if version == "latest":
        return (1_000_000,)
    numbers = re.findall(r"\d+", version)
    return tuple(int(number) for number in numbers) if numbers else (0,)
