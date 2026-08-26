from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Protocol, cast

from .errors import SignerError
from .keys import generate_key
from .signing import sign_apk
from .stamp import stamp_apk
from .tools import resolve_tool
from .verify import verify_apk


class KeygenArgs(Protocol):
    tool_id: str
    keys_dir: Path
    keytool: Path | None


class StampArgs(Protocol):
    apk: Path
    build_recipe: Path
    registry: Path
    keys_dir: Path
    out: Path
    dev_id: str
    build_id: str
    attestation_key: Path
    issued_at: str | None
    apkanalyzer: Path | None


class SignArgs(Protocol):
    apk: Path
    build_recipe: Path
    registry: Path
    keys_dir: Path
    out: Path
    build_id: str
    signed_metadata: Path
    apksigner: Path | None


class VerifyArgs(Protocol):
    apk: Path
    attestation_public_key: Path
    attestation_key_id: str
    apksigner: Path | None
    apkanalyzer: Path | None


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        match cast(str, args.command):
            case "keygen":
                keygen_args = cast(KeygenArgs, cast(object, args))
                print(
                    generate_key(
                        keygen_args.tool_id,
                        keygen_args.keys_dir,
                        resolve_tool("keytool", keygen_args.keytool),
                    )
                )
            case "stamp":
                stamp_args = cast(StampArgs, cast(object, args))
                _ = stamp_apk(
                    apk=stamp_args.apk,
                    build_recipe_path=stamp_args.build_recipe,
                    registry_path=stamp_args.registry,
                    dev_id=stamp_args.dev_id,
                    build_id=stamp_args.build_id,
                    attestation_key=stamp_args.attestation_key,
                    keys_dir=stamp_args.keys_dir,
                    output=stamp_args.out,
                    apkanalyzer=resolve_tool("apkanalyzer", stamp_args.apkanalyzer),
                    issued_at=stamp_args.issued_at,
                )
            case "sign":
                sign_args = cast(SignArgs, cast(object, args))
                _ = sign_apk(
                    apk=sign_args.apk,
                    build_recipe_path=sign_args.build_recipe,
                    registry_path=sign_args.registry,
                    build_id=sign_args.build_id,
                    keys_dir=sign_args.keys_dir,
                    output=sign_args.out,
                    metadata_output=sign_args.signed_metadata,
                    apksigner=resolve_tool("apksigner", sign_args.apksigner),
                )
            case "verify":
                verify_args = cast(VerifyArgs, cast(object, args))
                _ = verify_apk(
                    apk=verify_args.apk,
                    attestation_public_key=verify_args.attestation_public_key,
                    apksigner=resolve_tool("apksigner", verify_args.apksigner),
                    apkanalyzer=resolve_tool("apkanalyzer", verify_args.apkanalyzer),
                    expected_key_id=verify_args.attestation_key_id,
                )
            case unknown:
                parser.error(f"unknown command: {unknown}")
        return 0
    except SignerError as error:
        print(f"{error.code}: {error}", file=sys.stderr)
        return 1


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m lightsigner")
    commands = parser.add_subparsers(dest="command", required=True)
    keygen = commands.add_parser("keygen")
    _ = keygen.add_argument("--tool-id", required=True)
    _ = keygen.add_argument("--keys-dir", type=Path, default=Path("keys"))
    _ = keygen.add_argument("--keytool", type=Path)

    stamp = commands.add_parser("stamp")
    _artifact_args(stamp)
    _ = stamp.add_argument("--dev-id", required=True)
    _ = stamp.add_argument("--build-id", required=True)
    _ = stamp.add_argument("--attestation-key", required=True, type=Path)
    _ = stamp.add_argument("--issued-at")
    _ = stamp.add_argument("--apkanalyzer", type=Path)

    sign = commands.add_parser("sign")
    _artifact_args(sign)
    _ = sign.add_argument("--build-id", required=True)
    _ = sign.add_argument("--signed-metadata", required=True, type=Path)
    _ = sign.add_argument("--apksigner", type=Path)

    verify = commands.add_parser("verify")
    _ = verify.add_argument("--apk", required=True, type=Path)
    _ = verify.add_argument("--attestation-public-key", required=True, type=Path)
    _ = verify.add_argument("--attestation-key-id", default="light-attest-1")
    _ = verify.add_argument("--apksigner", type=Path)
    _ = verify.add_argument("--apkanalyzer", type=Path)
    return parser


def _artifact_args(parser: argparse.ArgumentParser) -> None:
    _ = parser.add_argument("--apk", required=True, type=Path)
    _ = parser.add_argument("--build-recipe", required=True, type=Path)
    _ = parser.add_argument("--registry", required=True, type=Path)
    _ = parser.add_argument("--keys-dir", required=True, type=Path)
    _ = parser.add_argument("--out", required=True, type=Path)


if __name__ == "__main__":
    raise SystemExit(main())
