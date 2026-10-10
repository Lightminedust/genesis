package org.genesis;

/**
 * La caméra des vues 3D de l'atome et du noyau : un lacet (autour de la
 * verticale), un tangage (autour de l'horizontale) et une perspective douce.
 *
 * Repère de rendu : x vers la droite, y vers le bas de l'écran, z vers
 * l'observateur. Les orbitales sont écrites en repère de chimiste (z vers le
 * haut) : {@link #fromChemistry} fait la conversion.
 */
final class Camera3D {
    double yaw = 0.55;
    double pitch = -0.38;          // négatif : on regarde un peu par-dessus
    private double cosYaw = 1;
    private double sinYaw;
    private double cosPitch = 1;
    private double sinPitch;

    /** À appeler une fois par image, après avoir changé les angles. */
    void update() {
        pitch = Math.max(-1.45, Math.min(1.45, pitch));
        cosYaw = Math.cos(yaw);
        sinYaw = Math.sin(yaw);
        cosPitch = Math.cos(pitch);
        sinPitch = Math.sin(pitch);
    }

    /** Tourne un point du repère de rendu ; out = {x, y, profondeur (vers l'observateur)}. */
    void rotate(double x, double y, double z, double[] out) {
        double x1 = x * cosYaw + z * sinYaw;
        double z1 = -x * sinYaw + z * cosYaw;
        out[0] = x1;
        out[1] = y * cosPitch - z1 * sinPitch;
        out[2] = y * sinPitch + z1 * cosPitch;
    }

    /** Repère de chimiste (z vers le haut) → repère de rendu, puis rotation. */
    void fromChemistry(double x, double y, double z, double[] out) {
        rotate(x, -z, y, out);
    }

    /** Facteur de perspective pour une profondeur donnée (même unité que la distance focale). */
    static double perspective(double depth, double focal) {
        return focal / Math.max(focal * 0.12, focal - depth);
    }
}
