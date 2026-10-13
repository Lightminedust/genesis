r"""Exporte des frames réelles de l'écran de l'atome, pour l'aperçu 3D (gradlew atomPreview).

Aucun serveur : une session du moteur, un élément chargé, la frame telle
qu'elle serait envoyée au client.

Exemple (depuis logic) :
  venv\Scripts\python.exe -m tools.export_atom_frames ..\artifacts\frames
"""
import os
import sys

from engine import session_frame
from simulator.repository import ElementRepository
from simulator.simulation import Session

# nom → (Z, état (n, l) imposé à l'électron unique, atome excité, électrons retirés)
CASES = {
    "boron": (5, None, False),
    "carbon": (6, None, False),
    "nitrogen": (7, None, False),
    "oxygen": (8, None, False),
    "neon": (10, None, False),
    "scandium": (21, None, False),
    "iron": (26, None, False),
    "praseodymium": (59, None, False),
    "uranium": (92, None, False),
    "hydrogen": (1, None, False),
    "hydrogen3d": (1, (3, 2), False),
    "sodium_excited": (11, None, True),
    "hydrogen2p": (1, (2, 1), False),
    "sodium": (11, None, False),
    "sodium_ion": (11, None, False, 1),
    "uranium_ion": (92, None, False, 1),
}


def main(out):
    os.makedirs(out, exist_ok=True)
    repository = ElementRepository()
    for name, case in CASES.items():
        z, state, excited = case[:3]
        session = Session(repository, seed=3)
        session.load_element(z)
        for _ in range(case[3] if len(case) > 3 else 0):
            session.remove_electron()
        if state is not None:
            session.electron_state = state
        if excited:
            # La raie D du sodium : 589 nm, 16 ns (ce que mettrait un photon absorbé).
            session.excited = {"line": 589.0, "lifetime_s": 16e-9, "cause": "photon"}
        evaluation = session.evaluate("LOAD_ELEMENT")
        frame = session_frame(1, session, evaluation, repository.source, [], "LOAD_ELEMENT", True)
        with open(os.path.join(out, name + ".bin"), "wb") as handle:
            handle.write(frame.SerializeToString())
        print(name, len(frame.subshells), "sous-couches,", len(frame.nucleons), "nucléons")

    # Une désintégration réelle : l'uranium 238 jusqu'à son prochain événement (tiré au hasard).
    session = Session(repository, seed=3)
    session.load_element(92)
    first = session_frame(1, session, session.evaluate("LOAD_ELEMENT"), repository.source, [],
                          "LOAD_ELEMENT", True)
    with open(os.path.join(out, "uranium_now.bin"), "wb") as handle:
        handle.write(first.SerializeToString())
    events = session.next_event()
    frame = session_frame(2, session, session.evaluate("NEXT_EVENT"), repository.source, events, "NEXT_EVENT", True)
    with open(os.path.join(out, "uranium_decay.bin"), "wb") as handle:
        handle.write(frame.SerializeToString())
    print("désintégration :", frame.nucleus.nuclide, [e.kind for e in frame.events])


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join("..", "artifacts", "frames"))
