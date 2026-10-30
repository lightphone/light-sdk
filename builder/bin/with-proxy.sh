#!/usr/bin/env bash
# Runs a command with the build-time Maven proxy listening on
# http://maven-proxy:8080/, for image build steps that resolve dependencies
# the way the runtime build does (see Dockerfile). The image must be built
# with --add-host maven-proxy=127.0.0.1.
#
# usage: with-proxy.sh COMMAND [ARG...]
#
# Exits with COMMAND's status; the proxy is stopped on every exit path.

set -Eeuo pipefail

[[ $# -gt 0 ]] || { echo "usage: with-proxy.sh COMMAND [ARG...]" >&2; exit 64; }

if ! getent hosts maven-proxy | grep -q '^127\.0\.0\.1'; then
    echo "build the image with --add-host maven-proxy=127.0.0.1" >&2
    exit 1
fi

export LIGHT_BUILDER_HOME="${LIGHT_BUILDER_HOME:-/opt/light-builder}"
bin="$(cd "$(dirname "$0")" && pwd)"

bash "$bin/maven-proxy.sh" &
proxy_pid=$!

stop_proxy() {
    kill "$proxy_pid" 2>/dev/null || true
    wait "$proxy_pid" 2>/dev/null || true
}
trap stop_proxy EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for _ in $(seq 1 30); do
    if curl -fsS -o /dev/null http://maven-proxy:8080/healthz 2>/dev/null; then
        break
    fi
    if ! kill -0 "$proxy_pid" 2>/dev/null; then
        echo "maven proxy exited during startup" >&2
        exit 1
    fi
    sleep 1
done
if ! curl -fsS -o /dev/null http://maven-proxy:8080/healthz; then
    echo "maven proxy did not become healthy" >&2
    exit 1
fi

"$@"
