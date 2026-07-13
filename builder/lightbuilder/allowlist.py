"""Allowlists and validation regexes.

Centralized so policy changes happen in one place and tests can import the
same constants the runtime uses. App metadata validation (applicationId, versionCode, permission list,
manifest contents) lives in the Kotlin Gradle plugin. This
file only governs which files are to be copied from the dev's repo.
"""

from __future__ import annotations

import re

ALLOWED_SOURCE_EXTENSIONS: frozenset[str] = frozenset({".kt", ".java"})
ALLOWED_RES_EXTENSIONS: frozenset[str] = frozenset(
    {".xml", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".json", ".ttf", ".otf"}
)
ALLOWED_ASSET_EXTENSIONS: frozenset[str] = frozenset(
    {".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".json", ".txt", ".md",
     ".ttf", ".otf", ".bin", ".dat", ".csv", ".html", ".css"}
)

# Subdirectories of tool/src/main that the extractor reads from. Each maps
# to its extension allowlist.
EXTRACTION_TREES: dict[str, frozenset[str]] = {
    "kotlin": ALLOWED_SOURCE_EXTENSIONS,
    "java": ALLOWED_SOURCE_EXTENSIONS,
    "res": ALLOWED_RES_EXTENSIONS,
    "assets": ALLOWED_ASSET_EXTENSIONS,
}

# Files allowed at the root of the dev's tool/ module (not under src/main).
# These are the dev-controlled configuration files that the plugin reads.
ALLOWED_TOOL_ROOT_FILES: frozenset[str] = frozenset({
    "build.gradle.kts",
    "lighttool.toml",
})

# Path components that, if seen anywhere under the extraction tree, abort the
# build. META-INF is the classic Java SPI smuggling vector; .git/.svn would
# let a malicious repo embed history into the artifact.
FORBIDDEN_PATH_COMPONENTS: frozenset[str] = frozenset(
    {"META-INF", ".git", ".svn", ".hg", "__MACOSX"}
)

# Hard cap on any single file.
MAX_FILE_SIZE_BYTES: int = 5 * 1024 * 1024

# Hard cap on the total extracted payload.
MAX_TOTAL_EXTRACTED_BYTES: int = 100 * 1024 * 1024

# Hard cap on the number of files extracted.
MAX_FILE_COUNT: int = 10_000

# Patterns we refuse to see in the dev's tool/build.gradle.kts even before
# letting gradle execute it. Mirrors the Kotlin plugin's universal banlist;
# defence-in-depth so an obviously-malicious build script never gets a
# chance to run.
BUILD_SCRIPT_FORBIDDEN_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\bbuildscript\s*\{"), "buildscript {} block not allowed"),
    (re.compile(r"\bresolutionStrategy\b"), "resolutionStrategy not allowed"),
    (re.compile(r"\bdependencySubstitution\b"), "dependencySubstitution not allowed"),
    (re.compile(r"\bapply\s*\(\s*plugin"), "apply(plugin = ...) not allowed"),
    (re.compile(r"\bapply\s*\(\s*from"), "apply(from = ...) not allowed"),
    (re.compile(r"\bapply\s*<"), "apply<...>() not allowed"),
)
