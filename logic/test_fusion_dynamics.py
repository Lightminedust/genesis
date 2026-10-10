import math
import unittest
import numpy as np
from scipy import constants as sc
from fusion_dynamics import (FusionExperiment, potential_terms, uniform_field_orbit, product_kinematics,
                             magnetic_field, reflect_at_contact, boost_four)
import fusion
import reactor


class DynamicsTest(unittest.TestCase):
    def test_active_catalog_exposes_all_elements_and_isotope_properties(self):
        from engine import fill_collision
        from fusion_dynamics import collision_catalog, nucleus_properties
        import genesis_pb2 as pb
        r=FusionExperiment(7)
        frame=pb.ReactorState();fill_collision(frame,r)
        self.assertEqual({e.z for e in frame.catalog},set(range(1,119)))
        self.assertGreater(sum(len(e.isotopes) for e in frame.catalog),3500)
        for e in collision_catalog():
            r.set_nucleus('a',e['z']*1000+e['default_a'])
            p=nucleus_properties(*r.a)
            self.assertGreater(p['mass_mev'],0)
            self.assertGreater(p['radius_fm'],0)
            self.assertEqual(p['charge_state'],e['z'])

    def test_proton_proton_fuses_only_by_the_weak_interaction(self):
        r=FusionExperiment(4);r.set_nucleus('a',1001);r.set_nucleus('b',1001)
        shot=r.calculate(64,True)
        self.assertEqual(shot['outcome'],'fusion_products')
        last=shot['samples'][-1]['bodies']
        self.assertEqual(sorted(b['kind'] for b in last),['neutrino','nucleus','positron'])
        self.assertEqual([(b['z'],b['a']) for b in last if b['kind']=='nucleus'],[(1,2)])
        self.assertAlmostEqual(shot['q'],.420,delta=.002)
        total=sum(b['mass']+b['kinetic'] for b in last)
        self.assertAlmostEqual(total,2*reactor.nuclear_mass_mev(1,1)+.064,delta=1e-6)
        self.assertLess(shot['probability'],1e-20)

    def test_proton_proton_without_weak_conversion_rebounds_after_contact(self):
        for impact,field in ((0.,0.),(.5,0.),(1.,0.),(.5,5.3)):
            with self.subTest(impact=impact,field=field):
                r=FusionExperiment(4);r.set_nucleus('a',1001);r.set_nucleus('b',1001)
                r.set_energy(1000);r.set_field(field);r.set_impact(impact)
                shot=r.calculate(1000)
                self.assertEqual(shot['outcome'],'elastic')
                self.assertTrue(shot['resolved'])
                self.assertFalse(shot['fused'])
                self.assertEqual(shot['q'],0)
                self.assertIn('diffusion elastique',shot['reaction'])
                phases=[s['phase'] for s in shot['samples']]
                self.assertIn('contact',phases)
                self.assertEqual(phases[-1],'scattered')
                self.assertEqual([s['time'] for s in shot['samples']],sorted(s['time'] for s in shot['samples']))
                contact=next(s for s in shot['samples'] if s['phase']=='contact')
                first=next(s for s in shot['samples'] if s['phase']=='scattered')
                relative=lambda s:np.asarray(s['bodies'][0]['position'])-np.asarray(s['bodies'][1]['position'])
                speed=lambda s:np.asarray(s['bodies'][0]['velocity'])-np.asarray(s['bodies'][1]['velocity'])
                self.assertLess(np.dot(relative(contact),speed(contact)),0)
                self.assertGreater(np.dot(relative(first),speed(first)),0)
                self.assertGreater(np.linalg.norm(relative(shot['samples'][-1])),5*shot['contact'])
                self.assertLess(shot['error'],1e-5)
                for sample in (contact,first,shot['samples'][-1]):
                    self.assertAlmostEqual(sample['kinetic']+sample['potential'],1.,delta=2e-5)
                    self.assertEqual([(b['z'],b['a']) for b in sample['bodies']],[(1,1),(1,1)])

    def test_contact_reflection_conserves_four_momentum_and_tangential_momentum(self):
        ma,mb=reactor.nuclear_mass_mev(1,1),reactor.nuclear_mass_mev(1,2)
        state=np.array([1.,0,0,-1.,0,0,-80.,20,5,30.,-10,3])
        outgoing=reflect_at_contact(state,ma,mb)
        total=lambda s:sum(math.hypot(m,float(np.linalg.norm(p))) for m,p in
                           ((ma,s[6:9]),(mb,s[9:12])))
        np.testing.assert_allclose(outgoing[6:9]+outgoing[9:12],state[6:9]+state[9:12],atol=1e-10)
        self.assertAlmostEqual(total(outgoing),total(state),delta=1e-9)
        cm_beta=(state[6:9]+state[9:12])/total(state)
        _,before=boost_four(math.hypot(ma,float(np.linalg.norm(state[6:9]))),state[6:9],-cm_beta)
        _,after=boost_four(math.hypot(ma,float(np.linalg.norm(outgoing[6:9]))),outgoing[6:9],-cm_beta)
        self.assertLess(before[0],0)
        self.assertAlmostEqual(after[0],-before[0],delta=1e-9)
        np.testing.assert_allclose(after[1:],before[1:],atol=1e-9)

    def test_closed_compound_channel_falls_back_to_elastic_scattering(self):
        # ³He + p -> ⁴Li is endothermic by about 3.1 MeV: at 1 MeV,
        # even a requested capture cannot end in that compound nucleus.
        r=FusionExperiment(4);r.set_nucleus('a',2003);r.set_nucleus('b',1001)
        r.set_field(0)
        shot=r.calculate(1000,True)
        self.assertEqual(shot['outcome'],'elastic')
        self.assertTrue(shot['resolved'])
        self.assertFalse(shot['fused'])
        self.assertEqual(shot['q'],0)
        self.assertEqual(shot['probability'],0)
        self.assertEqual(shot['sigma_b'],0)
        self.assertIn('Aucun noyau compose accessible',shot['notes'])
        self.assertNotIn('Fusion montree',shot['notes'])
        self.assertEqual(shot['samples'][-1]['phase'],'scattered')
        self.assertLess(shot['error'],1e-5)
        last=shot['samples'][-1]
        self.assertEqual(sum(b['z'] for b in last['bodies']),3)
        self.assertEqual(sum(b['a'] for b in last['bodies']),4)
        self.assertAlmostEqual(last['kinetic']+last['potential'],1.,delta=2e-5)

    def test_weld_is_computed_and_turns_motion_into_heat(self):
        r=FusionExperiment(3);r.set_nucleus('a',1002);r.set_nucleus('b',1003)
        shot=r.calculate(64,True)
        merge=[s for s in shot['samples'] if s['phase']=='merge']
        self.assertGreater(len(merge),20)
        times=[s['time'] for s in shot['samples']]
        self.assertEqual(times,sorted(times))
        energy=.064
        for s in merge:
            heat=sum(b['excitation'] for b in s['bodies'])
            self.assertAlmostEqual(s['kinetic']+s['potential']+heat,energy,delta=2e-3)
        sep=lambda s:np.linalg.norm(np.asarray(s['bodies'][0]['position'])-np.asarray(s['bodies'][1]['position']))
        self.assertLess(sep(merge[-1]),.3)
        self.assertGreater(sum(b['excitation'] for b in merge[-1]['bodies']),5)
        after=[s for s in shot['samples'] if s['phase'] not in ('approach','merge')]
        self.assertGreaterEqual(after[0]['time'],merge[-1]['time'])

    def test_carbon_oxygen_evaporation_conserves_four_momentum_and_nucleons(self):
        r=FusionExperiment(7);r.set_nucleus('a',6012);r.set_nucleus('b',8016)
        r.set_energy(20000);r.set_field(0);r.fire()
        self.assertFalse(r.error)
        s=r.shot;self.assertTrue(s['resolved']);self.assertEqual(s['outcome'],'modelled_products')
        bodies=s['samples'][-1]['bodies']
        self.assertEqual(sum(b['z'] for b in bodies),14)
        self.assertEqual(sum(b['a'] for b in bodies),28)
        energies=[b['mass']+b['kinetic'] for b in bodies]
        before=reactor.nuclear_mass_mev(6,12)+reactor.nuclear_mass_mev(8,16)+20
        self.assertAlmostEqual(sum(energies),before,places=7)
        momentum=sum(e*b['velocity']/sc.c for b,e in zip(bodies,energies))
        self.assertLess(np.linalg.norm(momentum),1e-7)
        self.assertGreaterEqual(len(bodies),2)
        self.assertTrue(any(b['kind']=='gamma' and b['kinetic']>0 for b in bodies))

    def test_superheavy_contact_does_not_invent_a_stable_element(self):
        # Pb + Pb (Z = 164) : quasi-fission, deux fragments et leurs evaporations, rien au-dela de Z = 118.
        r=FusionExperiment(7);r.set_nucleus('a',82208);r.set_nucleus('b',82208)
        r.set_energy(800000);r.set_field(0);r.fire(True)
        self.assertFalse(r.error)
        s=r.shot
        self.assertEqual(s['outcome'],'quasi_fission')
        self.assertFalse(s['fused'])
        self.assertTrue(all(b['z']<=118 for p in s['samples'] for b in p['bodies']))
        self._conserved(s,(82,208),(82,208),800)

    def _conserved(self,s,a,b,energy_mev):
        bodies=s['samples'][-1]['bodies']
        self.assertEqual(sum(x['z'] for x in bodies),a[0]+b[0])
        self.assertEqual(sum(x['a'] for x in bodies),a[1]+b[1])
        energies=[x['mass']+x['kinetic'] for x in bodies]
        before=reactor.nuclear_mass_mev(*a)+reactor.nuclear_mass_mev(*b)+energy_mev
        self.assertAlmostEqual(sum(energies),before,places=6)
        momentum=sum(e*x['velocity']/sc.c for x,e in zip(bodies,energies))
        self.assertLess(np.linalg.norm(momentum),1e-6)

    def test_heavy_compound_fissions_and_conserves(self):
        # C-12 + U-238 a 80 MeV : californium 250 tres excite ; la fission de Bohr-Wheeler l'emporte.
        outcomes=set()
        for seed in range(4):
            r=FusionExperiment(seed);r.set_nucleus('a',6012);r.set_nucleus('b',92238)
            r.set_energy(80000);r.set_field(0);r.fire(True)
            self.assertFalse(r.error)
            s=r.shot
            self.assertTrue(s['fused'])
            self.assertTrue(s['resolved'])
            outcomes.add(s['outcome'])
            self._conserved(s,(6,12),(92,238),80)
        self.assertIn('fission',outcomes)

    def test_forced_shot_always_shows_fusion_with_its_true_probability(self):
        for seed in range(6):
            r=FusionExperiment(seed);r.fire(True)
            s=r.shot
            self.assertTrue(s['fused'])
            self.assertTrue(0<s['probability']<=.5+1e-12)
            self.assertIn('probabilite',s['notes'])

    def test_rejected_energy_keeps_session_usable(self):
        from simulation import Session
        from repository import ElementRepository
        from engine import UniverseEngineServicer, to_event_message
        import genesis_pb2 as pb
        session=Session(ElementRepository(mongo_uri=None));session.start_reactor()
        events=UniverseEngineServicer._apply(session,pb.UserAction(type=pb.UserAction.SET_REACTOR_ENERGY,amount=float('nan')))
        self.assertTrue(session.reactor.error)
        for event in events:to_event_message(event)
        session.reactor.set_energy(2e6)
        self.assertTrue(session.fire_reactor())
        self.assertTrue(session.reactor.error)
        session.reactor.set_energy(64);session.fire_reactor()
        self.assertFalse(session.reactor.error)
        self.assertIsNotNone(session.reactor.shot)

    def test_force_is_gradient_of_potential(self):
        pair=fusion.Pair((1,2),(1,3))
        for r in (1.,3.,8.,20.,100.):
            h=1e-5
            vc,vn,fc,fn=potential_terms(pair,r)
            left=potential_terms(pair,r-h)
            right=potential_terms(pair,r+h)
            gradient=-(sum(right[:2])-sum(left[:2]))/(2*h)
            self.assertAlmostEqual(float(fc+fn),float(gradient),delta=1e-6)

    def test_products_conserve_relativistic_energy_and_momentum(self):
        for fuel in reactor.FUELS:
            for branch in range(len(fuel['branches'])):
                products,q=product_kinematics(fuel,branch,64.,np.array([1.,0.,0.]))
                self.assertAlmostEqual(sum(p['kinetic'] for p in products),q+.064,places=8)
                momentum=sum(p['mass']*(1+p['kinetic']/p['mass'])*p['v']/sc.c for p in products)
                self.assertLess(np.linalg.norm(momentum),1e-10)

    def test_lorentz_returns_after_a_gyroperiod_and_preserves_speed(self):
        mass=reactor.nuclear_mass_mev(2,4)
        v=np.array([1.3e7,0.,0.])
        gamma=1/math.sqrt(1-np.dot(v,v)/sc.c**2)
        period=2*math.pi*gamma*mass*1e6*sc.e/sc.c**2/(2*sc.e*5.3)
        p,v1=uniform_field_orbit(v,2,mass,5.3,period)
        self.assertLess(np.linalg.norm(p),1e-12)
        self.assertAlmostEqual(np.linalg.norm(v1)/np.linalg.norm(v),1,places=13)
        half,_=uniform_field_orbit(v,2,mass,5.3,period/2)
        self.assertAlmostEqual(np.linalg.norm(half),np.linalg.norm(v)*period/math.pi,places=10)

    def test_neutron_and_zero_field_are_ballistic(self):
        v=np.array([1e7,2e7,3e7])
        for charge,b in ((0,5.3),(1,0)):
            p,v1=uniform_field_orbit(v,charge,1000,b,1e-9)
            np.testing.assert_allclose(p,v*1e-9)
            np.testing.assert_array_equal(v1,v)

    def test_dipole_field_is_divergence_free(self):
        point=np.array([4.,-3.,2.])
        axis=np.array([0.,0.,1.])
        h=1e-4
        divergence=0
        for k in range(3):
            offset=np.eye(3)[k]*h
            divergence+=(magnetic_field(point+offset,2,axis,2)[k]-magnetic_field(point-offset,2,axis,2)[k])/(2*h)
        self.assertLess(abs(divergence)/np.linalg.norm(magnetic_field(point,2,axis,2)),1e-8)

    def test_seed_reproducibility_and_conservation(self):
        a=FusionExperiment(7);b=FusionExperiment(7)
        a.fire();b.fire()
        self.assertEqual(a.shot['energy'],b.shot['energy'])
        self.assertEqual(a.shot['fused'],b.shot['fused'])
        self.assertLess(a.shot['error'],1e-6)
        np.testing.assert_array_equal(a.shot['samples'][50]['bodies'][0]['position'],
                                      b.shot['samples'][50]['bodies'][0]['position'])

    def test_reactions_are_not_forced_and_no_tunnel_trajectory(self):
        outcomes=set()
        for seed in range(10):
            r=FusionExperiment(seed);r.fire();s=r.shot
            outcomes.add(s['fused'])
            self.assertLess(s['error'],1e-5)
            self.assertTrue(0<s['probability']<=.5+1e-12)
            times=[p['time'] for p in s['samples']]
            self.assertTrue(np.all(np.diff(times)>=-1e-30))
            self.assertTrue(all(p['phase'] in ('approach','scattered','merge','compound','products') for p in s['samples']))
            # La soudure part de la sortie du tunnel (au contact ou en deca) : aucune trajectoire dans la barriere.
            for p in s['samples']:
                if p['phase']=='merge':
                    gap=np.linalg.norm(np.asarray(p['bodies'][0]['position'])-np.asarray(p['bodies'][1]['position']))
                    self.assertLessEqual(gap,s['contact']*1.0001)
            if s['fused']:
                self.assertTrue(all(p['phase']=='approach' for p in s['samples'] if p['time']<0))
        self.assertEqual(outcomes,{False,True})

    def test_contract_and_send_once(self):
        from engine import fill_reactor
        import genesis_pb2
        r=FusionExperiment(7);r.fire()
        out=genesis_pb2.ReactorState();fill_reactor(out,r)
        decoded=genesis_pb2.ReactorState.FromString(out.SerializeToString())
        self.assertEqual(decoded.collision.run,1)
        self.assertGreater(len(decoded.collision.samples),300)
        self.assertLess(out.ByteSize(),4*1024*1024)
        second=genesis_pb2.ReactorState();fill_reactor(second,r)
        self.assertEqual(len(second.collision.samples),0)

    def test_grpc_session_delivers_calculated_collision(self):
        import grpc
        import io
        import queue
        from concurrent.futures import ThreadPoolExecutor
        from contextlib import redirect_stdout
        from engine import UniverseEngineServicer, PROTOCOL_VERSION
        from repository import ElementRepository
        import genesis_pb2 as pb
        import genesis_pb2_grpc as rpc
        requests=queue.Queue()
        def actions():
            while True:
                action=requests.get()
                if action is None:return
                yield action
        server=grpc.server(ThreadPoolExecutor(max_workers=2))
        rpc.add_UniverseEngineServicer_to_server(UniverseEngineServicer(ElementRepository(mongo_uri=None)),server)
        port=server.add_insecure_port('127.0.0.1:0')
        with redirect_stdout(io.StringIO()):
            server.start()
            try:
                with grpc.insecure_channel(f'127.0.0.1:{port}') as channel:
                    stream=rpc.UniverseEngineStub(channel).RunSimulation(actions(),timeout=15)
                    requests.put(pb.UserAction(type=pb.UserAction.REACTOR_START))
                    frame=next(stream)
                    while not frame.reactor.active:frame=next(stream)
                    self.assertEqual(frame.protocol_version,PROTOCOL_VERSION)
                    requests.put(pb.UserAction(type=pb.UserAction.SET_REACTOR_FIELD,amount=0.0))
                    requests.put(pb.UserAction(type=pb.UserAction.REACTOR_FIRE))
                    frame=next(stream)
                    while not frame.reactor.HasField('collision'):frame=next(stream)
                    self.assertEqual(frame.reactor.collision.field_t,0.0)
                    self.assertGreater(len(frame.reactor.collision.samples),300)
                    requests.put(pb.UserAction(type=pb.UserAction.SET_REACTOR_NUCLEUS_A,value=6012))
                    requests.put(pb.UserAction(type=pb.UserAction.SET_REACTOR_NUCLEUS_B,value=8016))
                    requests.put(pb.UserAction(type=pb.UserAction.SET_REACTOR_ENERGY,amount=20000))
                    requests.put(pb.UserAction(type=pb.UserAction.REACTOR_FIRE))
                    frame=next(stream)
                    while not frame.reactor.HasField('collision'):frame=next(stream)
                    self.assertEqual(frame.reactor.selected_a.z,6)
                    self.assertEqual(frame.reactor.selected_b.z,8)
                    self.assertEqual(frame.reactor.collision.energy_kev,20000)
                    self.assertGreater(frame.reactor.collision.run,1)
                    requests.put(pb.UserAction(type=pb.UserAction.SET_REACTOR_ENERGY,amount=float('nan')))
                    frame=next(stream)
                    while not frame.reactor.error:frame=next(stream)
                    requests.put(pb.UserAction(type=pb.UserAction.SET_REACTOR_ENERGY,amount=21000))
                    frame=next(stream)
                    while frame.reactor.requested_energy_kev!=21000:frame=next(stream)
                    self.assertFalse(frame.reactor.error)
                    stream.cancel()
            finally:
                requests.put(None)
                server.stop(0).wait()


if __name__=='__main__': unittest.main()
