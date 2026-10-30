"""Exporte des frames réelles du laboratoire moléculaire (pour les aperçus hors écran du client).

    python tools/export_molecule_frames.py <dossier>

Chaque scénario lance le vrai laboratoire, attend l'état voulu, et écrit <dossier>/<nom>.pb (UniverseFrame).
"""

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import genesis_pb2  # noqa: E402
from moleculelab import frame as molecule_frame  # noqa: E402
from moleculelab.lab import MoleculeLab  # noqa: E402


def wait(lab, condition, limit=120.0):
    start = time.time()
    while time.time() - start < limit:
        snap, grid = lab.snapshot()
        if snap is not None and condition(snap, grid):
            return snap, grid
        time.sleep(0.05)
    raise SystemExit("état non atteint")


def save(lab, folder, name):
    frame = genesis_pb2.UniverseFrame()
    molecule_frame.fill(frame.molecule_lab, lab, {})
    (folder / f"{name}.pb").write_bytes(frame.SerializeToString())
    print(name, len(frame.molecule_lab.atoms), "atomes", frame.molecule_lab.grid.version, "grille")


def settled(kind, label=None):
    def check(s, g):
        return (s["relaxed"] and g is not None and g["kind"] == kind and len(g["positions"]) == len(s["positions"])
                and (label is None or s["label"].startswith(label)) and "energy" in s
                and abs(g["positions"] - s["positions"]).max() < 1e-6)
    return check


def main():
    folder = Path(sys.argv[1])
    folder.mkdir(parents=True, exist_ok=True)
    lab = MoleculeLab(rng_seed=2)
    try:
        wait(lab, lambda s, g: s.get("validation") is not None and s["relaxed"], 180)
        wait(lab, settled(0, "H₂O"))
        time.sleep(0.4)
        wait(lab, settled(0, "H₂O"))
        save(lab, folder, "water")
        lab.set_view(1)
        wait(lab, settled(1, "H₂O"))
        save(lab, folder, "water_deformation")
        lab.set_view(2, -1)
        wait(lab, settled(2, "H₂O"))
        save(lab, folder, "water_homo")
        lab.set_view(0)
        lab.load_preset(8)
        wait(lab, settled(0, "C₂H₄"))
        time.sleep(0.4)
        wait(lab, settled(0, "C₂H₄"))
        save(lab, folder, "ethylene")
        for index, name, label in ((5, "nitrogen", "N₂"), (4, "carbon_dioxide", "CO₂"), (10, "formaldehyde", "CH₂O"),
                                   (7, "hcl", "HCl")):
            lab.load_preset(index)
            wait(lab, settled(0, label))
            time.sleep(0.4)
            wait(lab, settled(0, label))
            save(lab, folder, name)
        lab.load_preset(13)
        wait(lab, settled(0, "C₆H₆"), 240)
        time.sleep(0.4)
        wait(lab, settled(0, "C₆H₆"), 240)
        save(lab, folder, "benzene")
        lab.load_preset(0)
        wait(lab, settled(0, "H₂"))
        lab.drag_atom(1, [0.0, 0.0, 1.6])
        time.sleep(3.0)
        save(lab, folder, "h2_pulled")
        lab.release()
        lab.load_preset(2)
        wait(lab, settled(0, "CH₄"))
        lab.compute_modes()
        wait(lab, lambda s, g: "frequencies" in s and not s["computing_modes"])
        time.sleep(0.5)
        save(lab, folder, "methane_modes")
        lab.load_reaction(0)
        lab.set_dynamics(1, 300)
        time.sleep(4.0)
        save(lab, folder, "reactor")
    finally:
        lab.close()


if __name__ == "__main__":
    main()
