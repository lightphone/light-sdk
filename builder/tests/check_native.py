"""Check an APK's native libraries against its build inventory."""

from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path


def main(apk: Path, inventory: Path) -> None:
    approved = json.loads(inventory.read_text(encoding="utf-8"))["libraries"]
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise SystemExit("duplicate APK entries")
        libs = [name for name in names if name.startswith("lib/")]
        unapproved = [
            name for name in libs
            if hashlib.sha256(archive.read(name)).hexdigest() not in approved.get(name, [])
        ]
    abis = sorted({name.split("/")[1] for name in libs})
    print(f"   {len(libs)} native libraries, ABIs {abis}, unapproved {unapproved}")
    if unapproved:
        raise SystemExit("unapproved native libraries")
    if abis not in ([], ["arm64-v8a"]):
        raise SystemExit(f"unexpected ABIs: {abis}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("inventory", type=Path)
    args = parser.parse_args()
    main(args.apk, args.inventory)
