package org.genesis.common;

/**
 * Caméra du laboratoire : orbite autour d'une cible, avec inertie.
 *
 * Glisser fait tourner, la molette zoome, et la vue continue sur sa lancée
 * puis ralentit (frottement), comme une carte qu'on pousse. Le zoom est
 * logarithmique : un cran multiplie la distance, ce qui permet de passer de
 * l'atome (picomètres) au noyau (femtomètres) sans à-coup.
 *
 * Projection en perspective faite à la main : on garde la main sur le rendu
 * (halos, transparences, tri en profondeur) que la 3D JavaFX ne permet pas.
 */
public final class LabCamera {

    public double yaw = -0.55;            // rotation autour de la verticale (rad)
    public double pitch = 0.42;           // élévation (rad)
    public double zoom = 5.0;             // log10 de la taille de vue, en fm
    /** Le frottement de l'inertie (s⁻¹) : plus il est faible, plus la vue glisse longtemps. */
    public double friction = 3.2;
    /** Le demi-angle de vue vertical (degrés) : plus petit, la perspective est plus douce (une figure plutôt qu'un grand angle). */
    public double fovHalfDeg = 22;
    public double targetX;
    public double targetY;
    public double targetZ;
    /**
     * Rotation libre (trackball) : glisser tourne la vue autour des axes de l'écran — un geste horizontal autour de sa
     * verticale, un geste vertical autour de son horizontale —, sans verticale du monde ni butée : on peut regarder un
     * objet par-dessous. Sans élan : la vue s'arrête où on la lâche. Faux : l'orbite habituelle (lacet, tangage), avec
     * inertie. Le lacet et le tangage ne servent plus alors qu'à orient (la vue visée).
     */
    public boolean free;
    /** La base libre (avant, haut : unitaires), créée au premier usage depuis le lacet et le tangage ; celle visée. */
    private double[] freeForward, freeUp, goalForward, goalUp;

    private double yawSpeed;
    private double pitchSpeed;
    private double zoomSpeed;
    private double goalZoom = Double.NaN;
    private double goalYaw = Double.NaN;
    private double goalPitch = Double.NaN;

    // Base de la caméra, recalculée à chaque image.
    private double cx;
    private double cy;
    private double cz;
    private double rx;
    private double ry;
    private double rz;
    private double ux;
    private double uy;
    private double uz;
    private double fx;
    private double fy;
    private double fz;
    private double focal;
    private double centerX;
    private double centerY;

    /** Distance de la caméra à la cible pour une taille de vue donnée. */
    public double distance() {
        return Math.pow(10, zoom) * 1.25;
    }

    public void drag(double dx, double dy) {
        if (free) {
            // Ce qui est devant la cible suit la main : vers la droite, la vue tourne autour de son haut ; vers le bas,
            // autour de sa droite (voir setup : droite = avant × haut).
            ensureFree();
            double[] r = cross(freeForward, freeUp);
            rotateFree(freeUp, -dx * FREE_TURN);
            rotateFree(r, -dy * FREE_TURN);
            goalForward = goalUp = null;
            return;
        }
        yawSpeed = -dx * 0.35;
        pitchSpeed = dy * 0.35;
        yaw -= dx * 0.006;
        pitch = clampPitch(pitch + dy * 0.006);
        goalYaw = Double.NaN;
        goalPitch = Double.NaN;
    }

    public void scroll(double notches) {
        zoomSpeed += -notches * 0.9;
        goalZoom = Double.NaN;
    }

    public void flyTo(double zoomLevel) {
        goalZoom = zoomLevel;
        zoomSpeed = 0;
    }

    public void orient(double newYaw, double newPitch) {
        if (free) {
            double[][] b = basis(newYaw, newPitch);
            goalForward = b[0];
            goalUp = b[1];
            return;
        }
        goalYaw = newYaw;
        goalPitch = newPitch;
    }

    /** Radians par pixel glissé, en rotation libre. */
    private static final double FREE_TURN = 0.007;

    /** L'avant et le haut de l'orbite (lacet, tangage) : le haut est dans le plan vertical, la droite horizontale. */
    private static double[][] basis(double yaw, double pitch) {
        double[] f = {-Math.cos(pitch) * Math.sin(yaw), -Math.sin(pitch), -Math.cos(pitch) * Math.cos(yaw)};
        double[] r = {-f[2], 0, f[0]};
        double rn = Math.hypot(r[0], r[2]);
        r[0] /= rn;
        r[2] /= rn;
        return new double[][]{f, cross(r, f)};
    }

    private void ensureFree() {
        if (freeForward != null) return;
        double[][] b = basis(yaw, pitch);
        freeForward = b[0];
        freeUp = b[1];
    }

    /** Tourne la base libre d'un angle (rad) autour d'un axe unitaire (Rodrigues), puis la garde orthonormée. */
    private void rotateFree(double[] axis, double angle) {
        freeForward = rotate(freeForward, axis, angle);
        freeUp = rotate(freeUp, axis, angle);
        orthonormalize();
    }

    private void orthonormalize() {
        freeForward = unit(freeForward);
        double d = dot(freeUp, freeForward);
        double[] u = {freeUp[0] - d * freeForward[0], freeUp[1] - d * freeForward[1], freeUp[2] - d * freeForward[2]};
        if (norm(u) > 1e-9) freeUp = unit(u);
    }

    private static double[] rotate(double[] v, double[] a, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle), d = dot(a, v);
        double[] x = cross(a, v);
        return new double[]{v[0] * c + x[0] * s + a[0] * d * (1 - c), v[1] * c + x[1] * s + a[1] * d * (1 - c),
                v[2] * c + x[2] * s + a[2] * d * (1 - c)};
    }

    /** La base libre glisse vers celle visée par orient (même allure que l'orbite : une seconde environ). */
    private void easeFree(double dt) {
        if (goalForward == null) return;
        double t = Math.min(1, dt * 2);
        double[] g = goalForward;
        // Visée à l'opposé : on passe par le côté (la moyenne de deux vecteurs opposés n'a pas de direction).
        if (dot(freeForward, g) < -.9) {
            double[] r = cross(freeForward, freeUp);
            g = unit(new double[]{g[0] + r[0], g[1] + r[1], g[2] + r[2]});
        }
        for (int k = 0; k < 3; k++) {
            freeForward[k] += (g[k] - freeForward[k]) * t;
            freeUp[k] += (goalUp[k] - freeUp[k]) * t;
        }
        orthonormalize();
        if (g == goalForward && distance(freeForward, goalForward) < 2e-3 && distance(freeUp, goalUp) < 2e-3) {
            freeForward = goalForward.clone();
            freeUp = goalUp.clone();
            goalForward = goalUp = null;
        }
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double[] unit(double[] a) {
        double n = norm(a);
        return new double[]{a[0] / n, a[1] / n, a[2] / n};
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static double clampPitch(double p) {
        return Math.max(-0.15, Math.min(1.45, p));
    }

    /** L'inertie : la vitesse décroît, la vue glisse puis s'arrête. */
    public void tick(double dt, boolean dragging, double minZoom, double maxZoom) {
        if (free) {
            ensureFree();
            if (!dragging) easeFree(dt);
        } else if (!dragging) {
            yaw -= yawSpeed * dt;
            pitch = clampPitch(pitch + pitchSpeed * dt);
            double damping = Math.exp(-dt * friction);
            yawSpeed *= damping;
            pitchSpeed *= damping;
        }
        zoom += zoomSpeed * dt;
        zoomSpeed *= Math.exp(-dt * 6);
        if (!Double.isNaN(goalZoom)) {
            zoom += (goalZoom - zoom) * Math.min(1, dt * 2.2);
            if (Math.abs(goalZoom - zoom) < 0.002) {
                goalZoom = Double.NaN;
            }
        }
        if (!free && !Double.isNaN(goalYaw)) {
            yaw += (goalYaw - yaw) * Math.min(1, dt * 2);
            pitch += (goalPitch - pitch) * Math.min(1, dt * 2);
            if (Math.abs(goalYaw - yaw) < 0.002) {
                goalYaw = Double.NaN;
            }
        }
        zoom = Math.max(minZoom, Math.min(maxZoom, zoom));
    }

    /** Prépare la projection pour un écran (largeur, hauteur) centré en (centerX, centerY). */
    public void setup(double width, double height, double screenCenterX, double screenCenterY, double distance) {
        if (free) {
            // La base libre : droite = avant × haut.
            ensureFree();
            fx = freeForward[0];
            fy = freeForward[1];
            fz = freeForward[2];
            ux = freeUp[0];
            uy = freeUp[1];
            uz = freeUp[2];
            rx = fy * uz - fz * uy;
            ry = fz * ux - fx * uz;
            rz = fx * uy - fy * ux;
        } else {
            double cosP = Math.cos(pitch);
            double sinP = Math.sin(pitch);
            double cosY = Math.cos(yaw);
            double sinY = Math.sin(yaw);
            // La caméra regarde la cible depuis (yaw, pitch).
            fx = -cosP * sinY;
            fy = -sinP;
            fz = -cosP * cosY;
            // droite = avant × haut du monde
            rx = -fz;
            ry = 0;
            rz = fx;
            double rn = Math.hypot(rx, rz);
            rx /= rn;
            rz /= rn;
            // haut = droite × avant
            ux = ry * fz - rz * fy;
            uy = rz * fx - rx * fz;
            uz = rx * fy - ry * fx;
        }
        cx = targetX - fx * distance;
        cy = targetY - fy * distance;
        cz = targetZ - fz * distance;
        focal = height / (2 * Math.tan(Math.toRadians(fovHalfDeg)));
        centerX = screenCenterX;
        centerY = screenCenterY;
    }

    /** Projette un point : {x écran, y écran, profondeur, échelle (px par unité)} ; null derrière la caméra. */
    public double[] project(double x, double y, double z) {
        double dx = x - cx;
        double dy = y - cy;
        double dz = z - cz;
        double depth = dx * fx + dy * fy + dz * fz;
        if (depth <= 1e-9) {
            return null;
        }
        double sx = dx * rx + dy * ry + dz * rz;
        double sy = dx * ux + dy * uy + dz * uz;
        double scale = focal / depth;
        return new double[]{centerX + sx * scale, centerY - sy * scale, depth, scale};
    }

    /** Axes de la caméra dans le monde : droite, haut, avant. */
    /** Le rayon de vue qui passe par le pixel (sx, sy) : origine (la caméra) puis direction unitaire. */
    public double[] ray(double sx, double sy) {
        double a = (sx - centerX) / focal, b = -(sy - centerY) / focal;
        double dx = fx + a * rx + b * ux, dy = fy + a * ry + b * uy, dz = fz + a * rz + b * uz;
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[]{cx, cy, cz, dx / n, dy / n, dz / n};
    }

    /** Le point de l'écran où se projette la cible (le centre donné à setup). */
    public double centerX() {
        return centerX;
    }

    public double centerY() {
        return centerY;
    }

    /** L'orientation de la vue (avant, haut), pour les images en cache : en rotation libre, lacet et tangage ne la disent plus. */
    public String orientationKey() {
        return fx + "|" + fy + "|" + fz + "|" + ux + "|" + uy + "|" + uz;
    }

    /** La position de l'œil dans le monde. */
    public double[] eye() {
        return new double[]{cx, cy, cz};
    }

    public double[] right() {
        return new double[]{rx, ry, rz};
    }

    public double[] up() {
        return new double[]{ux, uy, uz};
    }

    public double[] forward() {
        return new double[]{fx, fy, fz};
    }

    /** Coordonnées dans la base de la caméra (pour tourner les nucléons avec la vue). */
    public double[] toView(double x, double y, double z) {
        return new double[]{x * rx + y * ry + z * rz, x * ux + y * uy + z * uz, x * fx + y * fy + z * fz};
    }
}
