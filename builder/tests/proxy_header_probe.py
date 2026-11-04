"""Isolated integration probe; invoked by test_proxy_headers inside Docker."""

from __future__ import annotations

import http.server
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent


# Exact upstream (host, path) pairs, so a routing or fallback mistake reaches
# the wrong host or prefix and gets a 404.
ARTIFACTS = {
    ("dl.google.com", "/dl/android/maven2/example/google.jar"): 200,
    ("repo.maven.apache.org", "/maven2/example/central.jar"): 200,
    ("plugins.gradle.org", "/m2/example/redirect.jar"): 303,
    ("repo.maven.apache.org", "/redirected.jar"): 200,
    ("jitpack.io", "/com/github/lightphone/example.jar"): 200,
}


class Repository(http.server.BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        host = (self.headers["Host"] or "").split(":")[0]
        status = ARTIFACTS.get((host, self.path), 404)
        self.send_response(status)
        self.send_header(
            "Set-Cookie", "__cf_bm=test; Domain=repo.maven.apache.org; Path=/"
        )
        self.send_header("Content-Type", "application/java-archive")
        if status == 303:
            self.send_header("Location", "https://repo.maven.apache.org/redirected.jar")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(b"artifact" if status == 200 else b"missing")

    do_HEAD = do_GET

    def log_message(self, *_args: object) -> None:
        pass


def serve_dns() -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    server.bind(("127.0.0.1", 53))
    while True:
        request, address = server.recvfrom(4096)
        end = 12
        while request[end]:
            end += request[end] + 1
        question = request[12 : end + 5]
        answer = (
            b"\xc0\x0c"
            + struct.pack("!HHIH", 1, 1, 30, 4)
            + socket.inet_aton("127.0.0.1")
        )
        header = request[:2] + struct.pack("!HHHHH", 0x8180, 1, 1, 0, 0)
        server.sendto(header + question + answer, address)


def main() -> None:
    names = [
        "dl.google.com",
        "repo.maven.apache.org",
        "plugins.gradle.org",
        "jitpack.io",
    ]
    cert, key = ROOT / "ca.pem", ROOT / "key.pem"
    subprocess.run(
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-nodes",
            "-days",
            "1",
            "-subj",
            "/CN=proxy-test",
            "-addext",
            "subjectAltName=" + ",".join(f"DNS:{name}" for name in names),
            "-keyout",
            str(key),
            "-out",
            str(cert),
        ],
        check=True,
        capture_output=True,
    )
    for directory in ["client", "proxy", "fastcgi", "uwsgi", "scgi"]:
        Path(f"/tmp/nginx/{directory}").mkdir(parents=True, exist_ok=True)
    Path("/tmp/nginx/resolver.conf").write_text("resolver 127.0.0.1 ipv6=off;\n")
    config = (
        (ROOT / "nginx.conf")
        .read_text()
        .replace("/etc/ssl/certs/ca-certificates.crt", str(cert))
    )
    (ROOT / "test.conf").write_text(config)
    threading.Thread(target=serve_dns, daemon=True).start()
    repository = http.server.ThreadingHTTPServer(("127.0.0.1", 443), Repository)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(cert, key)
    repository.socket = tls.wrap_socket(repository.socket, server_side=True)
    threading.Thread(target=repository.serve_forever, daemon=True).start()
    nginx = subprocess.Popen(
        ["nginx", "-c", str(ROOT / "test.conf"), "-g", "daemon off;"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    failed = True
    try:
        for _ in range(50):
            try:
                urllib.request.urlopen(
                    "http://127.0.0.1:8080/healthz", timeout=1
                ).close()
                break
            except (OSError, urllib.error.URLError):
                time.sleep(0.1)
        else:
            raise RuntimeError("proxy did not become healthy")
        paths = [
            ("/example/google.jar", 200),
            ("/example/central.jar", 200),
            ("/example/redirect.jar", 200),
            ("/com/github/lightphone/example.jar", 200),
            ("/example/missing.jar", 404),
        ]
        for method in ["GET", "HEAD"]:
            for path, expected in paths:
                request = urllib.request.Request(
                    "http://127.0.0.1:8080" + path, method=method
                )
                try:
                    response = urllib.request.urlopen(request, timeout=5)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    assert response.status == expected, (path, response.status)
                    assert response.headers.get_all("Set-Cookie") is None, (
                        f"Cookie forwarded on {method} {path}"
                    )
                    if expected == 200:
                        assert (
                            response.headers["Content-Type"]
                            == "application/java-archive"
                        )
                        assert response.read() == (
                            b"artifact" if method == "GET" else b""
                        )
        print("Proxy GET/HEAD, fallback, redirect, cookie and artifact checks passed")
        failed = False
    finally:
        nginx.terminate()
        try:
            _, errors = nginx.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            nginx.kill()
            _, errors = nginx.communicate()
        repository.shutdown()
        if failed or nginx.returncode:
            print(errors.decode(), file=sys.stderr)


if __name__ == "__main__":
    main()
