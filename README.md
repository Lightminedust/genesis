# Genesis — Atomic Simulator

**English** · [Français](README.fr.md)

An atom that lives by the rules of physics. The engine runs time; at every
instant it applies the rules, and whatever happens follows from them: an
excited electron falls back and emits a photon, a photon is absorbed or passes
through, an unstable nucleus decays. Nothing is scripted: each event happens at
the moment drawn by the physical law, together with the calculation that
justifies it.

Licence: [EULA.txt](EULA.txt).

```
JavaFX (sim/)  --- bidirectional gRPC --->  Python (logic/)  ---> MongoDB
  draws the events     genesis.proto v28     clock + rules        periodic table
                                             scipy, radioactivedecay, mendeleev
```

## Running

```bash
# 1. The engine and the database (--build is required after an update)
docker compose down
docker compose up --build

# 2. The client, in a second terminal
cd sim
gradle run
```

The client connects to `localhost:50051`; for another engine:
`gradle run -PgenesisHost=192.168.1.20 -PgenesisPort=50052`.
A container left on an old image is detected (`protocol_version`).

The application starts in **English** by default. The top bar lets you choose
**English** or **Français** in every laboratory; the choice is remembered. To
start directly in French: `gradle run -PgenesisLang=fr` (a language forced this
way is not saved). The engine always writes in French; the client translates
everything it shows at display time, including the engine's explanations and
messages (tables in `sim/app/src/main/resources/i18n/`: exact sentences,
templates where numbers, nuclides and element names are `{0}` slots, and the
names of the 118 elements).

**Without Docker** (Windows, slow connection): the engine also runs directly,
with its data in memory. Everything works except the Molecule scale (PySCF only
exists on Linux).

```bash
cd logic
python -m venv venv && venv\Scripts\pip install -r requirements.txt   # once
venv\Scripts\python -m grpc_tools.protoc -I../core --python_out=. --grpc_python_out=. ../core/genesis.proto
venv\Scripts\python engine.py
```

The Docker build keeps downloaded packages in a cache: only what changes is
downloaded again.

## Windows package

On Windows, with JDK 21 (`jpackage`), `gradle` and the `logic/venv` environment
already holding the engine's dependencies, install PyInstaller in that
environment and build the package:

```powershell
.\logic\venv\Scripts\python.exe -m pip install "pyinstaller>=6,<7"
powershell -NoProfile -ExecutionPolicy Bypass -File .\packaging\build-windows.ps1 -Version 0.3.0
```

The script tests the standalone engine, then produces `dist/windows/Genesis`
(portable application), `Genesis-0.3.0-portable.zip` and an `.exe` installer.
The installer puts Genesis in the user profile and adds shortcuts to the Start
menu and the desktop. For a new version, raise `-Version`; the same install
identifier allows upgrades.

The package bundles the JavaFX client, its Java runtime and the Python engine.
On first start, a loading screen stays visible until the Python engine has
opened its local connection. If it fails, the log is in
`%LOCALAPPDATA%\Genesis\logs\engine.log`.
The user needs neither Gradle, Python, Docker nor MongoDB.
The engine listens only on `127.0.0.1` and stops with Genesis. Logs and cache
are written to `%LOCALAPPDATA%\Genesis\`; the portable application writes there
too. The Molecule scale stays hidden in this version.
The package is not signed; code signing and third-party notices must be
prepared before public distribution.

**Updates.** At start-up, the packaged application asks GitHub, in the
background, for the latest release of
[genesis-releases](https://github.com/Lightminedust/genesis-releases) and
compares it with its own version (given by `jpackage`). If a newer one exists,
a small card appears at the bottom right once loading is over: **Install now**
(Windows: the installer is downloaded, its SHA-256 is checked against
`CHECKSUMS-SHA256.txt`, then it runs and Genesis closes), **See the release**,
**Later** or **Skip this version**. On Linux and macOS, the button opens the
download page. Offline, or when run from the sources, nothing is shown
(`-Dgenesis.version=0.1.0` simulates an older version for testing). Each
release must therefore publish its `CHECKSUMS-SHA256.txt`.

**Support.** While the engine starts, the loading screen shows a quiet
"Support Genesis" section: GitHub, the Genesis website, Gemmie
(gemmi-e.com/contact), r/gemmie and nellawassi@gmail.com.

## Linux x86-64 package

On Ubuntu 22.04 (or a compatible build system), install JDK 21, `gradle`,
`fakeroot`, the JavaFX/GTK libraries and a Python environment with
`logic/requirements.txt`, `grpcio-tools` and PyInstaller. Then run:

```bash
bash packaging/build-linux.sh 0.3.0
```

The script freezes the same Python engine, checks its gRPC service and the
table of 118 elements, builds the JavaFX client, then produces in `dist/linux/`
an installer `Genesis-0.3.0-linux-x64.deb` and a portable archive
`Genesis-0.3.0-linux-x64.tar.gz`. A GitHub Actions workflow
`.github/workflows/linux-package.yml` runs this build on Ubuntu.

The archive is extracted and started with `Genesis/bin/Genesis`; the `.deb`
adds a launcher to the applications menu. Java and Python are included. The
engine listens only on `127.0.0.1` and stops with the application. On Linux,
its log is in `${XDG_STATE_HOME:-~/.local/state}/genesis/logs/engine.log`
and its cache in `${XDG_CACHE_HOME:-~/.cache}/genesis/cache`.

## macOS package (Apple Silicon)

On a Mac with JDK 21 and a Python environment holding `logic/requirements.txt`
and PyInstaller, run `bash packaging/build-macos.sh 0.3.0`; without a Mac, the
GitHub Actions workflow `.github/workflows/macos-package.yml` builds it on an
Apple Silicon runner. It produces `Genesis-0.3.0-macos-arm64.dmg` and
`Genesis-0.3.0-macos-arm64.zip` in `dist/macos/`. jpackage refuses a macOS app
version starting with 0, so the bundle carries `1.3.0` internally; the real
number is passed as `-Dgenesis.version`, which the update check reads first.
The app is signed ad hoc and not notarized: on first launch, right-click it
and choose **Open**. The engine log is in `~/Library/Logs/Genesis/engine.log`.

## Subatomic laboratory

> Hidden for now (incomplete): it no longer appears in the **Laboratories**
> menu and the **S** key no longer opens it. Its code is kept
> (`SUBATOMIC_SCREEN` in `App.java`); the description below applies when it is re-enabled.

From the home screen, open **◈ Subatomic laboratory** (key **S**). This mode is
independent of building the atom. You can create up to 12 nucleons, add 0 to 2
electrons, set the initial kinetic energy, then play or step through the 520
samples computed by Python. The nuclear view shows 3D positions, velocities
and separation; the curves show the kinetic, strong, Coulomb and total
energies, the signed radial force for two bodies and the conservation error. A
second graph shows the effective potential as a function of separation; its
compressed vertical axis keeps both the repulsive core and the attractive well
visible. The wheel zooms and dragging rotates the camera.

The **Computed couplings** layer links pairs of nucleons: cyan for an
attractive effective force, amber for a repulsive one; the thickness follows
`|F(r)|` sent by Python at each sample. These segments show a central force
between two particles, not physical field lines. The **Separation threshold**
gauge shows `−E_rel` for two bodies: a positive margin means they are bound in
this conservative model. For several bodies, it shows the minimum margin needed
to pull one nucleon out of the cluster frozen at that instant: this local
diagnostic does not guarantee future stability. The demonstration tritium
starts near a minimum found by minimising the same potential, then oscillates
without damping.

In the nuclear view, an inset shows the 1s electron density at another scale;
the full electronic scale also shows a magnified inset of the nucleus. Its
pixels evaluate `exp(−2r/a)` at physical coordinates in pm, with the radius `a`
computed by Python. **Grabbing a nucleon, pulling it and releasing it** sends a
vector impulse to the engine: it restarts from the chosen sample and recomputes
520 new frames and their energy balance. No particle motion is decided by the
Canvas. Changing only the number of electrons changes the orbital occupation
without resetting the running nuclear trajectory.

The first validated case is **p + n**. An effective central potential
`V(r) = Gᵣ exp(−r/λᵣ)/r − Gₐ exp(−r/λₐ)/r` has a repulsive core and an
attractive well. The force is exactly `−dV/dr`, including the derivative of the
exponentials; Coulomb is added between protons. A **velocity-Verlet**
integrator carries positions and velocities. The model places an already bound
pair at negative energy: it oscillates without damping, because no emission
that would carry the energy away is simulated. The channel of identical
nucleons receives an effective kinetic pressure; the model does not derive
nuclear levels, spins, reactions or cross sections.

Electrons live on a scale about 100,000 times larger. The **Electron scale**
button shows a slice of the 1s orbital density `|ψ|² ∝ exp(−2r/a)` and its
binding energy: hydrogen-like for one electron, Slater estimate for two. There
is no point-like electron trajectory and no Pauli repulsion between electron
and proton: exclusion concerns identical fermions. This laboratory is an
explicit *few-body* model, not a QCD calculation nor a complete simulator of
subatomic reactions. Masses and the Bohr scale come from
[CODATA/NIST](https://physics.nist.gov/cuu/Constants/index.html); the
[deuteron is a quantum bound state](https://www.energy.gov/science/np/articles/quantum-computing-atomic-nucleus)
and its measured binding must not be confused with the energy of this
classical well. The [Pauli principle between electrons](https://www.nist.gov/pml/atomic-spectroscopy-compendium-basic-ideas-notation-data-and-formulas/atomic-spectroscopy-10)
does not replace the quantum mechanics of the orbital.

## The subatomic world

**◉ The subatomic world** (key **K**) brings together three workshops; the collision is a 3D figure of the whole zone (open box graduated in fm, branching tracks with their time dots and shadows, computed angular laws as lobes, a magnifier on the hadrons), in the style of the fusion chamber; elsewhere, a documentary style (glass bubbles, glowing quarks, gluons as golden springs):

* **Collision** — a projectile hits a proton or a neutron: electron, positron, muons (one photon exchanged, 0.3 to 10 GeV, 5° to 170°), muon neutrino and antineutrino (a W, which changes the flavour of a quark), proton or pions (the strong force: diffraction, Δ formation, colour exchange and strings). The engine computes the possible cases
  and their probability: elastic scattering (Rosenbluth, Kelly form factors), Δ(1232) excitation (Breit–Wigner),
  higher resonances (duality) and deep inelastic (F₂ from DGLAP). Everything is in 3D: the nucleon bag (radius taken
  from the charge radius, MIT bag), the Coulomb well as a haze, and space curved by each particle's energy (Einstein
  lenses, θ_E² ∝ E, amplified by a displayed factor). Then it draws one event and shows it: the virtual photon as an
  electric arc (bends spaced by λ = ħc/Q, colour and brightness following ν) carrying away the electron's energy, the electron's recoil, the Δ decay or the breaking string. Each step has its formula and its
  numbers, and energy and momentum are conserved exactly.
* **Hadron workshop** — the Standard Model table (computed cards: W and Z widths, muon and
  top lifetimes…); you take quarks from it, choose their colours, and the engine judges: colour neutrality, Pauli, charge, mass
  (constituent quarks + hyperfine), fate (stable, strong, electromagnetic, weak through the W, a top that dies before forming
  a hadron), with a decay shown.
* **Parton sea** — the nucleon at a given resolution Q²: gluons and pairs that are created and annihilate.

Laws, sources and limits: [the subatomic world](docs/SUBATOMIC_MODEL.md) and [the nucleon model](docs/NUCLEON_MODEL.md).

## The molecular laboratory

**⌬ Molecular laboratory** (Laboratories menu) puts atoms on a workbench and computes their electrons. Nobody decides
that two atoms are bonded, or at what angle: for every position of the nuclei, the quantum tight-binding method
GFN1-xTB gives the energy and the exact force on each nucleus, and geometry, bonds, vibrations and reactions follow.

* **Bonds drawn as their electron pairs** — the occupied orbitals are localized (Pipek–Mezey) and each bond is shown
  by what holds it: a pair on two atoms is a line (covalent or polar: water, benzene, SF₆, Au₂); a pair spread over
  three atoms or more is a multicentre bond, drawn as a Lipscomb star from the pair's centroid (gold clusters: Au₆
  has three such pairs, one per corner triangle, and no pair on a single couple of atoms; the B–H–B bridges of
  diborane); two ions with no shared pair (NaCl) get dotted attraction. The planar D3h triangle of Au₆ is a ready
  molecule, and GFN1-xTB puts it below the pyramid (+0.60 eV) and the octahedron (+1.10 eV).
* **The polarization halo** — the valence electron cloud of each molecule seen through: at every pixel, the electrons
  per Å² the line of sight crosses, computed every frame at full resolution from each atom's free-atom cloud (GFN1,
  Abel-projected) scaled to the electrons it keeps (Mulliken charge); it matches the projected computed density to
  95–99 %. Its edge is the 0.3 e/Å² contour; its colour is the charge of the atoms that own the point: magenta δ−,
  cyan δ+, with δ+ / δ− signs beyond its edge. Fuller around the atoms that pull electrons (O, Cl), thin around those
  that give them up (H). Option: the conventional electrostatic potential map — the exact potential of nuclei and
  cloud (Fourier convolution) on the ρ = 0.002 a.u. surface.
* **A minimal, computed picture** — atoms are glass bubbles (0.45 × Pyykkö covalent radius) with a glowing Fresnel
  rim that turns magenta when the atom gained electrons (δ−) and cyan when it lost some (δ+), an incandescent nucleus,
  and a few fine arcs that leave the nucleus, are caught where the bonds pierce the bubble and lean the way the atom's
  cloud is deformed (its computed atomic dipole). A covalent bond is a single laser-like beam whose brightness follows
  the Mayer bond order and the density the two atoms still share between their nuclei (a broken bond keeps a Mayer
  order of 1 in this closed-shell model — O–O at 3 Å: 1.06 — but shares nothing: its beam goes out); its π part is two glass lobes on either side of the bond, along the computed direction of the
  π density (ethylene: the plane normal; N₂: two perpendicular pairs); its colour gives the internal force along the
  bond (blue none, white tension, orange compression); its grains are drawn from the density of its localized pair
  along the axis (spread through C–H, denser on the O side of O–H). Non-bonded attraction is drawn as dots (hydrogen
  bonds, van der Waals, ions), sliding toward the middle; repulsion as orange dots that leave the point where the two
  clouds touch (free-atom surfaces at 0.002 e/bohr³) and move out toward both atoms, as fast and bright as the
  computed pair force. Bloom is done on the GPU. The electric field lines are an option.
* **Three views, three languages** — Δρ is a deformation-density map in contour lines (seen through, like the halo:
  solid where the molecule gained electrons, dashed where it lost some, one contour per doubling from 0.025 to
  1.6 e/Å²): benzene gains on every bond and loses on its carbon nuclei, as in the textbooks. ψ is one molecular
  orbital drawn as in chemistry software: smooth solid lobes enclosing 85 % of |ψ|², blue where ψ > 0 and red where
  ψ < 0 (the phase of the wave, not a charge), a node between them, with a 3D ball-and-stick skeleton whose bonds have
  one, two or three sticks from the Mayer order; a closed mesh by marching tetrahedra on a fine grid (80 points per
  side, computed by separable Gaussians), rendered by the GPU — sharp even while rotating. The camera looks along the thinnest axis of the cloud and tilts by 40°
  when both phases would fall on the same lines of sight (a benzene π seen face-on); the view line says what the
  orbital is made of (water HOMO: O 2p 100 %). Each view draws only what it says.
* **Readings you can see** — the energy balance: cohesion (the energy to pull the molecule apart into atoms), then
  each GFN1-xTB term minus its value in the free atoms, what binds versus what costs, summing exactly to the binding
  energy (water: overlapping orbitals −19.2 eV, charges +1.4, nuclear repulsion +1.0 → −16.7 eV), and the motion of
  the nuclei against the cohesion. The orbital levels as a target: radius linear in energy, filled rings carrying
  their electrons, HOMO orange, the HOMO–LUMO gap a golden ring, LUMO dashed violet, vacuum at the edge; hover reads a
  ring, a click shows its orbital. The selected atom as a 2D force map: by default only the net force and, per
  neighbour, a link coloured and sized by what the pair does, with the neighbours listed as bonded / not bonded and a
  computed reason (bond character from its π part; for non-bonded pairs, the dominant part of their force: steric
  clash from an antibonding overlap, like or opposite charges, London, cores). Detailed tabs give one arrow per pair
  (the full vector, sideways part included — it holds the angles) adding up exactly to the net force, or one arrow per
  term (electrons, nuclei, London, hand). A 2D sketch card shows the molecule as a textbook skeletal formula (CH₃, OH written out, or every hydrogen; single,
  double, triple and conjugated bonds from the Mayer order) that turns with the 3D view, linked both ways to the scene, with live charges, the selected atom's bond lengths and force, a
  line of measurements and a hint that follows what you are doing.
* **Who does what to whom, without arrows** — the forces are split exactly into atom pairs (action = −reaction);
  hovering an atom says « H3 pulls it by 1.00 eV/Å (electrons +4.60 · nuclei −3.60) »; a squeezed nucleus trembles.
* **The whole periodic table up to radon** — GFN1-xTB is parametrized for Z = 1–86 (all 86 checked: each relaxes
  with a hydrogen attached); in the lab's periodic table, the elements beyond (uranium…) are greyed and say why.
* **Isotopes** — select an atom and give it any stable isotope or one living at least a year (¹H ²H ³H, ¹³C ¹⁴C,
  ¹⁸O…; AME masses), or give it to every atom of the element. Only the mass changes (Born–Oppenheimer): same
  geometry, other dynamics and vibrations — D₂O vibrates at 0.72–0.73 × H₂O (measured 0.73–0.74), HDO decouples
  its O–D and O–H stretches.
* **Molecules of life** — glucose, lauric acid (a fatty acid, a lipid), glycerol, alanine and caffeine, from the
  PubChem 3D conformers, relaxed by GFN1-xTB.
* **Workbench** — pick an element and click an atom to attach it: C + 4 H relaxes by itself into the tetrahedron
  (109.47°), water comes out bent. Grab an atom and pull: the rest of the molecule is held by its centre of mass, the
  bond stretches, the energy rises, and the bond breaks.
* **Dynamics and reactor** — relaxation (FIRE), molecular dynamics with a thermostat (CSVR) or free (energy
  conserved, 0.25 fs steps); molecules in a sphere (2 H₂ + O₂, H₂ + Cl₂, CH₄ + 2 O₂…) that react when heated or hit
  by a spark that deposits kinetic energy; the species present and each reaction are read from the Mayer bond orders
  and the shared density. Hot runs keep going: when a bond breaks the self-consistent cycle switches from Broyden
  mixing to a damped Newton step on the computed charge response (O₄ at 4,000 K: no stop, every step converged at
  300 K), and a strict constraint holds any atom at −2.5 e at most, where GFN1's unbounded third-order term would
  otherwise pile electrons on it (Pm₄ apart: −9 e); the status line says when it acts.
* **Vibrations** — the Hessian from the analytic forces gives the normal modes (cm⁻¹); exciting one gives it n
  quanta hν and lets the (anharmonic) dynamics run.

GFN1-xTB is rewritten in numpy and checked against the reference implementation dxtb to 3·10⁻⁷ Eh. It gets
geometries and vibrations right (water: 0.958 Å, frequencies 4–13 % low); it overestimates atomization energies and
some reaction energies (2 H₂ + O₂ → 2 H₂O: −9.4 eV computed, ≈ −5.5 eV measured) — the laboratory says so, with a
computed ↔ measured table. The model, its checks and its limits: [the molecular laboratory](docs/MOLECULE_MODEL.md).

## The chain reaction

> Hidden for now: it no longer appears in the **Laboratories** menu. Its code and engine are kept
> (`CHAIN_SCREEN` in `App.java`, `logic/chainlab/`); the description below applies when it is re-enabled.

**☢ Chain reaction · critical mass** (Laboratories menu) puts a sphere of fissile metal in the middle of the screen,
with or without a reflector, and computes what its neutrons do. Every number comes from a calculation on evaluated data:

* **A scene, not a form** — the material is a volume of nuclei (one dot for 10²³ real nuclei). Click in the sphere
  to give birth to a neutron; drag its edge to squeeze it at constant mass (Shift: add matter) and watch the nuclei
  close up and the dashed critical-radius ring come within reach; drag a beryllium, graphite or uranium token onto
  it to add a reflector; a slider slows time down to the nanosecond. Colour says the state: a cold blue aura when
  subcritical, amber at critical, a throbbing red beyond. Materials: U-235, Pu-239, U-238, Th-232, iron 56 and lead
  208 (which never fission: the neutrons bounce, get captured with a violet γ flash, or leak), and eight more.
* **Neutron transport** — first-collision probabilities in spherical shells, 44 energy groups built from ENDF/B-VIII.0
  (Bondarenko self-shielding, transport correction, adjoint flux). The dashboard gives k_eff, k∞, the fate of the
  neutrons (fission, capture, leakage), the critical radius and mass, k_eff(R), the reactivity in dollars, the period
  (inhour equation, delayed neutrons), Λ, β, ν̄. Godiva, Jezebel and Flattop come out critical within half a percent;
  the bare critical masses are those published (U-235 46 kg, Pu-239 10 kg, U-233 15 kg). U-238 and Th-232 never diverge.
* **The shot** — a cascade drawn neutron by neutron in the real geometry (ν from Terrell's law around the evaluated ν̄):
  each collision lands on a nucleus of the volume — a scatter sparks, a capture sends out a violet γ wave, a fission
  bursts it into two fragments in a flash. The generations line up at the bottom (1 → 4 → 6 → 10…, or a ✕). If the
  drawn chain holds, the computed excursion takes over: a storm of sparks spread along the computed flux, the metal
  heats up, glows (black-body colour of the computed temperature), expands, boils, flies apart. A single neutron in a
  barely supercritical sphere usually dies out — the scene says how rarely it lights a never-ending chain (2 % at
  k = 1.02). The numbers (k_eff, critical mass, N(t), energy, TNT, Δm = E/c²) sit behind « ⓘ The numbers ».
* **Sandbox — the quest for yield** — any weighed nucleus: the Aston curve, the best fission split and the fusion with
  itself, a Q gauge from red (endothermic) to gold (exothermic), the mass balance before / after, and a table
  U-235 · Fe-56 · Fe-60 · D + T. Fission and chain reaction (neutrons, σ_f, k_eff) and fusion (plasma, Coulomb, Lawson)
  stay two separate worlds.

The model, its data and its validation: [the chain reaction](docs/CHAIN_MODEL.md).

## What you can do

At launch, the simulator loads a uranium-238 atom and opens the Orbital tab (the shape of the valence cloud, before the spectrum). The atom screen has three bands:

- **The top bar**:
  - the element (click: the periodic table, which closes as soon as an element is chosen, or with Esc). A chosen element comes with its **natural isotope**, the most abundant on Earth (Fe-56, Cu-63, U-238); without a tabulated abundance, the longest-lived isotope (Tc-98, Pu-244);
  - three counters − p⁺ + · − n + · − e⁻ +;
  - light: the wavelength (click: a small setting, with the flame) and "Fire a photon";
  - **☢ Fire at the nucleus**: a **slow neutron** (0.0253 eV), whose outcome is drawn in proportion to the measured cross sections (scattering, (n,γ) capture, fission of U-233/U-235/Pu-239, (n,α) on Li-6 and B-10, (n,p) on He-3 and N-14), with Q computed from measured masses; or a **5 MeV α particle**, which opens the collision chamber with He-4 against the nucleus on screen. Without a measured cross section (Tc-98…), Genesis says so and does not invent the outcome;
  - on the right, the **Laboratories** menu (fusion · fission · transmutation, molecular laboratory, subatomic world), hidden until opened. As soon as one is chosen, a **waiting screen** (`LabLoader`) says what the engine is preparing and the elapsed time. It disappears when the frame confirms the laboratory, and says why if the engine does not answer (outdated, unreachable). Going back to the atom has the same wait;
  - a coloured dot that gives the connection state on hover.
- **The scene**, with its view bar:
  - the scale (Atom · Nucleus · Nucleon · Scale gap);
  - what dragging does (⟳ Rotate · ✥ Pan; right-click or Shift always pans);
  - ⌖ Recentre, and ready-made views (front, top, side, three-quarter);
  - the object's own rotation, the zoom;
  - **Bohr** (key `O`): the Bohr model (1913), the picture everyone knows — concentric circles in a single plane (they never cross), one per shell, electrons going round, and at the centre the real nucleus (the drop of nucleons placed by the engine), enlarged. The number of electrons per shell comes from the engine; the radii are not to scale and the scene says this is a historical picture (in reality the electron has no trajectory). A full shell of more than 8 electrons is drawn as a glowing ring, its electrons appearing on hover; beyond xenon (or with **Valence**), the core condenses into a sphere labelled with its noble gas ([Kr], [Rn]…). The shell counts sit in a legend under the atom (`[Rn] 86 · O 14 · P 10 · Q 8`), not in the scene. Nothing pops: a captured electron flies in, an excited one jumps from circle to circle, a lost one flies away, and a decay shakes the nucleus;
  - **Valence** (key `V`): only the open subshells are drawn. Full subshells are spherical (Unsöld's theorem) and, piled up in a heavy atom, they hide the shape of the valence; without them, yttrium's 4d¹ shows its d_z² lobes and ring in 3D;
  - a **double-click** centres the view on the chosen point and moves closer.

  The shell panel folds into a chip, and an ⓘ chip shows the legend and the **limits of the model** on hover.
- **The timeline**, at the bottom: ▶/⏸, "Next event", "Step by step", and the **time speed** in plain words ("1 real s = 1 billion simulated years"). Below, what becomes of the atom, drawn like a metro line:
  - **Past**: the nuclei already lived, greyed, with their real duration.
  - **Current nucleus**: its ring gives the probability that it has already decayed, P = 1 − e^(−t/τ).
  - **Future**: the stations down to the stable nucleus, with their path (α, β⁻…), their half-life and the mean time to get there (U-238 → Th-234 → … → Pb-206).
  - **Interactions**: clicking a station advances time up to it, event by event (decays are still drawn at random). Hovering a rail explains the path.
  - **Underneath**: the next step, and what it means in real time at the chosen speed.

The chain comes from the engine (`Session.decay_chain`, dominant path at each step, ICRP-107 / NUBASE2020 data; contract v28).

| Group | Action | Keys |
|---|---|---|
| BUILD | add / remove proton, neutron, electron · periodic table · empty | `P` `N` `E` (Shift: remove) · `T` · `R` |
| LIGHT | tune the laser (top bar, or click a line) · fire a photon · flame | `L` · `F` |
| NUCLEUS | fire a slow neutron · fire an α (☢ menu) | `B` |
| TIME | pause · speed · next event · step by step · go to a station (click) | `Space` · `[` `]` · `→` · `A` |
| VIEW | atom, nucleus, nucleon, scale gap · wheel zoom · drag: rotate (or pan) · double-click: centre · valence only · Bohr model | `1` `2` `3` `G` · `+` `−` · `V` · `O` |
| LABORATORIES | Laboratories menu: fusion · fission · transmutation, molecular laboratory, subatomic world | `U` · `K` |

The Matter and Molecule scales are disabled in the interface
(`App.ADVANCED_SCALES = false`); their code and tests are kept.

**A clean scene.** Particles carry a short label (one per kind); the
calculation (energy, λ, scale factor) is in the tooltip and in the journal.
An electron that cannot be added (Ar⁻, Mg⁻ do not exist: measured electron
affinity ≤ 0) bounces off, and the box it would have taken blinks red in the
shell card, with the reason.

**Particles arriving.** A proton, a neutron or a captured electron flies in
first; the atom changes (name, counters, journal, timeline) only when it hits,
with a flash on the nucleus. Several quick clicks fly in parallel and are
applied in order.

**Step by step** (on by default): time stops right after each event. You read
the calculation, look, then ▶ to resume or ⏭ to go to the next one.

## The rules applied

**Time.** Each process has a probability per second (de-excitation: Einstein
A coefficient; nucleus: λ = ln 2 / t½). The wait before the event is drawn
from the exponential law: the law of a memoryless phenomenon. At 1 ns/s you see
hydrogen emit; at 1,000 years/s, carbon-14 decay. "Next event" jumps straight
to the drawn moment.

**Light.**
- One-electron systems (H, He⁺, Li²⁺…): (n, l) states, exact energies
  (Rydberg, reduced mass), selection rule Δl = ±1, A coefficients computed on
  the real wave functions (2p: 1.60 ns; 3p → 1s branching: 88 %). The 2s, with
  no allowed transition, only de-excites by emitting two photons (0.12 s).
- Monochromatic photon, atom at rest at the centre of a Gaussian beam:
  radius w = max(1 µm, 2λ in vacuum). In the weak-coupling regime,
  P = σ / (πw²/2). Absorption follows a natural Lorentzian profile, of width
  (Γ_upper + Γ_lower)/(2π) in Hz, computed from the lifetimes.
  Hydrogen-like channels are weighted by their Einstein coefficients and
  degeneracies; no artificial ±0.1 % window.
  For the hydrogen-like 1s continuum, the Coulomb cross section makes
  ionisation probabilistic (σ at the H threshold: 6.30·10⁻²² m²) and
  E_k = E_photon − E_ionisation. Other continua are flagged as not computed;
  knowing the threshold is not enough to predict a probability.
  No Doppler, polarisation, saturation or stimulated emission yet.
  References: [NIST line profiles](https://physics.nist.gov/Pubs/AtSpec/node20.html),
  [hydrogen-like continuum, Nahar 2021](https://doi.org/10.3390/atoms9040073).
- Many-electron atoms: measured lines (NIST); absorption on the resonance lines
  with a known lifetime, two-level approximation, unpolarised (degeneracies and
  oscillator strengths not tabulated). Flame = line drawn according to its
  intensity; simplified collisional excitation.

**Spin.** Electron, proton, neutron: spin ½, fermions. At most two electrons
per orbital, with opposite spins (Pauli); orbitals first fill one by one, with
parallel spins (Hund). The photon has spin 1: when it leaves it carries away
one unit of angular momentum, hence Δl = ±1, and the electron's spin does not
change. Spin–orbit coupling splits the lines of the alkalis (sodium 589.0 /
589.6 nm: 3p₃/₂ and 3p₁/₂). The spin of each nucleus is measured (C-14: 0⁺,
Na-23: 3/2⁺).

**Electrons.** An added electron is captured, or repelled if the ion does not
exist: Ne⁻ (electron affinity ≤ 0), Cl²⁻ (no atomic dianion). Captured by a bare
nucleus, it falls into a high level then cascades down. Measured ionisation
energies for every ion.

**The nucleus.** Measured half-lives, paths and branching ratios (ICRP-107,
NUBASE2020), Q computed from measured masses (AME2020). Chains unfold on their
own: U-238 → … → Pb-206, through the isomer Pa-234m. A fully ionised Be-7 can no
longer capture an electron: it becomes stable. After an α decay, the daughter
atom keeps two electrons too many and loses them.

**Neutrons.** A slow neutron (0.0253 eV, 2,200 m/s) has no charge: no barrier
stops it. What it does is drawn in proportion to the measured thermal cross
sections (Sears 1992, NIST): elastic scattering, radiative capture (n,γ),
fission (U-233, U-235, Pu-239), (n,α) and (n,p). Q comes from measured masses;
a fission splits the compound nucleus around the heavy peak A ≈ 140 and frees
ν̄ neutrons (ENDF/B-VIII.0). Without a measured cross section, no outcome is
invented.

**Matter.** Zooming out beyond the atom (or the "Matter" button), 256 atoms of
the element are simulated in 3D molecular dynamics. A single rule between
neighbours, the Lennard-Jones potential V(r) = 4ε[(σ/r)¹² − (σ/r)⁶]: van der
Waals attraction, repulsion of the electron clouds. Solid, liquid, gas and
supercritical fluid emerge from it; the state of each atom is measured (does it
keep its neighbours? does it have any?), not decided. Heat enters through the
walls (thermal walls), pressure is measured from the impacts on the walls, the
piston changes the volume. No gravity: at this scale it is billions of times
weaker than thermal agitation. The model's argon melts around 65–70 K and has
its critical point at 134 K (real: 83.8 K and 150.7 K).

**The molecule.** The current atom and a partner chosen in the table. Nobody
decides whether they bond: for 21 distances R, quantum chemistry
(DFT PBE0/def2-SVP, PySCF) solves their electrons and gives the energy E(R), the
electron density, the difference from the isolated atoms (where the electrons
went), the charges (Mulliken), the bond order (Mayer) and the molecular
orbitals with their spins. At each distance, the lowest spin state wins (triplet
O₂ near bonding). You grab a nucleus and let go: the real force −dE/dR decides,
they vibrate (bond) or move apart. Checked: H₂ 0.76 Å, 4.4 eV, vibration
7.6 fs; NaCl 2.34 Å, 0.6 e transferred from Na to Cl; triplet O₂, double bond;
He₂ does not bond. Pauling electronegativity is shown only for comparison.
PySCF only exists for Linux and macOS: the Docker engine has it; started on
Windows outside Docker, this scale says so. First calculation of a pair: from a
few seconds to two minutes, then cached (Docker volume).

**The collision laboratory** (key `U`) lets you pick the two elements
independently in the full table, then their isotopes. The catalogue holds 118
elements and 3,557 isotopes; their masses are evaluated or estimated depending
on the available data. The cards identify Z, N, mass, radius, spin and magnetic
moment when known, with their source. At this scale, the participants are
**fully ionised nuclei**; chemical collisions of neutral atoms are not modelled
by this screen.

The beam energy and the impact parameter can be set directly. The engine
integrates the trajectories in the Coulomb potential and an effective nuclear
potential, with the Lorentz force. Light reactions use, within their domain,
the Bosch–Hale experimental fits; other collisions use a capture model, flagged
as an estimate in the shot panel and in "Model & data". Each pair gets its own
charges, masses, radii, barrier, trajectory and fields; this does not mean all
its reaction paths have been measured. **Calculate collision** draws the outcome
according to the probability of the branch used (often: they repel each other);
**Until fusion** shows the fusion and displays the probability it had on that
shot. Beyond iron (Z₁ + Z₂ > 26), welding two nuclei costs energy instead of
releasing it: the chamber then speaks of **transmutation** ("Until
transmutation").

The fusion goes all the way: a hot compound nucleus, then statistical
de-excitation — evaporation (Weisskopf), γ, or **fission** (Bohr–Wheeler, split
at constant charge density, Viola kinetic energy); the fragments then evaporate
their neutrons. Beyond Z = 118, no nucleus holds: **quasi-fission** (the two
nuclei rotate together for a moment then separate). Energy and momentum are
conserved at every step. Examples: C-12 + U-238 → excited californium-250 →
fission into two indium nuclei; Fe-56 + Pb-208 → hassium-264 → xenon +
tellurium; Pb-208 + Pb-208 → quasi-fission.

Each nucleus looks like what it is, **at true scale** (no magnification): a
Woods–Saxon density cloud tinted with its element's colour (CPK convention),
all its nucleons inside (protons red, neutrons lavender), a wave packet of
width ħ/p, a spin arrow that is longer the larger the spin. The **electric
field is one halo per nucleus**: its conventional tint follows the element's
identity and shades the isotope, while its shape and intensity follow the field
computed for its charge and radius. **Between the nuclei, the combined
potential** U = eφ = Σ kZᵢe²/rᵢ (the energy a +e charge placed there would have)
is painted in heat colours, from blue to white, where both potentials matter and
their fields oppose; its isopotentials are drawn there, and its **saddle** — the
point where E₁ + E₂ = 0 — is found by Newton's method and given a value ("saddle
U = … MeV"): it heats up as the nuclei approach. Very fine markers leave each
nucleus, follow the total field, are deflected by the other and are dragged by
their charge (the field moves with it). At contact, the overlap of the
Woods–Saxon densities ρ₁·ρ₂ (where the strong force acts) glows gold. The
**magnetic field of the shot** reads on the background with the usual
convention (dot if it comes out of the screen, cross if it goes in); the engine
really integrates it, and the probe gives each nucleus's Larmor radius
r_L = p/qB. The **dashboard** is a flat instrument: the energy split, the main
measurement on a graduated gauge, secondary measurements in rows, and — in the
full reading — the **potential curve V(r)** (hover: the value at the pointed
distance) and the table of laws; at the end, the **equivalences** of the
released energy (joules, TNT, Hiroshima, per gram of fuel). The interface offers
**two readings**: "Essentials" (the law of the moment, the energy split, three
measurements) and "Full" (all the metrics, layers, the probe, the medium). At
each step, **the law is set large on the scene** — a built-up fraction, value,
formula with numbers, and two answer chips ("r → 2r", "F → F/4") — and repeated
as a table in the panel.
Just before the first ejection, the scene switches by itself to **"Map"**: the
view pulls back in advance, the same nuclei keep their fields and spins, the
element created is a fixed centre and distances are compressed (log scale,
factor shown) so everything fits on screen; each label gives the computed speed
and the true distance. Clicking a body zooms onto it without moving the centre;
clicking empty space goes back.
At the **break-up**, playback slows down (t ∝ u³) and the camera holds the
barycentre while pulling back to keep the fragments, before returning to the
element created; in a plasma, each charged body leaves an **ionisation track**
whose thickness follows its stopping power dE/dx ∝ Z²/v² — the neutron and the
photon leave none. **The impact happens in the plasma** set in the Lawson
panel: its fields and potential are screened (Debye–Hückel, e^(−r/λ_D)) —
negligible at the scale of nuclei at 15 keV and 10²⁰ ions/m³ (λ_D ≈ 64 µm),
noticeable at extreme densities — and a medallion under the dashboard shows this
plasma **at its own scale**: a box four Debye lengths wide, with its grid and
its ruler in physical units, where ions and electrons move according to
Maxwell–Boltzmann (electrons √(m_i/m_e) times faster, each trailing the
distance it covers during the exposure); each particle's colour follows its
energy ½mv² (blue at 1 keV, white at 100 keV), a Langmuir wave (Bohm–Gross)
makes the electron cloud breathe, and bremsstrahlung photons are emitted at the
rate computed by the engine. Everything there follows T and n. The magnetic
field superposes the spin dipoles, the field of the moving charges and the
external field of the shot; the moving markers show its direction. A hot
nucleus is a vibrating liquid drop. The field grid is updated in a
`PixelBuffer`; the saddle zone and its isopotentials are computed on the same
grid. The equations, the model and the energy error can be viewed in
**Inspect**: they no longer cover the bottom of the scene. The decorative
particles have been removed; the grains that follow the electric field remain.

The trajectories received are kept while settings are updated; re-sending the
same shot does not restart playback. Between two samples of the same phase,
positions are interpolated with the engine's velocities (Hermite), to keep the
braking and the recoil. This link is shared by all elements and products. The
electric tracers keep their positions in the 3D world and query the total field
at each sub-step: they are no longer attached to the motion of the emitting
nucleus. Their playback speed is a graphical convention, independent of zoom.

**The view zooms with them.** The nuclei start at 12 times the turning-point
distance; as they get closer, the view zooms, then freezes in the braking zone
so you can see the nucleus slow down — echoes at equal time intervals tighten
behind it (repulsion turns its momentum into field energy, without friction) —
down to the femtometre for the Coulomb wall, quantum tunnelling and the weld.
Each nucleus is squeezed along the axis by the other's field (Coulomb
polarisation, computed: invisible for tritium, strong between heavy nuclei);
the β₂ value is on its card. The cards give their speed at every instant. The
camera has inertia (you push it, it glides then stops) and its framing changes
glide instead of jumping; a long move first pulls back, like flying over a map.
The magnetic field fades as the electric tension rises; small moving markers
follow its local vector, recomputed at every frame, without drawing orbits.
Before a fission, the nucleus stretches into a dumbbell (Cassini ovals) until
scission; before an emission, its surface bulges where the particle leaves.
Break-ups that replace two nuclei by two fragments are also recognised. At the
first break-up and at fissions, the camera watches the emission point for one
second of playback before following a fragment: its recoil stays visible. The
products' velocities are those of the Python samples, applied from their birth,
with no acceleration added by the rendering. No shock ring or trail is drawn
around the products. A local flash only appears if the engine actually emitted a
γ photon; its spatial intensity follows `I₀/[1+(r/R₀)²]` and its persistence on
screen decays exponentially. This persistence is a graphical exposure time, not
the physical duration of the emission.

**The weld is computed**: from the tunnel exit (where V(r) = E) or the contact,
the engine integrates the motion in the Coulomb + Woods–Saxon potential with
Gross–Kalinowski nuclear friction; the two nuclei fall into each other, their
momentum turns into heat (displayed), their electric fields merge into one. For
heavy nuclei, repulsion stops this calculation at contact (dinuclear system):
the final melting of the two shapes is shown slowly, flagged as not traced. The
products are shown as long as they stay on the map (the radius of their Lorentz
circle, around a centimetre, is given in text). **Once the fusion is done**, the
central view only presents the central element created: the camera glides onto
it (spin, electric field, magnetic dipole) with its card (binding per nucleon,
fields at the surface and at the pole, half-life, speed, Lorentz circle); the
other products are projected without labels. The **interactive results** then
open on the right (floating, detachable with "⧉ Window"): tabs *Created*,
*Rejected*, *Light* (γ photons: energy, wavelength, frequency) and *Energy* (Q in
joules and in **TNT equivalent**, per reaction and for 1 g of fuel, what each
product carries away); clicking an element opens its card and brings the main
view onto it (an ejected neutron, for example). A refused shot (energy too low
for the pair, or beyond the model) is stated in the centre of the screen, with a
button that sets the advised energy and fires again. The electric field takes
the conventional tint of each isotope; between the nuclei, the combined
potential and its saddle are given in numbers; the magnetic field reads through
markers advected in B; the wave packet flattens against the barrier in the
direction of the total field. You can pause, scrub, replay or fire new shots.

The **Plasma** mode sets T, n and τ (Lawson criterion, nτT curve); each shot
draws its energy from the Maxwell–Boltzmann distribution.

The equations, approximate models and drawing conventions are described in
[the collision model](docs/FUSION_MODEL.md).

**The stellar core** (disabled in the interface, `App.STAR_SCREEN = false`; code
and tests kept). You choose a real place (the Sun's core, the young Sun, a
massive star, a red giant, carbon burning, a D-T reactor, the Sun's surface)
then set temperature and density. Everything is computed:
- the reactions come from the REACLIB nuclear network (`pynucastro`,
  85 reactions on 24 nuclei: p-p chain, CNO cycle, triple-α, carbon, D-T),
  with electron screening; the composition evolves by integrating dY/dt (stiff
  solver from `scipy`);
- the energy is Q = Δm·c² of each reaction, minus what the escaping neutrinos
  carry away (measured mean energies, Bahcall);
- ionisation follows the Saha equation with pressure ionisation: in the Sun's
  core the nuclei are 18 pm apart, less than the size of an atom, electrons can
  no longer be bound;
- pressure adds nuclei, electrons (Fermi–Dirac: degenerate in a red giant) and
  light (only if the plasma is opaque);
- a window of ~220 nuclei, at their true size (172 pm in the Sun's core, with a
  dotted hydrogen atom for comparison), shows each fusion drawn according to the
  real rates: the reactants meet, the flash, the products, the escaping
  neutrino, the positron that annihilates. The panel says what was created and
  destroyed, the energy, the pressure, and what it means.

Checked: central pressure of the Sun 2.3·10¹⁶ Pa (standard model:
2.34·10¹⁶), ~1.6 mW/kg (less than a human body), hydrogen exhausted in
~4 billion years; D-T reactor: 4 atm; Sun's surface: 7,700 Pa, neutral atoms.

## Genesis, the little guide

> **Set aside.** Genesis is no longer shown: the journal has a single view, the logs. Its code is kept in `sim/app/.../mascot/`; to bring her back, set `GENESIS_ENABLED` to `true` in `sim/app/.../app/App.java`. What follows describes what she does when enabled.

Genesis is a ball of dark matter, full of universe. She floats in a glass ball set on a base (four cables, a golden diamond, the empty background acting as an indicator).

**Her shape is solid and simple** (`GenesisBlob.java`):
- **A round ball and two rounded ears**, with a glowing outline, blue then white, and a large halo around.
- **Inside, space**: black void, a blue nebula turning slowly, stars.
- **At the heart, a white light.**
- **At the bottom, white foam**, full of holes, with a few islands breaking off upwards.
- **Around, droplets** that leave the ball, float, then come back to melt into it.
- **Three bright highlights** at the top right, as on glass.

She has no face. Her moods show through three things:
- **Her heart of light**: it turns towards the mouse, beats when she speaks, dims when she sleeps.
- **Her ears**, on springs: they trail when she moves, stand up when she is curious, droop when she sleeps; one of them twitches now and then.
- **Her jelly body**: it stretches in the direction she goes, squashes when touched, makes small hops when she is happy; the foam tilts and laps with her movements.

She moves little and gently: she floats in the middle, leans towards the mouse, settles at the bottom to sleep.

**A click splits her** (`GenesisFlask.java`, 7.8 s): she pulls in her ears, splits into five masses that float, merge and separate as in a flask, then gathers, rebuilds herself and spits out residues of matter. She also does it on her own from time to time, at rest.

The shape is an implicit surface (the distance to the ball, the ears and the drops, joined by a smoothed minimum): the outline is smooth and sharp at all sizes. No library is added.

**The journal has two views**, with a toggle at the top right (the choice is remembered):
- **Journal** (without Genesis): the full logs. Each line carries a category tag (Light, Electrons, Nucleus, Info), the simulated time, the title, the calculation in an inset and the engine's explanation. Filters with counters keep a single category.
- **Genesis**: she explains each event in simple words, in three beats: what happened, "Why:", then "And now:" (or "Remember:", "Try:", "In other words:"). The logs stay below, one line each; clicking a line makes her explain that event. When several events happen at the same instant, she starts with the most important (the nucleus, then light, then electrons).

She reads the journal: each event becomes a sentence in her bubble, with the calculation in gold. After 80 s without an
event, she falls asleep: she settles at the bottom, ears drooping, heart dimmed. `gradlew mascotPreview` draws five sheets: her states,
twelve close instants (0.2 s apart, to judge continuity), eight more widely spaced instants, her reactions to the mouse, and her
split after a click; `gradlew journalPreview` draws the journal.

## The atom in 3D

The atom screen no longer draws orbits: everything there is computed, in 3D,
and you rotate it by dragging (it also rotates very slowly on its own).
`python -m tools.export_atom_frames` (in `logic`) writes real engine frames to
`artifacts/frames`; `gradlew dashboardPreview` renders the whole screen (bars, scene, timeline), a real uranium decay, the shell card, the limits legend and a neutron fission of U-235 into `artifacts/dashboard`; `gradlew atomPreview` renders them into
`artifacts/atom3d` (elements, rotation, dive, drop, scale gap, photon absorbed
by H, photoionisation of Na, narrow screen) and measures the time per frame
(1 to 6 ms); `-Ponly=photon` only plays the photons.

**The electron cloud** (`OrbitalCloud`). Each occupied subshell is the density
|ψ_nlm|² = R_nl(r)² · Σ occ_m Y_lm²: hydrogen-like radial function (its
n − l − 1 nodes), real spherical harmonics in the order of `orbitals.py`, Hund
filling sent by the engine, size scaled to the engine's Slater radius. The
single electron (H, He⁺…) is exact (a₀/Z); since the engine does not track m,
its cloud is the isotropic mixture of the 2l + 1 orientations. Exact volume
rendering: each pixel receives the density integrated along the line of sight.
Along the ray b⃗ + t·ê, the angular part is a polynomial in t; the integral
reduces to tables G_j(b) = ∫ g t^j dt, computed once per (n, l). The polynomial
coefficients are expanded once per frame and per orientation: each pixel costs
only a few multiplications. Spherical subshells, invariant under rotation, are
kept from one frame to the next. What you see, and what is a display choice:
- the shapes are exact: p hourglass (boron 2p¹), d_z² with its ring
  (scandium 3d¹), f lobes, radial nodes (rings of sodium's excited 4s); a full
  or half-full subshell is spherical (Unsöld);
- each subshell has its colour and its own brightness scale, set on the column
  at the most probable radius (S-curve below, logarithmic above). Without this,
  the 1s core would dazzle everything, and the atom would be a mere ball (its
  total density is nearly spherical). Full inner shells are dimmed; the front
  of the cloud is slightly brighter (depth cue, 30 % at the most probable
  radius);
- the **brief dots** are positions drawn from |ψ|² (Monte Carlo): what you would
  find by measuring an electron. The draws change, the law does not: the state is
  stationary;
- when the configuration changes (capture, an electron's jump), the new cloud
  appears in 0.9 s while the old one fades. For the single electron, this is the
  exact density of the mixture (cross terms cancel on average over m).

**The photon** (`PhotonPacket`), built from λ alone:
- **Shape**: a 3D wave packet, with a Gaussian envelope (a spindle that melts at the ends) and two perpendicular ribbons, E (colour of λ) and B (pale). They are in phase, |B| = |E|/c.
- **Polarisation**: linear, E along the atom's z axis. The packet arrives slightly at an angle, in perspective, and its ribbons follow the camera.
- **Pattern**: in vacuum, crests and envelope move together at c, so the pattern is fixed within the packet.
- **Crests on screen**: the distance between crests is λ, to scale when it fits on screen (X-rays: ~50 pm). A UV, visible or IR photon is thousands of times larger than the atom: its crests are then compressed along log λ, and the label gives the factor ("λ drawn ÷1,128"). The true picture is an atom bathed in a nearly uniform field oscillating at ν (dipole approximation).
- **Colour**: CIE 1931 (fit by Wyman, Sloan and Shirley 2013) → sRGB, for the whole simulator. Outside the visible, an announced conventional colour.
- **Ionising photon** (hν > current ionisation energy): chromatic fringes and a trembling halo, ∝ log(1 + (hν − I)/I).
- **Packet length**: an atomic or nuclear line lasts nanoseconds, i.e. ~10⁷ to 10⁸ oscillations; even a γ is a long packet, not a "brief flash". The packet is therefore drawn long.

**The photon in true 3D** (`Photon3DLayer`, JavaFX 3D on the scene, parallel projection aligned pixel for pixel):
- **Volumes**: a thin, almost transparent spindle (stretched sphere, Phong material, specular highlight), so the eye goes to the wave; two meshed ribbons (TriangleMesh, E and B, self-lit), a thin axis and an embedded point light that lights its volumes. The orientation follows the atom's camera.
- **The canvas keeps** the spindle's halo, the ionising photon's fringes, and the glow it casts in the fog it crosses. This glow is a rendering effect, not a computed scattering.

**The particles:**
- **The free electron** is a very bright point in a fading halo, not a marble: it is a point particle, with no measured radius.
- **The nucleons**, seen up close, are bubbles: a translucent body, a glowing rim, and their three valence quarks that rotate and exchange their colour. A shared glow melts their edges, and the nucleus reads as a drop. Their tint shifts when a pion changes their identity.

**The interaction.**
- **Path**: the photon heads for the acting orbital (excited or single electron, otherwise the valence), at the point where it is most probable (its lobe, at the most probable radius), and follows it if the atom rotates.
- **Absorption**: the photon disappears, a flash spreads from that point, the new orbital materialises in 0.22 s and pulses, and the old one fades in 0.9 s.
- **Photoionisation**: the electron leaves as a free wave packet, which spreads as it moves like any free packet. Its direction is drawn according to cos²θ around E, its speed on screen ∝ √K (slowed down), and its de Broglie crests are at λ = h/p = 1.226 nm/√(K/eV).
- **Contraction of the ion**: a subshell that keeps the same filling does not flicker. It goes from its old Slater radius to the new one in 0.35 s (sharp then braked motion). According to Slater, only the shells that the departed electron screened contract: the inner shells do not move.

**The right-hand panel** folds (»). A chip replaces it ("SHELLS · SPINS · 5f³ 6d¹"), and it is folded by default below 1,250 px of width.

**Transmutation, visible even at a billion years per second** (the effects last in real time):
- **The nucleus's recoil**, by conservation of momentum. For an α: p = √(2 m_α E_α), v = p/M, i.e. 243 km/s for U-238 → Th-234. For a β, the antineutrino carries an unknown share: the direction is approximate. The motion on screen is damped, and the speed is written.
- **The breathing cloud**: Z changes, so does the attraction. The Slater radii recomputed by the engine are reached as by a damped spring: the cloud swells after an α and tightens after a β⁻.
- **The decay wave**: three fronts with afterglow, moving away from the nucleus.
- **The banner** "Uranium → Thorium", with Z before/after and the recoil.

The photon heads for an impact point drawn at random from |ψ|² of the acting orbital (Monte Carlo): each shot hits somewhere else, where the electron is likely to be. The flash and the freed electron leave from that point. For heavy atoms, the shell map summarises the full subshells at the bottom in one line (click: see all). The cloud labels stay within the scene, away from the header.

**The dive.** Clicking the magnifier (or "Nucleus") crosses the decades at
constant speed (≤ 1.5 decades/s), with the field of view and the mean number of
electrons at that distance from the nucleus (Σ N·P(r' < r)). The camera enters
the cloud: only the slab |t| < 1.5 × the width of the field is integrated, with
exact tables, tabulated in thickness. The 1s veil stays visible for a long
time, and that is real: its density is highest at the nucleus. It fades around
100 fm, and the nuclear drop appears.

**The scale gap** ("Scale gap" button, key `G`). Nucleus and cloud together,
the distance to the nucleus compressed logarithmically in the image plane:
ρ = K·log₁₀(1 + r / 1 fm), one ring per decade from 1 fm to 10 nm. Brightness
follows the probability per area of the deformed image (column ×
b·db / (ρ·dρ)). Subshells become rings at their decade, and the centre, huge in
the image, is nearly empty: the screen computes how many electrons are there
(2.8·10⁻³ between 4.5 fm and 0.21 pm for iron). A card gives the true
proportion: if the nucleus were a 1 cm marble, the cloud would extend over
hundreds of metres.

**The nucleus, a drop of quantum liquid** (`NuclearDrop`). The nucleons placed
by the engine are rotated in 3D and depth-sorted, inside a membrane: the
computed silhouette of the deformed surface, with a diffuse skin (Woods–Saxon,
a = 0.55 fm). The surface vibrates according to Bohr and Mottelson:
R(θ, φ) = R₀ (1 + Σ α_λμ Y_λμ), modes λ = 2 and 3. Stiffness C_λ (surface
tension a_s against proton repulsion a_c, those of `nucleus.py`), irrotational
flow inertia B_λ = 3 A m_N R₀² / (4πλ), ħω_λ = ħ √(C_λ/B_λ), zero-point
amplitude β² = (2λ+1) ħ / (2 B ω). The nucleons follow the displacement field
∝ r^(λ−1) Y_λμ. The real period (~10⁻²¹ s) is shown in 3.6 s, with the true
frequency ratio λ = 3 / λ = 2. The model is coarse: if it predicts β > 0.3
(light nuclei, iron…), the card says so and the displayed amplitude is capped
at 0.12. The nucleons exchange **virtual pions**, each drawn as a flux tube a
bead runs through: range ħ/(m_π c) = 1.41 fm, duration ħ/(m_π c²) =
4.7·10⁻²⁴ s (slowed to 1.7 s), neighbour chosen according to Yukawa
e^(−r/1.41 fm)/r. Between a proton and a neutron, two exchanges out of three are
charged (√2 coupling for π±). The proton that emits a π⁺ (u d̄) becomes a
neutron, the neutron that absorbs it becomes a proton: the colours change, the
total charge never does (tested at every step). The "Quantum drop" card gives
ħω₂, the period, β₂ and the count of exchanges.

## What the screen shows

- **The map**: the atom in 3D at its true scale (see "The atom in 3D"): a
  probability fog |ψ|² per subshell, named with its filling and spins; the
  photons (coloured by λ, dotted if invisible), captured or repelled electrons,
  the particles leaving the nucleus. A **magnifier** permanently shows the
  nucleus, a hundred thousand times smaller (click: you dive into it, slowly,
  through the void). Hover: the subshell that dominates under the mouse, the
  nucleus, the electron, the pion.
- **The bubble**: the last event and its calculation, linked to the electron or
  nucleus that produced it; it stays until the next one.
- **The panels** depending on the scale: shells and spins as an energy diagram,
  1s at the bottom and each following subshell (Klechkowski) above; hovering a
  subshell explains its rank (n + l, neighbours, measured exceptions) (atom);
  Weizsäcker forces and measured mass balance E = Δm·c² (nucleus); quarks,
  colours, spins (nucleon).
- **The cards**: click a particle (electron, nucleon, photon, quark, α,
  neutrino…): mass, charge, spin, magnetic moment, lifetime (CODATA, PDG).
- **The bottom of the screen**: what may happen next ("half-life 5,700 years
  → ≈ 8 s of waiting at this speed").
- **Light**: energy levels with the electron and its jumps; spectrum with the
  lines absorbable right now (▲), the ionisation threshold and the laser.
- **Orbital**: for a one-electron system, the density of the (n,l) state
  actually occupied, reduced mass included. The engine does not track m:
  equiprobable mixture of the orientations, hence an isotropic density, even in
  p. The dots are draws conditioned on the xz slice, not a projection of 3D
  measurements. For many-electron atoms: valence approximated by Slater; an
  excited density that is not computed is marked as missing. The map shows all
  subshells in 3D (see "The atom in 3D"). The **phase ±** chip shows the sign of
  ψ = R_nl·Y_lm for one orbital — the one that received the last electron (Hund),
  or the last visible occupied one if that electron lies outside the xz slice —
  blue for ψ > 0, orange for ψ < 0, brightness following |ψ|². The panel's
  drawing shrinks with the space left by the journal instead of being cut.
- **Nucleus**: valley of stability, half-life, probability per second, paths.
- **Matter**: the box in 3D (drag to rotate), atoms coloured by agitation or
  state, trails of gas atoms, impacts on the walls; the measurements
  (temperature, pressure, density, share of each state), the real values for
  comparison, the V(r) curve.
- **Journal**: all events, from newest to oldest.

## Limits of the model

They are also shown in the application (the atom's ⓘ chip).

- **Electron cloud**: hydrogen-like orbitals with Slater's effective charge.
  Exact for a single electron, approximate beyond: no Hartree–Fock, no
  relativistic effects (gold, mercury, heavy elements).
- **Energies and lines**: measured (NIST) when they exist, otherwise estimated
  with Slater's rules.
- **Nucleus**: measured masses and decays (AME2020, NUBASE, ICRP-107);
  Weizsäcker for nuclei never observed. The arrangement of the nucleons is a
  picture, not a shell-model calculation.
- **Chance**: each event is drawn from the measured probabilities; two runs do
  not tell the same story.
- **Neutrons**: only thermal neutrons (0.0253 eV), and only on nuclei whose
  cross sections are tabulated (natural isotopes and a few actinides).
- **Beyond oganesson** (Z = 118): no proton is added.
- **Chain reaction**: isotropic scattering after the transport correction;
  graphite and beryllium as free gases (no S(α,β)); β not weighted by
  importance; during the excursion, solid inertia, plutonium phase changes,
  ionisation and fuel burn-up in k are neglected, and the reflector stays still.
  Only the 16 nuclides of the group library have a k∞.
- **Beyond iron** (Z₁ + Z₂ > 26): the collision chamber speaks of
  transmutation, not fusion, because welding two nuclei there costs energy.

## Where the numbers come from

| Quantity | Source |
|---|---|
| Physical constants, particle masses and spins | CODATA, via `scipy.constants`; PDG (quarks, neutrinos) |
| Spin and parity of nuclei | NUBASE, `mendeleev` database |
| Lennard-Jones parameters ε, σ | Magalhães et al. 2013, Poling et al. 2001, via `chemicals`; otherwise Bird–Stewart–Lightfoot |
| Real melting, boiling and critical points | `mendeleev` database |
| Neighbours, pair lists | `scipy.spatial.cKDTree` |
| Bonds, densities, molecular orbitals | DFT PBE0/def2-SVP, `pyscf` |
| Pauling electronegativity (comparison) | `mendeleev` database |
| Coulomb barrier, quantum tunnelling | nuclear radius R = 1.2·A^⅓ fm, Gamow factor |
| Exit channels of fusions | measured reactions (D+T, D+D, D+He-3, p+Li-7, D+Li-6, p+B-11, p+D) |
| Range of the strong force | ħ/(m_π c), m_π± = 139.570 MeV (PDG) |
| Stellar reaction rates, screening | REACLIB, via `pynucastro` (screen5) |
| Solar neutrino energies | Bahcall, *Neutrino Astrophysics* (1989); otherwise computed β spectrum |
| Plasma ionisation | Saha equation + potential lowering (ion sphere) |
| Electron pressure | relativistic Fermi–Dirac statistics (`scipy.integrate`) |
| Stellar places | standard solar model; ITER for the reactor |
| Wave functions, integrals | `scipy.special` (Laguerre), `scipy.integrate` |
| Half-lives, modes, branchings, daughters | ICRP-107 / NUBASE2020, via `radioactivedecay` |
| Fast and thermal neutron cross sections, ν̄, χ, delayed neutrons, fission energy (chain reaction) | ENDF/B-VIII.0 (NNDC, OpenMC HDF5), collapsed to 44 groups by `logic/tools/build_chain_groups.py` |
| Reference critical assemblies (validation) | ICSBEP: Godiva, Jezebel, Jezebel-233, Flattop-25; analytic benchmark LA-13511 |
| Molecular electrons and forces (molecular laboratory) | GFN1-xTB (Grimme, Bannwarth, Shushkov 2017); parameters from xtb / tblite (LGPL-3.0), STO-NG expansions, D3 radii and reference C6 (Grimme 2010) extracted by `logic/tools/build_xtb_data.py`; checked against dxtb |
| Molecular geometries, vibrations, reaction enthalpy (validation) | NIST CCCBDB; NIST-JANAF (ΔH°f of water) |
| Fission multiplicity distribution, spontaneous-fission ν̄ | Terrell (1957); Holden & Zucker (1985) |
| Thermal expansion of U, Pu, Th | CRC Handbook of Chemistry and Physics |
| Thermal neutron cross sections (scattering, absorption) | Sears, *Neutron News* 3 (1992) 26, NIST table via `periodictable` |
| Share of fission, (n,α), (n,p) in absorption; neutrons per fission ν̄ | ENDF/B-VIII.0 thermal evaluations (Mughabghab) |
| Atomic masses (Q), very short-lived nuclei | AME2020 / NUBASE, `mendeleev` database |
| Ionisation energies of each ion, affinities | NIST, `mendeleev` database |
| Emission lines, lifetimes of resonance lines | NIST ASD (embedded table) |
| Electron configuration | Klechkowski + 20 real exceptions |
| Effective charge, radii | Slater's rules |
| Nuclear binding balance (terms) | Weizsäcker |
| Nucleus never observed | estimates stated as such (Viola–Seaborg, Fermi phase space) |

## Tests

```bash
cd logic && python -m unittest discover -s tests -t .     # the whole engine (about ten minutes)
cd logic && python -m unittest tests.test_fusion          # a single test file
cd sim   && gradle test                                   # contract and rendering
```

## Structure

The repository has five folders; each has its README, which says what it holds and how to use it (written in French).

| Folder | Contents |
|---|---|
| [`core/`](core/README.md) | The gRPC contract shared by the engine and the client (`genesis.proto`) |
| [`logic/`](logic/README.md) | The Python engine: the physics, organised by domain |
| [`sim/`](sim/README.md) | The JavaFX client: the screens, organised by laboratory |
| [`docs/`](docs/README.md) | The physical models, explained in detail |
| [`artifacts/`](artifacts/README.md) | Preview images and frames |

The engine and the client are organised the same way, by domain:

| Domain | Engine (`logic/`) | Client (`sim/app/src/main/java/org/genesis/`) |
|---|---|---|
| Entry point | `engine.py` | `app/` (the application, the journal, the timeline) |
| gRPC binding | `genesis_pb2*.py` (generated) | `engine/` |
| Session, data | `simulator/` | — |
| Shared tools | `common/` | `common/` |
| The atom and its light | `atomic/` | `atom/` |
| The nucleus | `nuclear/` | `atom/` (nucleus panels) |
| Molecule and matter | `molecular/` | `matter/` |
| Fusion, reactor, star | `fusionlab/` | `fusion/` |
| Subatomic world | `quantumlab/` | `subatomic/` |
| Molecular laboratory (GFN1-xTB) | `moleculelab/` | `molecule/` |
| Chain reaction, critical mass (hidden) | `chainlab/` | `chain/` |
| Genesis (set aside, not shown) | — | `mascot/` |
| Tools and tests | `tools/`, `tests/` | `sim/app/src/test/java/org/genesis/` (same packages) |

File by file:

| Path | Role |
|---|---|
| `core/genesis.proto` | The shared contract (v28), absorbable wavelengths in double precision |
| `logic/chainlab/transport.py` | Neutron transport in a sphere: first-collision probabilities, k_eff, adjoint flux, Λ |
| `logic/chainlab/lab.py` | The chain reaction laboratory: settings, critical radius, cascade, excursion |
| `logic/moleculelab/xtb.py` | GFN1-xTB in numpy: energy and analytic forces, term by term |
| `logic/moleculelab/lab.py` | The molecular laboratory: workbench, relaxation, dynamics, pulling, spark, reactor, vibrations |
| `sim/app/.../molecule/MoleculeView.java` | The molecular laboratory screen; the scene in `MoleculeScene` (density view), `DeformationMap` (Δρ contour map), `OrbitalSurface` and `OrbitalLayer` (ψ glass lobes; `OrbitalDots` without 3D); the readings in `EnergyBalance` (energy balance), `EnergyTarget` (orbital levels as a target) and `AtomMap` (force map of the selected atom) |
| `sim/app/.../chain/ChainView.java` | The chain reaction screen; 3D sphere in `ChainScene`, sandbox in `YieldView` |
| `logic/atomic/light_interaction.py` | Natural profiles, Gaussian beam and hydrogen-like 1s continuum |
| `logic/simulator/simulation.py` | The clock and the rules: the living session |
| `logic/engine.py` | gRPC server: reads the actions, advances time, sends the events |
| `logic/atomic/hydrogen.py` | Einstein coefficients, lifetimes, absorption (one electron) |
| `logic/nuclear/nuclear_data.py` | Access to the nuclear databases (radioactivedecay, mendeleev) |
| `logic/nuclear/neutrons.py` | A slow neutron on a nucleus: measured thermal cross sections, drawn outcome, Q from measured masses |
| `logic/atomic/atomic_data.py` | Ionisations, affinities, resonance lines, spin doublets |
| `logic/common/particles.py` | The particle zoo: mass, charge, spin… |
| `logic/common/elements_db.py` | The `mendeleev` SQLite base behind one locked connection, shared by every thread |
| `logic/molecular/matter.py` | Molecular dynamics: Lennard-Jones, thermal walls, measured states |
| `logic/molecular/chemistry.py` | Two atoms in quantum chemistry: E(R), density, orbitals, dynamics |
| `logic/fusionlab/fusion.py` | The laboratory: potential, WKB, trajectory, evaporation cascade, fission, |ψ|² clouds, interpretations |
| `logic/fusionlab/collider.py` | Measured reactions, labels, two-body kinematics |
| `logic/fusionlab/fusion_dynamics.py` | Integrated collisions, computed weld (friction), probabilistic outcomes and product kinematics; nuclear data in `reactor.py` |
| `logic/fusionlab/lawson.py` | The plasma: Maxwellian ⟨σv⟩, bremsstrahlung, Lawson criterion, drawing the energy of a shot |
| `sim/app/.../fusion/ReactorView.java` | Fusion laboratory: the map, the settings, the results; fields in `FusionField`, rendering (map or close-up) in `FusionRenderer` |
| `sim/app/.../fusion/ResultView.java` | The interactive results: floating or detached window; a chosen element brings the view to it |
| `sim/app/.../fusion/CollisionTimeline.java` | The playback steps (physical time, tunnel, weld, products on the map) |
| `sim/app/.../fusion/FusionLab.java` | The old 3D laboratory (no longer linked to the interface) |
| `sim/app/.../common/LabCamera.java` | The camera with inertia and the perspective projection |
| `logic/fusionlab/star.py` | The stellar core: REACLIB network, Saha, Fermi–Dirac, energy, fusion window |
| `sim/app/.../fusion/StarView.java` | The stellar core screen |
| `sim/app/.../matter/MoleculeCanvas.java` | The molecule: density, Δρ, E(R), orbital diagram |
| `sim/app/.../matter/MatterCanvas.java` | The box of atoms in 3D, piston, measurements |
| `logic/atomic/atom.py` | Evaluation of the atom: shells, levels, orbital, nucleus |
| `logic/nuclear/halflife.py` | Estimates for nuclei never observed |
| `sim/app/.../atom/BohrModel.java` | The Bohr view: tilted rings, electrons that arrive, jump and leave, noble-gas core |
| `sim/app/.../atom/SimCanvas.java` | The map: scales, events, magnifier, dive, scale gap, banner |
| `sim/app/.../atom/OrbitalCloud.java` | The 3D cloud: |ψ|² projected exactly, dive slices, fleeting measurements |
| `sim/app/.../atom/NuclearDrop.java` | The drop nucleus: Bohr–Mottelson vibrations, p ↔ n pion exchanges |
| `sim/app/.../atom/Camera3D.java` | The camera of the 3D views of the atom and the nucleus |
| `sim/app/.../app/LabLoader.java` | The waiting screen when opening or leaving a laboratory; at start-up, the "Support Genesis" section |
| `sim/app/.../app/UpdateChecker.java` | The latest GitHub release, version comparison, verified download (SHA-256) |
| `sim/app/.../app/UpdateCard.java` | The "new version available" card |
| `sim/app/.../app/I18n.java` | Display-time translation (French source → English): reviewed labels, exact sentences, `{0}` templates, segments |
| `sim/app/.../atom/Photon3DLayer.java` | The photon in JavaFX 3D (spindle, meshed E/B ribbons, light), set on the scene |
| `sim/app/.../app/TimelineBar.java` | The timeline: decay chain as a metro line, time speed in plain words |
| `sim/app/.../atom/PhotonPacket.java` | The photon: 3D wave packet (E, B, envelope) built from λ |
| `sim/app/.../atom/LightPanel.java` | Energy levels and spectrum (laser setting) |
| `sim/app/.../atom/NucleusPanel.java` | Valley of stability and nucleus data |
| `sim/app/.../app/EventJournal.java` | The journal: logs by category, filters, compact view under Genesis |
