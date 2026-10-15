"""Entrée du moteur Windows : pas de console, mais un journal d'erreurs lisible."""

import os
import sys
import traceback
from pathlib import Path


def main():
    local = os.environ.get("LOCALAPPDATA")
    data = Path(local) / "Genesis" if local else Path.home() / "AppData" / "Local" / "Genesis"
    logs = data / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    stream = (logs / "engine.log").open("a", encoding="utf-8", buffering=1)
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
