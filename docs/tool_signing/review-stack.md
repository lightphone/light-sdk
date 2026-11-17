# Tracks 1+2 review stack

Baseline: `main` at `e81295e6bc29f987c44c656fdf5081117bbee833`.
Validated source: `integration/tool-signing-tracks-1-2` at `f05392f`.
Original track refs are preserved. No publication or root acceptance is implied.

## Slice 1: admission and policy contracts

Branch: `review/tool-signing-admission-contracts`, parent: baseline above.
Copied final admission persistence, fingerprint/migration and purge-precedence
contracts with their tests and documentation from the validated source.

Validation: `:sdk:trust:test :sdk:trust:checkTrustIsolation --offline` passed
with Zulu JDK 17. `git diff --check` passed.

Next: artifact verification, followed by runtime evaluation, bundle persistence,
installation, transport, enforcement, and host wiring. Each boundary must build
without forward references. Compare the final tree to `f05392f`, excluding this
progress document. Recovery protocol and physical LP3 validation remain open.
