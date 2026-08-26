from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .errors import SignerError
from .keys import generate_key
from .signing import sign_apk
from .stamp import stamp_apk
from .tools import resolve_tool
from .verify import verify_apk


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        match args.command:
            case "keygen":
                print(generate_key(args.tool_id, args.keys_dir, resolve_tool("keytool", args.keytool)))
            case "stamp":
                _ = stamp_apk(apk=args.apk, recipe_path=args.recipe, registry_path=args.registry,
                      dev_id=args.dev_id, build_id=args.build_id,
                      attestation_key=args.attestation_key, keys_dir=args.keys_dir,
                      output=args.out, apkanalyzer=resolve_tool("apkanalyzer", args.apkanalyzer),
                      issued_at=args.issued_at)
            case "sign":
                _ = sign_apk(apk=args.apk, recipe_path=args.recipe, registry_path=args.registry,
                     build_id=args.build_id, keys_dir=args.keys_dir, output=args.out,
                     metadata_output=args.signed_metadata,
                     apksigner=resolve_tool("apksigner", args.apksigner))
            case "verify":
                _ = verify_apk(apk=args.apk, attestation_public_key=args.attestation_public_key,
                       apksigner=resolve_tool("apksigner", args.apksigner),
                       apkanalyzer=resolve_tool("apkanalyzer", args.apkanalyzer),
                       expected_key_id=args.attestation_key_id)
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
    _ = parser.add_argument("--recipe", required=True, type=Path)
    _ = parser.add_argument("--registry", required=True, type=Path)
    _ = parser.add_argument("--keys-dir", required=True, type=Path)
    _ = parser.add_argument("--out", required=True, type=Path)


if __name__ == "__main__":
    raise SystemExit(main())
