"""Extrait les tables numériques de GFN1-xTB vers moleculelab/data/xtb-data.npz (le moteur n'en lit pas d'autre).

    python tools/build_xtb_data.py <dossier où sont installés dxtb, tad-mctc et tad-dftd3>

Les tables viennent des implémentations de référence (dxtb, tad-mctc, tad-dftd3 ; licence Apache-2.0), qui les
tiennent elles-mêmes de xtb, tblite et s-dftd3 (LGPL-3.0) :
  - les développements STO-NG des orbitales de Slater (Stewart, J. Chem. Phys. 52, 431, 1970) ;
  - les rayons covalents de D3 (Pyykkö et Atsumi 2009, métaux −10 %) et les rayons atomiques du Hamiltonien ;
  - les coefficients C6 de référence de DFT-D3 (Grimme et al. 2010) et leurs nombres de coordination, ⟨r⁴⟩/⟨r²⟩.
Les paramètres de GFN1-xTB eux-mêmes sont dans data/gfn1-xtb.toml (Grimme, Bannwarth, Shushkov, JCTC 2017).
"""

import sys
from pathlib import Path

import numpy as np

MAX_Z = 86


def main():
    sys.path.insert(0, sys.argv[1])
    import torch
    from tad_mctc.data import radii
    from tad_dftd3.data.r4r2 import R4R2
    from tad_dftd3.reference import Reference
    import dxtb
    base = Path(dxtb.__file__).parent / "_src" / "basis" / "sto-ng"
    arrays = {f"sto{n}g": np.load(base / f"sto-{n}g.npy") for n in range(1, 7)}
    arrays["cov_d3"] = radii.COV_D3(dtype=torch.double)[: MAX_Z + 1].numpy()
    arrays["atomic_radii"] = radii.ATOMIC_RADII(dtype=torch.double)[: MAX_Z + 1].numpy()
    arrays["r4r2"] = R4R2(dtype=torch.double)[: MAX_Z + 1].numpy()
    ref = Reference(dtype=torch.double)
    arrays["d3_cn"] = ref.cn[: MAX_Z + 1].numpy()
    arrays["d3_c6"] = ref.c6[: MAX_Z + 1, : MAX_Z + 1].numpy().astype(np.float32)
    out = Path(__file__).resolve().parent.parent / "moleculelab" / "data" / "xtb-data.npz"
    out.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(out, **arrays)
    print(out, f"{out.stat().st_size / 1024:.0f} Ko", {k: v.shape for k, v in arrays.items()})


if __name__ == "__main__":
    main()
