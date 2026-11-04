#!/usr/bin/env bash
# Reads one line per unreleased change on stdin (comma-separated PR labels;
# empty for a change without labels or without a PR) and prints the overall
# bump: none | patch | minor | major. The largest bump wins; "none" only when
# there are no changes or every change is labelled no-release.

set -euo pipefail

rank=0
levels=(none patch minor major)

while IFS= read -r line || [[ -n "$line" ]]; do
    IFS=, read -ra labels <<<"$line"
    level=1
    skip=0
    for label in ${labels[@]+"${labels[@]}"}; do
        case "$label" in
            release:major) (( level < 3 )) && level=3 ;;
            release:minor) (( level < 2 )) && level=2 ;;
            no-release) skip=1 ;;
        esac
    done
    if (( skip && level > 1 )); then
        echo "::warning::change has both no-release and a release label; using ${levels[level]}" >&2
    elif (( skip )); then
        level=0
    fi
    (( level > rank )) && rank=$level
done

echo "${levels[rank]}"
