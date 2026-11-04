#!/usr/bin/env bash
# Prints one line per unreleased change in SINCE_REF..HEAD_REF, following
# first parents (empty SINCE_REF: all history; HEAD_REF defaults to HEAD):
# the comma-separated labels of the PR that merged it, or an empty line for a
# direct push. Release commits made by the bot are left out.
# Feed the output to release-bump.sh.
#
# usage: release-labels.sh [SINCE_REF] [HEAD_REF]
# Needs GITHUB_REPOSITORY and an authenticated gh.

set -euo pipefail

# Keep in sync with the `paths` filters in release.yml and release-preview.yml.
paths=(sdk plugin tool builder lint-rules signer trust-format gradle.properties
    gradle/libs.versions.toml build.gradle.kts settings.gradle.kts)

head="${2:-HEAD}"
range="$head"
[[ -n "${1:-}" ]] && range="$1..$head"

git log --first-parent --format='%H%x09%an%x09%s' "$range" -- "${paths[@]}" |
    while IFS=$'\t' read -r sha author subject; do
        if [[ "$author" == *'[bot]' && "$subject" =~ ^chore:\ release\ [0-9] ]]; then
            continue
        fi
        gh api "repos/$GITHUB_REPOSITORY/commits/$sha/pulls" --jq '
            [.[] | select(.merged_at != null)][0].labels // []
            | map(.name) | join(",")'
    done
