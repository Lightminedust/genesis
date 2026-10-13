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
        goalYaw = newYaw;
        goalPitch = newPitch;
    }

    private static double clampPitch(double p) {
        return Math.max(-0.15, Math.min(1.45, p));
    }

    /** L'inertie : la vitesse décroît, la vue glisse puis s'arrête. */
    public void tick(double dt, boolean dragging, double minZoom, double maxZoom) {
        if (!dragging) {
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
        if (!Double.isNaN(goalYaw)) {
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
        double cosP = Math.cos(pitch);
        double sinP = Math.sin(pitch);
        double cosY = Math.cos(yaw);
        double sinY = Math.sin(yaw);
        // La caméra regarde la cible depuis (yaw, pitch).
        fx = -cosP * sinY;
        fy = -sinP;
        fz = -cosP * cosY;
        cx = targetX - fx * distance;
        cy = targetY - fy * distance;
        cz = targetZ - fz * distance;
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
