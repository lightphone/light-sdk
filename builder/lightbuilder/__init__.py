"""Light SDK tool builder.

Takes a developer's git ref, extracts an allowlisted subset of files into a
clean, baked-in copy of the SDK, and produces an unsigned APK plus a recipe
that captures every input that went into it.

The package is intentionally stdlib-only so the container image stays small
and there is nothing to audit on `pip install`.
"""

__version__ = "0.1.0"
