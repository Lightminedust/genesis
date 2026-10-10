package org.genesis;

import java.util.ArrayList;
import java.util.List;

/** Champs locaux évalués à partir de l'état, unités fm / m/s / T / V/m. */
final class FusionField {
    static final double C = 299792458.0;
    static final double KE = 8.9875517923e9;
    static final double E_CHARGE = 1.602176634e-19;
    static final double MU_N = 5.0507837393e-27;
    record V(double x, double y, double z) {
        V add(V b) { return new V(x+b.x, y+b.y, z+b.z); }
        V sub(V b) { return new V(x-b.x, y-b.y, z-b.z); }
        V mul(double s) { return new V(x*s, y*s, z*s); }
        double dot(V b) { return x*b.x+y*b.y+z*b.z; }
        V cross(V b) { return new V(y*b.z-z*b.y, z*b.x-x*b.z, x*b.y-y*b.x); }
        double norm() { return Math.sqrt(dot(this)); }
        V unit() { double n=norm(); return n>1e-30 ? mul(1/n) : ZERO; }
    }
    static final V ZERO = new V(0,0,0);
    record Body(int z, int a, String label, V position, V velocity, V spin, double radius, double moment) {}

    static V electric(V point, List<Body> bodies) {
        return electric(point, bodies, Double.POSITIVE_INFINITY);
    }

    /**
     * Le champ dans un plasma (écrantage de Debye–Hückel) : E = kZe·e^(−r/λ)(1 + r/λ)/r², λ en fm. λ = ∞ : Coulomb.
     */
    static V electric(V point, List<Body> bodies, double debyeFm) {
        V sum = ZERO;
        for (Body body : bodies) {
            if (body.z == 0) continue;
            V r = point.sub(body.position).mul(1e-15);
            double d = Math.max(r.norm(), body.radius*1e-15), x = d*1e15/debyeFm;
            sum = sum.add(r.mul(KE*E_CHARGE*body.z/(d*d*d)*Math.exp(-x)*(1+x)));
        }
        return sum;
    }

    /** Advection d'un marqueur de champ, en fm. La mobilité sert à la lecture, pas à simuler une particule. */
    static V advectElectric(V point, List<Body> bodies, double referenceField, double travelFm) {
        return advectElectric(point, bodies, referenceField, travelFm, Double.POSITIVE_INFINITY);
    }

    /** La même advection dans le champ écranté d'un plasma de longueur de Debye λ (fm). */
    static V advectElectric(V point, List<Body> bodies, double referenceField, double travelFm, double debyeFm) {
        if (travelFm <= 0) return point;
        V e = electric(point, bodies, debyeFm);
        double norm = e.norm();
        if (norm < 1e-30) return point;
        double step = travelFm * Math.sqrt(Math.min(9, norm / Math.max(1e-30, referenceField)));
        V middle = point.add(e.mul(.5 * step / norm));
        V direction = electric(middle, bodies, debyeFm).unit();
        return point.add(direction.mul(step));
    }

    static V dipole(V displacementFm, V moment, double radiusFm) {
        if (moment.dot(moment) == 0) return ZERO;
        V r = displacementFm.mul(1e-15);
        double a = radiusFm*1e-15, r2 = r.dot(r);
        return r.mul(3*moment.dot(r)).add(moment.mul(2*a*a-r2))
                .mul(1e-7/Math.pow(r2+a*a,2.5));
    }

    static V magnetic(V point, List<Body> bodies, double externalT) {
        V sum = new V(0,0,externalT);
        for (Body body : bodies) {
            V r = point.sub(body.position);
            if (body.moment != 0) sum = sum.add(dipole(r, body.spin.mul(body.moment*MU_N), body.radius));
            if (body.z == 0) continue;
            V metres = r.mul(1e-15);
            double d = Math.max(metres.norm(), body.radius*1e-15);
            V e = metres.mul(KE*E_CHARGE*body.z/(d*d*d));
            sum = sum.add(body.velocity.cross(e).mul(1/(C*C)));
        }
        return sum;
    }

    /** Déplace un repère visuel dans B total par RK2 ; la mobilité est une convention de lecture. */
    static V advectMagnetic(V point, List<Body> bodies, double externalT, double referenceT, double travelFm) {
        if (travelFm <= 0) return point;
        V b = magnetic(point, bodies, externalT);
        double magnitude = b.norm();
        if (magnitude < 1e-30) return point;
        double step = travelFm * Math.sqrt(Math.min(2, magnitude / Math.max(1e-30, referenceT)));
        V midpoint = point.add(b.mul(.5 * step / magnitude));
        return point.add(magnetic(midpoint, bodies, externalT).unit().mul(step));
    }

    /** Énergie potentielle d'une charge test +e, MeV ; sphères uniformément chargées. */
    static double potential(V point, List<Body> bodies) {
        double sum = 0;
        for (Body b : bodies) {
            if (b.z == 0) continue;
            double r=point.sub(b.position).norm(), a=b.radius;
            sum += 1.439964548*b.z*(r>=a ? 1/r : (3-r*r/(a*a))/(2*a));
        }
        return sum;
    }

    static V direction(V p, List<Body> bodies, boolean magnetic, double b, int sign) {
        V field = magnetic ? magnetic(p,bodies,b) : electric(p,bodies);
        return field.unit().mul(sign);
    }

    /** RK4 de dr/ds = ±F/|F|. s est une longueur de tracé, pas le temps d'une particule. */
    static List<V> trace(V seed, List<Body> bodies, boolean magnetic, double b,
                         double step, double extent, int count, int sign) {
        List<V> points = new ArrayList<>();
        V p=seed;
        points.add(p);
        for(int i=0;i<count;i++) {
            V k1=direction(p,bodies,magnetic,b,sign);
            if(k1.norm()<0.5) break;
            V k2=direction(p.add(k1.mul(step/2)),bodies,magnetic,b,sign);
            V k3=direction(p.add(k2.mul(step/2)),bodies,magnetic,b,sign);
            V k4=direction(p.add(k3.mul(step)),bodies,magnetic,b,sign);
            p=p.add(k1.add(k2.mul(2)).add(k3.mul(2)).add(k4).mul(step/6));
            if(p.norm()>extent*2.5) break;
            points.add(p);
            if(magnetic && i>4) {
                boolean inside=false;
                for(Body body:bodies)if(p.sub(body.position).norm()<body.radius*.98){inside=true;break;}
                if(inside)break;
            }
            if(i>12 && p.sub(seed).norm()<step*0.7) break;
        }
        return points;
    }
}
