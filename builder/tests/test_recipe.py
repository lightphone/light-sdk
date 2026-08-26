from __future__ import annotations

import json
from pathlib import Path

from lightbuilder import recipe


def test_recipe_uses_shared_tool_and_sdk_objects(tmp_path: Path) -> None:
    artifact = tmp_path / "tool-unsigned.apk"
    artifact.write_bytes(b"apk")
    destination = tmp_path / "recipe.json"

    record = recipe.write(
        artifact=artifact,
        tool=recipe.Tool(
            id="com.example.tool",
            version_code=3,
            version_name="1.2.0",
            git_url="https://github.com/example/tool",
            git_commit="a" * 40,
        ),
        sdk_git_ref="v0.1.1",
        build=recipe.Build(
            image_digest="sha256:image",
            gradle_command=("./gradlew", ":tool:assembleRelease"),
            source_date_epoch=1_787_616_000,
            extracted_files=("lighttool.toml",),
        ),
        dest=destination,
    )

    assert record["tool"] == {
        "id": "com.example.tool",
        "versionCode": 3,
        "versionName": "1.2.0",
        "gitUrl": "https://github.com/example/tool",
        "gitCommit": "a" * 40,
    }
    assert record["sdkGitRef"] == "v0.1.1"
    assert json.loads(destination.read_text(encoding="utf-8")) == record
