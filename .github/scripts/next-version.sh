#!/usr/bin/env bash
# Prints the next release version, or "skip" when there is nothing to release.
#
# usage: next-version.sh TAG_VERSION SDK_VERSION BUMP
#   TAG_VERSION  latest vX.Y.Z tag without the "v", or "" when there are none
#   SDK_VERSION  sdkVersion from gradle.properties
#   BUMP         none | patch | minor | major

set -euo pipefail

semver='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

tag="$1"
current="$2"
bump="$3"

if [[ ! "$current" =~ $semver ]]; then
    echo "sdkVersion '$current' is not X.Y.Z" >&2
    exit 1
fi
if [[ -n "$tag" && ! "$tag" =~ $semver ]]; then
    echo "tag version '$tag' is not X.Y.Z" >&2
    exit 1
fi

# Exit status 0 when $1 > $2.
newer() {
    local a b i
    IFS=. read -ra a <<<"$1"
    IFS=. read -ra b <<<"$2"
    for i in 0 1 2; do
        (( a[i] > b[i] )) && return 0
        (( a[i] < b[i] )) && return 1
    done
    return 1
}

case "$bump" in
    none) echo skip; exit 0 ;;
    patch | minor | major) ;;
    *) echo "unknown bump '$bump'" >&2; exit 1 ;;
esac

if [[ -z "$tag" ]]; then
    echo "$current"
    exit 0
fi

IFS=. read -r major minor patch <<<"$tag"
case "$bump" in
    patch) candidate="$major.$minor.$((patch + 1))" ;;
    minor) candidate="$major.$((minor + 1)).0" ;;
    major) candidate="$((major + 1)).0.0" ;;
esac

if newer "$tag" "$current"; then
    echo "::warning::sdkVersion $current is behind latest tag v$tag; releasing $candidate" >&2
fi

if newer "$current" "$candidate"; then
    echo "$current"
else
    echo "$candidate"
fi
