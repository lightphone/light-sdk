from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Protocol, cast

from .bundle import build_bundle, verify_bundle
from .errors import SignerError
from .keys import STAMP_KEY_ALIAS, generate_key, generate_stamp_key
from .signing import sign_apk
from .tools import resolve_tool
from .trust_statement import inject_trust_statement
from .verify import verify_apk


class KeygenArgs(Protocol):
    tool_id: str
    keys_dir: Path
    keytool: Path | None


class InjectTrustStatementArgs(Protocol):
    apk: Path
    build_recipe: Path
    registry: Path
    keys_dir: Path
    out: Path
    dev_id: str
    build_id: str
    issued_at: str | None
    apkanalyzer: Path | None


class StampKeygenArgs(Protocol):
    output_dir: Path
    keytool: Path | None


class SignArgs(Protocol):
    apk: Path
    build_recipe: Path
    registry: Path
    keys_dir: Path
    out: Path
    build_id: str
    signed_metadata: Path
    stamp_keystore: Path
    stamp_key_alias: str
    apksigner: Path | None


class VerifyArgs(Protocol):
    apk: Path
    stamp_cert_sha256: str
    apksigner: Path | None
    apkanalyzer: Path | None


class BundleBuildArgs(Protocol):
    portal: Path
    output_dir: Path
    private_key: Path
    openssl: Path | None


class BundleVerifyArgs(Protocol):
    bundle_json: Path
    bundle_sig: Path
    public_key: list[Path]
    openssl: Path | None


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
            case "inject-trust-statement":
                statement_args = cast(InjectTrustStatementArgs, cast(object, args))
                _ = inject_trust_statement(
                    apk=statement_args.apk,
                    build_recipe_path=statement_args.build_recipe,
                    registry_path=statement_args.registry,
                    dev_id=statement_args.dev_id,
                    build_id=statement_args.build_id,
                    keys_dir=statement_args.keys_dir,
                    output=statement_args.out,
                    apkanalyzer=resolve_tool("apkanalyzer", statement_args.apkanalyzer),
                    issued_at=statement_args.issued_at,
                )
            case "stamp-keygen":
                keygen_args = cast(StampKeygenArgs, cast(object, args))
                print(
                    generate_stamp_key(
                        keygen_args.output_dir,
                        resolve_tool("keytool", keygen_args.keytool),
                    )
                )
            case "sign":
                sign_args = cast(SignArgs, cast(object, args))
                _ = sign_apk(
                    apk=sign_args.apk,
                    build_recipe_path=sign_args.build_recipe,
                    registry_path=sign_args.registry,
                    build_id=sign_args.build_id,
                    keys_dir=sign_args.keys_dir,
                    stamp_keystore=sign_args.stamp_keystore,
                    stamp_key_alias=sign_args.stamp_key_alias,
                    output=sign_args.out,
                    metadata_output=sign_args.signed_metadata,
                    apksigner=resolve_tool("apksigner", sign_args.apksigner),
                )
            case "verify":
                verify_args = cast(VerifyArgs, cast(object, args))
                _ = verify_apk(
                    apk=verify_args.apk,
                    stamp_cert_sha256=verify_args.stamp_cert_sha256,
                    apksigner=resolve_tool("apksigner", verify_args.apksigner),
                    apkanalyzer=resolve_tool("apkanalyzer", verify_args.apkanalyzer),
                )
            case "bundle-build":
                bundle_args = cast(BundleBuildArgs, cast(object, args))
                _ = build_bundle(
                    portal=bundle_args.portal,
                    output_dir=bundle_args.output_dir,
                    private_key=bundle_args.private_key,
                    openssl=resolve_tool("openssl", bundle_args.openssl),
                )
            case "bundle-verify":
                bundle_args = cast(BundleVerifyArgs, cast(object, args))
                _ = verify_bundle(
                    bundle=bundle_args.bundle_json,
                    signature=bundle_args.bundle_sig,
                    public_keys=bundle_args.public_key,
                    openssl=resolve_tool("openssl", bundle_args.openssl),
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

    statement = commands.add_parser("inject-trust-statement")
    _artifact_args(statement)
    _ = statement.add_argument("--dev-id", required=True)
    _ = statement.add_argument("--build-id", required=True)
    _ = statement.add_argument("--issued-at")
    _ = statement.add_argument("--apkanalyzer", type=Path)

    stamp_keygen = commands.add_parser("stamp-keygen")
    _ = stamp_keygen.add_argument("--output-dir", required=True, type=Path)
    _ = stamp_keygen.add_argument("--keytool", type=Path)

    sign = commands.add_parser("sign")
    _artifact_args(sign)
    _ = sign.add_argument("--build-id", required=True)
    _ = sign.add_argument("--signed-metadata", required=True, type=Path)
    _ = sign.add_argument("--stamp-keystore", required=True, type=Path)
    _ = sign.add_argument("--stamp-key-alias", default=STAMP_KEY_ALIAS)
    _ = sign.add_argument("--apksigner", type=Path)

    verify = commands.add_parser("verify")
    _ = verify.add_argument("--apk", required=True, type=Path)
    _ = verify.add_argument("--stamp-cert-sha256", required=True)
    _ = verify.add_argument("--apksigner", type=Path)
    _ = verify.add_argument("--apkanalyzer", type=Path)

    bundle = commands.add_parser("bundle")
    bundle_commands = bundle.add_subparsers(dest="bundle_command", required=True)
    bundle_build = bundle_commands.add_parser("build")
    bundle_build.set_defaults(command="bundle-build")
    _ = bundle_build.add_argument("--portal", required=True, type=Path)
    _ = bundle_build.add_argument("--output-dir", required=True, type=Path)
    _ = bundle_build.add_argument("--private-key", required=True, type=Path)
    _ = bundle_build.add_argument("--openssl", type=Path)
    bundle_verify = bundle_commands.add_parser("verify")
    bundle_verify.set_defaults(command="bundle-verify")
    _ = bundle_verify.add_argument("--bundle-json", required=True, type=Path)
    _ = bundle_verify.add_argument("--bundle-sig", required=True, type=Path)
    _ = bundle_verify.add_argument("--public-key", required=True, action="append", type=Path)
    _ = bundle_verify.add_argument("--openssl", type=Path)
    return parser


def _artifact_args(parser: argparse.ArgumentParser) -> None:
    _ = parser.add_argument("--apk", required=True, type=Path)
    _ = parser.add_argument("--build-recipe", required=True, type=Path)
    _ = parser.add_argument("--registry", required=True, type=Path)
    _ = parser.add_argument("--keys-dir", required=True, type=Path)
    _ = parser.add_argument("--out", required=True, type=Path)


if __name__ == "__main__":
    raise SystemExit(main())
