from __future__ import annotations

import sys
from pathlib import Path

SIGNER_ROOT = Path(__file__).resolve().parent.parent
if str(SIGNER_ROOT) not in sys.path:
    sys.path.insert(0, str(SIGNER_ROOT))
