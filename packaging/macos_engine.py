"""Entry point for the frozen macOS engine; keep its diagnostics in a Library log."""

import os
import sys
import traceback
from pathlib import Path


def main():
    log = Path(os.environ.get(
        "GENESIS_ENGINE_LOG",
        Path.home() / "Library" / "Logs" / "Genesis" / "engine.log",
    ))
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
