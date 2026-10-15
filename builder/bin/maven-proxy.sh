#!/usr/bin/env bash
# Entrypoint for the build-time Maven proxy container (see proxy/nginx.conf).
# Runs from the builder image with --entrypoint so the proxy is pinned by the
# same digest as the build toolchain.

set -Eeuo pipefail

mkdir -p /tmp/nginx

nameservers="$(awk '/^nameserver/ { printf "%s ", $2 }' /etc/resolv.conf)"
if [[ -z "$nameservers" ]]; then
    echo "no nameserver in /etc/resolv.conf" >&2
    exit 1
fi
echo "resolver ${nameservers}ipv6=off;" > /tmp/nginx/resolver.conf

exec nginx -c "$LIGHT_BUILDER_HOME/proxy/nginx.conf" -g "daemon off;"
