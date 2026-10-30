package org.genesis.molecule;

import org.genesis.common.LabCamera;

import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;
import javafx.scene.transform.Affine;

import java.util.ArrayList;
import java.util.List;

/**
 * La vue ψ comme dans les logiciels de chimie (Avogadro, GaussView) : les lobes de l'orbitale en surfaces pleines et
 * lisses — bleu ψ > 0, rouge ψ < 0 —, éclairées par une lumière principale et une lumière d'appoint portées par la
 * caméra, avec un reflet discret ; le squelette en boules et bâtonnets, dans la même scène 3D : chaque chose cache ce
 * qui est derrière elle. La carte graphique dessine tout, sous le canevas de la scène (qui garde les étiquettes, la
 * légende et l'échelle). La caméra 3D reprend exactement celle de la scène (même œil, mêmes axes, même champ
 * vertical) : les étiquettes tombent sur les atomes.
 */
final class OrbitalLayer {
    static final Color PLUS = Color.web("#3D7BF5"), MINUS = Color.web("#E04A3A");
    private static final double ATOM = .14, STICK = .045;

    private final Group world = new Group(), skeleton = new Group();
    private final SubScene scene = new SubScene(world, 10, 10, true, SceneAntialiasing.BALANCED);
    private final Pane root = new Pane(scene);
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Affine pose = new Affine();
    private final TriangleMesh plusMesh = new TriangleMesh(VertexFormat.POINT_NORMAL_TEXCOORD),
            minusMesh = new TriangleMesh(VertexFormat.POINT_NORMAL_TEXCOORD);
    private final PointLight key = new PointLight(Color.web("#FFFFFF")), fill = new PointLight(Color.web("#59667A"));
    private final PhongMaterial atomMaterial = material(Color.web("#E9EEF5"), .5), stickMaterial = material(Color.web("#A9B5C6"), .3);
    private final List<Sphere> atoms = new ArrayList<>();
    private final List<Cylinder> sticks = new ArrayList<>();
    private OrbitalSurface.Mesh shown;

    /** La carte graphique sait-elle dessiner en 3D ? Sinon, la vue ψ garde ses points. */
    static boolean available() {
        return Platform.isSupported(ConditionalFeature.SCENE3D);
    }

    OrbitalLayer() {
        // Le fond est celui de la vue ψ, peint sous la couche (un dégradé en remplissage de sous-scène y laissait une
        // bande plus claire).
        scene.setFill(Color.TRANSPARENT);
        root.setStyle("-fx-background-color: radial-gradient(center 50% 42%, radius 75%, #0B1626 0%, #050A14 50%, #010307 100%);");
        camera.setNearClip(.05);
        camera.setFarClip(500);
        camera.setVerticalFieldOfView(true);
        camera.getTransforms().add(pose);
        scene.setCamera(camera);
        scene.widthProperty().bind(root.widthProperty());
        scene.heightProperty().bind(root.heightProperty());
        root.setMouseTransparent(true);
        root.setPickOnBounds(false);
        root.setVisible(false);
        MeshView plus = new MeshView(plusMesh), minus = new MeshView(minusMesh);
        plus.setMaterial(material(PLUS, .45));
        minus.setMaterial(material(MINUS, .45));
        // Les deux faces : une surface fermée cache de toute façon son revers, et un triangle mal tourné ne fait plus de trou.
        for (MeshView lobe : new MeshView[]{plus, minus}) lobe.setCullFace(CullFace.NONE);
        world.getChildren().addAll(new AmbientLight(Color.web("#3A4250")), key, fill, skeleton, plus, minus);
    }

    private static PhongMaterial material(Color colour, double shine) {
        PhongMaterial m = new PhongMaterial(colour);
        m.setSpecularColor(Color.gray(shine));
        m.setSpecularPower(36);
        return m;
    }

    Node getNode() {
        return root;
    }

    /**
     * Montre `surface` (null : rien) et le squelette (`positions`, `bonds` : les paires liées) sous la caméra de la
     * scène.
     */
    void update(OrbitalSurface.Mesh surface, LabCamera lab, double[][] positions, List<MoleculeScene.Bond> bonds) {
        boolean visible = surface != null;
        root.setVisible(visible);
        if (!visible) return;
        double[] eye = lab.eye(), r = lab.right(), u = lab.up(), f = lab.forward();
        // L'œil et les axes de la caméra de la scène : x à droite, y vers le bas de l'écran, z vers l'avant.
        pose.setToTransform(r[0], -u[0], f[0], eye[0], r[1], -u[1], f[1], eye[1], r[2], -u[2], f[2], eye[2]);
        camera.setFieldOfView(2 * lab.fovHalfDeg);
        double distance = Math.sqrt(sq(eye[0] - lab.targetX) + sq(eye[1] - lab.targetY) + sq(eye[2] - lab.targetZ));
        place(key, eye, r, u, -.5 * distance, .6 * distance);
        place(fill, eye, r, u, .8 * distance, -.4 * distance);
        if (surface != shown) load(surface);
        skeleton(positions, bonds);
    }

    private static void place(PointLight light, double[] eye, double[] r, double[] u, double right, double up) {
        light.setTranslateX(eye[0] + right * r[0] + up * u[0]);
        light.setTranslateY(eye[1] + right * r[1] + up * u[1]);
        light.setTranslateZ(eye[2] + right * r[2] + up * u[2]);
    }

    private static double sq(double x) {
        return x * x;
    }

    /** Les deux lobes : les mêmes sommets, les triangles de chaque phase dans leur maillage. */
    private void load(OrbitalSurface.Mesh surface) {
        shown = surface;
        int[] tri = surface.triangles();
        byte[] phase = surface.phase();
        int plusCount = 0;
        for (int k = 0; k < surface.count(); k++) if (phase[tri[3 * k]] > 0) plusCount++;
        int[] plus = new int[9 * plusCount], minus = new int[9 * (surface.count() - plusCount)];
        int p = 0, m = 0;
        for (int k = 0; k < surface.count(); k++) {
            boolean positive = phase[tri[3 * k]] > 0;
            int[] faces = positive ? plus : minus;
            int at = positive ? p : m;
            for (int a = 0; a < 3; a++) {
                faces[at + 3 * a] = tri[3 * k + a];
                faces[at + 3 * a + 1] = tri[3 * k + a];
                faces[at + 3 * a + 2] = 0;
            }
            if (positive) p += 9;
            else m += 9;
        }
        for (TriangleMesh mesh : new TriangleMesh[]{plusMesh, minusMesh}) {
            mesh.getFaces().clear();
            mesh.getPoints().setAll(surface.points());
            mesh.getNormals().setAll(surface.normals());
            mesh.getTexCoords().setAll(0, 0);
        }
        plusMesh.getFaces().setAll(plus);
        minusMesh.getFaces().setAll(minus);
    }

    /**
     * Les noyaux (petites boules) et les liaisons (bâtonnets), à leur place de cette image : un, deux ou trois bâtonnets
     * selon l'indice de Mayer (voir MoleculeScene.strokes), côte à côte dans le plan perpendiculaire à la densité π de
     * la liaison — comme on dessine C=C —, le second plus fin pour une liaison conjuguée.
     */
    private void skeleton(double[][] positions, List<MoleculeScene.Bond> bonds) {
        while (atoms.size() < positions.length) {
            Sphere s = new Sphere(ATOM, 24);
            s.setMaterial(atomMaterial);
            atoms.add(s);
            skeleton.getChildren().add(s);
        }
        for (int i = 0; i < atoms.size(); i++) {
            Sphere s = atoms.get(i);
            boolean on = i < positions.length && positions[i] != null;
            s.setVisible(on);
            if (!on) continue;
            s.setTranslateX(positions[i][0]);
            s.setTranslateY(positions[i][1]);
            s.setTranslateZ(positions[i][2]);
        }
        int used = 0;
        for (MoleculeScene.Bond bond : bonds) {
            if (bond.a() >= positions.length || bond.b() >= positions.length) continue;
            double[] a = positions[bond.a()], b = positions[bond.b()];
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2], length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1e-6) continue;
            double[] d = {dx / length, dy / length, dz / length};
            // Les bâtonnets s'écartent le long de w : perpendiculaire à la liaison et à l'axe de sa densité π.
            double[] w = bond.piAxis() != null ? cross(d, bond.piAxis()) : null;
            if (w == null || norm(w) < .2) w = Math.abs(d[0]) < .9 ? cross(d, new double[]{1, 0, 0}) : cross(d, new double[]{0, 0, 1});
            double nw = norm(w);
            w = new double[]{w[0] / nw, w[1] / nw, w[2] / nw};
            int n = MoleculeScene.strokes(bond.order());
            double gap = n == 1 ? 0 : .12;
            for (int s = 0; s < n; s++) {
                double offset = n == 2 ? (s - .5) * gap : (s - 1) * gap;
                double radius = n == 1 ? STICK : s == n - 1 && MoleculeScene.partial(bond.order()) ? .018 : .03;
                if (used == sticks.size()) {
                    Cylinder c = new Cylinder(1, 1, 12);
                    c.setMaterial(stickMaterial);
                    c.getTransforms().add(new Affine());
                    sticks.add(c);
                    skeleton.getChildren().add(c);
                }
                Cylinder c = sticks.get(used++);
                c.setVisible(true);
                double cx = (a[0] + b[0]) / 2 + offset * w[0], cy = (a[1] + b[1]) / 2 + offset * w[1], cz = (a[2] + b[2]) / 2 + offset * w[2];
                // Le cylindre de JavaFX (rayon 1, hauteur 1) est le long de y : y → la liaison, x et z → son rayon.
                double[] e3 = cross(w, d);
                ((Affine) c.getTransforms().get(0)).setToTransform(
                        w[0] * radius, d[0] * length, e3[0] * radius, cx,
                        w[1] * radius, d[1] * length, e3[1] * radius, cy,
                        w[2] * radius, d[2] * length, e3[2] * radius, cz);
            }
        }
        for (int k = used; k < sticks.size(); k++) sticks.get(k).setVisible(false);
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
