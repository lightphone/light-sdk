"""Native-library inventory tests."""

from __future__ import annotations

import hashlib
import io
import zipfile
from pathlib import Path

import pytest
from lightbuilder import native

ALLOWLIST = ["io.github.david-allison:anki-android-backend", "androidx.room"]


def _aar(**libs: bytes) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as archive:
        archive.writestr("classes.jar", b"")
        for path, data in libs.items():
            archive.writestr(f"jni/{path}", data)
    return buf.getvalue()


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _log(uri: str, status: int = 200, method: str = "GET") -> str:
    return f'2026-10-01T23:47:27+00:00 {method} {uri} {status} 100 "1.2.3.4:443" "404 : {status}"'


ANKI_PATH = (
    "/io/github/david-allison/anki-android-backend/0.1.70/anki-android-backend-0.1.70.aar"
)


def test_jni_libraries_map_to_apk_paths() -> None:
    libs = native.jni_libraries(_aar(**{"arm64-v8a/librs.so": b"rs"}))
    assert libs == {"lib/arm64-v8a/librs.so": _sha(b"rs")}


def test_image_inventory_collects_every_cached_aar(tmp_path: Path) -> None:
    (tmp_path / "a").mkdir()
    (tmp_path / "a/one.aar").write_bytes(_aar(**{"arm64-v8a/libx.so": b"v1"}))
    (tmp_path / "b").mkdir()
    (tmp_path / "b/two.aar").write_bytes(_aar(**{"arm64-v8a/libx.so": b"v2"}))

    doc = native.image_inventory(tmp_path)

    assert doc == {
        "schemaVersion": 1,
        "libraries": {"lib/arm64-v8a/libx.so": sorted([_sha(b"v1"), _sha(b"v2")])},
    }


def test_served_aars_keeps_successful_aar_gets_only() -> None:
    lines = [
        _log(ANKI_PATH),
        _log(ANKI_PATH),
        _log(ANKI_PATH.replace(".aar", ".pom")),
        _log("/androidx/room/room-runtime/2.7.0/room-runtime-2.7.0.aar", status=404),
        _log("/androidx/room/room-runtime/2.7.0/room-runtime-2.7.0.aar", method="HEAD"),
        "garbage",
    ]
    served = native.served_aars(lines)
    assert served == [
        native.Artifact("io.github.david-allison", "anki-android-backend", "0.1.70", ANKI_PATH)
    ]


@pytest.mark.parametrize(
    "uri",
    [
        "/io/../../etc/x/1/x-1.aar",
        "/x/1/x-1.aar",
        "/io/github/foo/1.0/bar-1.0.aar",
        "/io/github/%2e%2e/foo/1.0/foo-1.0.aar",
    ],
)
def test_served_aars_rejects_unexpected_paths(uri: str) -> None:
    with pytest.raises(ValueError):
        native.served_aars([_log(uri)])


@pytest.mark.parametrize(
    ("group", "name", "allowed"),
    [
        ("io.github.david-allison", "anki-android-backend", True),
        ("io.github.david-allison", "anki-android-backend-extra", True),
        ("io.github.david-allison", "anki-android-backendx", False),
        ("androidx.room", "room-runtime", True),
        ("androidx.room.sub", "x", True),
        ("androidx.roomx", "room-runtime", False),
        ("androidx.camera", "camera-core", False),
    ],
)
def test_is_allowed_matches_plugin_rules(group: str, name: str, allowed: bool) -> None:
    assert native.is_allowed(group, name, ALLOWLIST) is allowed


def test_build_inventory_adds_allowlisted_served_aars_from_upstream() -> None:
    image = native._document({"lib/arm64-v8a/libsdk.so": {_sha(b"sdk")}})
    camera_path = "/androidx/camera/camera-core/1.5.0/camera-core-1.5.0.aar"
    served = native.served_aars([_log(ANKI_PATH), _log(camera_path)])
    fetched = []

    def fetch(path: str) -> bytes:
        fetched.append(path)
        return _aar(**{"arm64-v8a/librs.so": b"rs"})

    doc = native.build_inventory(image, served, ALLOWLIST, fetch=fetch)

    assert fetched == [ANKI_PATH]
    assert doc["libraries"] == {
        "lib/arm64-v8a/librs.so": [_sha(b"rs")],
        "lib/arm64-v8a/libsdk.so": [_sha(b"sdk")],
    }


@pytest.mark.parametrize(
    "document",
    [
        {},
        {"schemaVersion": 2, "libraries": {}},
        {"schemaVersion": 1, "libraries": []},
        {"schemaVersion": 1, "libraries": {"assets/x.so": ["0" * 64]}},
        {"schemaVersion": 1, "libraries": {"lib/arm64-v8a/x.so": "0" * 64}},
        {"schemaVersion": 1, "libraries": {"lib/arm64-v8a/x.so": ["nothex"]}},
    ],
)
def test_build_inventory_rejects_malformed_image_inventory(document: dict) -> None:
    with pytest.raises(ValueError):
        native.build_inventory(document, [], ALLOWLIST, fetch=lambda _: b"")


def test_load_allowlist_skips_comments_and_rejects_empty(tmp_path: Path) -> None:
    allowlist = tmp_path / "allowed.txt"
    allowlist.write_text("# comment\n\nandroidx.room\n")
    assert native.load_allowlist(allowlist) == ["androidx.room"]
    allowlist.write_text("# only comments\n")
    with pytest.raises(ValueError):
        native.load_allowlist(allowlist)


def test_load_allowlist_reads_plugin_resource() -> None:
    resource = (
        Path(__file__).resolve().parents[2]
        / "plugin/src/main/resources/com/thelightphone/plugin/allowed-dependencies.txt"
    )
    assert "io.ktor" in native.load_allowlist(resource)
