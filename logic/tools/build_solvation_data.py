"""Construit moleculelab/data/gfn1-alpb.json : les paramètres du solvant implicite ALPB de GFN1-xTB.

Les valeurs sont celles de xtb et tblite (Ehlert, Stahn, Spicher, Grimme, J. Chem. Theory Comput. 17, 4250, 2021 ;
LGPL-3.0, comme gfn1-xtb.toml), lues dans leurs sources Fortran à des commits fixés :

- tblite : src/tblite/solvation/data/{alpb,cds,shift}/param_alpb_<solvant>.fh (les jeux « gfn1_alpb_ ») et les
  rayons de van der Waals D3 (src/tblite/solvation/data.f90), les paramètres CM5 (src/tblite/solvation/cm5.f90) ;
- mctc-lib : les rayons atomiques de CM5 (src/mctc/data/atomicrad.f90).

    venv\\Scripts\\python tools/build_solvation_data.py
"""

import json
import re
import sys
import urllib.request
from pathlib import Path

TBLITE = "https://raw.githubusercontent.com/tblite/tblite/c6f66b93f7b3ed5db77698021822eaedb8074ce6/src/tblite/solvation/"
MCTC = "https://raw.githubusercontent.com/grimme-lab/mctc-lib/71a21af5de3bf3e08d1e70bc91848fd539f90173/src/mctc/data/"
SOLVENTS = ["acetone", "acetonitrile", "aniline", "benzaldehyde", "benzene", "ch2cl2", "chcl3", "cs2", "dioxane", "dmf",
            "dmso", "ethanol", "ether", "ethylacetate", "furane", "hexadecane", "hexane", "methanol", "nitromethane",
            "octanol", "phenol", "thf", "toluene", "water", "woctanol"]
NUMBER = re.compile(r"[-+]?\d+\.\d*(?:[eEdD][-+]?\d+)?(?=_wp)")
OUT = Path(__file__).resolve().parent.parent / "moleculelab" / "data" / "gfn1-alpb.json"


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read().decode("utf-8")


def block(text, name):
    """Les nombres du jeu `name` (« type(...),parameter :: name = ... ( ... ) »)."""
    start = text.index(f":: {name} =")
    end = text.find("type(", start + 1)
    return [float(x.replace("d", "e").replace("D", "e")) for x in NUMBER.findall(text[start:end if end > 0 else None])]


def table(text, name, count):
    start = text.index(name)
    values = [float(x) for x in NUMBER.findall(text[start:])][:count]
    assert len(values) == count, (name, len(values))
    return values


def main():
    data = {"source": {"tblite": TBLITE, "mctc-lib": MCTC,
                       "reference": "Ehlert, Stahn, Spicher, Grimme, J. Chem. Theory Comput. 17, 4250 (2021)"}}
    data["vdw_d3_angstrom"] = table(fetch(TBLITE + "data.f90"), "vdw_rad_d3(1:94)", 94)
    data["atomic_rad_angstrom"] = table(fetch(MCTC + "atomicrad.f90"), "atomic_rad(max_elem)", 118)
    cm5 = fetch(TBLITE + "cm5.f90")
    data["cm5_a0"] = table(cm5, "cm5_a0(max_elements)", 118)
    data["cm5_alpha_per_angstrom"] = 2.4740
    data["cm5_pairs"] = {f"{a}-{b}": float(v) for a, b, v in
                         re.findall(r"pairpar\((\d+),(\d+)\) = ([-+]?\d+\.\d+)_wp", cm5)}
    solvents = {}
    for name in SOLVENTS:
        alpb = block(fetch(f"{TBLITE}data/alpb/param_alpb_{name}.fh"), f"gfn1_alpb_{name}")
        cds = block(fetch(f"{TBLITE}data/cds/param_alpb_{name}.fh"), f"gfn1_alpb_{name}")
        shift = block(fetch(f"{TBLITE}data/shift/param_alpb_{name}.fh"), f"gfn1_alpb_{name}")
        assert len(alpb) == 3 + 94 and len(cds) == 1 + 2 * 94 and len(shift) == 3, (name, len(alpb), len(cds), len(shift))
        solvents[name] = {"epsilon": alpb[0], "born_scale": alpb[1], "born_offset": alpb[2], "descreening": alpb[3:],
                          "probe_angstrom": cds[0], "tension": cds[1:95], "sqrt_hbond": cds[95:],
                          "molar_mass": shift[0], "density": shift[1], "gshift_kcal": shift[2]}
        print(name, "ε =", alpb[0])
    data["solvents"] = solvents
    OUT.write_text(json.dumps(data, indent=1), encoding="utf-8")
    print("écrit", OUT)


if __name__ == "__main__":
    sys.exit(main())
