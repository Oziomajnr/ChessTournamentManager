"""Android build configuration, AAB signing and certificate verification."""

import argparse
import base64
import os
import re
import ssl
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile


def build_properties(root):
    if (root / "local.properties").exists():
        raise ValueError("Expected a clean release checkout")
    names = {
        "firebase.projectId": "FIREBASE_PROJECT_ID",
        "firebase.apiKey": "FIREBASE_API_KEY",
        "firebase.writeKey": "FIREBASE_WRITE_KEY",
    }
    lines = []
    for prop, env in names.items():
        value = os.environ.get(env)
        if not value or any(ch in value for ch in '\n\r"'):
            raise ValueError("Missing or invalid " + env)
        lines.append(prop + "=" + value + "\n")
    with (root / "local.properties").open("x") as properties:
        os.chmod(properties.name, 0o600)
        properties.writelines(lines)


def sign(bundle):
    alias = os.environ["ANDROID_UPLOAD_KEY_ALIAS"]
    with ZipFile(bundle) as z:
        if any(
            re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC)", n, re.IGNORECASE)
            for n in z.namelist()
        ):
            raise ValueError("Expected an unsigned AAB from the build job")
    with tempfile.TemporaryDirectory() as tmp:
        key = Path(tmp) / "upload.jks"
        key.touch(mode=0o600)
        key.write_bytes(
            base64.b64decode(os.environ["ANDROID_UPLOAD_KEYSTORE_B64"], validate=True)
        )
        args = [
            "-keystore",
            str(key),
            "-storepass:env",
            "ANDROID_UPLOAD_STORE_PASSWORD",
        ]
        subprocess.run(
            [
                "jarsigner",
                *args,
                "-keypass:env",
                "ANDROID_UPLOAD_KEY_PASSWORD",
                str(bundle),
                alias,
            ],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        subprocess.run(
            ["jarsigner", "-verify", "-strict", *args, str(bundle), alias],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        expected = subprocess.check_output(
            ["keytool", "-exportcert", *args, "-alias", alias]
        )
        report = subprocess.check_output(
            ["keytool", "-printcert", "-jarfile", str(bundle), "-rfc"], text=True
        )
        cert = re.search(
            r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
            report,
            re.DOTALL,
        )
        if not cert or ssl.PEM_cert_to_DER_cert(cert.group()) != expected:
            raise ValueError(
                "Signed bundle does not match the configured upload certificate"
            )


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("operation", choices=["configure", "sign"])
    p.add_argument("path", type=Path)
    args = p.parse_args()
    (build_properties if args.operation == "configure" else sign)(args.path)
