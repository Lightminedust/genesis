package org.genesis.fusion;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.genesis.fusion.FusionField.*;

public class FusionFieldTest {
    private Body proton(V p){return new Body(1,1,"p",p,ZERO,new V(0,0,1),.84,2.793);}
    @Test void electricFieldObeysInverseSquareAndSuperposition(){
        List<Body> bodies=List.of(proton(ZERO));
        assertEquals(4,electric(new V(10,0,0),bodies).norm()/electric(new V(20,0,0),bodies).norm(),1e-12);
        assertEquals(0,electric(ZERO,List.of(proton(new V(-10,0,0)),proton(new V(10,0,0)))).norm());
    }
    @Test void neutronHasMagneticButNoElectricField(){
        Body neutron=new Body(0,1,"n",ZERO,ZERO,new V(0,0,1),.84,-1.913);
        assertEquals(0,electric(new V(5,0,0),List.of(neutron)).norm());
        assertTrue(magnetic(new V(5,0,0),List.of(neutron),0).norm()>0);
    }
    @Test void fieldLineFollowsAnIsolatedCharge(){
        List<V> path=trace(new V(2,0,0),List.of(proton(ZERO)),false,0,.1,100,100,1);
        assertTrue(path.get(path.size()-1).x()>10);
        for(V p:path){assertEquals(0,p.y(),1e-12);assertEquals(0,p.z(),1e-12);}
    }
    @Test void regularizedDipoleHasNoMagneticMonopoles(){
        V p=new V(4,-3,2),m=new V(0,0,MU_N*2);
        double h=1e-4;
        double div=(dipole(p.add(new V(h,0,0)),m,2).x()-dipole(p.sub(new V(h,0,0)),m,2).x()
                   +dipole(p.add(new V(0,h,0)),m,2).y()-dipole(p.sub(new V(0,h,0)),m,2).y()
                   +dipole(p.add(new V(0,0,h)),m,2).z()-dipole(p.sub(new V(0,0,h)),m,2).z())/(2*h);
        assertTrue(Math.abs(div)/dipole(p,m,2).norm()<1e-8);
    }
    @Test void neutralMasslessMarkersDoNotIntroduceSingularFields(){
        var photon=new Body(0,0,"gamma",ZERO,new V(C,0,0),ZERO,0,0);
        var bodies=List.of(photon);
        assertEquals(ZERO,electric(ZERO,bodies));
        assertEquals(new V(0,0,5),magnetic(ZERO,bodies,5));
        assertEquals(0,potential(ZERO,bodies));
    }

    @Test void everyElementContributesItsOwnChargeAndMotionToTheLocalField(){
        V point=new V(30,12,-7),velocity=new V(2e7,-1e6,3e6);
        double protonField=electric(point,List.of(proton(ZERO))).norm();
        for(int z=1;z<=118;z++){
            Body body=new Body(z,Math.max(1,2*z),"Z="+z,ZERO,velocity,ZERO,5,0);
            V e=electric(point,List.of(body));
            assertEquals(z,e.norm()/protonField,1e-10,"charge Z="+z);
            V expected=velocity.cross(e).mul(1/(C*C));
            assertTrue(magnetic(point,List.of(body),0).sub(expected).norm()<expected.norm()*1e-12);
            assertEquals(5.3,magnetic(point,List.of(body),5.3).z()-expected.z(),1e-4);
        }
    }

    @Test void electricMarkersFollowTheCombinedFieldAndStayInWorldCoordinates(){
        Body a=proton(new V(-6,0,0)),b=proton(new V(6,0,0));
        V p=new V(-2,2,0);
        double reference=electric(p,List.of(a)).norm();
        V alone=advectElectric(p,List.of(a),reference,.2).sub(p);
        V interacting=advectElectric(p,List.of(a,b),reference,.2).sub(p);
        assertTrue(interacting.unit().y()>alone.unit().y(),"l'autre noyau courbe le flux vers le flanc");
        assertEquals(ZERO,advectElectric(ZERO,List.of(a,b),reference,.2),"au point neutre, pas de mouvement arbitraire");
        Body moved=proton(new V(-20,0,0));
        V next=advectElectric(p,List.of(moved,b),reference,.2);
        assertTrue(next.sub(p).norm()<=.6,"déplacer une source ne doit pas emporter le marqueur avec elle");
        V offset=new V(100,-70,25);
        var shifted=List.of(proton(a.position().add(offset)),proton(b.position().add(offset)));
        assertTrue(advectElectric(p.add(offset),shifted,reference,.2).sub(offset)
                .sub(advectElectric(p,List.of(a,b),reference,.2)).norm()<1e-12);
    }
    @Test void magneticMarkersReadAllNucleiAtEachStep(){
        Body tritium=new Body(1,3,"T",new V(-5,0,0),ZERO,new V(0,0,1),2.3,2.98);
        Body gold=new Body(79,197,"Au",new V(5,0,0),ZERO,new V(0,1,0),7.6,.15);
        V seed=new V(-2,3,1);
        double reference=magnetic(seed,List.of(tritium,gold),5.3).norm();
        V alone=advectMagnetic(seed,List.of(tritium),5.3,reference,.5);
        V together=advectMagnetic(seed,List.of(tritium,gold),5.3,reference,.5);
        assertTrue(together.sub(alone).norm()>1e-6,"le second dipôle doit infléchir le repère magnétique");
        assertEquals(seed,advectMagnetic(seed,List.of(tritium,gold),5.3,reference,0));
    }
}
