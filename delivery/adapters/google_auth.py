"""Exchange the approved publisher's locally signed assertion for a short-lived Play token."""

import base64
import json
import os
import subprocess
import tempfile
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def token(credential):
    endpoint = "https://oauth2.googleapis.com/token"
    if (
        credential.get("type") != "service_account"
        or credential.get("token_uri") != endpoint
    ):
        raise ValueError("Expected a Google service-account credential")

    def encode(value):
        return base64.urlsafe_b64encode(value).rstrip(b"=")

    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT", "kid": credential["private_key_id"]}
    claims = {
        "iss": credential["client_email"],
        "aud": endpoint,
        "iat": now,
        "exp": now + 600,
        "scope": "https://www.googleapis.com/auth/androidpublisher https://www.googleapis.com/auth/playdeveloperreporting",
    }
    body = b".".join(encode(json.dumps(item).encode()) for item in (header, claims))
    with tempfile.NamedTemporaryFile() as private:
        private.write(credential["private_key"].encode())
        private.flush()
        sig = subprocess.check_output(
            ["openssl", "dgst", "-sha256", "-sign", private.name],
            input=body,
            env={"PATH": os.environ["PATH"]},
        )
    data = urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": (body + b"." + encode(sig)).decode(),
        }
    ).encode()
    with urlopen(Request(endpoint, data=data), timeout=30) as response:
        return json.load(response)["access_token"]
