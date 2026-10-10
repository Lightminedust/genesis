"""Laboratoire de collisions calculées, sans trajectoire de tunnel inventée.

Trajectoires classiques : solve_ivp DOP853, Coulomb + Woods-Saxon + q v×B.
Réaction : section efficace Bosch–Hale / aire du disque incident (modèle
géométrique uniforme, pas une section différentielle en paramètre d'impact).
Tunnel : enveloppe WKB du potentiel effectif, distincte du taux expérimental.
Produits : cinématique relativiste à deux corps, Lorentz exact dans B uniforme.
Les positions internes de nucléons et les moments orientés sont illustratifs.
"""
import math
from functools import lru_cache
import numpy as np
from scipy import constants as sc
from scipy.integrate import solve_ivp, cumulative_trapezoid
from scipy.special import expit

import fusion
import lawson
import reactor
import nuclear_data

HEAVIEST_COMPOUND_Z = 118      # au-dela, aucun noyau n'a jamais ete observe : quasi-fission


def _grouped(names):
    """« n + n + He-4 » devient « 2 n + He-4 »."""
    counts = {}
    for name in names:
        counts[name] = counts.get(name, 0) + 1
    return [(f'{count} {name}' if count > 1 else name) for name, count in counts.items()]

FM_C_S = 1e-15 / sc.c
HBAR_C = fusion.HBAR_C
N_PER_MEV_FM = 1e21 * sc.e
# Friction radiale de Gross & Kalinowski (Phys. Rep. 45, 1978) : gamma(r) = K (dV_N/dr)^2, K = 4e-23 s/MeV (en fm/c par MeV).
GK_FRICTION = 4e-23 / FM_C_S
ELECTRON_MEV = sc.physical_constants['electron mass energy equivalent in MeV'][0]


@lru_cache(maxsize=1)
def collision_catalog():
    """The database includes evaluated and extrapolated masses, not only measurements."""
    return fusion.catalog()


@lru_cache(maxsize=4096)
def nucleus_properties(z, a):
    mass_u, estimated = fusion.mass_u(z, a)
    row = nuclear_data._DB.execute(
        'select spin, g_factor from isotopes where atomic_number=? and mass_number=?', (z, a)).fetchone()
    spin_text = nuclear_data.spin_parity(z, a)
    # A measured I=0 implies zero dipole; absent g factor for I!=0 does not.
    zero_spin = bool(spin_text) and spin_text.strip('()+- ') == '0'
    known_moment = (z == 0 and a == 1) or zero_spin or bool(row and row[1] is not None and row[0])
    data = nuclear_data.info(z, a) or {}
    binding = ((z * nuclear_data.atomic_mass_u(1, 1) + (a-z) * fusion.NEUTRON_MASS_U
                - mass_u) * fusion.MEV_PER_U / a)
    return dict(z=z, a=a, label=fusion.label(z,a), name=fusion.full_name(z,a),
                symbol=nuclear_data.SYMBOLS.get(z,'n'), color_hex=fusion.element_color(z),
                mass_u=mass_u, mass_mev=reactor.nuclear_mass_mev(z,a), mass_estimated=estimated,
                mass_source=('Weizsaecker, estimation' if estimated else
                             'AME / Mendeleev : valeur evaluee ; statut mesure/extrapolation non fourni'),
                radius_fm=reactor.sphere_radius(z,a), radius_measured=(z,a) in reactor.CHARGE_RADIUS_FM,
                magnetic_moment=fusion.magnetic_moment(z,a), moment_known=known_moment,
                spin_parity=spin_text or 'inconnu', stable=bool(data.get('stable',False)),
                half_life_s=float(data.get('half_life_s') or 0),
                binding_per_nucleon_mev=binding, charge_state=z)


def two_body_momentum(total, m1, m2):
    """Stable Kallen expression: total energy and rest masses in MeV."""
    if total < m1+m2-1e-9:
        raise ValueError('Canal ferme : energie insuffisante')
    return math.sqrt(max(0., (total-m1-m2)*(total+m1+m2)*(total-m1+m2)*(total+m1-m2))) / (2*total)


def boost_four(energy, momentum, beta):
    """Lorentz boost from a parent's rest frame to the laboratory CM frame."""
    beta = np.asarray(beta)
    b2 = float(np.dot(beta,beta))
    if b2 < 1e-30:
        return float(energy), np.asarray(momentum).copy()
    gamma = 1/math.sqrt(1-b2)
    dot = np.dot(beta,momentum)
    return gamma*(energy+dot), np.asarray(momentum)+(((gamma-1)*dot/b2)+gamma*energy)*beta


def reflect_at_contact(state, mass_a, mass_b):
    """Reflect the inward relative momentum at the contact surface.

    Work in the pair's centre-of-momentum frame, reverse only the normal
    component, then boost back. This preserves total four-momentum and the
    tangential momentum (hence orbital angular momentum) at contact.
    """
    outgoing=np.asarray(state,dtype=float).copy()
    normal=outgoing[:3]-outgoing[3:6]
    normal/=np.linalg.norm(normal)
    pa,pb=outgoing[6:9],outgoing[9:12]
    ea,eb=math.hypot(mass_a,float(np.linalg.norm(pa))),math.hypot(mass_b,float(np.linalg.norm(pb)))
    cm_beta=(pa+pb)/(ea+eb)
    _,relative=boost_four(ea,pa,-cm_beta)
    inward=float(np.dot(relative,normal))
    if inward>=0:
        raise ValueError('Contact sans vitesse radiale entrante : rebond impossible')
    relative=relative-2*inward*normal
    ea_cm=math.hypot(mass_a,float(np.linalg.norm(relative)))
    eb_cm=math.hypot(mass_b,float(np.linalg.norm(relative)))
    _,outgoing[6:9]=boost_four(ea_cm,relative,cm_beta)
    _,outgoing[9:12]=boost_four(eb_cm,-relative,cm_beta)
    return outgoing


def potential_terms(pair, radius):
    r = np.maximum(np.asarray(radius, dtype=float), 1e-9)
    c = fusion.COULOMB * pair.zz
    vc = np.where(r >= pair.contact, c / r,
                  c / (2 * pair.contact) * (3 - (r / pair.contact) ** 2))
    fc = np.where(r >= pair.contact, c / r ** 2, c * r / pair.contact ** 3)
    f = expit((pair.contact - r) / fusion.DIFFUSENESS_FM)
    vn = -pair.depth * f
    fn = -pair.depth / fusion.DIFFUSENESS_FM * f * (1 - f)
    return vc, vn, fc, fn


def magnetic_field(displacement_fm, moment_mu_n, axis, radius_fm):
    """Dipôle régularisé, B = curl[μ₀ m×r / (4π(r²+a²)^(3/2))]."""
    r = np.asarray(displacement_fm) * 1e-15
    a = radius_fm * 1e-15
    m = np.asarray(axis) * moment_mu_n * reactor.MU_N
    r2 = np.dot(r, r)
    return sc.mu_0 / (4 * math.pi) * (3 * r * np.dot(m, r) + m * (2 * a * a - r2)) / (r2 + a * a) ** 2.5


def product_kinematics(fuel, branch, energy_kev, direction):
    products = fuel['branches'][branch][1]
    before = sum(reactor.nuclear_mass_mev(*p) for p in (fuel['a'], fuel['b']))
    masses = [reactor.nuclear_mass_mev(*p) for p in products]
    total = before + energy_kev / 1000
    m1, m2 = masses
    momentum = math.sqrt((total ** 2 - (m1 + m2) ** 2) * (total ** 2 - (m1 - m2) ** 2)) / (2 * total)
    result = []
    for za, mass, sign in zip(products, masses, (1, -1)):
        e = math.hypot(mass, momentum)
        result.append({'za': za, 'mass': mass, 'kinetic': momentum ** 2 / (e + mass),
                       'v': np.asarray(direction) * (sign * sc.c * momentum / e)})
    return result, before - sum(masses)


def uniform_field_orbit(velocity, charge, mass_mev, field_t, time_s):
    """Solution relativiste exacte de Lorentz dans B=(0,0,B), sans E."""
    v = np.asarray(velocity, dtype=float)
    gamma = 1 / math.sqrt(1 - np.dot(v, v) / sc.c ** 2)
    mass = mass_mev * 1e6 * sc.e / sc.c ** 2
    omega = charge * sc.e * field_t / (gamma * mass)
    if abs(omega * time_s) < 1e-10:
        return v * time_s, v.copy()
    angle = omega * time_s
    sn, cs = math.sin(angle), math.cos(angle)
    one_minus_cos = 2 * math.sin(angle / 2) ** 2
    p = np.array([(v[0] * sn + v[1] * one_minus_cos) / omega,
                  (v[1] * sn - v[0] * one_minus_cos) / omega, v[2] * time_s])
    return p, np.array([v[0] * cs + v[1] * sn, v[1] * cs - v[0] * sn, v[2]])


class FusionExperiment:
    """A reproducible collision experiment, for any catalogued isotope pair.

    Bare nuclei only. The entrance potential is an effective optical model,
    not QCD. Measured light-channel fits and generic capture have distinct labels.
    """
    def __init__(self, rng_seed=None):
        self.rng = np.random.default_rng(rng_seed)
        self.fuel_index, self.kt_kev, self.field_t = 0, 15.0, 5.3
        self.density_m3, self.confinement_s = 1e20, 3.0     # plasma de réacteur (ITER : 10²⁰ m⁻³, ~3 s)
        self.rarity = 0.0
        self.a, self.b = (1,2), (1,3)
        self.energy_kev, self.impact_fm, self.thermal_mode = 64., 0., False
        self.run = self.sent_run = self.trials = self.reactions = 0
        self.catalog_sent = False
        self.shot = None
        self.error = ''

    @property
    def fuel(self):
        for fuel in reactor.FUELS:
            if sorted((self.a,self.b)) == sorted((fuel['a'],fuel['b'])):
                return dict(fuel, a=self.a, b=self.b)
        return dict(a=self.a,b=self.b,compound=(self.a[0]+self.b[0],self.a[1]+self.b[1]),branches=[])

    def _reset(self):
        self.shot = None
        self.error = ''
        self.trials = self.reactions = 0

    def set_nucleus(self, which, code):
        z,a = divmod(int(code),1000)
        if which not in ('a','b') or not 1 <= z <= 118 or not nuclear_data.is_known(z,a):
            raise ValueError('Isotope absent du catalogue : choisir un element puis un isotope disponible')
        setattr(self,which,(z,a))
        self.fuel_index = next((i for i,f in enumerate(reactor.FUELS)
                               if sorted((self.a,self.b)) == sorted((f['a'],f['b']))),-1)
        self._reset()

    def set_fuel(self,index):
        if not 0 <= int(index) < len(reactor.FUELS):
            raise ValueError('Combustible inconnu')
        self.fuel_index=int(index)
        self.a,self.b=reactor.FUELS[self.fuel_index]['a'],reactor.FUELS[self.fuel_index]['b']
        self._reset()

    @staticmethod
    def _number(value,low,high,label):
        if not math.isfinite(value) or not low <= value <= high:
            raise ValueError(f'{label} : valeur attendue entre {low:g} et {high:g}')
        return float(value)

    def set_energy(self,kev):
        self.energy_kev=self._number(kev,.1,2e6,'Energie (keV)')
        self.thermal_mode=False
        self.error=''

    def set_impact(self,fm):
        self.impact_fm=self._number(fm,0,50,"Parametre d'impact (fm)")
        self.error=''

    def set_thermal(self,on):
        self.thermal_mode=bool(on)
        self.error=''

    def set_temperature(self,kev):
        self.kt_kev=self._number(kev,.5,5000,'Temperature (keV)')
        self.error=''

    def set_density(self,per_m3):
        self.density_m3=self._number(per_m3,1e16,1e33,'Densite (ions/m3)')
        self.error=''

    def set_confinement(self,seconds):
        self.confinement_s=self._number(seconds,1e-6,1e10,'Temps de confinement (s)')
        self.error=''

    def plasma(self):
        """Le bilan du plasma réglé (critère de Lawson) pour le couple choisi."""
        return lawson.lawson(self.a,self.b,float(self.kt_kev),float(self.density_m3),float(self.confinement_s))

    def set_field(self,tesla):
        self.field_t=self._number(tesla,0,12,'Champ magnetique (T)')
        self.error=''

    def _direction(self):
        v=self.rng.normal(size=3)
        return v/np.linalg.norm(v)

    def fire(self,force=False):
        """Un tir. `force` : on montre la fusion (la capture est retenue), sa probabilité réelle reste affichée."""
        energy=self.energy_kev
        self.rarity=0.
        if self.thermal_mode:
            # L'énergie du choc est tirée dans le plasma : flux maxwellien, ou pic de Gamow si l'on veut une fusion.
            try:
                energy,self.rarity=lawson.sample_energy(self.a,self.b,self.kt_kev,self.rng,bool(force))
            except ValueError as error:
                self.error=str(error)
                return [{'kind':'info','title':'Collision non calculee','detail':self.error}]
        try:
            shot=self.calculate(energy,bool(force))
        except (ValueError,RuntimeError,FloatingPointError,OverflowError) as error:
            self.error=str(error)
            return [{'kind':'info','title':'Collision non calculee','detail':self.error}]
        self.shot=shot
        self.error=''
        return [{'kind':'fusion' if shot['fused'] else 'info','title':shot['reaction'],
                 'formula':f"E = {energy:.4g} keV ; P = {shot['probability']:.3%}",
                 'detail':shot['notes']}]

    def calculate(self,energy_kev,force=False):
        self._number(energy_kev,.1,2e6,'Energie (keV)')
        fuel=self.fuel
        pair=fusion.Pair(self.a,self.b)
        ma,mb=(reactor.nuclear_mass_mev(*p) for p in (self.a,self.b))
        pair.mu=ma*mb/(ma+mb)
        energy=energy_kev/1000
        # Relative energy divided by reduced baryon number bounds both projectile velocities.
        reduced_a=self.a[1]*self.b[1]/(self.a[1]+self.b[1])
        if energy/reduced_a>10:
            raise ValueError(f'Hors modele : au-dela de {10*reduced_a:.4g} MeV pour ce couple (10 MeV par nucleon reduit),'
                             ' les noyaux se fragmentent ; non modelise.')
        if pair.zz>0 and pair.turning_point(energy)>=.99e6:
            raise ValueError(f'Energie trop basse : le mur de Coulomb de ce couple est a {pair.barrier:.4g} MeV ;'
                             f' a {energy_kev:.3g} keV ils s arretent a plus d un nanometre l un de l autre.')
        p_inf=two_body_momentum(ma+mb+energy,ma,mb)
        k=p_inf/HBAR_C
        light=bool(fuel['branches']) and 2 <= energy_kev <= 400
        # p + p : pas de noyau lie a deux protons ; la seule fusion passe par l'interaction faible, pendant le contact.
        pp=self.a==(1,1) and self.b==(1,1)
        branch=0
        impact=self.impact_fm
        if pp:
            gamow=math.pi*sc.alpha*math.sqrt(2*pair.mu*1000)
            sigma_b=float(lawson.PP_S0_KEV_B/energy_kev*math.exp(-gamow/math.sqrt(energy_kev)))
            aperture=1/k
            if self.thermal_mode or (force and impact>aperture):
                impact=aperture*math.sqrt(self.rng.random())
            probability=sigma_b*100/(math.pi*aperture**2) if impact<=aperture else 0.
            model='Interaction faible p + p (S(0) = 4,01e-22 keV b, Adelberger 2011) + trajectoire relativiste'
            notes=('p + p -> d + e+ + nu_e : pendant le contact, un proton devient neutron (interaction faible) ; spectre beta'
                   ' permis p E (E0 - E)^2, impulsion conservee par le recul du deuton.')
        elif light:
            sigmas=np.array([float(reactor.cross_section_b(name,energy_kev)) for name,_ in fuel['branches']])
            sigma_b=float(sum(sigmas))
            aperture=max(1/k,math.sqrt(sigma_b*100/(.5*math.pi)))
            if self.thermal_mode or (force and impact>aperture):
                impact=aperture*math.sqrt(self.rng.random())
            probability=sigma_b*100/(math.pi*aperture**2) if impact<=aperture else 0.
            branch=int(self.rng.choice(len(sigmas),p=sigmas/sum(sigmas)))
            model='Bosch-Hale (2-400 keV) + trajectoire relativiste'
            notes="Section efficace ajustee aux mesures ; probabilite uniforme sur le disque incident, pas une section differentielle en impact."
        else:
            # Partial-wave capture, not compound formation or evaporation-residue production.
            angular=-.5+math.sqrt(.25+(k*impact)**2)
            probability=float(pair.tunneling(energy,angular))
            ell=np.arange(0,min(2000,math.ceil(k*max(pair.barrier_r,pair.contact)*2)+20))
            rg=np.linspace(max(pair.contact*.75,pair.inside),max(4*pair.barrier_r,40),220)
            base=sum(potential_terms(pair,rg)[:2])
            tops=np.max(base[None,:]+(HBAR_C**2/(2*pair.mu))*ell[:,None]*(ell[:,None]+1)/rg[None,:]**2,axis=1)
            transmissions=expit(2*math.pi*(energy-tops)/max(pair.hbar_omega,1e-6))
            sigma_b=float(math.pi/k**2*np.sum((2*ell+1)*transmissions)/100)
            aperture=max(pair.contact,math.sqrt(max(sigma_b,0)*100/math.pi))
            model='Capture effective Woods-Saxon / WKB ; sigma par ondes partielles Hill-Wheeler'
            notes='Capture estimee, non section mesuree de fusion. Noyaux nus ; ecrantage electronique, transfert et couplages collectifs absents.'
        compound=(1,2) if pp else fuel['compound']
        compound_mass=fusion.mass_u(*compound)[0] if compound[0]<=HEAVIEST_COMPOUND_Z else None
        # Une branche absente de la table Bosch-Hale peut encore etre calculee par le modele statistique.
        # Le repli elastique ne s'applique que si aucun compose n'est accessible en energie ; p+p garde sa voie faible.
        closed_channel=(not pp and not light and compound[0]<=HEAVIEST_COMPOUND_Z and
                        (compound_mass is None or ma+mb+energy<reactor.nuclear_mass_mev(*compound)-1e-9))
        if closed_channel:
            notes+=' Aucun noyau compose accessible a cette energie : la capture ne produit pas de voie de sortie liee.'
            probability=sigma_b=0.
        captured=bool(probability>0 and (force or self.rng.random()<probability))
        if force and probability<=0 and not closed_channel:
            raise ValueError(f'Energie trop basse : probabilite de fusion nulle (< 1e-308) a {energy_kev:.3g} keV et b = {impact:.3g} fm ;'
                             f' le mur de Coulomb est a {pair.barrier:.4g} MeV.')
        spins=[self._direction(),self._direction()]
        moments=[fusion.magnetic_moment(*p) for p in (self.a,self.b)]
        radii=[reactor.sphere_radius(*p) for p in (self.a,self.b)]
        # Depart ou la repulsion ne vaut encore que ~8 % de E : les noyaux traversent la carte lances, puis freinent.
        start=max(150.,12*pair.turning_point(energy),3*impact,4*pair.contact)
        vstart=float(sum(potential_terms(pair,start)[:2]))
        momentum=two_body_momentum(ma+mb+energy-vstart,ma,mb)
        speed=momentum/math.hypot(ma,momentum)+momentum/math.hypot(mb,momentum)
        impact_start=impact*p_inf/momentum
        if impact_start>=start:
            raise ValueError('Geometrie initiale hors domaine ; diminuer le parametre d impact')
        relative=np.array([-math.sqrt(start**2-impact_start**2),impact_start,0.])
        initial=np.r_[relative*mb/(ma+mb),-relative*ma/(ma+mb),[momentum,0,0],[-momentum,0,0]]
        b_external=np.array([0.,0.,self.field_t])
        calls=0

        def rhs(_t,s):
            nonlocal calls
            calls+=1
            if calls>65000:
                raise RuntimeError('Integration arretee : limite de calcul atteinte pour cette collision')
            r=s[:3]-s[3:6]
            distance=np.linalg.norm(r)
            _,_,fc,fn=potential_terms(pair,distance)
            force=float(fc+fn)*r/max(distance,1e-9)
            va=s[6:9]/math.sqrt(ma*ma+np.dot(s[6:9],s[6:9]))
            vb=s[9:12]/math.sqrt(mb*mb+np.dot(s[9:12],s[9:12]))
            ba=b_external+magnetic_field(r,moments[1],spins[1],radii[1])
            bb=b_external+magnetic_field(-r,moments[0],spins[0],radii[0])
            rm=r*1e-15
            ea=sc.e*self.b[0]/(4*math.pi*sc.epsilon_0)*rm/max(distance*1e-15,radii[1]*1e-15)**3
            eb=-sc.e*self.a[0]/(4*math.pi*sc.epsilon_0)*rm/max(distance*1e-15,radii[0]*1e-15)**3
            ba+=np.cross(vb,ea)/sc.c
            bb+=np.cross(va,eb)/sc.c
            la=self.a[0]*sc.e*sc.c*np.cross(va,ba)/N_PER_MEV_FM
            lb=self.b[0]*sc.e*sc.c*np.cross(vb,bb)/N_PER_MEV_FM
            return np.r_[va,vb,force+la,-force+lb]

        def turning(_t,s):
            va=s[6:9]/math.sqrt(ma*ma+np.dot(s[6:9],s[6:9]))
            vb=s[9:12]/math.sqrt(mb*mb+np.dot(s[9:12],s[9:12]))
            return np.dot(s[:3]-s[3:6],va-vb)
        turning.direction=1
        def contact(_t,s):return np.linalg.norm(s[:3]-s[3:6])-pair.contact
        contact.direction,contact.terminal=-1,True
        def escaped(_t,s):return np.linalg.norm(s[:3]-s[3:6])-start*1.02
        escaped.direction,escaped.terminal=1,True
        span=8*start/speed
        sol=solve_ivp(rhs,(0,span),initial,method='DOP853',rtol=3e-9,atol=1e-10,
                      max_step=span/180,events=(turning,contact,escaped),dense_output=True)
        if not sol.success:raise RuntimeError(sol.message)
        touched=bool(len(sol.t_events[1]))
        decision=sol.t_events[1][0] if touched else sol.t_events[0][0] if len(sol.t_events[0]) else sol.t[-1]
        unresolved_contact=touched and not captured
        time_scale=pair.contact/speed*FM_C_S
        stop=decision if captured else sol.t[-1]
        tau=np.linspace(np.arcsinh(-decision*FM_C_S/time_scale),np.arcsinh((stop-decision)*FM_C_S/time_scale),360)
        times=np.clip(decision+np.sinh(tau)*time_scale/FM_C_S,0,stop)
        samples,error=[],0.
        for t,state in zip(times,sol.sol(times).T):
            sep=np.linalg.norm(state[:3]-state[3:6])
            vc,vn,fc,fn=(float(v) for v in potential_terms(pair,sep))
            pa,pb=state[6:9],state[9:12]
            ea,eb=math.sqrt(ma*ma+np.dot(pa,pa)),math.sqrt(mb*mb+np.dot(pb,pb))
            kinetic=np.dot(pa,pa)/(ea+ma)+np.dot(pb,pb)/(eb+mb)
            error=max(error,abs(kinetic+vc+vn-energy)/energy)
            bodies=[self._body(*p,state[i*3:i*3+3],momentum_vec/e*sc.c,spins[i])
                    for i,(p,momentum_vec,e) in enumerate(zip((self.a,self.b),(pa,pb),(ea,eb)))]
            samples.append(dict(time=(t-decision)*FM_C_S,phase='approach' if t<decision or captured else 'scattered',
                                bodies=bodies,coulomb=fc*N_PER_MEV_FM,strong=fn*N_PER_MEV_FM,
                                kinetic=kinetic,potential=vc+vn))
        q=excitation=lifetime=0.
        welded=0.
        if captured and (pp or light or compound[0]<=HEAVIEST_COMPOUND_Z and fusion.mass_u(*compound)[0] is not None):
            merge=self._merge(pair,samples[-1],energy)
            samples.extend(merge)
            if merge:welded=merge[-1]['time']
        after_merge=len(samples)
        outcome='elastic'
        resolved=True
        fused=False
        products=[]
        if captured:
            if pp:
                products,q=self._weak_pp(ma+mb+energy)
                self._sample_products(samples,products,0.,time_scale)
                fused,resolved,outcome=True,True,'fusion_products'
            elif light:
                products,q=product_kinematics(fuel,branch,energy_kev,self._direction())
                lifetime=float(self.rng.exponential(reactor.HBAR_MEV_S/reactor.HE5_WIDTH_MEV)) if compound==(2,5) else 0.
                excitation=ma+mb+energy-reactor.nuclear_mass_mev(*compound)
                if lifetime>0:
                    compound_body=self._body(*compound,np.zeros(3),np.zeros(3),self._direction(),max(0.,excitation))
                    for t in np.linspace(0,lifetime,18,endpoint=False):
                        samples.append(dict(time=float(t),phase='compound',bodies=[compound_body],
                                            coulomb=0.,strong=0.,kinetic=0.,potential=0.))
                particles=[self._track(p['za'],p['mass'],p['v'],lifetime,np.zeros(3)) for p in products]
                self._sample_products(samples,particles,lifetime,time_scale)
                fused,resolved,outcome=True,True,'fusion_products'
            elif compound[0]<=HEAVIEST_COMPOUND_Z and fusion.mass_u(*compound)[0] is not None:
                excitation=ma+mb+energy-reactor.nuclear_mass_mev(*compound)
                if excitation>=0:
                    root=self._track(compound,ma+mb+energy,np.zeros(3),0.,np.zeros(3),excitation)
                    tracks,events,fissioned,excitation_left=self._decay_tree([root],time_scale)
                    samples.extend(self._sample_tree(tracks,events,0.,time_scale))
                    final=[p for p in tracks if p.get('death') is None]
                    products=final
                    q=ma+mb-sum(p['mass']-p.get('excitation',0.) for p in final)
                    resolved=excitation_left<1e-6
                    fused=True
                    outcome='fission' if fissioned else 'modelled_products' if resolved else 'excited_residue'
                    notes+=(' Formation composee supposee apres capture. Desexcitation statistique : evaporation de Weisskopf'
                            ' (n, p, d, t, He3, alpha), gamma, fission de Bohr-Wheeler (partage a densite de charge'
                            ' constante, energie cinetique de Viola) ; les fragments evaporent a leur tour. Masses'
                            ' evaluees (Weizsaecker si inconnues), cinematique relativiste exacte a chaque etape.'
                            ' Radioactivite ulterieure des residus non suivie.')
                    if fusion.mass_u(*compound)[1]:
                        notes+=' Masse du noyau compose estimee (jamais mesuree).'
                    if not resolved:
                        notes+=' Suite interrompue : noyau excite hors du modele de sortie.'
                else:
                    resolved=False
                    outcome='contact_unresolved'
                    notes+=' Masse composee incompatible avec cette energie ; aucune voie de sortie imposee.'
            else:
                tracks,events,frames=self._quasi_fission(samples[-1],ma+mb+energy,pair.contact,time_scale)
                samples.extend(frames)
                products=[p for p in tracks if p.get('death') is None]
                q=ma+mb-sum(p['mass']-p.get('excitation',0.) for p in products)
                resolved,fused,outcome=True,False,'quasi_fission'
                if compound[0]<=118:
                    excitation=ma+mb+energy-reactor.nuclear_mass_mev(*compound)
                notes+=(' Au-dela de Z = 118, aucun noyau ne tient : le systeme a deux noyaux tourne un instant puis se'
                        ' separe (quasi-fission), apres un transfert partiel de nucleons vers la symetrie ; energie'
                        ' cinetique de Viola. Aucun nouvel element annonce.')
            if not products and not light and not fused:
                # Keep both identities: capture alone is not a compound-nucleus prediction.
                last=samples[-1]
                samples.append(dict(last,time=max(0.,last['time']),phase='contact'))
        elif unresolved_contact:
            samples[-1]['phase']='contact'
            outgoing=reflect_at_contact(sol.y_events[1][0],ma,mb)
            scattered=solve_ivp(rhs,(0,span),outgoing,method='DOP853',rtol=3e-9,atol=1e-10,
                                max_step=span/180,events=escaped,dense_output=True)
            if not scattered.success:
                raise RuntimeError(scattered.message)
            if not len(scattered.t_events[0]):
                raise RuntimeError('La branche elastique n a pas atteint la distance de sortie')
            outgoing_times=np.sinh(np.linspace(0,np.arcsinh(scattered.t[-1]*FM_C_S/time_scale),240)[1:])*time_scale/FM_C_S
            for t,state in zip(outgoing_times,scattered.sol(outgoing_times).T):
                sep=np.linalg.norm(state[:3]-state[3:6])
                vc,vn,fc,fn=(float(v) for v in potential_terms(pair,sep))
                pa,pb=state[6:9],state[9:12]
                ea,eb=math.hypot(ma,float(np.linalg.norm(pa))),math.hypot(mb,float(np.linalg.norm(pb)))
                kinetic=np.dot(pa,pa)/(ea+ma)+np.dot(pb,pb)/(eb+mb)
                error=max(error,abs(kinetic+vc+vn-energy)/energy)
                bodies=[self._body(*p,state[i*3:i*3+3],momentum_vec/e*sc.c,spins[i])
                        for i,(p,momentum_vec,e) in enumerate(zip((self.a,self.b),(pa,pb),(ea,eb)))]
                samples.append(dict(time=float(t*FM_C_S),phase='scattered',bodies=bodies,
                                    coulomb=fc*N_PER_MEV_FM,strong=fn*N_PER_MEV_FM,
                                    kinetic=kinetic,potential=vc+vn))
            outcome,resolved='elastic',True
            notes+=(' Contact sans reaction faible : 2He non lie, les protons repartent elastiquement.' if pp else
                    ' Capture non realisee au contact : reflexion elastique de la vitesse radiale dans le centre de masse.')
        for later in samples[after_merge:]:
            later['time']+=welded
        if welded>0:
            notes+=(' Soudure calculee : mouvement radial dans le potentiel Coulomb + Woods-Saxon depuis la sortie du'
                    ' tunnel (ou le contact), friction nucleaire de Gross-Kalinowski ; l energie dissipee devient chaleur.')
        if error>1e-4:
            raise RuntimeError(f'Precision insuffisante : ecart relatif energie {error:.3g}')
        self.run+=1
        self.trials+=1
        self.reactions+=int(fused)
        grid=np.geomspace(max(.5,pair.contact*.7),max(start,100.),320)
        vc,vn,_,_=potential_terms(pair,grid)
        kappa=np.sqrt(np.maximum(0.,2*pair.mu*(vc+vn-energy)))/HBAR_C
        action=cumulative_trapezoid(kappa[::-1],-grid[::-1],initial=0)[::-1]
        names=' + '.join(fusion.label(*p) for p in (self.a,self.b))
        if force and captured:
            notes+=f' Fusion montree a la demande : a ce tir, sa probabilite etait de {probability:.3g}.'
        if products:
            product_names=[p.get('lepton') or ('gamma' if p['za']==(0,0) else fusion.label(*p['za'])) for p in products]
            suffix=' + '.join(_grouped(product_names))
        elif outcome=='elastic':suffix='diffusion elastique'
        else:suffix='contact / issue non resolue'
        if self.thermal_mode:
            notes+=(f' Energie tiree dans un plasma a kT = {self.kt_kev:.3g} keV'
                    f'{" (pic de Gamow : parmi les chocs qui fusionnent)" if force else " (flux maxwellien)"} ;'
                    f' un choc sur 10^{-self.rarity:.1f} est au moins aussi energique.')
        return dict(run=self.run,samples=samples,energy=energy_kev,fused=fused,probability=probability,
                    thermal=self.kt_kev if self.thermal_mode else 0.,rarity=self.rarity,
                    sigma_b=sigma_b,aperture=aperture,impact=impact,time_scale=time_scale,q=q,error=error,
                    reaction=names+' -> '+suffix,field=self.field_t,fuel=self.fuel_index,r=grid,v=vc+vn,
                    wave=np.exp(-np.minimum(action,700)),contact=pair.contact,depth=pair.depth,lifetime=lifetime,
                    wkb=pair.tunneling(energy),barrier=pair.barrier,trials=self.trials,reactions=self.reactions,
                    mu=pair.mu,model=model,outcome=outcome,resolved=resolved,excitation=max(0.,excitation),
                    notes=notes,compound=compound)

    def _track(self,za,mass,velocity,time,position,excitation=0.):
        gamma=1/math.sqrt(1-np.dot(velocity,velocity)/sc.c**2) if mass else 0.
        return dict(za=za,mass=mass,v=np.asarray(velocity),birth=time,origin=np.asarray(position),
                    excitation=excitation,spin=self._direction(),energy=gamma*mass if mass else 0.)

    def _at(self,particle,t):
        elapsed=max(0.,t-particle['birth'])
        if particle['mass']==0:
            p=particle['v']*elapsed
            v=particle['v']
        else:p,v=uniform_field_orbit(particle['v'],particle['za'][0],particle['mass'],self.field_t,elapsed)
        return particle['origin']+p*1e15,v

    def _sample_products(self,samples,particles,birth,time_scale):
        # Both nuclear separation and macroscopic Lorentz transport, densely sampled in log time.
        charged=next((p for p in particles if p['za'][0]>0),None)
        end=1e-8
        if charged:
            gamma=1/math.sqrt(1-np.dot(charged['v'],charged['v'])/sc.c**2)
            period=2*math.pi*gamma*charged['mass']*1e6/sc.c**2/(charged['za'][0]*max(self.field_t,.1))
            end=min(2*period,3e-7)
        ts=np.unique(np.r_[0.,np.geomspace(max(time_scale*.01,1e-25),end,340),np.linspace(0,end,180)])
        for dt in ts:
            self._particle_frame(samples,particles,float(birth+dt),'products')

    def _particle_frame(self,samples,particles,t,phase):
        bodies=[]
        kinetic=0.
        for p in particles:
            pos,vel=self._at(p,t)
            body=self._lepton(p['lepton'],pos,vel) if p.get('lepton') else self._body(*p['za'],pos,vel,p['spin'],p.get('excitation',0.))
            body['kinetic']=p.get('energy',0.)-p['mass']
            bodies.append(body)
            kinetic+=p.get('energy',0.)-p['mass']
        samples.append(dict(time=t,phase=phase,bodies=bodies,coulomb=0.,strong=0.,kinetic=kinetic,potential=0.))

    def _weak_pp(self,total):
        """p + p -> d + e+ + nu_e, a trois corps. Energie du positon tiree dans le spectre beta permis
        p E (E0 - E)^2 (sans fonction de Fermi), directions isotropes ; le deuton prend l'impulsion opposee et
        l'energie du neutrino ferme le bilan (deux iterations sur le recul)."""
        md,me=reactor.nuclear_mass_mev(1,2),ELECTRON_MEV
        available=total-md-me
        if available<=0:
            raise ValueError('Canal ferme : energie insuffisante pour p + p -> d + e+ + nu')
        shape=lambda t:math.sqrt(t*(t+2*me))*(t+me)*(available-t)**2
        peak=max(shape(available*x) for x in np.linspace(.01,.99,60))
        while True:
            te=available*self.rng.random()
            if self.rng.random()*peak<=shape(te):break
        ue,un=self._direction(),self._direction()
        pe=math.sqrt(te*(te+2*me))
        enu=available-te
        for _ in range(3):
            pd=-(pe*ue+enu*un)
            td=math.hypot(md,float(np.linalg.norm(pd)))-md
            enu=max(0.,available-te-td)
        pd=-(pe*ue+enu*un)
        ed=math.hypot(md,float(np.linalg.norm(pd)))
        deuteron=self._track((1,2),md,pd/ed*sc.c,0.,np.zeros(3))
        positron=self._track((1,0),me,pe*ue/(te+me)*sc.c,0.,np.zeros(3))
        neutrino=self._track((0,0),0.,un*sc.c,0.,np.zeros(3))
        positron['lepton'],neutrino['lepton']='e+','nu_e'
        neutrino['energy']=enu
        q=2*reactor.nuclear_mass_mev(1,1)-md-me
        return [deuteron,positron,neutrino],q

    @staticmethod
    def _lepton(kind,position,velocity):
        if kind=='e+':
            return dict(z=1,a=0,position=np.asarray(position),velocity=np.asarray(velocity),spin=np.zeros(3),
                        radius=0.,moment=1838.28,label='e+',name='positon (antielectron)',color='#FF7AD9',
                        spin_parity='1/2',mass=ELECTRON_MEV,excitation=0.,kind='positron',known_moment=True)
        return dict(z=0,a=0,position=np.asarray(position),velocity=np.asarray(velocity),spin=np.zeros(3),
                    radius=0.,moment=0.,label='nu_e',name='neutrino electronique',color='#B8F5C8',
                    spin_parity='1/2',mass=0.,excitation=0.,kind='neutrino',known_moment=True)

    def _merge(self,pair,last,energy):
        """La soudure, calculee : du point ou le mouvement redevient classique (sortie du tunnel, ou contact) jusqu'au
        recouvrement complet. Mouvement radial dans Coulomb + Woods-Saxon, freine par la friction nucleaire
        gamma(r) = K (dV_N/dr)^2 ; l'energie perdue par friction devient chaleur, partagee selon les masses."""
        a,b=last['bodies'][0],last['bodies'][1]
        ma,mb=a['mass'],b['mass']
        m=ma+mb
        axis=np.asarray(a['position'],float)-np.asarray(b['position'],float)
        norm=float(np.linalg.norm(axis))
        axis=axis/norm if norm>0 else np.array([1.,0.,0.])
        def total(r):return float(sum(potential_terms(pair,r)[:2]))
        if total(pair.contact)<=energy:
            r0=pair.contact
        else:
            low,high=1e-3,pair.contact
            if total(low)>energy:return []
            for _ in range(80):
                mid=.5*(low+high)
                if total(mid)>energy:low=mid
                else:high=mid
            r0=high
        mu=pair.mu
        v0=math.sqrt(max(0.,2*(energy-total(r0))/mu))
        end=max(.04*pair.contact,.15)

        def rhs(_t,s):
            _,_,fc,fn=potential_terms(pair,max(s[0],1e-6))
            gamma=GK_FRICTION*float(fn)**2
            return [s[1],(float(fc+fn)-gamma*s[1])/mu,gamma*s[1]*s[1]]
        def overlapped(_t,s):return s[0]-end
        overlapped.terminal,overlapped.direction=True,-1
        def turned(_t,s):return s[1]
        turned.terminal,turned.direction=True,1
        span=4000.
        sol=solve_ivp(rhs,(0,span),[r0,-v0,0.],method='DOP853',rtol=1e-8,atol=1e-10,max_step=span/400,
                      events=(overlapped,turned),dense_output=True)
        if not sol.success or sol.t[-1]<=0:return []
        times=np.linspace(0,sol.t[-1],48)
        out=[]
        for t,(r,v,heat) in zip(times,sol.sol(times).T):
            r=max(float(r),0.)
            vc,vn,fc,fn=(float(x) for x in potential_terms(pair,max(r,1e-6)))
            bodies=[]
            for body,sign,share in ((a,1,mb/m),(b,-1,ma/m)):
                bodies.append(self._body(body['z'],body['a'],sign*axis*r*share,sign*axis*v*share*sc.c,
                                         np.asarray(body['spin'],float),max(0.,heat)*(1-share)))
            out.append(dict(time=float(t)*FM_C_S,phase='merge',bodies=bodies,coulomb=fc*N_PER_MEV_FM,
                            strong=fn*N_PER_MEV_FM,kinetic=.5*mu*v*v,potential=vc+vn))
        return out

    def _decay_tree(self,roots,time_scale):
        """Chaque noyau excite se desexcite a son tour : evaporation (Weisskopf), gamma, fission (Bohr-Wheeler).
        Cinematique a deux corps exacte dans le repere du parent, puis transformee de Lorentz. Les fragments de
        fission, neutroniques et chauds, evaporent ensuite leurs neutrons."""
        tracks=list(roots)
        queue=[p for p in roots if p['excitation']>1e-8]
        events=[]
        fissioned=False
        steps=0
        while queue and steps<90:
            parent=min(queue,key=lambda item:item['birth'])
            queue=[item for item in queue if item is not parent]
            z,a=parent['za']
            excitation=parent['excitation']
            widths=fusion.decay_widths(z,a,excitation)
            options=[w for w in widths if w['kind'] in ('gamma','fission') or
                     (w['kind']=='particle' and nuclear_data.is_known(*w['residual'])
                      and nuclear_data.is_known(*w['ejectile']))]
            weights=np.array([w['weight'] for w in options],dtype=float)
            if not options or not np.all(np.isfinite(weights)) or weights.sum()<=0:
                options=[{'kind':'gamma','weight':fusion.GAMMA_WIDTH_MEV}]
                weights=np.array([fusion.GAMMA_WIDTH_MEV])
            chosen=options[int(self.rng.choice(len(options),p=weights/weights.sum()))]
            # Duree de vie : estimee par la largeur totale (pas un niveau mesure).
            t=parent['birth']+float(self.rng.exponential(reactor.HBAR_MEV_S/weights.sum()))
            position,velocity=self._at(parent,t)
            steps+=1
            daughters=None
            if chosen['kind']=='fission':
                split=self._scission(z,a,parent['mass'])
                if split:
                    (z1,a1,e1),(z2,a2,e2)=split
                    daughters=(((z1,a1),reactor.nuclear_mass_mev(z1,a1)+e1,e1),
                               ((z2,a2),reactor.nuclear_mass_mev(z2,a2)+e2,e2))
                    fissioned=True
            if daughters is None and chosen['kind']=='particle':
                residual,ejectile=chosen['residual'],chosen['ejectile']
                spectrum=chosen['spectrum']/sum(chosen['spectrum'])
                epsilon=float(self.rng.choice(chosen['eps'],p=spectrum))
                remaining=max(0.,excitation-chosen['separation']-epsilon)
                daughters=((residual,reactor.nuclear_mass_mev(*residual)+remaining,remaining),
                           (ejectile,reactor.nuclear_mass_mev(*ejectile),0.))
            if daughters is None:
                daughters=(((z,a),reactor.nuclear_mass_mev(z,a),0.),((0,0),0.,0.))
                chosen={'kind':'gamma'}
            (za1,m1,x1),(za2,m2,x2)=daughters
            momentum=two_body_momentum(parent['mass'],m1,m2)
            direction=self._direction()
            beta=velocity/sc.c
            parent['death']=t
            for za,m,sign,exc in ((za1,m1,1,x1),(za2,m2,-1,x2)):
                e,p=boost_four(math.hypot(m,momentum),direction*(sign*momentum),beta)
                track=self._track(za,m,p/e*sc.c,t,position,exc)
                track['energy']=e
                tracks.append(track)
                if exc>1e-8:
                    queue.append(track)
            events.append((t,chosen['kind'],position))
        return tracks,events,fissioned,sum(p['excitation'] for p in queue)

    def _scission(self,z,a,parent_mass):
        """Le partage d'une fission : densite de charge inchangee (A1/Z1 = A/Z), un peu d'asymetrie tiree ;
        energie cinetique des fragments selon Viola (1985) ; le reste les laisse chauds (ils evaporeront)."""
        tke=0.1189*z*z/a**(1/3)+7.3
        z1=z//2-int(self.rng.integers(0,5))
        z2=z-z1
        base=round(a*z1/z)
        for shift in (0,1,-1,2,-2,3,-3,4,-4):
            a1=base+shift
            a2=a-a1
            if a1<max(1,z1) or a2<max(1,z2):
                continue
            if fusion.mass_u(z1,a1)[0] is None or fusion.mass_u(z2,a2)[0] is None:
                continue
            m1,m2=reactor.nuclear_mass_mev(z1,a1),reactor.nuclear_mass_mev(z2,a2)
            free=parent_mass-m1-m2
            if free<=0:
                continue
            kinetic=min(tke,free)
            spare=free-kinetic
            e1=spare*a1/a
            return (z1,a1,e1),(z2,a2,spare-e1)
        return None

    def _quasi_fission(self,last,total,contact,time_scale):
        """Le systeme a deux noyaux tourne un instant puis se separe : transfert partiel vers la symetrie, energie
        cinetique de Viola pour le systeme entier, fragments chauds qui evaporent a leur tour."""
        (za,aa),(zb,ab)=self.a,self.b
        z,a=za+zb,aa+ab
        drift=float(self.rng.uniform(.1,.4))
        z1=int(round(za+(z/2-za)*drift))
        z2=z-z1
        chosen=None
        for shift in (0,1,-1,2,-2,3,-3,4,-4,5,-5,6,-6):
            a1=int(round(aa+(a/2-aa)*drift))+shift
            a2=a-a1
            if a1<max(1,z1) or a2<max(1,z2) or z1>118 or z2>118:
                continue
            if fusion.mass_u(z1,a1)[0] is None or fusion.mass_u(z2,a2)[0] is None:
                continue
            m1,m2=reactor.nuclear_mass_mev(z1,a1),reactor.nuclear_mass_mev(z2,a2)
            if total-m1-m2>0:
                chosen=(a1,a2,m1,m2)
                break
        if chosen is None:
            # Aucun transfert vers la symetrie n'est energetiquement permis : ils se separent sans echange.
            z1,z2=za,zb
            chosen=(aa,ab,reactor.nuclear_mass_mev(za,aa),reactor.nuclear_mass_mev(zb,ab))
        a1,a2,m1,m2=chosen
        free=total-m1-m2
        kinetic=min(0.1189*z*z/a**(1/3)+7.3,free)
        e1=(free-kinetic)*a1/a
        e2=free-kinetic-e1
        pa=np.asarray(last['bodies'][0]['position'],dtype=float)
        pb=np.asarray(last['bodies'][1]['position'],dtype=float)
        axis=(pa-pb)/max(np.linalg.norm(pa-pb),1e-12)
        ma,mb=reactor.nuclear_mass_mev(za,aa),reactor.nuclear_mass_mev(zb,ab)
        # Ils se touchent, tournent ensemble (moment cinetique de l'entree), puis se separent.
        sticking=float(self.rng.uniform(2e-21,8e-21))
        turn=float(self.rng.uniform(.25,1.1))*(1 if self.rng.random()<.5 else -1)
        frames=[]
        for t in np.linspace(0,sticking,14):
            angle=turn*t/sticking
            c,s=math.cos(angle),math.sin(angle)
            rot=np.array([[c,-s,0],[s,c,0],[0,0,1]])
            bodies=[self._body(za,aa,rot@(axis*contact*mb/(ma+mb)),np.zeros(3),last['bodies'][0]['spin']),
                    self._body(zb,ab,rot@(-axis*contact*ma/(ma+mb)),np.zeros(3),last['bodies'][1]['spin'])]
            frames.append(dict(time=float(t),phase='contact',bodies=bodies,coulomb=0.,strong=0.,kinetic=0.,potential=0.))
        c,s=math.cos(turn),math.sin(turn)
        out=np.array([[c,-s,0],[s,c,0],[0,0,1]])@axis
        momentum=two_body_momentum(total,m1+e1,m2+e2)
        roots=[]
        for za_,mass,exc,sign,share in (((z1,a1),m1+e1,e1,1,mb/(ma+mb)),((z2,a2),m2+e2,e2,-1,ma/(ma+mb))):
            e=math.hypot(mass,momentum)
            roots.append(self._track(za_,mass,out*(sign*sc.c*momentum/e),sticking,out*(sign*contact*share),exc))
            roots[-1]['energy']=e
        tracks,events,_,_=self._decay_tree(roots,time_scale)
        frames.extend(self._sample_tree(tracks,events,sticking,time_scale,before='evaporation'))
        return tracks,events,frames

    def _sample_tree(self,tracks,events,start,time_scale,before='compound'):
        """Images de l'arbre : autour de chaque desintegration, puis en temps logarithmique jusqu'a ce que le champ
        magnetique ait courbe les particules chargees."""
        last=max([t for t,_,_ in events],default=start)
        final=[p for p in tracks if p.get('death') is None]
        charged=next((p for p in final if p['za'][0]>0 and p['mass']>0),None)
        reach=1e-8
        if charged:
            gamma=1/math.sqrt(max(1e-12,1-np.dot(charged['v'],charged['v'])/sc.c**2))
            period=2*math.pi*gamma*charged['mass']*1e6/sc.c**2/(charged['za'][0]*max(self.field_t,.1))
            reach=min(2*period,3e-7)
        marks=[start]
        for t,_,_ in events:
            marks+=[t-1e-30,t]
        times=np.unique(np.r_[marks,np.linspace(start,last,80),
                              last+np.geomspace(max(time_scale*.01,1e-25),reach,320),last+np.linspace(0,reach,120)])
        frames=[]
        first=events[0][0] if events else math.inf
        for t in times:
            alive=[p for p in tracks if p['birth']<=t and (p.get('death') is None or t<p['death'])]
            hot=any(p['excitation']>1e-8 for p in alive)
            phase=before if t<first else 'evaporation' if hot else 'products'
            self._particle_frame(frames,alive,float(t),phase)
        return frames

    def _evaporate(self,compound,excitation,total_energy,time_scale):
        """Exact two-body recoil at each estimated statistical decay, with four-vector boosts.

        Fission interrupts this path rather than silently renormalising its competitors. Every retained channel
        uses tabulated residual/ejectile masses. No guessed unknown residual is emitted.
        """
        z,a=compound
        parent=self._track(compound,total_energy,np.zeros(3),0.,np.zeros(3),excitation)
        tracks=[parent]
        frames=[]
        t=0.
        for step in range(24):
            self._particle_frame(frames,tracks,t,'compound' if step==0 else 'evaporation')
            widths=fusion.decay_widths(z,a,excitation)
            options=[w for w in widths if w['kind'] in ('gamma','fission') or
                     (w['kind']=='particle' and nuclear_data.is_known(*w['residual'])
                      and nuclear_data.is_known(*w['ejectile']))]
            if not options or excitation<=1e-8:break
            weights=np.array([w['weight'] for w in options])
            if not np.all(np.isfinite(weights)) or sum(weights)<=0:break
            chosen=options[int(self.rng.choice(len(options),p=weights/sum(weights)))]
            if chosen['kind']=='fission':break
            # Width lifetime is a model estimate, not a measured nuclear level.
            lifetime=float(self.rng.exponential(reactor.HBAR_MEV_S/sum(weights)))
            for dt in np.linspace(0,lifetime,5,endpoint=False)[1:]:
                self._particle_frame(frames,tracks,t+dt,'compound' if step==0 else 'evaporation')
            t+=lifetime
            position,velocity=self._at(parent,t)
            if chosen['kind']=='gamma':
                residual=(z,a)
                ejectile=(0,0)
                remaining=0.
                m_emit=0.
                m_res=reactor.nuclear_mass_mev(z,a)
            else:
                residual=chosen['residual']
                ejectile=chosen['ejectile']
                spectrum=chosen['spectrum']/sum(chosen['spectrum'])
                epsilon=float(self.rng.choice(chosen['eps'],p=spectrum))
                remaining=max(0.,excitation-chosen['separation']-epsilon)
                m_emit=reactor.nuclear_mass_mev(*ejectile)
                m_res=reactor.nuclear_mass_mev(*residual)+remaining
            momentum=two_body_momentum(parent['mass'],m_res,m_emit)
            direction=self._direction()
            beta=velocity/sc.c
            daughters=[]
            for za,m,sign,exc in ((residual,m_res,1,remaining),(ejectile,m_emit,-1,0.)):
                e,p=boost_four(math.hypot(m,momentum),direction*(sign*momentum),beta)
                vel=p/e*sc.c
                track=self._track(za,m,vel,t,position,exc)
                track['energy']=e
                daughters.append(track)
            index=next(i for i,p in enumerate(tracks) if p is parent)
            tracks[index:index+1]=daughters
            parent=daughters[0]
            z,a=residual
            excitation=remaining
            if remaining<=1e-8:break
        self._sample_products(frames,tracks,t,time_scale)
        return frames,tracks,excitation

    @staticmethod
    def _body(z,a,position,velocity,spin,excitation=0.):
        if a==0:
            return dict(z=0,a=0,position=np.asarray(position),velocity=np.asarray(velocity),spin=np.zeros(3),
                        radius=0.,moment=0.,label='gamma',name='photon gamma',color='#FFF4AC',
                        spin_parity='1',mass=0.,excitation=0.,kind='gamma',known_moment=True)
        p=nucleus_properties(z,a)
        return dict(z=z,a=a,position=np.asarray(position),velocity=np.asarray(velocity),spin=np.asarray(spin),
                    radius=p['radius_fm'],moment=p['magnetic_moment'],label=p['label'],name=p['name'],
                    color=p['color_hex'],spin_parity=p['spin_parity'],mass=p['mass_mev']+excitation,
                    excitation=excitation,kind='neutron' if z==0 else 'nucleus',known_moment=p['moment_known'])
