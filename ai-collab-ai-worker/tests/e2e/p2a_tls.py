"""P2-A 本地 TLS 测试证书与 truststore 生成。

证书只写入调用方提供的临时目录，私钥不进入仓库；SAN 同时覆盖 localhost 和 127.0.0.1，
供 Java OkHttp 与 Python urllib/httpx 共同验证测试 HTTPS Server。
"""

from __future__ import annotations

import datetime as dt
import ipaddress
import subprocess
from dataclasses import dataclass
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

TRUSTSTORE_PASSWORD = "p2a-test-changeit"


@dataclass(frozen=True)
class TlsMaterial:
    cert: Path
    key: Path
    truststore: Path
    truststore_password: str = TRUSTSTORE_PASSWORD


def generate_tls_material(directory: Path) -> TlsMaterial:
    directory.mkdir(parents=True, exist_ok=True)
    cert_path = directory / "local-mcp-cert.pem"
    key_path = directory / "local-mcp-key.pem"
    truststore_path = directory / "local-mcp-truststore.p12"

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = dt.datetime.now(dt.UTC)
    subject = x509.Name(
        [
            x509.NameAttribute(NameOID.COUNTRY_NAME, "CN"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "AI Collab P2-A"),
            x509.NameAttribute(NameOID.COMMON_NAME, "localhost"),
        ]
    )
    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(minutes=5))
        .not_valid_after(now + dt.timedelta(days=2))
        .add_extension(
            x509.SubjectAlternativeName(
                [
                    x509.DNSName("localhost"),
                    x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
                ]
            ),
            critical=False,
        )
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .sign(key, hashes.SHA256())
    )

    key_path.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    cert_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))

    if truststore_path.exists():
        truststore_path.unlink()
    _run_keytool(
        [
            "-importcert",
            "-noprompt",
            "-alias",
            "p2a-local-mcp",
            "-file",
            str(cert_path),
            "-keystore",
            str(truststore_path),
            "-storetype",
            "PKCS12",
            "-storepass",
            TRUSTSTORE_PASSWORD,
        ]
    )
    return TlsMaterial(cert_path, key_path, truststore_path)


def _run_keytool(arguments: list[str]) -> None:
    command = [
        "keytool",
        *arguments,
        "-J-Duser.language=en",
        "-J-Duser.country=US",
    ]
    completed = subprocess.run(
        command,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if completed.returncode != 0:
        raise RuntimeError(
            "keytool 创建测试 truststore 失败: "
            f"stdout={completed.stdout.strip()} stderr={completed.stderr.strip()}"
        )


__all__ = ["TRUSTSTORE_PASSWORD", "TlsMaterial", "generate_tls_material"]
