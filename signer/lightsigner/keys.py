from __future__ import annotations

import hashlib
import os
from pathlib import Path

from .errors import SignerError
from .registry import validate_tool_id
from .tools import run_tool

KEY_ALIAS = "light-tool"
PASSWORD_ENV = "LIGHT_SIGNER_KEY_PASSWORD"
STAMP_PASSWORD_ENV = "LIGHT_SIGNER_STAMP_KEY_PASSWORD"
STAMP_KEY_ALIAS = "light-stamp"


def key_directory(keys_dir: Path, key_id: str) -> Path:
    return keys_dir / validate_tool_id(key_id)

def keystore_path(keys_dir: Path, key_id: str) -> Path:
    return key_directory(keys_dir, key_id) / "signing.p12"

def certificate_path(keys_dir: Path, key_id: str) -> Path:
    return key_directory(keys_dir, key_id) / "certificate.der"

def require_password(variable: str = PASSWORD_ENV) -> None:
    if not os.environ.get(variable):
        raise SignerError("missing_key_password", f"{variable} is required")

def generate_key(tool_id: str, keys_dir: Path, keytool: Path) -> str:
    _ = validate_tool_id(tool_id)
    require_password()
    directory = key_directory(keys_dir, tool_id)
    keystore = keystore_path(keys_dir, tool_id)
    certificate = certificate_path(keys_dir, tool_id)
    if directory.exists():
        raise SignerError("key_exists", f"key already exists for {tool_id}")
    # Octal maps each Unix owner/group/other permission triplet to one digit.
    # Owner-only access: this directory contains the APK private-key keystore.
    directory.mkdir(parents=True, mode=0o700)
    try:
        _ = run_tool(
            [
                str(keytool),
                "-genkeypair",
                "-alias",
                KEY_ALIAS,
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-sigalg",
                "SHA256withECDSA",
                "-dname",
                f"CN={tool_id},O=Light",
                "-validity",
                "36500",
                "-keystore",
                str(keystore),
                "-storetype",
                "PKCS12",
                "-storepass:env",
                PASSWORD_ENV,
                "-keypass:env",
                PASSWORD_ENV,
            ]
        )
        _ = run_tool(
            [
                str(keytool),
                "-exportcert",
                "-alias",
                KEY_ALIAS,
                "-keystore",
                str(keystore),
                "-storetype",
                "PKCS12",
                "-storepass:env",
                PASSWORD_ENV,
                "-file",
                str(certificate),
            ]
        )
        # Owner read/write only: the PKCS#12 file contains the APK private key.
        os.chmod(keystore, 0o600)
        # Publicly readable, owner writable: the DER certificate contains no secret.
        os.chmod(certificate, 0o644)
        return certificate_sha256(certificate)
    except Exception:
        for path in (certificate, keystore):
            path.unlink(missing_ok=True)
        directory.rmdir()
        raise


def generate_stamp_key(output_dir: Path, keytool: Path) -> str:
    require_password(STAMP_PASSWORD_ENV)
    keystore = output_dir / "signing.p12"
    certificate = output_dir / "certificate.der"
    if output_dir.exists():
        raise SignerError("key_exists", "source stamp key already exists")
    output_dir.mkdir(parents=True, mode=0o700)
    try:
        run_tool([
            str(keytool), "-genkeypair", "-alias", STAMP_KEY_ALIAS,
            "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA",
            "-dname", "CN=Light Source Stamp,O=Light", "-validity", "36500",
            "-keystore", str(keystore), "-storetype", "PKCS12",
            "-storepass:env", STAMP_PASSWORD_ENV, "-keypass:env", STAMP_PASSWORD_ENV,
        ])
        run_tool([
            str(keytool), "-exportcert", "-alias", STAMP_KEY_ALIAS,
            "-keystore", str(keystore), "-storetype", "PKCS12",
            "-storepass:env", STAMP_PASSWORD_ENV, "-file", str(certificate),
        ])
        os.chmod(keystore, 0o600)
        os.chmod(certificate, 0o644)
        return certificate_sha256(certificate)
    except Exception:
        for path in (certificate, keystore):
            path.unlink(missing_ok=True)
        output_dir.rmdir()
        raise


def certificate_sha256(certificate: Path) -> str:
    try:
        return hashlib.sha256(certificate.read_bytes()).hexdigest()
    except OSError as error:
        raise SignerError("missing_certificate", f"cannot read certificate: {certificate}") from error
