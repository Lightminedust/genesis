package org.genesis;

import java.util.List;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Le photon construit à partir de λ, et le nuage qui se contracte quand l'atome devient ion. */
class PhotonPacketTest {

    @Test void xRaysAreDrawnToScaleAndUltravioletIsCompressedAndSaid() {
        double atomScale = 1e-12;           // 1 pm par pixel : l'échelle de l'atome
        PhotonPacket.Look xray = PhotonPacket.look(0.05, atomScale, 5.14);
        assertTrue(xray.toScale());
        assertEquals(50, xray.spacing(), 1e-9);    // 0,05 nm = 50 pm = 50 px
        PhotonPacket.Look uv = PhotonPacket.look(98.7, atomScale, 5.14);
        assertFalse(uv.toScale());
        assertEquals(98_700 / uv.spacing(), uv.compression(), 1e-6);
        assertTrue(PhotonPacket.scaleNote(uv).startsWith("λ dessinée ÷"));
        // Plus courte longueur d'onde, crêtes plus serrées.
        assertTrue(PhotonPacket.look(10, atomScale, 0).spacing() < uv.spacing());
    }

    @Test void onlyPhotonsAboveTheIonisationEnergyLookIonising() {
        // 98,7 nm = 12,56 eV : ionise le sodium (5,14 eV), pas l'hydrogène (13,6 eV).
        assertEquals(12.56, PhotonPacket.look(98.7, 1e-12, 5.14).energyEv(), 0.01);
        assertEquals((12.562 - 5.139) / 5.139, PhotonPacket.look(98.7, 1e-12, 5.139).ionising(), 0.01);
        assertEquals(0, PhotonPacket.look(98.7, 1e-12, 13.6).ionising());
    }

    @Test void visibleLightKeepsItsTrueColourAndInvisibleLightGetsAConvention() {
        assertEquals(Visuals.wavelengthColor(656.28), PhotonPacket.colour(656.28));
        Color uv = PhotonPacket.colour(98.7);
        assertTrue(uv.getBlue() > uv.getGreen(), "UV : violet conventionnel");
    }

    private static OrbitalCloud.Shell shell(String label, int n, int l, double radius, int electrons, int... occupation) {
        return new OrbitalCloud.Shell(n, l, 3, radius, electrons, occupation, false, Color.WHITE, label);
    }

    @Test void aShellThatStaysContractsInsteadOfBlinking() {
        OrbitalCloud cloud = new OrbitalCloud();
        cloud.update(List.of(shell("2p", 2, 1, 40, 4, 2, 1, 1), shell("3s", 3, 0, 170, 1, 1)));
        for (int i = 0; i < 120; i++) {
            cloud.tick(1 / 60.0);
        }
        // L'ion : 3s est parti, 2p (même remplissage) a un rayon de Slater plus petit.
        cloud.update(List.of(shell("2p", 2, 1, 36, 4, 2, 1, 1)), true);
        OrbitalCloud.Layer stayed = cloud.layer(0);
        assertEquals(1, stayed.fade, 1e-9, "elle reste visible");
        assertEquals(40.0 / 36, stayed.scale, 1e-6, "elle part de son ancienne taille");
        double before = stayed.peakPm();
        for (int i = 0; i < 6; i++) {
            cloud.tick(1 / 60.0);
        }
        assertTrue(stayed.peakPm() < before, "elle se contracte");
        for (int i = 0; i < 60; i++) {
            cloud.tick(1 / 60.0);
        }
        assertEquals(1, stayed.scale, 1e-9);
        assertEquals(36, stayed.peakPm(), 1e-6);
        assertEquals(0, cloud.actorIndex());
    }
}
