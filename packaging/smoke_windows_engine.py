"""Vérifie le vrai exécutable gelé, ses données nucléaires et son service gRPC."""

import os
import socket
import subprocess
import sys
import time
from pathlib import Path

import grpc

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "logic"))
if len(sys.argv) > 2:
    sys.path.insert(0, str(Path(sys.argv[2]).resolve()))
import genesis_pb2 as pb  # noqa: E402
import genesis_pb2_grpc as rpc  # noqa: E402


def main():
    executable = Path(sys.argv[1]).resolve()
    if not executable.is_file():
        raise FileNotFoundError(executable)
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    environment = os.environ.copy()
    environment.update(GRPC_HOST="127.0.0.1", GRPC_PORT=str(port))
    environment.pop("MONGO_URI", None)
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    process = subprocess.Popen([str(executable)], env=environment,
                               cwd=executable.parent, creationflags=flags)
    try:
        deadline = time.monotonic() + 120
        last_error = None
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            stub = rpc.UniverseEngineStub(channel)
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"Le moteur s'est arrêté avec le code {process.returncode}")
                try:
                    table = stub.GetPeriodicTable(pb.TableRequest(), timeout=3)
                    if table.protocol_version != 25 or len(table.elements) != 118:
                        raise AssertionError("Table périodique ou protocole incomplet")
                    print(f"Moteur autonome OK : {len(table.elements)} éléments, protocole {table.protocol_version}")
                    return
                except grpc.RpcError as error:
                    last_error = error
                    time.sleep(1)
        raise TimeoutError(f"Le moteur n'a pas répondu : {last_error}")
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


if __name__ == "__main__":
    main()
