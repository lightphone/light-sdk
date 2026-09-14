from __future__ import annotations

from pathlib import Path


FORMAT_ROOT = Path(__file__).resolve().parents[2] / "trust-format"
DOMAIN_SEPARATOR = (FORMAT_ROOT / "bundle-domain-separator.txt").read_bytes().rstrip(b"\r\n")
SIGNED_PAYLOAD_PREFIX = DOMAIN_SEPARATOR + b"\0"
SUPPORTED_SCHEMA_VERSION = 1
