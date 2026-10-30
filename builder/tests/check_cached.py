"""Check a BuildKit --progress=plain log for stage steps that were rebuilt.

The bundle build in CI runs right after the image build and must reuse its
layers, so the bundle comes from the same toolchain and warm-up as the image.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

STEP = re.compile(r"^#(\d+) \[(?:[\w.-]+/[\w.-]+ )?([\w.-]+) \d+/\d+\] (.*)$")
CACHED = re.compile(r"^#(\d+) CACHED$")


def uncached_steps(log: str, stages: set[str]) -> tuple[list[str], set[str]]:
    """Return rebuilt steps of ``stages`` and the stages with no steps at all."""
    steps: dict[str, str] = {}
    seen: set[str] = set()
    cached: set[str] = set()
    for line in log.splitlines():
        if match := STEP.match(line):
            step_id, stage, instruction = match.groups()
            if stage in stages:
                seen.add(stage)
                # FROM steps resolve the base image and report no cache state.
                if not instruction.startswith("FROM "):
                    steps[step_id] = f"[{stage}] {instruction}"
        elif match := CACHED.match(line):
            cached.add(match.group(1))
    rebuilt = [step for step_id, step in steps.items() if step_id not in cached]
    return rebuilt, stages - seen


def main(log: Path, stages: list[str]) -> None:
    rebuilt, missing = uncached_steps(log.read_text(encoding="utf-8"), set(stages))
    if missing:
        raise SystemExit(f"no steps found for stages: {', '.join(sorted(missing))}")
    if rebuilt:
        raise SystemExit("steps rebuilt instead of cached:\n  " + "\n  ".join(rebuilt))
    print(f"all steps cached for: {', '.join(stages)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("stages", nargs="+")
    args = parser.parse_args()
    main(args.log, args.stages)
