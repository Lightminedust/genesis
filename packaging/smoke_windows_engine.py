"""Vérifie le vrai exécutable gelé, ses données nucléaires et son service gRPC."""

import os
import re
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

# Le numéro du contrat que le moteur doit annoncer : celui des sources, lu sans importer le moteur.
EXPECTED_PROTOCOL = int(re.search(r"^PROTOCOL_VERSION\s*=\s*(\d+)", (ROOT / "logic" / "engine.py").read_text(encoding="utf-8"),
                                  re.MULTILINE).group(1))


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
                    if table.protocol_version != EXPECTED_PROTOCOL or len(table.elements) != 118:
                        raise AssertionError("Table périodique ou protocole incomplet")
                    print(f"Moteur autonome OK : {len(table.elements)} éléments, protocole {table.protocol_version}")
                    k = chain_lab(stub)
                    print(f"Réaction en chaîne OK : bibliothèque ENDF embarquée, k_eff = {k:.4f}")
                    energy, atoms = molecule_lab(stub)
                    print(f"Laboratoire moléculaire OK : paramètres GFN1-xTB embarqués, {atoms} atomes, E = {energy:.3f} eV")
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


def chain_lab(stub):
    """Ouvre le laboratoire de la réaction en chaîne : la bibliothèque de groupes doit être dans le paquet."""
    def actions():
        yield pb.UserAction(type=pb.UserAction.HELLO)
        yield pb.UserAction(type=pb.UserAction.CHAIN_START)
        time.sleep(120)

    responses = stub.RunSimulation(actions(), timeout=120)
    try:
        for frame in responses:
            chain = frame.chain
            if chain.error:
                raise AssertionError(f"Laboratoire de la réaction en chaîne : {chain.error}")
            if chain.active and chain.ready and chain.k_eff > 0:
                return chain.k_eff
    finally:
        responses.cancel()
    raise AssertionError("Le laboratoire de la réaction en chaîne n'a rendu aucun k_eff")


def molecule_lab(stub):
    """Ouvre le laboratoire moléculaire : les paramètres GFN1-xTB et les tables D3 doivent être dans le paquet."""
    def actions():
        yield pb.UserAction(type=pb.UserAction.HELLO)
        yield pb.UserAction(type=pb.UserAction.MOLECULE_START)
        time.sleep(120)

    responses = stub.RunSimulation(actions(), timeout=120)
    try:
        for frame in responses:
            lab = frame.molecule_lab
            if lab.error:
                raise AssertionError(f"Laboratoire moléculaire : {lab.error}")
            if lab.active and lab.ready and lab.atoms and lab.energy < 0:
                return lab.energy, len(lab.atoms)
    finally:
        responses.cancel()
    raise AssertionError("Le laboratoire moléculaire n'a rendu aucune énergie")


if __name__ == "__main__":
    main()
