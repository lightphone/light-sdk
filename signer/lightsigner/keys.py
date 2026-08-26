from __future__ import annotations

import hashlib
import os
from pathlib import Path

from .errors import SignerError
from .registry import validate_tool_id
from .tools import run_tool

KEY_ALIAS = "light-tool"
PASSWORD_ENV = "LIGHT_SIGNER_KEY_PASSWORD"


def key_directory(keys_dir: Path, key_id: str) -> Path:
    return keys_dir / validate_tool_id(key_id)

def keystore_path(keys_dir: Path, key_id: str) -> Path:
    return key_directory(keys_dir, key_id) / "signing.p12"

def certificate_path(keys_dir: Path, key_id: str) -> Path:
    return key_directory(keys_dir, key_id) / "certificate.der"

def require_password() -> None:
    if not os.environ.get(PASSWORD_ENV):
        raise SignerError("missing_key_password", f"{PASSWORD_ENV} is required")

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


def certificate_sha256(certificate: Path) -> str:
    try:
        return hashlib.sha256(certificate.read_bytes()).hexdigest()
    except OSError as error:
        raise SignerError("missing_certificate", f"cannot read certificate: {certificate}") from error
