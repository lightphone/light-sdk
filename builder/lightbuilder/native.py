"""Native-library inventory for the signing service's APK check.

A packaged ``lib/<abi>/<name>.so`` is approved only if its bytes match a
``jni/<abi>/<name>.so`` from either:

* an AAR in the image's warmed Gradle cache (the SDK's own dependency graph,
  inventoried at image build time), or
* an AAR the build-time Maven proxy served whose coordinates match the SDK's
  dependency allowlist directly. Transitive dependencies with native code
  fail closed until allowlisted explicitly.

Proxy-served AARs are never hashed from the proxy: the untrusted build talks
to it directly and could compromise it. The proxy log only says which
allowlisted coordinates to fetch; the bytes come straight from upstream over
verified HTTPS.
"""

from __future__ import annotations

import hashlib
import io
import re
import urllib.error
import urllib.request
import zipfile
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path

SCHEMA_VERSION = 1

# Same order as proxy/nginx.conf. JitPack serves com.github.lightphone only.
UPSTREAMS = (
    "https://dl.google.com/dl/android/maven2",
    "https://repo.maven.apache.org/maven2",
)
JITPACK = "https://jitpack.io"
JITPACK_GROUP_PATH = "/com/github/lightphone/"

# Matches proxy/nginx.conf's `maven` log_format.
_LOG_LINE = re.compile(r"^\S+ (?P<method>GET|HEAD) (?P<uri>\S+) (?P<status>\d{3}) ")
_SEGMENT = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


@dataclass(frozen=True)
class Artifact:
    group: str
    name: str
    version: str
    path: str


def jni_libraries(aar: bytes) -> dict[str, str]:
    """Map each APK path an AAR's native library packages to, to its SHA-256."""
    libraries = {}
    with zipfile.ZipFile(io.BytesIO(aar)) as archive:
        for entry in archive.infolist():
            if entry.filename.startswith("jni/") and entry.filename.endswith(".so"):
                libraries["lib/" + entry.filename[len("jni/"):]] = hashlib.sha256(
                    archive.read(entry)
                ).hexdigest()
    return libraries


def image_inventory(cache: Path) -> dict:
    libraries: dict[str, set[str]] = {}
    for aar in sorted(cache.rglob("*.aar")):
        for path, digest in jni_libraries(aar.read_bytes()).items():
            libraries.setdefault(path, set()).add(digest)
    return _document(libraries)


def served_aars(log_lines: Iterable[str]) -> list[Artifact]:
    """AARs the proxy successfully served, parsed from its access log."""
    artifacts = set()
    for line in log_lines:
        match = _LOG_LINE.match(line)
        if not match or match["method"] != "GET" or match["status"] != "200":
            continue
        artifact = _parse_artifact_path(match["uri"])
        if artifact is not None:
            artifacts.add(artifact)
    return sorted(artifacts, key=lambda a: a.path)


def _parse_artifact_path(uri: str) -> Artifact | None:
    if not uri.endswith(".aar"):
        return None
    segments = uri.strip("/").split("/")
    if len(segments) < 4 or not all(_SEGMENT.match(s) for s in segments):
        raise ValueError(f"unexpected AAR path in proxy log: {uri}")
    *group, name, version, filename = segments
    if not filename.startswith(f"{name}-{version}"):
        raise ValueError(f"unexpected AAR path in proxy log: {uri}")
    return Artifact(".".join(group), name, version, "/" + "/".join(segments))


def load_allowlist(path: Path) -> list[str]:
    lines = (line.strip() for line in path.read_text(encoding="utf-8").splitlines())
    entries = [line for line in lines if line and not line.startswith("#")]
    if not entries:
        raise ValueError(f"empty dependency allowlist: {path}")
    return entries


def is_allowed(group: str, name: str, allowlist: Iterable[str]) -> bool:
    """Mirrors LightSdkPlugin.isAllowedCoordinate (see allowed-dependencies.txt)."""
    coordinate = f"{group}:{name}"
    for entry in allowlist:
        if ":" in entry:
            if coordinate == entry or coordinate.startswith(entry + "-"):
                return True
        elif group == entry or group.startswith(entry + "."):
            return True
    return False


def fetch_upstream(path: str) -> bytes:
    bases = (JITPACK,) if path.startswith(JITPACK_GROUP_PATH) else UPSTREAMS
    for base in bases:
        try:
            # urllib verifies TLS certificates against the system CA bundle.
            with urllib.request.urlopen(base + path, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
    raise FileNotFoundError(f"no upstream serves {path}")


def build_inventory(
    image: dict,
    served: Iterable[Artifact],
    allowlist: Iterable[str],
    fetch: Callable[[str], bytes] = fetch_upstream,
) -> dict:
    check_document(image)
    allowlist = list(allowlist)
    libraries = {path: set(digests) for path, digests in image["libraries"].items()}
    for artifact in served:
        if not is_allowed(artifact.group, artifact.name, allowlist):
            continue
        for path, digest in jni_libraries(fetch(artifact.path)).items():
            libraries.setdefault(path, set()).add(digest)
    return _document(libraries)


def check_document(document: dict) -> None:
    if document.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"unsupported inventory schema: {document.get('schemaVersion')!r}")
    libraries = document.get("libraries")
    if not isinstance(libraries, dict):
        raise ValueError("inventory libraries must be an object")
    for path, digests in libraries.items():
        if not (path.startswith("lib/") and path.endswith(".so")):
            raise ValueError(f"unexpected inventory path: {path}")
        if not isinstance(digests, list) or not all(
            isinstance(d, str) and re.fullmatch(r"[0-9a-f]{64}", d) for d in digests
        ):
            raise ValueError(f"inventory digests for {path} must be SHA-256 hex strings")


def _document(libraries: dict[str, set[str]]) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "libraries": {path: sorted(digests) for path, digests in sorted(libraries.items())},
    }
