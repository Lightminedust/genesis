package org.genesis.app;

import org.genesis.engine.EngineClient;
import org.genesis.atom.NucleusPanel;
import org.genesis.atom.QuantumView;
import org.genesis.atom.SimCanvas;
import org.genesis.atom.StabilityChart;
import org.genesis.common.Visuals;

import com.studio.genesis.grpc.AtomState;
import com.studio.genesis.grpc.DecayChannel;
import com.studio.genesis.grpc.Element;
import com.studio.genesis.grpc.NuclearStatus;
import com.studio.genesis.grpc.Nucleon;
import com.studio.genesis.grpc.Orbital;
import com.studio.genesis.grpc.SimEvent;
import com.studio.genesis.grpc.Stability;
import com.studio.genesis.grpc.Subshell;
import com.studio.genesis.grpc.UniverseFrame;
import com.studio.genesis.grpc.UserAction;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrat côté client et logique de rendu pure.
 * La physique elle-même est testée côté Python (test_atom.py, test_simulation.py).
 */
public class AppTest {
    private static I18n.Language previousLanguage;

    @BeforeAll static void useFrenchForExistingDisplayAssertions() {
        previousLanguage = I18n.language();
        I18n.setLanguage(I18n.Language.FR);
    }

    @AfterAll static void restoreLanguage() {
        I18n.setLanguage(previousLanguage);
    }

    // ------------------------------------------------------------------
    // Contrat
    // ------------------------------------------------------------------
    @Test void helloIsTheDefaultActionType() {
        assertEquals(UserAction.ActionType.HELLO, UserAction.getDefaultInstance().getType());
    }

    @Test void timeAndLightActionsExist() {
        assertEquals(12, UserAction.ActionType.FIRE_PHOTON.getNumber());
        assertEquals(13, UserAction.ActionType.HEAT.getNumber());
        assertEquals(14, UserAction.ActionType.SET_SPEED.getNumber());
        assertEquals(15, UserAction.ActionType.TOGGLE_PAUSE.getNumber());
        assertEquals(16, UserAction.ActionType.NEXT_EVENT.getNumber());
    }

    @Test void photonCarriesItsWavelength() {
        UserAction action = UserAction.newBuilder().setType(UserAction.ActionType.FIRE_PHOTON)
                .setAmount(121.567).build();
        assertEquals(121.567, action.getAmount(), 1e-9);
    }

    @Test void resonanceTuningSurvivesTheNetworkWithoutFloatRounding() throws Exception {
        double wavelength = 121.568445617321;
        UniverseFrame decoded = UniverseFrame.parseFrom(UniverseFrame.newBuilder()
                .addAbsorbableNm(wavelength).build().toByteArray());
        UserAction action = UserAction.parseFrom(UserAction.newBuilder()
                .setType(UserAction.ActionType.FIRE_PHOTON)
                .setAmount(decoded.getAbsorbableNm(0)).build().toByteArray());
        assertEquals(wavelength, action.getAmount());
    }

    @Test void staleEngineIsDetectable() {
        UniverseFrame stale = UniverseFrame.newBuilder().setTick(1827).setProtocolVersion(3).build();
        assertNotEquals(EngineClient.PROTOCOL_VERSION, stale.getProtocolVersion());
        assertEquals(27, EngineClient.PROTOCOL_VERSION);
    }

    @Test void frameSurvivesSerialization() throws Exception {
        UniverseFrame decoded = UniverseFrame.parseFrom(carbon14Frame().toByteArray());
        assertEquals(Stability.UNSTABLE, decoded.getAtom().getStability());
        assertEquals("C-14", decoded.getNucleus().getNuclide());
        assertEquals(3.85e-12, decoded.getNucleus().getDecayRate(), 1e-14);
        assertEquals(2.75f, decoded.getAtom().getNuclearRadiusFm(), 1e-4);
    }

    @Test void eventsTravelWithTheirCalculation() {
        UniverseFrame frame = carbon14Frame().toBuilder()
                .addEvents(SimEvent.newBuilder().setKind("decay").setDecayMode("beta-")
                        .setTitle("C-14 → N-14 : désintégration β⁻").setQMev(0.1565f)
                        .addParticles("électron (β-)").addParticles("antineutrino électronique"))
                .build();
        SimEvent event = frame.getEvents(0);
        assertEquals("beta-", event.getDecayMode());
        assertEquals(2, event.getParticlesCount());
        assertEquals(0.1565f, event.getQMev(), 1e-6);
    }

    @Test void nucleonsAreThreeDimensionalInFemtometres() {
        Nucleon nucleon = carbon14Frame().getNucleons(3);
        assertNotEquals(0f, nucleon.getZ());
    }

    @Test void subshellsCarryTheirHundOccupation() {
        Subshell p = carbon14Frame().getSubshells(2);
        assertEquals("2p", p.getLabel());
        assertEquals(List.of(1, 1, 0), p.getOccupationList());
        assertEquals(6, p.getCapacity());
    }

    @Test void missingElementDegradesGracefully() {
        UniverseFrame frame = UniverseFrame.newBuilder()
                .setAtom(AtomState.newBuilder().setStability(Stability.NOT_AN_ATOM))
                .build();
        assertEquals("", frame.getElement().getSymbol());
        assertFalse(frame.getHasOrbital());
        assertFalse(frame.getNucleus().getUnstable());
        assertEquals(0, frame.getEventsCount());
    }

    // ------------------------------------------------------------------
    // Couleurs et grandeurs physiques affichées
    // ------------------------------------------------------------------
    @Test void balmerAlphaLooksRed() {
        Color hAlpha = Visuals.wavelengthColor(656.28);
        assertTrue(hAlpha.getRed() > 0.8 && hAlpha.getGreen() < 0.2 && hAlpha.getBlue() < 0.2);
    }

    @Test void sodiumLooksYellowOrangeAndBalmerBetaLooksCyanBlue() {
        // CIE 1931 → sRGB : la raie D du sodium est jaune orangé (#FF8x00), comme une lampe au sodium.
        Color sodium = Visuals.wavelengthColor(589.0);
        assertTrue(sodium.getRed() > 0.9 && sodium.getGreen() > 0.4 && sodium.getGreen() < 0.75
                && sodium.getBlue() < 0.1, sodium.toString());
        Color hBeta = Visuals.wavelengthColor(486.13);
        assertTrue(hBeta.getBlue() > 0.8 && hBeta.getGreen() > 0.6 && hBeta.getRed() < 0.1);
    }

    @Test void invisibleLightIsFlaggedNotInvented() {
        assertEquals(Visuals.UV, Visuals.wavelengthColor(121.57));
        assertEquals(Visuals.IR, Visuals.wavelengthColor(1875.1));
        assertEquals("UV", Visuals.region(121.57));
        assertEquals("IR", Visuals.region(1875.1));
        assertEquals("visible", Visuals.region(500));
    }

    @Test void lengthsUseTheRightUnit() {
        assertEquals("2,75 fm", Visuals.formatLength(2.75e-15));
        assertEquals("65 pm", Visuals.formatLength(65e-12));
        assertEquals("0,84 fm", Visuals.formatLength(0.84e-15));
        assertEquals("1,5 nm", Visuals.formatLength(1.5e-9));
    }

    @Test void durationsMatchTheEngine() {
        assertEquals("1,6 ns", Visuals.duration(1.5953e-9));
        assertEquals("5 700 ans", Visuals.duration(5700 * 365.25 * 86400));
        assertEquals("24,1 jours", Visuals.duration(24.1 * 86400));
        assertEquals("4,47 milliards d'années", Visuals.duration(4.468e9 * 365.25 * 86400));
        assertTrue(Visuals.duration(6e-22).contains("10⁻²²"));
    }

    @Test void scaleBarsUseRoundNumbers() {
        assertEquals(1e-10, Visuals.niceScaleLength(1e-12, 110), 1e-22);
        assertEquals(2e-15, Visuals.niceScaleLength(2.3e-17, 110), 1e-27);
    }

    @Test void principalQuantumNumberIsReadFromTheState() {
        assertEquals(2, SimCanvas.principal("2p"));
        assertEquals(10, SimCanvas.principal("10d"));
        assertEquals(1, SimCanvas.principal("fondamental"));
    }

    @Test void longFormulasWrapOnWords() {
        List<String> lines = SimCanvas.wrap("un deux trois quatre cinq six sept huit neuf dix", 20);
        assertTrue(lines.size() > 1);
        assertTrue(lines.stream().allMatch(line -> line.length() <= 20));
    }

    @Test void journalColoursLightByItsWavelength() {
        SimEvent hAlpha = SimEvent.newBuilder().setKind("emitted").setWavelengthNm(656.28f).build();
        assertEquals(Visuals.wavelengthColor(656.28f), SimCanvas.eventColor(hAlpha));
    }

    // ------------------------------------------------------------------
    // Instruments
    // ------------------------------------------------------------------
    @Test void logScaleShowsFourDecades() {
        assertEquals(1.0, QuantumView.logScale(1.0), 1e-9);
        assertEquals(0.5, QuantumView.logScale(1e-2), 1e-9);
        assertEquals(0.0, QuantumView.logScale(1e-4), 1e-9);
        assertEquals(0.0, QuantumView.logScale(0), 1e-9);
    }

    @Test void measurementsFollowTheTrueDensity() {
        Orbital orbital = Orbital.newBuilder().addAllDensity(List.of(0.25f, 0.75f)).setSize(1).build();
        double[] cumulative = QuantumView.buildCumulative(orbital);
        int second = 0;
        for (int i = 0; i < 1000; i++) {
            if (QuantumView.sample(cumulative, (i + 0.5) / 1000.0) == 1) {
                second++;
            }
        }
        assertEquals(750, second, 1);
    }

    @Test void decayArrowsPointWhereTheNucleusGoes() {
        assertArrayEquals(new int[]{1, -1}, StabilityChart.decayVector("beta-"));
        assertArrayEquals(new int[]{-1, 1}, StabilityChart.decayVector("CE"));
        assertArrayEquals(new int[]{-2, -2}, StabilityChart.decayVector("alpha"));
        assertArrayEquals(new int[]{0, -2}, StabilityChart.decayVector("2n"));
        assertNull(StabilityChart.decayVector("IT"));
    }

    @Test void decayProbabilityIsWrittenReadably() {
        assertEquals("3,85 × 10⁻¹²", NucleusPanel.scientific(3.85e-12));
        assertEquals("β⁻ (n → p + e⁻ + ν̄)", NucleusPanel.modeName("beta-"));
    }

    private static UniverseFrame carbon14Frame() {
        UniverseFrame.Builder builder = UniverseFrame.newBuilder()
                .setTick(7)
                .setSource("mongodb")
                .setProtocolVersion(EngineClient.PROTOCOL_VERSION)
                .setLastAction("ADD_NEUTRON")
                .setAtom(AtomState.newBuilder()
                        .setProtons(6).setNeutrons(8).setElectrons(6)
                        .setMassNumber(14).setStability(Stability.UNSTABLE)
                        .setNuclearRadiusFm(2.75f))
                .setElement(Element.newBuilder().setZ(6).setSymbol("C").setName("Carbone")
                        .addAllStableIsotopes(List.of(12, 13)))
                .setNucleus(NuclearStatus.newBuilder().setNuclide("C-14").setUnstable(true)
                        .setHalfLife("5 700 ans").setDecayRate(3.85e-12)
                        .addChannels(DecayChannel.newBuilder().setMode("beta-").setFraction(1).setDaughter("N-14")))
                .addSubshells(Subshell.newBuilder().setN(1).setL(0).setElectrons(2).setCapacity(2)
                        .setLabel("1s").addAllOccupation(List.of(2)).setRadiusPm(9.4f))
                .addSubshells(Subshell.newBuilder().setN(2).setL(0).setElectrons(2).setCapacity(2)
                        .setLabel("2s").addAllOccupation(List.of(2)).setRadiusPm(65f))
                .addSubshells(Subshell.newBuilder().setN(2).setL(1).setElectrons(2).setCapacity(6)
                        .setLabel("2p").addAllOccupation(List.of(1, 1, 0)).setRadiusPm(65f));
        for (int i = 0; i < 14; i++) {
            builder.addNucleons(Nucleon.newBuilder()
                    .setKind(i < 6 ? Nucleon.Kind.PROTON : Nucleon.Kind.NEUTRON)
                    .setX(0.1f * i).setY(-0.2f * i).setZ(0.15f * (i - 7)));
        }
        return builder.build();
    }
}
