"""Extraction policy tests.

These tests construct realistic-but-malicious dev repos and assert that the
extractor refuses them. Add a new test here every time you discover a fresh
way a dev's repo can mis-shape input.
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from lightbuilder.extract import ExtractionError, extract


VALID_BUILD_GRADLE = """\
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.light.sdk)
}
dependencies {
    implementation(project(":sdk:client"))
}
"""

VALID_LIGHTTOOL_TOML = """\
[tool]
id = "com.example.mytool"
label = "My Tool"
versionCode = 1
versionName = "1.0"
"""


def _make_dev_repo(root: Path, subpath: str = "tool") -> Path:
    tool = root / subpath if subpath != "." else root
    src = tool / "src" / "main" / "kotlin" / "com" / "example"
    src.mkdir(parents=True)
    (src / "Main.kt").write_text("package com.example\nfun main() = Unit\n")
    res = tool / "src" / "main" / "res" / "values"
    res.mkdir(parents=True)
    (res / "strings.xml").write_text('<?xml version="1.0"?><resources/>\n')
    (tool / "build.gradle.kts").write_text(VALID_BUILD_GRADLE)
    (tool / "lighttool.toml").write_text(VALID_LIGHTTOOL_TOML)
    return root


def test_happy_path_copies_source_resources_and_root_files(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    report = extract(dev, dst)

    assert (dst / "src/main/kotlin/com/example/Main.kt").is_file()
    assert (dst / "src/main/res/values/strings.xml").is_file()
    assert (dst / "build.gradle.kts").is_file()
    assert (dst / "lighttool.toml").is_file()
    assert "build.gradle.kts" in report.files_copied
    assert "lighttool.toml" in report.files_copied
    assert "kotlin/com/example/Main.kt" in report.files_copied
    assert report.bytes_copied > 0


def test_missing_lighttool_toml_rejected(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    (dev / "tool" / "lighttool.toml").unlink()
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    with pytest.raises(ExtractionError, match="lighttool.toml is required"):
        extract(dev, dst)


def test_missing_build_gradle_rejected(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    (dev / "tool" / "build.gradle.kts").unlink()
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    with pytest.raises(ExtractionError, match="build.gradle.kts is required"):
        extract(dev, dst)


def test_malicious_build_script_rejected(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    (dev / "tool" / "build.gradle.kts").write_text(
        VALID_BUILD_GRADLE + "\nbuildscript { }\n"
    )
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    with pytest.raises(ExtractionError, match="buildscript"):
        extract(dev, dst)


def test_apply_from_in_build_script_rejected(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    (dev / "tool" / "build.gradle.kts").write_text(
        VALID_BUILD_GRADLE + '\napply(from = "evil.gradle.kts")\n'
    )
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    with pytest.raises(ExtractionError, match="apply\\(from"):
        extract(dev, dst)


def test_disallowed_source_extension_rejected(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    bad = dev / "tool" / "src" / "main" / "kotlin" / "evil.groovy"
    bad.write_text("System.exit(1)\n")
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    with pytest.raises(ExtractionError, match="extension not allowed"):
        extract(dev, dst)


def test_meta_inf_directory_skipped(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    smuggle = dev / "tool" / "src" / "main" / "res" / "META-INF" / "services"
    smuggle.mkdir(parents=True)
    (smuggle / "com.example.Service").write_text("evil\n")
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    report = extract(dev, dst)
    assert not any("META-INF" in f for f in report.files_copied)


def test_symlinked_file_rejected(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    target = tmp_path / "outside.kt"
    target.write_text("// leaked\n")
    link = dev / "tool" / "src" / "main" / "kotlin" / "Linked.kt"
    os.symlink(target, link)
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    with pytest.raises(ExtractionError, match="symlink"):
        extract(dev, dst)


def test_symlinked_directory_rejected(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    target = tmp_path / "outside_dir"
    target.mkdir()
    (target / "file.kt").write_text("// outside\n")
    link = dev / "tool" / "src" / "main" / "kotlin" / "linked_dir"
    os.symlink(target, link)
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    with pytest.raises(ExtractionError, match="symlinked directory"):
        extract(dev, dst)


def test_oversize_file_rejected(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    big = dev / "tool" / "src" / "main" / "res" / "raw"
    big.mkdir(parents=True)
    (big / "huge.png").write_bytes(b"0" * (6 * 1024 * 1024))
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    with pytest.raises(ExtractionError, match="exceeds"):
        extract(dev, dst)


def test_missing_tool_module(tmp_path: Path) -> None:
    dev = tmp_path / "dev"
    dev.mkdir()

    with pytest.raises(ExtractionError, match="tool path does not exist"):
        extract(dev, tmp_path / "workspace" / "tool")


def test_existing_dest_tree_is_wiped(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    dst = tmp_path / "workspace" / "tool"
    stale = dst / "src" / "main" / "kotlin" / "Stale.kt"
    stale.parent.mkdir(parents=True)
    stale.write_text("// from a previous build\n")

    extract(dev, dst)

    assert not stale.exists(), "previous build's source must not leak through"


def test_settings_gradle_at_repo_root_ignored(tmp_path: Path) -> None:
    # The dev's settings.gradle.kts, gradle.properties, gradle wrapper, etc.
    # are NOT extracted — the container uses the baked-in SDK's copies. Make
    # sure the extractor doesn't touch them either way.
    dev = _make_dev_repo(tmp_path / "dev")
    (dev / "settings.gradle.kts").write_text("// dev's evil settings\n")
    (dev / "build.gradle.kts").write_text("// dev's evil root build\n")
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    report = extract(dev, dst)

    assert not (dst / "../settings.gradle.kts").exists()
    assert "settings.gradle.kts" not in report.files_copied


# --- Custom --tool-path behaviour ------------------------------------------

def test_custom_tool_subpath(tmp_path: Path) -> None:
    # Dev keeps their tool in apps/mytool/ instead of the default tool/.
    dev = _make_dev_repo(tmp_path / "dev", subpath="apps/mytool")
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    report = extract(dev, dst, tool_subpath="apps/mytool")

    assert (dst / "src/main/kotlin/com/example/Main.kt").is_file()
    assert (dst / "lighttool.toml").is_file()
    assert "lighttool.toml" in report.files_copied


def test_repo_root_as_tool_path(tmp_path: Path) -> None:
    # The dev's repo root IS the tool — no enclosing subdirectory.
    dev = _make_dev_repo(tmp_path / "dev", subpath=".")
    dst = tmp_path / "workspace" / "tool"
    dst.mkdir(parents=True)

    report = extract(dev, dst, tool_subpath=".")

    assert (dst / "build.gradle.kts").is_file()
    assert (dst / "lighttool.toml").is_file()
    assert "build.gradle.kts" in report.files_copied


def test_absolute_tool_path_rejected(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    with pytest.raises(ExtractionError, match="must be relative"):
        extract(dev, tmp_path / "workspace" / "tool", tool_subpath="/etc")


def test_parent_traversal_in_tool_path_rejected(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    with pytest.raises(ExtractionError, match="\\.\\."):
        extract(dev, tmp_path / "workspace" / "tool", tool_subpath="../outside")


def test_nonexistent_tool_path_rejected(tmp_path: Path) -> None:
    dev = _make_dev_repo(tmp_path / "dev")
    with pytest.raises(ExtractionError, match="does not exist"):
        extract(dev, tmp_path / "workspace" / "tool", tool_subpath="nope/missing")
