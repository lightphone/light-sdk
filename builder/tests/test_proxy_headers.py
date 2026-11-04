"""Run the real proxy against deterministic local TLS repositories."""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.skipif(
    not os.environ.get("LIGHT_BUILDER_TEST_IMAGE"),
    reason="Set LIGHT_BUILDER_TEST_IMAGE to run the isolated Docker proxy check",
)
def test_public_artifacts_do_not_forward_cookies(tmp_path: Path) -> None:
    shutil.copy(ROOT / "builder/proxy/nginx.conf", tmp_path / "nginx.conf")
    shutil.copy(
        Path(__file__).with_name("proxy_header_probe.py"), tmp_path / "probe.py"
    )
    container = subprocess.check_output(
        [
            "docker",
            "create",
            "--platform",
            "linux/amd64",
            "--network",
            "none",
            "--user",
            "0",
            "--entrypoint",
            "python3",
            os.environ["LIGHT_BUILDER_TEST_IMAGE"],
            "/tmp/proxy-header-test/probe.py",
        ],
        text=True,
    ).strip()
    try:
        subprocess.run(
            ["docker", "cp", str(tmp_path), f"{container}:/tmp/proxy-header-test"],
            check=True,
        )
        result = subprocess.run(
            ["docker", "start", "--attach", container],
            check=False,
            capture_output=True,
            text=True,
            timeout=90,
        )
        assert result.returncode == 0, result.stdout + result.stderr
    finally:
        subprocess.run(
            ["docker", "rm", "--force", container], check=False, capture_output=True
        )
