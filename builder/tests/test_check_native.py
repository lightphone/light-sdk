"""Checks for the APK native-library validator."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import warnings
import zipfile
from pathlib import Path

import pytest

from tests.check_native import main


def _files(tmp_path: Path, entries: list[tuple[str, bytes]]) -> tuple[Path, Path]:
    apk = tmp_path / "tool-unsigned.apk"
    with zipfile.ZipFile(apk, "w") as archive, warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        for path, data in entries:
            archive.writestr(path, data)
    inventory = tmp_path / "native-libraries.json"
    inventory.write_text(json.dumps({
        "schemaVersion": 1,
        "libraries": {"lib/arm64-v8a/libok.so": [hashlib.sha256(b"ok").hexdigest()]},
    }))
    return apk, inventory


def test_approved_library_passes(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    apk, inventory = _files(tmp_path, [("lib/arm64-v8a/libok.so", b"ok")])

    main(apk, inventory)

    assert "1 native libraries, ABIs ['arm64-v8a'], unapproved []" in capsys.readouterr().out


def test_unapproved_library_fails(tmp_path: Path) -> None:
    apk, inventory = _files(tmp_path, [("lib/arm64-v8a/libok.so", b"wrong")])

    with pytest.raises(SystemExit, match="unapproved native libraries"):
        main(apk, inventory)


def test_wrong_abi_fails(tmp_path: Path) -> None:
    apk, inventory = _files(tmp_path, [("lib/x86/libok.so", b"ok")])
    inventory.write_text(json.dumps({
        "schemaVersion": 1,
        "libraries": {"lib/x86/libok.so": [hashlib.sha256(b"ok").hexdigest()]},
    }))

    with pytest.raises(SystemExit, match="unexpected ABIs: \\['x86'\\]"):
        main(apk, inventory)


def test_duplicate_entry_fails(tmp_path: Path) -> None:
    apk, inventory = _files(tmp_path, [
        ("lib/arm64-v8a/libok.so", b"ok"),
        ("lib/arm64-v8a/libok.so", b"ok"),
    ])

    with pytest.raises(SystemExit, match="duplicate APK entries"):
        main(apk, inventory)


def test_cli_reports_failure(tmp_path: Path) -> None:
    apk, inventory = _files(tmp_path, [("lib/arm64-v8a/libok.so", b"wrong")])

    result = subprocess.run(
        [sys.executable, str(Path(__file__).with_name("check_native.py")), str(apk), str(inventory)],
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode != 0
    assert "1 native libraries" in result.stdout
    assert "unapproved native libraries" in result.stderr
