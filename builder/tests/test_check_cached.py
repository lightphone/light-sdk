"""Checks for the BuildKit cache-reuse check."""

from __future__ import annotations

from pathlib import Path

import pytest

from tests.check_cached import main, uncached_steps

LOG = """\
#1 [internal] load build definition from Dockerfile
#1 DONE 0.0s
#4 [base 1/3] FROM docker.io/library/eclipse-temurin:17-jdk-noble@sha256:abc
#4 DONE 0.1s
#5 [base 2/3] RUN apt-get update
#5 CACHED
#6 [linux/amd64 sdk 1/2] RUN git clone --no-tags https://example.com/sdk /opt/light-sdk
#6 CACHED
#7 [gradle-warmup 1/1] RUN ./gradlew :tool:assembleRelease
#7 CACHED
#8 [maven-bundle 2/2] RUN ./gradlew publish
#8 0.512 Starting a Gradle Daemon
#8 DONE 120.3s
"""


def test_all_cached() -> None:
    assert uncached_steps(LOG, {"base", "sdk", "gradle-warmup"}) == ([], set())


def test_rebuilt_step_reported() -> None:
    rebuilt, missing = uncached_steps(LOG, {"maven-bundle"})
    assert rebuilt == ["[maven-bundle] RUN ./gradlew publish"]
    assert missing == set()


def test_stage_without_steps_reported() -> None:
    assert uncached_steps(LOG, {"proxy-base"}) == ([], {"proxy-base"})


def test_main_fails_on_rebuilt_step(tmp_path: Path) -> None:
    log = tmp_path / "build.log"
    log.write_text(LOG, encoding="utf-8")
    with pytest.raises(SystemExit, match="rebuilt instead of cached"):
        main(log, ["gradle-warmup", "maven-bundle"])
