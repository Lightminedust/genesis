package org.genesis.atom;

import org.genesis.app.I18n;

import org.genesis.common.Visuals;

import com.studio.genesis.grpc.DecayChannel;
import com.studio.genesis.grpc.NuclearStatus;
import com.studio.genesis.grpc.NuclearTerm;
import com.studio.genesis.grpc.UniverseFrame;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * Le noyau en chiffres : sa place dans la vallée de stabilité, sa demi-vie,
 * sa probabilité de se désintégrer à chaque seconde, et les voies mesurées.
 * Le moment exact de la désintégration, lui, est tiré au hasard par le
 * moteur : c'est ce que dit la physique, on ne peut pas faire mieux.
 */
public class NucleusPanel {

    private final StabilityChart chart;
    private final Label title = new Label("—");
    private final Label status = new Label("");
    private final Label rate = new Label("");
    private final Label channels = new Label("");
    private final Label binding = new Label("");
    private final ScrollPane root;

    public NucleusPanel(StabilityChart chart) {
        this.chart = chart;
        title.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 15px; -fx-font-weight: bold;");
        for (Label label : new Label[]{status, rate, channels, binding}) {
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            label.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 12px;");
        }
        rate.setStyle("-fx-text-fill: #FDE68A; -fx-font-family: Consolas, monospace; -fx-font-size: 11px;");
        binding.setStyle("-fx-text-fill: #94A3B8; -fx-font-family: Consolas, monospace; -fx-font-size: 11px;");
        VBox text = new VBox(4, title, status, rate, channels, binding);
        text.setPadding(new Insets(8, 12, 8, 12));
        VBox content = new VBox(6, chart.getNode(), text);
        content.setPadding(new Insets(8, 12, 8, 12));
        content.setStyle("-fx-background-color: #05070F;");
        chart.getNode().widthProperty().bind(content.widthProperty().subtract(24));
        root = new ScrollPane(content);
        root.setFitToWidth(true);
        root.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.setStyle("-fx-background: #05070F; -fx-background-color: #05070F;");
    }

    public Region getNode() {
        return root;
    }

    public void applyFrame(UniverseFrame frame) {
        chart.applyFrame(frame);
        NuclearStatus nucleus = frame.getNucleus();
        if (nucleus.getNuclide().isEmpty()) {
            title.setText("Aucun noyau");
            status.setText("Ajoute des protons et des neutrons.");
            rate.setText("");
            channels.setText("");
            binding.setText("");
            return;
        }
        title.setText(nucleus.getNuclide() + "  ·  " + frame.getAtom().getProtons() + " p + "
                + frame.getAtom().getNeutrons() + " n");
        if (!nucleus.getUnstable()) {
            status.setText("Stable : aucune désintégration n'est possible, il vivra éternellement.");
            rate.setText("");
            channels.setText("");
        } else {
            status.setText("Radioactif · demi-vie " + nucleus.getHalfLife() + " (" + nucleus.getSource() + ")");
            double perSecond = nucleus.getDecayRate();
            rate.setText(I18n.text("probabilité par seconde λ = ln 2 / t½ = ") + scientific(perSecond) + " s⁻¹\n"
                    + I18n.text("à la vitesse actuelle : ") + scientific(perSecond * frame.getSpeed())
                    + I18n.text(" chance par seconde réelle"));
            StringBuilder text = new StringBuilder("Voies mesurées :");
            for (DecayChannel channel : nucleus.getChannelsList()) {
                text.append("\n  ").append(modeName(channel.getMode())).append(" → ")
                        .append(channel.getDaughter().isEmpty() ? "fragments" : channel.getDaughter())
                        .append("   ").append(percent(channel.getFraction()));
            }
            channels.setText(text.toString());
        }
        double total = 0;
        for (NuclearTerm term : frame.getNuclearTermsList()) {
            total += term.getMev();
        }
        binding.setText(frame.getNuclearTermsCount() == 0 ? "" : I18n.text("énergie de liaison (Weizsäcker) : ")
                + Visuals.format(total) + " MeV, soit " + Visuals.fixed(total / Math.max(1, frame.getAtom()
                .getMassNumber()), 2) + I18n.text(" MeV par nucléon"));
    }

    public static String modeName(String mode) {
        return switch (mode) {
            case "beta-" -> "β⁻ (n → p + e⁻ + ν̄)";
            case "beta+" -> "β⁺ (p → n + e⁺ + ν)";
            case "CE" -> "capture électronique";
            case "alpha" -> "α (2p + 2n)";
            case "n" -> "émission d'un neutron";
            case "p" -> "émission d'un proton";
            case "2n" -> "émission de deux neutrons";
            case "2p" -> "émission de deux protons";
            case "beta-n" -> "β⁻ puis neutron";
            case "beta+p" -> "β⁺ puis proton";
            case "IT" -> "γ (transition isomérique)";
            case "SF" -> "fission spontanée";
            default -> mode;
        };
    }

    public static String scientific(double value) {
        if (value == 0) {
            return "0";
        }
        if (value >= 0.01 && value < 1000) {
            return Visuals.format(value);
        }
        int exponent = (int) Math.floor(Math.log10(Math.abs(value)));
        return Visuals.fixed(value / Math.pow(10, exponent), 2) + " × 10" + Visuals.superscript(exponent);
    }

    private static String percent(double fraction) {
        double value = fraction * 100;
        if (value >= 1) {
            String text = String.format(Locale.ROOT, "%.1f %%", value);
            return I18n.language() == I18n.Language.FR ? text.replace('.', ',') : text;
        }
        return scientific(value) + " %";
    }
}
