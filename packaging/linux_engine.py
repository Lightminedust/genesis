"""Entry point for the frozen Linux engine; keep its diagnostics in an XDG log."""

import os
import sys
import traceback
from pathlib import Path


def main():
    state_home = os.environ.get("XDG_STATE_HOME")
    state_dir = Path(state_home) if state_home and Path(state_home).is_absolute() else Path.home() / ".local" / "state"
    log = Path(os.environ.get("GENESIS_ENGINE_LOG", state_dir / "genesis" / "logs" / "engine.log"))
    log.parent.mkdir(parents=True, exist_ok=True)
    stream = log.open("a", encoding="utf-8", buffering=1)
    sys.stdout = stream
    sys.stderr = stream

    from engine import serve

    serve()


if __name__ == "__main__":
    try:
        main()
    except BaseException:
        traceback.print_exc()
        sys.exit(1)
