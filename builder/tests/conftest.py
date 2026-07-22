"""Make the ``lightbuilder`` package importable when running ``pytest`` from
either the repo root or the ``builder/`` directory.
"""

from __future__ import annotations

import sys
from pathlib import Path

BUILDER_ROOT = Path(__file__).resolve().parent.parent
if str(BUILDER_ROOT) not in sys.path:
    sys.path.insert(0, str(BUILDER_ROOT))
