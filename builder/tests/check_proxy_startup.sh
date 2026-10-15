#!/usr/bin/env bash
# The proxy must start even when upstream DNS is unavailable at startup.
set -Eeuo pipefail

[[ $# -eq 1 ]] || { echo "usage: check_proxy_startup.sh IMAGE" >&2; exit 64; }
image="$1"
proxy="light-proxy-offline-$$"
trap 'docker rm -f "$proxy" >/dev/null 2>&1 || true' EXIT

docker run -d --platform=linux/amd64 --network none --name "$proxy" \
    --entrypoint /opt/light-builder/bin/maven-proxy.sh "$image" >/dev/null

for _ in $(seq 1 30); do
    if docker exec "$proxy" curl -fsS -o /dev/null http://127.0.0.1:8080/healthz 2>/dev/null; then
        echo "proxy starts without network access"
        exit 0
    fi
    sleep 1
done

docker logs "$proxy" >&2
exit 1
