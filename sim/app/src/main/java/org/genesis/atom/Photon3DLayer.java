package org.genesis.atom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.ParallelCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;

/**
 * Les photons en vraie 3D (JavaFX 3D), posés sur la scène de l'atome.
 *
 * Chaque photon est un groupe 3D construit à partir de λ (via {@link PhotonPacket.Look}) :
 *  - le fuseau : une sphère étirée le long de la propagation, matériau Phong translucide, cœur spéculaire ;
 *  - les champs : deux rubans maillés (TriangleMesh), E dans un plan, B dans le plan perpendiculaire, en phase, sous
 *    l'enveloppe gaussienne, auto-éclairés de la couleur de λ ;
 *  - l'axe : un fin cylindre blanc ;
 *  - une lumière ponctuelle embarquée, de la couleur du photon, qui éclaire ses propres volumes.
 *
 * Projection parallèle : un pixel de la scène = un pixel du canevas. L'orientation vient de la caméra de l'atome
 * (repère de vue) : le photon tourne avec l'atome quand on le fait tourner.
 */
public final class Photon3DLayer {

    /** Ce que la scène 2D dit de chaque photon à cette image. */
    record View(Object id, double x, double y, double ux, double uy, PhotonPacket.Look look, double fade) {
    }

    private final Group world = new Group();
    private final SubScene scene = new SubScene(world, 10, 10, true, SceneAntialiasing.BALANCED);
    private final Pane root = new Pane(scene);
    private final Map<Object, Photon> photons = new HashMap<>();

    Photon3DLayer() {
        scene.setFill(Color.TRANSPARENT);
        ParallelCamera camera = new ParallelCamera();
        camera.setNearClip(0.01);
        camera.setFarClip(100_000);
        scene.setCamera(camera);
        scene.widthProperty().bind(root.widthProperty());
        scene.heightProperty().bind(root.heightProperty());
        root.setMouseTransparent(true);
        root.setPickOnBounds(false);
        world.getChildren().add(new AmbientLight(Color.web("#3A4258")));
    }

    Pane getNode() {
        return root;
    }

    /** Met les photons à jour : création, orientation, disparition. */
    void update(List<View> views, Camera3D camera) {
        Map<Object, Photon> alive = new HashMap<>();
        for (View view : views) {
            Photon photon = photons.remove(view.id());
            if (photon == null || photon.stale(view.look())) {
                if (photon != null) {
                    world.getChildren().remove(photon.group);
                }
                photon = new Photon(view.look());
                world.getChildren().add(photon.group);
            }
            photon.place(view, camera);
            alive.put(view.id(), photon);
        }
        for (Photon gone : photons.values()) {
            world.getChildren().remove(gone.group);
        }
        photons.clear();
        photons.putAll(alive);
    }

    /** Un photon : son groupe 3D et les paramètres qui l'ont construit. */
    private static final class Photon {
        final Group group = new Group();
        final Affine pose = new Affine();
        final PhotonPacket.Look look;
        final PhongMaterial envelopeMaterial;
        final PhongMaterial ribbonE;
        final PhongMaterial ribbonB;
        final PointLight light;

        Photon(PhotonPacket.Look look) {
            this.look = look;
            Color colour = look.colour();
            double length = look.length();
            double amplitude = look.amplitude();

            Sphere envelope = new Sphere(1, 40);
            envelope.setScaleX(length * 0.38);
            envelope.setScaleY(amplitude * 1.05);
            envelope.setScaleZ(amplitude * 1.05);
            envelopeMaterial = new PhongMaterial(colour.deriveColor(0, 1, 1, 0.05));
            envelopeMaterial.setSpecularColor(Color.color(1, 1, 1, 0.9));
            envelopeMaterial.setSpecularPower(24);
            envelope.setMaterial(envelopeMaterial);

            ribbonE = glowing(colour, 0.85);
            ribbonB = glowing(colour.interpolate(Color.WHITE, 0.55), 0.6);
            MeshView fieldE = ribbon(look, false);
            fieldE.setMaterial(ribbonE);
            MeshView fieldB = ribbon(look, true);
            fieldB.setMaterial(ribbonB);

            Cylinder axis = new Cylinder(Math.max(0.6, amplitude * 0.035), length * 0.7, 12);
            axis.getTransforms().add(new Rotate(90, Rotate.Z_AXIS));
            axis.setMaterial(glowing(colour.interpolate(Color.WHITE, 0.6), 0.7));

            light = new PointLight(colour.interpolate(Color.WHITE, 0.3));
            group.getChildren().addAll(light, axis, fieldB, fieldE, envelope);
            group.getTransforms().add(pose);
        }

        /** Les rubans se reconstruisent si λ ou l'échelle ont changé (zoom). */
        boolean stale(PhotonPacket.Look other) {
            return Math.abs(other.spacing() / look.spacing() - 1) > 0.05 || Math.abs(other.nm() / look.nm() - 1) > 1e-6
                    || Math.abs(other.ionising() - look.ionising()) > 0.05;
        }

        private static PhongMaterial glowing(Color colour, double strength) {
            WritableImage glow = new WritableImage(1, 1);
            glow.getPixelWriter().setColor(0, 0, colour.deriveColor(0, 1, strength, 1));
            PhongMaterial material = new PhongMaterial(colour.deriveColor(0, 1, 0.6, 0.9));
            material.setSelfIlluminationMap(glow);
            material.setSpecularColor(Color.WHITE);
            return material;
        }

        /**
         * Un ruban : la surface entre l'axe (x) et la courbe du champ, A·e^(−s²/2σ²)·cos(2πs/λ), dans le plan xy (E) ou
         * xz (B, un peu plus court). Les deux faces sont visibles.
         */
        private static MeshView ribbon(PhotonPacket.Look look, boolean magnetic) {
            int samples = 120;
            double half = look.length() / 2;
            double sigma = look.length() / 5;
            double amplitude = look.amplitude() * (magnetic ? 0.82 : 1);
            TriangleMesh mesh = new TriangleMesh();
            mesh.getTexCoords().addAll(0, 0);
            for (int i = 0; i < samples; i++) {
                double s = -half + 2 * half * i / (samples - 1);
                double field = amplitude * Math.exp(-0.5 * s * s / (sigma * sigma)) * Math.cos(2 * Math.PI * s / look.spacing());
                mesh.getPoints().addAll((float) s, 0f, 0f);
                if (magnetic) {
                    mesh.getPoints().addAll((float) s, 0f, (float) field);
                } else {
                    mesh.getPoints().addAll((float) s, (float) field, 0f);
                }
            }
            for (int i = 0; i < samples - 1; i++) {
                int a = 2 * i;
                int b = 2 * i + 1;
                int c = 2 * (i + 1);
                int d = 2 * (i + 1) + 1;
                mesh.getFaces().addAll(a, 0, b, 0, c, 0);
                mesh.getFaces().addAll(b, 0, d, 0, c, 0);
            }
            MeshView view = new MeshView(mesh);
            view.setCullFace(CullFace.NONE);
            return view;
        }

        /**
         * Oriente le groupe : x local = propagation k, y local = E, z local = B, exprimés dans le repère de vue de
         * l'atome (z vers l'observateur), converti en repère JavaFX (z vers le fond).
         */
        void place(View view, Camera3D camera) {
            double kz = 0.85;
            double kn = Math.sqrt(1 + kz * kz);
            double kx = view.ux() / kn;
            double ky = view.uy() / kn;
            kz /= kn;
            double[] e1 = new double[3];
            camera.fromChemistry(0, 0, 1, e1);
            double along = e1[0] * kx + e1[1] * ky + e1[2] * kz;
            for (int i = 0; i < 3; i++) {
                e1[i] -= along * new double[]{kx, ky, kz}[i];
            }
            double norm = Math.sqrt(e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2]);
            if (norm < 0.2) {
                e1 = new double[]{-ky, kx, 0};
                norm = Math.hypot(kx, ky);
            }
            for (int i = 0; i < 3; i++) {
                e1[i] /= norm;
            }
            double[] e2 = {ky * e1[2] - kz * e1[1], kz * e1[0] - kx * e1[2], kx * e1[1] - ky * e1[0]};
            pose.setToTransform(kx, e1[0], e2[0], view.x(),
                    ky, e1[1], e2[1], view.y(),
                    -kz, -e1[2], -e2[2], 0);
            double fade = Math.max(0, Math.min(1, view.fade()));
            group.setOpacity(fade);
            light.setColor(look.colour().interpolate(Color.WHITE, 0.3).deriveColor(0, 1, fade, 1));
        }
    }

    /** Pour les tests : combien de photons 3D sont dans la scène. */
    int count() {
        return photons.size();
    }

    List<Object> ids() {
        return new ArrayList<>(photons.keySet());
    }
}
