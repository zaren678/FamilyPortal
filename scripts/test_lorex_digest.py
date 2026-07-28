#!/usr/bin/env python3
"""Compare Digest Authorization header variants against a Lorex NVR."""

import argparse
import getpass
import hashlib
import http.client
import secrets
import ssl
import time
import urllib.request


PATH = "/cgi-bin/eventManager.cgi?action=attach&codes=%5BAll%5D"


def md5(value: str) -> str:
    return hashlib.md5(value.encode(), usedforsecurity=False).hexdigest()


def parse_challenge(value: str) -> dict[str, str]:
    scheme, parameters = value.split(" ", 1)
    if scheme.lower() != "digest":
        raise RuntimeError(f"Expected Digest authentication, received {scheme}")
    return urllib.request.parse_keqv_list(urllib.request.parse_http_list(parameters))


def connection(host: str, port: int) -> http.client.HTTPSConnection:
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return http.client.HTTPSConnection(host, port, context=context, timeout=10)


def get_challenge(host: str, port: int) -> dict[str, str]:
    client = connection(host, port)
    client.request("GET", PATH, headers={"User-Agent": "curl/8.7.1", "Accept": "*/*"})
    response = client.getresponse()
    response.read()
    header = response.getheader("WWW-Authenticate")
    client.close()
    if response.status != 401 or not header:
        raise RuntimeError(f"Expected 401 Digest challenge, received HTTP {response.status}")
    return parse_challenge(header)


def digest_fields(username: str, password: str, challenge: dict[str, str]) -> dict[str, str]:
    realm = challenge["realm"]
    nonce = challenge["nonce"]
    opaque = challenge.get("opaque", "")
    cnonce = secrets.token_hex(8)
    nc = "00000001"
    ha1 = md5(f"{username}:{realm}:{password}")
    ha2 = md5(f"GET:{PATH}")
    response = md5(f"{ha1}:{nonce}:{nc}:{cnonce}:auth:{ha2}")
    return {
        "username": username,
        "realm": realm,
        "nonce": nonce,
        "uri": PATH,
        "cnonce": cnonce,
        "nc": nc,
        "qop": "auth",
        "response": response,
        "opaque": opaque,
    }


def quoted(name: str, fields: dict[str, str]) -> str:
    return f'{name}="{fields[name]}"'


def build_header(fields: dict[str, str], variant: str) -> str:
    if variant == "ha_original":
        parts = [
            quoted("username", fields), quoted("realm", fields), quoted("nonce", fields),
            quoted("uri", fields), quoted("response", fields), 'algorithm="MD5"',
            quoted("opaque", fields), 'qop="auth"', f'nc={fields["nc"]}',
            quoted("cnonce", fields),
        ]
    elif variant == "unquoted_tokens":
        parts = [
            quoted("username", fields), quoted("realm", fields), quoted("nonce", fields),
            quoted("uri", fields), quoted("response", fields), "algorithm=MD5",
            quoted("opaque", fields), "qop=auth", f'nc={fields["nc"]}',
            quoted("cnonce", fields),
        ]
    elif variant == "no_algorithm":
        parts = [
            quoted("username", fields), quoted("realm", fields), quoted("nonce", fields),
            quoted("uri", fields), quoted("response", fields), quoted("opaque", fields),
            "qop=auth", f'nc={fields["nc"]}', quoted("cnonce", fields),
        ]
    elif variant == "curl_order":
        parts = [
            quoted("username", fields), quoted("realm", fields), quoted("nonce", fields),
            quoted("uri", fields), quoted("cnonce", fields), f'nc={fields["nc"]}',
            "qop=auth", quoted("response", fields), quoted("opaque", fields),
        ]
    else:
        raise ValueError(variant)
    return "Digest " + ", ".join(part for part in parts if not part.endswith('=""'))


def attempt(host: str, port: int, username: str, password: str, variant: str) -> int:
    challenge = get_challenge(host, port)
    fields = digest_fields(username, password, challenge)
    client = connection(host, port)
    client.request(
        "GET",
        PATH,
        headers={
            "Authorization": build_header(fields, variant),
            "User-Agent": "curl/8.7.1",
            "Accept": "*/*",
            "Connection": "close",
        },
    )
    response = client.getresponse()
    status = response.status
    content_type = response.getheader("Content-Type")
    print(f"{variant}: HTTP {status}, Content-Type={content_type!r}")
    client.close()
    return status


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="192.168.1.74")
    parser.add_argument("--port", type=int, default=443)
    parser.add_argument("--username", default="admin")
    args = parser.parse_args()
    password = getpass.getpass("Dahua password: ")

    for variant in ("ha_original", "unquoted_tokens", "no_algorithm", "curl_order"):
        if attempt(args.host, args.port, args.username, password, variant) == 200:
            print(f"First successful variant: {variant}")
            return
        time.sleep(0.5)
    raise SystemExit("No tested Digest header variant succeeded")


if __name__ == "__main__":
    main()
