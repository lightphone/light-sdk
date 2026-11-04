#!/usr/bin/env bash
# usage: .github/scripts/tests/run.sh

set -uo pipefail

scripts="$(cd "$(dirname "$0")/.." && pwd)"
failures=0

expect() {
    local name="$1" want="$2" got
    shift 2
    if got="$("$@" 2>/dev/null)" && [[ "$got" == "$want" ]]; then
        echo "ok   $name"
    else
        echo "FAIL $name: want '$want', got '${got:-<error>}'"
        failures=$((failures + 1))
    fi
}

expect_error() {
    local name="$1"
    shift
    if "$@" >/dev/null 2>&1; then
        echo "FAIL $name: expected an error"
        failures=$((failures + 1))
    else
        echo "ok   $name"
    fi
}

expect_warning() {
    local name="$1"
    shift
    if "$@" 2>&1 >/dev/null | grep -q '^::warning::'; then
        echo "ok   $name"
    else
        echo "FAIL $name: expected a warning"
        failures=$((failures + 1))
    fi
}

next() { "$scripts/next-version.sh" "$@"; }
bump() { printf '%s' "$1" | "$scripts/release-bump.sh"; }

expect "patch" 0.2.4 next 0.2.3 0.2.3 patch
expect "minor" 0.3.0 next 0.2.4 0.2.4 minor
expect "major on 0.x" 1.0.0 next 0.2.4 0.2.4 major
expect "major" 2.0.0 next 1.4.2 1.4.2 major
expect "multi-digit parts" 0.10.0 next 0.9.12 0.9.12 minor
expect "nothing new" skip next 0.2.3 0.2.3 none
expect "no tags" 0.2.3 next "" 0.2.3 patch
expect "hand edit wins" 0.3.0 next 0.2.4 0.3.0 patch
expect "hand edit patch jump" 0.2.6 next 0.2.4 0.2.6 patch
expect "hand edit below candidate" 0.3.0 next 0.2.4 0.2.6 minor
expect "hand edit equals candidate" 0.3.0 next 0.2.4 0.3.0 minor
expect "behind tag patches tag" 0.2.5 next 0.2.4 0.2.1 patch
expect_warning "behind tag warns" next 0.2.4 0.2.1 patch
expect_error "snapshot version" next 0.2.3 0.3.0-SNAPSHOT patch
expect_error "leading zero" next 0.2.3 0.02.3 patch
expect_error "bad tag" next 0.2 0.2.3 patch
expect_error "unknown bump" next 0.2.3 0.2.3 huge

expect "no changes" none bump ''
expect "unlabelled change" patch bump $'\n'
expect "unrelated labels" patch bump 'bug,docs'
expect "minor" minor bump 'release:minor'
expect "largest wins" major bump $'release:minor\nrelease:major\n\n'
expect "minor beats unlabelled" minor bump $'\nrelease:minor'
expect "all no-release" none bump $'no-release\nno-release'
expect "no-release with other change" patch bump $'no-release\n\n'
expect "size label beats no-release" minor bump 'no-release,release:minor'
expect_warning "conflicting labels warn" bump 'no-release,release:minor'

if (( failures )); then
    echo "$failures failed"
    exit 1
fi
echo "all passed"
