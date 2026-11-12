"""Exercise failure cleanup through the host-side build driver's CLI."""

from __future__ import annotations

import os
import subprocess
import sys
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ACCESS = '2026-11-04T13:34:58Z HEAD /plugin.gradle.plugin/1/plugin.gradle.plugin-1.jar 404 0 "upstream" "404"'
ARTIFACT = '2026-11-04T13:34:59Z GET /example/1/example-1.aar 200 100 "upstream" "200"'
UPSTREAM_FAILURE = '2026-11-04T13:35:00Z GET /example/2/example-2.aar 502 0 "upstream" "502"'
DIAGNOSTIC = "nginx: upstream diagnostic"
# More access lines than any console tail, so diagnostics must not depend on
# being among the most recent combined log lines.
STDOUT = [ACCESS] * 200 + [UPSTREAM_FAILURE] + [ARTIFACT] * 200


def test_compile_failure_keeps_diagnostics_and_full_proxy_logs(
    tmp_path: Path,
) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    docker = bin_dir / "docker"
    docker.write_text(
        f"#!{sys.executable}\n"
        + textwrap.dedent(
            f"""
            import sys
            args = sys.argv[1:]
            if args[0] == 'logs':
                if '--tail' in args:
                    sys.exit('build.sh must not tail combined docker logs')
                print({DIAGNOSTIC!r}, file=sys.stderr)
                print('\\n'.join({STDOUT!r}))
            elif args[0] == 'inspect':
                print('proxy exit status: 0 (running)')
            elif args[0] == 'run' and '--git-url' in args:
                print('e: HomeScreen.kt:141:23 Initializer type mismatch', file=sys.stderr)
                sys.exit(42)
            elif args[0] == 'run' and 'https://repo.maven.apache.org/' in args:
                sys.exit(1)
            """
        )
    )
    docker.chmod(0o755)
    output = tmp_path / "output"
    result = subprocess.run(
        [
            "bash",
            str(ROOT / "builder/bin/build.sh"),
            "--image",
            "builder:test",
            "--dev-repo",
            str(ROOT),
            "--output-dir",
            str(output),
        ],
        env={**os.environ, "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}"},
        check=False,
        capture_output=True,
        text=True,
        timeout=15,
    )

    assert result.returncode == 42
    assert "Initializer type mismatch" in result.stderr
    assert DIAGNOSTIC in result.stderr
    assert UPSTREAM_FAILURE in result.stderr
    assert ACCESS not in result.stderr + result.stdout
    assert ARTIFACT not in result.stderr + result.stdout
    assert (output / "proxy-access.log").read_text() == "\n".join(STDOUT) + "\n"
    assert (output / "proxy-error.log").read_text() == f"{DIAGNOSTIC}\n"
