"""Exporte des frames du laboratoire de la réaction en chaîne, pour les aperçus JavaFX sans serveur.

    python tools/export_chain_frames.py [dossier]   (défaut : ../artifacts/chain)
"""

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import genesis_pb2  # noqa: E402
from chainlab import frame as chain_frame  # noqa: E402
from chainlab.lab import ChainLab  # noqa: E402


def wait(lab):
    time.sleep(0.05)
    while lab.computing:
        time.sleep(0.02)


def write(lab, folder, name):
    message = genesis_pb2.UniverseFrame()
    message.protocol_version = 28
    chain_frame.fill(message.chain, lab, {})
    (folder / f"{name}.pb").write_bytes(message.SerializeToString())
    counts = [round(c) for c in message.chain.generation_counts]
    print(name, f"k={message.chain.k_eff:.4f}", message.chain.regime, len(message.chain.tracks), "traces", counts)


def shoot(lab, position=(0.0, 0.0, 0.0), count=1, survive=False, tries=40):
    """Un tir ; avec `survive`, on tire jusqu'à ce que la chaîne tienne jusqu'au bout de l'échantillon."""
    for _ in range(tries):
        lab.fire(position=list(position), count=count)
        wait(lab)
        if not survive or lab.shot["counts"][-1] > 0:
            return


def main():
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2] / "artifacts" / "chain"
    folder.mkdir(parents=True, exist_ok=True)
    lab = ChainLab(11)
    wait(lab)
    keys = [c.key for c in lab.cores]
    write(lab, folder, "subcritical")
    shoot(lab, (1.0, 0.5, 0.0))
    write(lab, folder, "subcritical_shot")
    lab.set_compression(1.8)
    lab.set_radius(7.0 / 1.8 ** (1 / 3))
    wait(lab)
    shoot(lab, survive=True)
    write(lab, folder, "compressed_shot")
    lab.set_compression(1.0)
    lab.set_radius(9.5)
    wait(lab)
    shoot(lab, count=10, survive=True)
    write(lab, folder, "dispersal")
    lab.set_core(keys.index("Fe56"))
    lab.set_radius(9.0)
    wait(lab)
    shoot(lab, (0.0, 0.0, 0.0), count=3)
    write(lab, folder, "iron")
    lab.set_core(keys.index("U235"))
    lab.set_reflector(0, 8.0)
    lab.set_radius(6.0)
    wait(lab)
    shoot(lab, count=3, survive=True)
    write(lab, folder, "beryllium")
    lab.set_reflector(-1)
    lab.set_mode(1)
    for code, name in ((26056, "sandbox_fe56"), (92235, "sandbox_u235"), (6012, "sandbox_c12"), (82208, "sandbox_pb208")):
        lab.set_nuclide(code)
        wait(lab)
        write(lab, folder, name)
    lab.close()


if __name__ == "__main__":
    main()
