"""Command-line entry point invoked by the container's shell wrapper.

Two subcommands:

* ``prepare`` — given a checked-out dev repo on disk, run the whitelist
  extraction into the baked-in SDK's tool/ module. The SDK's Gradle plugin
  reads the extracted ``lighttool.toml`` at configure time and generates the
  AndroidManifest.xml + applies applicationId/versionCode/versionName. Nothing
  else happens here.

* ``collect`` — after gradle finishes, locate the unsigned APK, copy it to
  the output dir, and emit ``recipe.json`` with the SHA-256 and every input
  that fed the build.

The split exists so the shell can run gradle between the two Python phases
without Python managing subprocess lifecycle for a long-running JVM. This
module never makes a network call.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import zipfile
from pathlib import Path

from . import extract, recipe


def cmd_prepare(args: argparse.Namespace) -> int:
    try:
        report = extract.extract(
            args.dev_repo,
            args.workspace_tool,
            tool_subpath=args.tool_path,
        )
    except extract.ExtractionError as e:
        _emit_error(args.output_dir, "policy_violation", str(e))
        return 2

    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "extraction.json").write_text(
        json.dumps(
            {"files": report.files_copied, "bytes": report.bytes_copied},
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )
    _write_source_zip(
        args.output_dir / "extracted-source.zip",
        tool_root=args.workspace_tool,
        report=report,
    )
    return 0


def _write_source_zip(
    zip_path: Path,
    *,
    tool_root: Path,
    report: extract.ExtractionReport,
) -> None:
    """Bundle the exact files the extractor accepted into a zip.

    Contents mirror the staged tool module layout (build.gradle.kts,
    lighttool.toml, src/main/**), so the zip is a faithful copy of the source
    that fed gradle — nothing more, nothing less than what extraction.json
    lists. Written deterministically (sorted entries, fixed timestamp/mode) so
    two builds of the same commit produce a byte-identical archive.
    """
    entries = sorted(
        (dest.relative_to(tool_root).as_posix(), dest)
        for dest in report.dest_paths
    )
    with zipfile.ZipFile(zip_path, "w") as zf:
        for arcname, dest in entries:
            info = zipfile.ZipInfo(arcname, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, dest.read_bytes())


def cmd_collect(args: argparse.Namespace) -> int:
    apk = _find_unsigned_apk(args.workspace)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    out_apk = args.output_dir / "tool-unsigned.apk"
    shutil.copyfile(apk, out_apk)

    extracted_files: tuple[str, ...] = ()
    report_path = args.output_dir / "extraction.json"
    if report_path.exists():
        extracted_files = tuple(json.loads(report_path.read_text())["files"])

    result = recipe.write(
        artifact=out_apk,
        inputs=recipe.BuildInputs(
            image_digest=args.image_digest,
            sdk_git_ref=args.sdk_git_ref,
            dev_git_url=args.dev_git_url,
            dev_git_ref=args.dev_git_ref,
            dev_git_commit=args.dev_git_commit,
            gradle_command=tuple(json.loads(args.gradle_command)),
            source_date_epoch=args.source_date_epoch,
            extracted_files=extracted_files,
        ),
        dest=args.output_dir / "recipe.json",
    )
    print(result["artifact"]["sha256"])
    return 0


def _find_unsigned_apk(workspace: Path) -> Path:
    candidate = workspace / "tool" / "build" / "outputs" / "apk" / "release"
    if not candidate.is_dir():
        raise SystemExit(f"no apk output dir at {candidate}")
    # With -DlightSdk.unsigned=true the plugin clears signingConfig so AGP
    # emits the artifact under its `*-unsigned.apk` name. Accept either name
    # so the container's behaviour doesn't silently differ if that flag is
    # ever forgotten — but warn loudly if we see a signed APK, since a
    # signing service should not be operating on already-signed input.
    for pattern in ("*-unsigned.apk", "*-release.apk", "*.apk"):
        apks = sorted(candidate.glob(pattern))
        if apks:
            if len(apks) > 1:
                raise SystemExit(f"expected one apk, found {len(apks)} in {candidate}")
            apk = apks[0]
            if "unsigned" not in apk.name:
                print(
                    f"warning: apk {apk.name} is not marked unsigned; "
                    "did you forget -DlightSdk.unsigned=true?",
                    file=sys.stderr,
                )
            return apk
    raise SystemExit(f"no apk found under {candidate}")


def _emit_error(output_dir: Path, kind: str, message: str) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "error.json").write_text(
        json.dumps({"kind": kind, "message": message}, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"{kind}: {message}", file=sys.stderr)


def _parse(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="lightbuilder")
    sub = p.add_subparsers(dest="cmd", required=True)

    prep = sub.add_parser("prepare", help="extract dev source into the workspace")
    prep.add_argument("--dev-repo", type=Path, required=True)
    prep.add_argument("--workspace-tool", type=Path, required=True,
                      help="path to the baked-in SDK's tool/ module")
    prep.add_argument("--tool-path", default="tool",
                      help="path inside the dev repo where the tool lives "
                           "(default: tool; use '.' for repo root)")
    prep.add_argument("--output-dir", type=Path, required=True)

    coll = sub.add_parser("collect", help="hash artifact + write recipe.json")
    coll.add_argument("--workspace", type=Path, required=True)
    coll.add_argument("--output-dir", type=Path, required=True)
    coll.add_argument("--image-digest", required=True)
    coll.add_argument("--sdk-git-ref", required=True)
    coll.add_argument("--dev-git-url", required=True)
    coll.add_argument("--dev-git-ref", required=True)
    coll.add_argument("--dev-git-commit", required=True)
    coll.add_argument("--gradle-command", required=True, help="JSON-encoded argv array")
    coll.add_argument("--source-date-epoch", type=int, required=True)

    ns = p.parse_args(argv)
    for attr in ("dev_repo", "workspace_tool", "output_dir", "workspace"):
        if hasattr(ns, attr) and getattr(ns, attr) is not None:
            setattr(ns, attr, getattr(ns, attr).resolve())
    return ns


def main(argv: list[str] | None = None) -> int:
    args = _parse(argv)
    if args.cmd == "prepare":
        return cmd_prepare(args)
    if args.cmd == "collect":
        return cmd_collect(args)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
