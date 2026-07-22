"""Whitelist extraction from the dev's checked-out repo.

The extractor copies a strict subset of the dev's repo into a clean,
baked-in copy of the SDK. Everything else — including the dev's settings.gradle,
top-level build files, gradle wrapper, and any modules other than tool/ — is
discarded.

What we carry over:
  * tool/src/main/{kotlin,java,res,assets}/**  (extension-allowlisted)
  * tool/build.gradle.kts                       (static-scanned before gradle runs)
  * tool/lighttool.toml                          (parsed by the SDK's plugin)

Anything outside that, anything symlinked, anything inside a forbidden path
component (META-INF, .git, …), or any file whose extension is not on the
per-tree allowlist is refused. Validation of *contents* (banned imports,
manifest correctness, applicationId format) is handled by the SDK's Gradle
plugin; this module is concerned only with file-system trust.
"""

from __future__ import annotations

import os
import shutil
import stat as stat_mod
from dataclasses import dataclass, field
from pathlib import Path

from .allowlist import (
    ALLOWED_TOOL_ROOT_FILES,
    BUILD_SCRIPT_FORBIDDEN_PATTERNS,
    EXTRACTION_TREES,
    FORBIDDEN_PATH_COMPONENTS,
    MAX_FILE_COUNT,
    MAX_FILE_SIZE_BYTES,
    MAX_TOTAL_EXTRACTED_BYTES,
)


class ExtractionError(Exception):
    """Raised when the dev's repo violates the extraction policy."""


@dataclass
class ExtractionReport:
    files_copied: list[str] = field(default_factory=list)
    bytes_copied: int = 0
    # Absolute paths of the copies inside the workspace tool module, in the
    # order they were written. Used to build the extracted-source zip; not
    # serialized into extraction.json/recipe.json.
    dest_paths: list[Path] = field(default_factory=list)

    def record(self, rel_path: str, size: int, dest: Path) -> None:
        self.files_copied.append(rel_path)
        self.bytes_copied += size
        self.dest_paths.append(dest)


def extract(
    dev_repo: Path,
    dest_tool: Path,
    *,
    tool_subpath: str = "tool",
) -> ExtractionReport:
    """Copy allowlisted files from ``dev_repo/<tool_subpath>/...`` into ``dest_tool``.

    The dest tool/ is the baked-in SDK's tool/ module; we overwrite per-build.
    ``tool_subpath`` defaults to ``tool`` to match the SDK's own layout, but
    devs who don't want to mirror the SDK structure can point to any
    relative path inside their repo (including ``.`` for the repo root).

    Returns a report listing every file that made it across. Raises
    ``ExtractionError`` on any policy violation.
    """
    dev_repo = dev_repo.resolve(strict=True)

    if not tool_subpath:
        raise ExtractionError("tool path is empty")
    if Path(tool_subpath).is_absolute():
        raise ExtractionError(f"tool path must be relative to the dev repo: {tool_subpath}")
    if any(part == ".." for part in Path(tool_subpath).parts):
        raise ExtractionError(f"tool path may not contain '..': {tool_subpath}")

    src_tool = (dev_repo / tool_subpath).resolve()
    # Resolving with symlinks must still land inside the dev repo. Catches
    # a symlinked tool/ that points outside the cloned tree.
    if not _is_inside(src_tool, dev_repo):
        raise ExtractionError(f"tool path resolves outside the dev repo: {tool_subpath}")
    if not src_tool.is_dir():
        raise ExtractionError(f"tool path does not exist or is not a directory: {tool_subpath}")

    src_main = src_tool / "src" / "main"
    if not src_main.is_dir():
        raise ExtractionError(f"missing src/main under {tool_subpath}/")

    report = ExtractionReport()

    _copy_tool_root_files(src_tool=src_tool, dest_tool=dest_tool, report=report)

    for subdir, allowed_exts in EXTRACTION_TREES.items():
        src_tree = src_main / subdir
        if not src_tree.exists():
            continue
        dst_tree = dest_tool / "src" / "main" / subdir
        _wipe(dst_tree)
        _walk_and_copy(
            src_tree=src_tree,
            src_root=src_main,
            dst_tree=dst_tree,
            allowed_exts=allowed_exts,
            report=report,
        )

    return report


def _copy_tool_root_files(
    *,
    src_tool: Path,
    dest_tool: Path,
    report: ExtractionReport,
) -> None:
    # Allowlist by exact filename. Do not descend; only direct children of
    # tool/ are considered. Mandatory files are checked here too.
    seen: set[str] = set()
    for entry in src_tool.iterdir():
        if entry.is_dir():
            continue
        if entry.name not in ALLOWED_TOOL_ROOT_FILES:
            # Quietly ignore stray files (.gitignore, .DS_Store, etc.)
            continue
        _validate_regular_file(entry, src_tool)
        size = entry.stat().st_size
        if size > MAX_FILE_SIZE_BYTES:
            raise ExtractionError(f"{entry.name} exceeds {MAX_FILE_SIZE_BYTES} bytes")
        if entry.name == "build.gradle.kts":
            _scan_build_script(entry)
        dest = dest_tool / entry.name
        dest.parent.mkdir(parents=True, exist_ok=True)
        # Overwrite atomically by writing alongside and renaming. We don't
        # use shutil.copyfile directly because some filesystems would
        # preserve the dest's existing mtime, leaking determinism state.
        shutil.copyfile(entry, dest)
        os.chmod(dest, 0o644)
        report.record(entry.name, size, dest)
        seen.add(entry.name)

    for required in ("build.gradle.kts", "lighttool.toml"):
        if required not in seen:
            raise ExtractionError(f"tool/{required} is required")


def _scan_build_script(path: Path) -> None:
    """Refuse probably-malicious build scripts before gradle parses them.
    """
    content = path.read_text(encoding="utf-8", errors="strict")
    stripped = _strip_kotlin_comments(content)
    for pattern, message in BUILD_SCRIPT_FORBIDDEN_PATTERNS:
        if pattern.search(stripped):
            raise ExtractionError(f"tool/build.gradle.kts: {message}")


def _strip_kotlin_comments(text: str) -> str:
    import re as _re
    no_block = _re.sub(r"/\*.*?\*/", "", text, flags=_re.DOTALL)
    return _re.sub(r"//.*", "", no_block)


def _walk_and_copy(
    *,
    src_tree: Path,
    src_root: Path,
    dst_tree: Path,
    allowed_exts: frozenset[str],
    report: ExtractionReport,
) -> None:
    for dirpath, dirnames, filenames in os.walk(src_tree, followlinks=False):
        dirnames[:] = [d for d in dirnames if d not in FORBIDDEN_PATH_COMPONENTS]

        for d in dirnames:
            entry = Path(dirpath) / d
            if entry.is_symlink():
                raise ExtractionError(f"symlinked directory not allowed: {entry.relative_to(src_root)}")

        for fname in filenames:
            if fname in FORBIDDEN_PATH_COMPONENTS:
                raise ExtractionError(f"forbidden filename: {fname}")
            entry = Path(dirpath) / fname
            _copy_one(
                entry=entry,
                src_tree=src_tree,
                src_root=src_root,
                dst_tree=dst_tree,
                allowed_exts=allowed_exts,
                report=report,
            )


def _copy_one(
    *,
    entry: Path,
    src_tree: Path,
    src_root: Path,
    dst_tree: Path,
    allowed_exts: frozenset[str],
    report: ExtractionReport,
) -> None:
    _validate_regular_file(entry, src_root)
    rel = entry.relative_to(src_tree)
    for part in rel.parts:
        if part in FORBIDDEN_PATH_COMPONENTS:
            raise ExtractionError(f"forbidden path component: {rel}")
        if part.startswith(".."):
            raise ExtractionError(f"parent traversal in path: {rel}")

    ext = entry.suffix.lower()
    if ext not in allowed_exts:
        raise ExtractionError(
            f"extension not allowed in {src_tree.name}/: {rel} (got {ext or '<none>'})"
        )

    size = entry.lstat().st_size
    if size > MAX_FILE_SIZE_BYTES:
        raise ExtractionError(
            f"file exceeds {MAX_FILE_SIZE_BYTES} bytes: {rel} ({size} bytes)"
        )

    if len(report.files_copied) >= MAX_FILE_COUNT:
        raise ExtractionError(f"too many files (>{MAX_FILE_COUNT})")
    if report.bytes_copied + size > MAX_TOTAL_EXTRACTED_BYTES:
        raise ExtractionError(
            f"total extracted size exceeds {MAX_TOTAL_EXTRACTED_BYTES} bytes"
        )

    dst = dst_tree / rel
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(entry, dst)
    os.chmod(dst, 0o644)
    report.record(f"{src_tree.name}/{rel}", size, dst)


def _validate_regular_file(entry: Path, scope_root: Path) -> None:
    st = entry.lstat()
    if stat_mod.S_ISLNK(st.st_mode):
        raise ExtractionError(f"symlink not allowed: {entry.relative_to(scope_root)}")
    if not stat_mod.S_ISREG(st.st_mode):
        raise ExtractionError(f"not a regular file: {entry.relative_to(scope_root)}")
    resolved = entry.resolve(strict=True)
    if not _is_inside(resolved, scope_root):
        raise ExtractionError(f"path escapes source tree: {entry.relative_to(scope_root)}")


def _wipe(path: Path) -> None:
    if path.is_symlink():
        raise ExtractionError(f"destination is a symlink: {path}")
    if path.exists():
        shutil.rmtree(path)


def _is_inside(child: Path, parent: Path) -> bool:
    try:
        child.resolve(strict=False).relative_to(parent.resolve(strict=False))
    except ValueError:
        return False
    return True
