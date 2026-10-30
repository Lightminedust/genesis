"""Le laboratoire de la réaction en chaîne : une sphère fissile, son réflecteur, et ce que le transport en dit.

Les calculs (transport, recherche du rayon critique, excursion) prennent de quelques dizaines de millisecondes à
quelques secondes : ils tournent dans un fil à part, sur les derniers réglages reçus, et la session envoie une
frame dès qu'un résultat est prêt.
"""

import math
import threading
import traceback

import numpy as np

from nuclear import nuclear_data

from . import cascade, kinetics, library, materials, sandbox, transport
from .text import energy_text, fixed, number

CORE_SHELLS = 16
REFLECTOR_SHELLS = 14
CURVE_SHELLS = (10, 10)
CRITICAL_TOLERANCE = 1e-4
SCALES = (1.0, 1.0005, 1.001, 1.003, 1.01, 1.03, 1.1, 1.25, 1.5, 2.0, 3.0, 4.5, 6.5)
HIROSHIMA_J = 6.3e13          # 15 kt de TNT (estimation publiée de Little Boy)
TNT_J_PER_TONNE = 4.184e9
REGIMES = {
    "sous-critique": "Chaque génération compte moins de neutrons que la précédente : la chaîne s'éteint.",
    "critique": "Une génération remplace exactement la précédente : la population reste stable.",
    "sur-critique différé": "La population croît, mais au rythme des neutrons retardés (secondes) : un réacteur se pilote ici.",
    "critique prompt": "Les neutrons prompts suffisent à eux seuls : la population explose en nanosecondes.",
    "inerte": "Aucune fission : la matière diffuse ou absorbe les neutrons, sans jamais en produire.",
}
MAX_SOURCES = 20


def _chord(inner, outer):
    """Corde moyenne ℓ̄ = 4V/S d'une sphère (inner = 0) ou d'une coquille."""
    if outer <= inner:
        return None
    return 4.0 / 3.0 * (outer ** 3 - inner ** 3) / (outer ** 2 + inner ** 2)


class ChainLab:
    def __init__(self, rng_seed=0):
        self.cores = materials.cores()
        self.reflectors = materials.reflectors()
        self.rng = np.random.default_rng(rng_seed)
        self.core = 0
        self.radius = 7.0
        self.reflector = -1
        self.thickness = 5.0
        self.compression = 1.0
        self.mode = 0
        self.nuclide = (26, 56)
        self.run = 0
        self.error = ""
        self.status = "Calcul du transport des neutrons…"
        self.result = None
        self.shot = None
        self.excursion = None          # l'excursion du tir `excursion_run`, calculée après sa cascade
        self.excursion_run = -1
        self.explored = None
        self.comparison = None
        self.validation = ""
        self.catalog_sent = False
        self._critical_cache = {}
        self._expansion_cache = {}
        self._fire_request = (None, 1)
        self._pending = {"settings": True, "fire": False, "explore": False}
        self._dirty = False
        self._lock = threading.Condition()
        self._closed = False
        self._busy = False
        self._worker = threading.Thread(target=self._loop, daemon=True)
        self._worker.start()

    # ------------------------------------------------------------------ réglages (fil du moteur)
    def _request(self, job):
        with self._lock:
            self._pending[job] = True
            self._lock.notify()

    def set_core(self, index):
        if 0 <= index < len(self.cores):
            self.core = index
            self.shot = None
            self._request("settings")

    def set_radius(self, cm):
        self.radius = float(min(max(cm, 0.3), 200.0))
        self.shot = None
        self._request("settings")

    def set_reflector(self, index, thickness=None):
        self.reflector = index if 0 <= index < len(self.reflectors) else -1
        if thickness is not None and thickness > 0:
            self.thickness = float(min(max(thickness, 0.5), 60.0))
        self.shot = None
        self._request("settings")

    def set_compression(self, factor):
        self.compression = float(min(max(factor, 0.3), 4.0))
        self.shot = None
        self._request("settings")

    def go_critical(self):
        result = self.result
        if result and result.get("critical_radius"):
            self.set_radius(result["critical_radius"])

    def fire(self, position=None, count=1):
        """Un tir : `count` neutrons d'amorçage, nés en `position` (cm, dans le cœur) ou près du centre."""
        self._fire_request = (None if position is None else np.asarray(position, float),
                              int(min(max(count, 1), MAX_SOURCES)))
        self._request("fire")

    def set_mode(self, mode):
        self.mode = 1 if mode else 0
        if self.mode == 1 and self.explored is None:
            self._request("explore")
        self._dirty = True

    def set_nuclide(self, code):
        """value = 1000 Z + A ; A = 0 : l'isotope qu'on trouve dans la nature."""
        z, a = divmod(int(code), 1000)
        if a == 0 and 1 <= z <= 118:
            from nuclear import nuclides
            a = nuclides.natural_isotope(z) or 0
        if not (1 <= z <= 118 and a >= z):
            return
        if nuclear_data.atomic_mass_u(z, a) is None:
            self.error = f"{nuclear_data.SYMBOLS.get(z, '?')}-{a} n'a jamais été pesé : aucun bilan possible."
            self._dirty = True
            return
        self.error = ""
        self.nuclide = (z, a)
        self._request("explore")

    def take_dirty(self):
        dirty, self._dirty = self._dirty, False
        return dirty

    @property
    def computing(self):
        return self._busy or any(self._pending.values())

    def close(self):
        with self._lock:
            self._closed = True
            self._lock.notify()

    # ------------------------------------------------------------------ le fil de calcul
    def _loop(self):
        while True:
            with self._lock:
                while not self._closed and not any(self._pending.values()):
                    self._lock.wait()
                if self._closed:
                    return
                job = next(j for j in ("settings", "explore", "fire") if self._pending[j])
                self._pending[job] = False
                self._busy = True
            try:
                if job == "settings":
                    self.status = "Calcul du transport des neutrons…"
                    self._dirty = True
                    self.result = self._compute()
                    self.status = ""
                elif job == "explore":
                    self.status = "Bilan des masses du noyau…"
                    self._dirty = True
                    self.explored = sandbox.explore(*self.nuclide, self._bare_critical)
                    if self.comparison is None:
                        self.comparison = sandbox.comparison(self._bare_critical)
                    self.status = ""
                else:
                    if self.result is None:
                        self.result = self._compute()
                    # La cascade d'abord, tout de suite visible ; l'excursion ensuite, plus longue à calculer.
                    self.status = "Cascade…"
                    shot = self._cascade()
                    self.excursion = None
                    self.shot = shot
                    self.run += 1
                    self._dirty = True
                    self.status = "Excursion…"
                    excursion = self._excursion(shot)
                    if self.shot is shot:
                        self.excursion = excursion
                        self.excursion_run = self.run
                    self.status = ""
                self.error = ""
            except Exception as error:   # le laboratoire doit survivre à un calcul qui échoue
                traceback.print_exc()
                self.error = f"Calcul impossible : {error}"
                self.status = ""
            finally:
                self._busy = False
                self._dirty = True

    # ------------------------------------------------------------------ le transport
    def _materials(self, core, radius, compression, reflector, thickness, shells=(CORE_SHELLS, REFLECTOR_SHELLS)):
        composition = core.number_densities(compression)
        mats = [library.macroscopic(composition, chord_cm=_chord(0, radius))]
        t = 0.0
        if reflector is not None:
            t = thickness
            mats.append(library.macroscopic(reflector.number_densities(1.0), chord_cm=_chord(radius, radius + t)))
        radii, region = transport.shells(radius, t, core_shells=shells[0], reflector_shells=shells[1])
        return composition, mats, radii, region

    def _solve(self, core, radius, compression, reflector, thickness, shells=(CORE_SHELLS, REFLECTOR_SHELLS)):
        composition, mats, radii, region = self._materials(core, radius, compression, reflector, thickness, shells)
        # Une matière sans fission n'a pas de k : on suit un neutron injecté, au spectre de fission de l'U-235.
        spectrum = None if core.fissile else library.get("U235", "chi")[10]
        return transport.solve(radii, region, mats, source_spectrum=spectrum), composition, mats

    def _k_infinity(self, core, compression):
        m = library.macroscopic(core.number_densities(compression), None)
        a = np.diag(m["total"]) - m["scatter"].T
        f = m["chi"].T * m["nu_fission"][None, :]
        if not m["nu_fission"].any():
            return 0.0
        return float(max(np.linalg.eigvals(np.linalg.solve(a, f)).real))

    def _critical(self, core, compression, reflector, thickness):
        """k(R) sur une grille de rayons, puis le rayon critique affiné (None si k∞ ≤ 1)."""
        key = (core.key, compression, reflector.key if reflector else None, thickness if reflector else 0)
        if key in self._critical_cache:
            return self._critical_cache[key]
        k_inf = self._k_infinity(core, compression)
        radii = np.geomspace(0.5, 80.0, 14)
        ks = [self._solve(core, r, compression, reflector, thickness, CURVE_SHELLS)[0].k for r in radii]
        critical = None
        if k_inf > 1 and ks[-1] > 1:
            i = int(np.argmax(np.array(ks) > 1))
            lo, hi = (radii[i - 1], ks[i - 1]) if i > 0 else (0.2, 0.0), (radii[i], ks[i])
            a, b = math.log(lo[0]), math.log(hi[0])
            fa, fb = lo[1] - 1, hi[1] - 1
            for _ in range(12):
                x = b - fb * (b - a) / (fb - fa) if fb != fa else 0.5 * (a + b)
                fx = self._solve(core, math.exp(x), compression, reflector, thickness)[0].k - 1
                if abs(fx) < CRITICAL_TOLERANCE:
                    a = b = x
                    break
                if fx * fb < 0:
                    a, fa = b, fb
                b, fb = x, fx
            critical = math.exp(b)
        value = {"k_infinity": k_inf, "curve_radius": list(radii), "curve_k": ks, "critical_radius": critical}
        self._critical_cache[key] = value
        return value

    def _bare_critical(self, name):
        """Pour le bac à sable : k∞ et masse critique nue du métal pur de ce noyau."""
        z = int(library.get(name, "z"))
        core = materials.Material(name, name, "", {name: 1.0}, materials.metal_density(z, {name: 1.0}), z)
        crit = self._critical(core, 1.0, None, 0.0)
        r = crit["critical_radius"]
        mass = core.density * 4 / 3 * math.pi * r ** 3 / 1000 if r else 0.0
        return {"k_infinity": crit["k_infinity"], "critical_kg": mass}

    def _compute(self):
        core = self.cores[self.core]
        reflector = self.reflectors[self.reflector] if self.reflector >= 0 else None
        radius, compression, thickness = self.radius, self.compression, self.thickness
        solution, composition, mats = self._solve(core, radius, compression, reflector, thickness)
        crit = self._critical(core, compression, reflector, thickness)
        density = core.density * compression
        volume = 4 / 3 * math.pi * radius ** 3
        core_shells = solution.region == 0
        flux_core = (solution.flux[:, core_shells] * solution.volume[core_shells]).sum(axis=1)
        production = float(np.dot(mats[0]["nu_fission"], flux_core))
        lambdas, delayed_rates = library.delayed(composition, mats[0]["micro"])
        betas = (delayed_rates @ flux_core) / production if len(lambdas) else np.zeros(0)
        beta = float(betas.sum())
        k = solution.k
        reactivity = (k - 1) / k if k > 0 else -math.inf
        omega = kinetics.inhour(reactivity, solution.generation_time, betas, lambdas) if len(lambdas) and k > 0 else 0.0
        if k <= 0:
            regime = "inerte"
        elif abs(k - 1) < 5e-4:
            regime = "critique"
        elif k < 1:
            regime = "sous-critique"
        elif reactivity < beta:
            regime = "sur-critique différé"
        else:
            regime = "critique prompt"
        bounds = library.bounds_ev()
        lethargy = np.log(bounds[:-1] / bounds[1:])
        mid = np.sqrt(bounds[:-1] * bounds[1:])
        fissions = solution.fission_by_group
        removal = solution.fissions + float(solution.capture_by_shell.sum()) + solution.leakage
        nu_bar = production / float(np.dot(mats[0]["fission"], flux_core)) if mats[0]["fission"].any() else 0.0
        source, unknown = materials.spontaneous_source(core, compression, volume)
        q_prompt, q_recoverable = self._fission_energy(composition, flux_core, mats[0]["micro"])
        reflector_mass = 0.0
        if reflector is not None:
            reflector_mass = reflector.density * 4 / 3 * math.pi * ((radius + thickness) ** 3 - radius ** 3) / 1000
        result = {
            "core": core, "reflector": reflector, "radius": radius, "thickness": thickness if reflector else 0.0,
            "compression": compression, "solution": solution, "composition": composition, "materials": mats,
            "k": k, "k_infinity": crit["k_infinity"], "regime": regime, "density": density,
            "mass_kg": density * volume / 1000, "reflector_mass_kg": reflector_mass,
            "critical_radius": crit["critical_radius"],
            "critical_mass": density * 4 / 3 * math.pi * crit["critical_radius"] ** 3 / 1000 if crit["critical_radius"] else 0.0,
            "curve_radius": crit["curve_radius"], "curve_k": crit["curve_k"],
            "leakage": solution.leakage / removal, "fission": solution.fissions / removal,
            "capture": float(solution.capture_by_shell.sum()) / removal,
            "mean_fission_energy": float(np.dot(fissions, mid) / fissions.sum()) if fissions.sum() > 0 else 0.0,
            "generation_time": solution.generation_time, "beta": beta, "betas": betas, "lambdas": lambdas,
            "dollars": reactivity / beta if beta > 0 and k > 0 else 0.0, "omega": omega,
            "prompt_critical_k": 1 / (1 - beta) if beta < 1 else math.inf, "nu_bar": nu_bar,
            "spectrum": flux_core / lethargy / max(float((flux_core / lethargy).max()), 1e-300),
            "fission_spectrum": fissions / max(float(fissions.sum()), 1e-300),
            "source": source, "source_unknown": unknown, "q_prompt": q_prompt, "q_recoverable": q_recoverable,
            "q_mass": self._mass_q(core) if core.fissile else 0.0,
            "core_atoms": sum(composition.values()) / library.BARN_CM2 * volume,
            "divergence": cascade.divergence_probability(k, nu_bar),
        }
        if not self.validation:
            self.validation = self._validate()
        return result

    def _fission_energy(self, composition, flux_core, micro):
        """Q prompt et Q récupérable (ENDF MT 458), pondérés par les fissions de chaque noyau."""
        total = prompt = recoverable = 0.0
        for name, density in composition.items():
            if not library.fissile_data(name):
                continue
            rate = float(np.dot(micro[name][library.FISSION] * density, flux_core))
            release = library.get(name, "energy_release")
            total += rate
            prompt += rate * float(release[7]) / 1e6
            recoverable += rate * float(release[8]) / 1e6
        return (prompt / total, recoverable / total) if total > 0 else (0.0, 0.0)

    @staticmethod
    def _mass_q(core):
        z, a = int(library.get(core.main, "z")), int(library.get(core.main, "a"))
        return sandbox.mean_fission_q(z, a) or 0.0

    def _validate(self):
        """Godiva (ICSBEP HEU-MET-FAST-001) recalculé : la sphère critique mesurée doit donner k ≈ 1."""
        godiva = next(c for c in self.cores if c.key == "HEU")
        k = self._solve(godiva, 8.7407, 1.0, None, 0.0)[0].k
        return f"Godiva (ICSBEP HEU-MET-FAST-001, R = 8,7407 cm) recalculé : k = {fixed(k, 4)} (mesuré : 1,000)"

    # ------------------------------------------------------------------ le tir
    def _cascade(self):
        """Les neutrons du tir, un par un."""
        result = self.result
        mats = result["materials"]
        nu_by_group = np.where(mats[0]["fission"] > 0, mats[0]["nu_fission"] / np.where(mats[0]["fission"] > 0,
                                                                                        mats[0]["fission"], 1), 0)
        outer = result["radius"] + result["thickness"]
        position, count = self._fire_request
        spectrum = None if result["core"].fissile else library.get("U235", "chi")[10]
        shot = cascade.run(result["radius"], outer, mats, nu_by_group, self.rng, sources=count, start=position,
                           source_spectrum=spectrum)
        shot["sources"] = count
        shot["result"] = result
        return shot

    def _excursion(self, shot):
        """La population calculée après ce tir : cinétique et contre-réaction (mise en cache par réglage)."""
        result = shot["result"]
        count = shot["sources"]
        key = (result["core"].key, result["radius"], result["compression"],
               result["reflector"].key if result["reflector"] else None, result["thickness"], count)
        cached = self._expansion_cache.get(("excursion",) + key)
        if cached is None:
            cached = self._fire(result, count)
            self._expansion_cache[("excursion",) + key] = cached
        return cached

    def _fire(self, result, count):
        core, reflector = result["core"], result["reflector"]
        radius, thickness, compression = result["radius"], result["thickness"], result["compression"]
        shot = {}
        if result["k"] <= 0:
            shot.update({"time_s": [], "log10_neutrons": [], "energy_j": [], "temperature_k": [], "radius_factor": [],
                         "k": [], "power_w": [], "phase": [], "energy": 0.0, "fissions": 0.0, "burned_fraction": 0.0,
                         "peak_temperature_k": 293.15, "dispersed": False, "quenched": False, "t_end": 0.0})
            shot["text"] = self._excursion_text(result, shot)
            return shot
        # L'excursion : k et Λ recalculés quand la sphère se dilate (densité ρ₀/s³, réflecteur immobile).
        key = (core.key, radius, compression, reflector.key if reflector else None, thickness)
        table = self._expansion_cache.get(key)
        if table is None:
            table = []
            for s in SCALES:
                if result["k"] < 1 and s > 1:
                    break
                sol = self._solve(core, radius * s, compression / s ** 3, reflector, thickness)[0]
                table.append((s, sol.k, sol.generation_time))
                if sol.k < 0.6:
                    break
            self._expansion_cache[key] = table
        scales = np.array([t[0] for t in table])
        ks = np.array([t[1] for t in table])
        times = np.array([t[2] for t in table])

        def k_of_s(s):
            return float(np.interp(s, scales, ks)) if len(scales) > 1 else float(ks[0])

        def time_of_s(s):
            return float(np.interp(s, scales, times)) if len(scales) > 1 else float(times[0])

        element = materials.element(core.z)
        caloric = kinetics.Caloric(element, core.mean_mass)
        expansion = materials.THERMAL_EXPANSION.get(core.z)
        curves = kinetics.excursion(k_of_s, time_of_s, result["betas"], result["lambdas"], max(result["nu_bar"], 1.0),
                                    result["q_prompt"] or 180.0, result["mass_kg"], radius, result["density"],
                                    caloric, expansion, n0=float(count))
        fissile_atoms = sum(n for name, n in result["composition"].items() if library.fissile_data(name)) \
            / library.BARN_CM2 * 4 / 3 * math.pi * radius ** 3
        curves["burned_fraction"] = curves["fissions"] / fissile_atoms if fissile_atoms else 0.0
        curves["caloric"] = caloric
        curves["expansion"] = expansion
        curves["text"] = self._excursion_text(result, curves)
        return curves

    @staticmethod
    def _excursion_text(result, c):
        energy = c["energy"]
        tnt = energy / TNT_J_PER_TONNE
        if result["k"] <= 0:
            return "Aucune fission : chaque neutron est diffusé, absorbé ou s'échappe, et rien ne le remplace."
        if result["k"] < 1:
            m = 1 / (1 - result["k"])
            return (f"Sous-critique : chaque neutron source n'engendre en moyenne que {number(m)} neutrons au total "
                    f"(multiplication M = 1/(1 − k)) ; la chaîne s'éteint.")
        # Des morceaux séparés par « · » : chacun se traduit seul, quelle que soit l'unité de l'énergie.
        if c["dispersed"]:
            return (f"La sphère s'est vaporisée puis dispersée · {number(c['fissions'])} fissions · {energy_text(energy)} · "
                    f"{number(tnt)} t de TNT")
        if c["quenched"]:
            return (f"La dilatation du métal chaud a ramené k sous 1 : l'excursion s'arrête d'elle-même · "
                    f"{number(c['fissions'])} fissions · {energy_text(energy)} · au plus {number(c['peak_temperature_k'])} K")
        return (f"Au bout de {number(c['t_end'])} s · {number(c['fissions'])} fissions · {energy_text(energy)} · "
                f"température {number(c['temperature_k'][-1])} K")

