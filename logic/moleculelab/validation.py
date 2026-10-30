"""Calculé ↔ mesuré : ce que GFN1-xTB fait bien (géométries, vibrations) et ce qu'il fait mal (énergies de réaction).

Chaque valeur est calculée par ce moteur (molécules relaxées par BFGS sur les forces analytiques), enregistrée dans
data/validation.json et revérifiée par les tests ;
rien n'est recopié. Références expérimentales :
  géométries d'équilibre r_e : NIST CCCBDB (H₂ 0,741 Å ; H₂O 0,958 Å et 104,5° ; CH₄ 1,087 Å) ;
  D_e(H₂) = 4,75 eV (D₀ = 4,48 eV + point zéro 0,27 eV) ;
  2 H₂ + O₂ → 2 H₂O(g) : ΔH°₂₉₈ = −483,6 kJ/mol, soit ≈ −5,5 eV d'énergie électronique une fois retirés les points
  zéro (H₂O 0,558 eV, H₂ 0,273 eV, O₂ 0,098 eV) ;
  fréquences harmoniques de H₂O : 3832, 3943 et 1649 cm⁻¹ (ω_e, CCCBDB) ;
  atomisations D_e (sans point zéro, comme le modèle ; ATcT/CCCBDB) : CH₄ 18,2 eV (D₀ 17,02) ; H₂O 10,1 eV (D₀ 9,51) ;
  O₂ 5,21 eV (D₀ 5,12) ; CO₂ 16,9 eV (D₀ 16,56) ; d'où CH₄ + 2 O₂ → CO₂ + 2 H₂O : ≈ −8,5 eV.
"""

import json
from functools import lru_cache
from pathlib import Path

import numpy as np
from scipy.optimize import minimize

from . import analysis, xtb

EV = xtb.HARTREE_EV
MOLECULES = {
    "H2": ([1, 1], [[0, 0, 0], [0, 0, 0.74]], None),
    "H2O": ([8, 1, 1], [[0, 0, 0], [0, 0.76, 0.59], [0, -0.76, 0.59]], None),
    "CH4": ([6, 1, 1, 1, 1], [[0, 0, 0], [0.63, 0.63, 0.63], [-0.63, -0.63, 0.63], [-0.63, 0.63, -0.63],
                              [0.63, -0.63, -0.63]], None),
    "O2": ([8, 8], [[0, 0, 0], [0, 0, 1.21]], 2),            # triplet : deux électrons non appariés
    "O2_lab": ([8, 8], [[0, 0, 0], [0, 0, 1.21]], None),     # l'état du laboratoire : restreint, π* à demi remplies
    "CO2": ([6, 8, 8], [[0, 0, 0], [0, 0, 1.16], [0, 0, -1.16]], None),
}


def relax(numbers, positions, unpaired=None):
    calc = xtb.Calculator(numbers, unpaired=unpaired)

    def energy(x):
        r = calc.compute(x.reshape(-1, 3))
        return r.energy, r.gradient.ravel()

    found = minimize(energy, np.asarray(positions, float).ravel(), jac=True, method="BFGS", options={"gtol": 1e-6})
    return calc, found.fun, found.x.reshape(-1, 3)


def _angle(p, a, b, c):
    u, v = p[a] - p[b], p[c] - p[b]
    return float(np.degrees(np.arccos(u @ v / np.linalg.norm(u) / np.linalg.norm(v))))


STORED = Path(__file__).resolve().parent / "data" / "validation.json"


def stored():
    """Le tableau tel que ce moteur l'a calculé (python -m moleculelab.validation) ; vérifié par les tests à chaque
    passage (tests/test_moleculelab.py). Le recalculer au démarrage prendrait plusieurs secondes de calcul qui
    disputeraient l'interpréteur au laboratoire."""
    try:
        return [tuple(row) for row in json.loads(STORED.read_text(encoding="utf-8"))]
    except (OSError, ValueError):
        return None


@lru_cache(maxsize=1)
def facts():
    """[(grandeur, calculé, mesuré)] : des chaînes prêtes à afficher (point décimal ; le client le localise)."""
    relaxed = {key: relax(*spec) for key, spec in MOLECULES.items()}
    out = []
    p = relaxed["H2"][2]
    out.append(("r(H–H)", f"{np.linalg.norm(p[0] - p[1]):.3f} Å", "0.741 Å"))
    atom_h = xtb.Calculator([1]).compute(np.zeros((1, 3)), gradient=False).energy
    out.append(("Énergie de liaison de H₂ (Dₑ)", f"{(2 * atom_h - relaxed['H2'][1]) * EV:.2f} eV", "4.75 eV"))
    calc, _, p = relaxed["H2O"]
    out.append(("r(O–H) de l'eau", f"{np.linalg.norm(p[0] - p[1]):.3f} Å", "0.958 Å"))
    out.append(("Angle H–O–H", f"{_angle(p, 1, 0, 2):.1f}°", "104.5°"))
    masses = np.array([analysis.mass(z) for z in MOLECULES["H2O"][0]])
    frequencies = analysis.normal_modes(calc, p, masses)[0]
    out.append(("Vibrations de l'eau", " · ".join(f"{f:.0f}" for f in sorted(frequencies)) + " cm⁻¹",
                "1649 · 3832 · 3943 cm⁻¹"))
    calc_w, _, p_w = relaxed["H2O"]
    water = calc_w.compute(p_w)
    out.append(("Moment dipolaire de l'eau", f"{np.linalg.norm(analysis.dipole(water)):.2f} D", "1.85 D"))
    p = relaxed["CH4"][2]
    out.append(("r(C–H) du méthane", f"{np.linalg.norm(p[0] - p[1]):.3f} Å", "1.087 Å"))
    out.append(("Angle H–C–H", f"{_angle(p, 1, 0, 2):.2f}°", "109.47°"))
    reaction = (2 * relaxed["H2O"][1] - 2 * relaxed["H2"][1] - relaxed["O2"][1]) * EV
    out.append(("2 H₂ + O₂ → 2 H₂O (ΔE)", f"{reaction:.2f} eV".replace("-", "−"), "≈ −5.5 eV"))
    # Les atomisations telles que l'écran les montre (les atomes de référence du laboratoire, O₂ restreint) : comparées
    # à D_e, sans point zéro, puisque le modèle n'en a pas.
    atom = {z: xtb.Calculator([z]).compute(np.zeros((1, 3)), gradient=False).energy for z in (1, 6, 8)}
    binding = {key: (sum(atom[z] for z in relaxed[key][0].basis.numbers) - relaxed[key][1]) * EV
               for key in ("CH4", "H2O", "O2_lab", "CO2")}
    for key, name, measured in (("CH4", "CH₄", "18.2"), ("H2O", "H₂O", "10.1"), ("O2_lab", "O₂", "5.21"), ("CO2", "CO₂", "16.9")):
        out.append((f"Atomisation de {name} (Dₑ)", f"{binding[key]:.2f} eV", f"{measured} eV"))
    out.append(("CH₄ + 2 O₂ : liaison totale (Dₑ)", f"{binding['CH4'] + 2 * binding['O2_lab']:.2f} eV", "28.6 eV"))
    combustion = (2 * relaxed["H2O"][1] + relaxed["CO2"][1] - relaxed["CH4"][1] - 2 * relaxed["O2_lab"][1]) * EV
    out.append(("CH₄ + 2 O₂ → CO₂ + 2 H₂O (ΔE)", f"{combustion:.2f} eV".replace("-", "−"), "≈ −8.5 eV"))
    return out


def main():
    STORED.write_text(json.dumps(facts(), ensure_ascii=False, indent=1), encoding="utf-8")
    print(STORED)


if __name__ == "__main__":
    main()


MODEL = (
    "GFN1-xTB (Grimme, Bannwarth, Shushkov, JCTC 13, 1989, 2017), réécrit en numpy et vérifié "
    "contre l'implémentation de référence dxtb à 10⁻⁷ Eh près. Base minimale d'orbitales de Slater (STO-NG), "
    "Hamiltonien de liaisons fortes H⁰ = ½(h_A + h_B) K π(R) S, électrostatique auto-cohérente des charges de couche "
    "(Klopman–Ohno, 3e ordre), répulsion des cœurs, dispersion D3(BJ), liaison halogène. Paramètres : xtb/tblite "
    "(LGPL-3.0). Les noyaux sont classiques (Born–Oppenheimer) ; l'état électronique est restreint, occupations "
    "lissées par Fermi à 300 K. Ce que la méthode fait bien : géométries (±0,02 Å), angles, vibrations (4 à 13 % trop basses), "
    "polarité. Ce qu'elle fait mal : les énergies d'atomisation (1,3 à 2,2 fois trop fortes) et les énergies de réaction "
    "(1,7 à 1,8 fois trop fortes pour 2 H₂ + O₂ et CH₄ + 2 O₂) — voir le tableau calculé ↔ mesuré. Sans spin, O₂ n'a pas "
    "de barrière de spin : ses réactions sont trop faciles."
)
