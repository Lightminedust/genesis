package org.genesis.app;

import org.genesis.atom.PhotonPacket;
import org.genesis.common.Visuals;

import com.studio.genesis.grpc.SimEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Le journal : tout ce qui est arrivé, du plus récent au plus ancien.
 *
 * Chaque ligne dit de quoi il s'agit (une étiquette de catégorie : lumière,
 * électrons, noyau, info), quand (le temps simulé), ce qui est arrivé (le
 * titre), le calcul (dans un encart) et l'explication. Des filtres ne gardent
 * qu'une catégorie. En mode compact (sous Genesis), une ligne par événement :
 * un clic demande à Genesis de l'expliquer.
 */
public class EventJournal {

    /** Les catégories d'événements, pour l'étiquette et les filtres. */
    enum Category {
        LIGHT("LUMIÈRE", "#F2B84B"), ELECTRON("ÉLECTRONS", "#7DD3FC"), NUCLEUS("NOYAU", "#FB7185"), INFO("INFO", "#8B9BB8");

        final String label;
        final String colour;

        Category(String label, String colour) {
            this.label = label;
            this.colour = colour;
        }
    }

    private static final int MAX_ENTRIES = 300;

    private final List<SimEvent> events = new ArrayList<>();          // du plus récent au plus ancien
    private final VBox entries = new VBox(0);
    private final ScrollPane scroller = new ScrollPane(entries);
    private final HBox filters = new HBox(4);
    private final VBox root = new VBox();
    private final Map<Category, ToggleButton> chips = new EnumMap<>(Category.class);
    private final ToggleButton all = new ToggleButton("Tout");
    private Category filter;                 // null : tout
    private boolean compact;
    private Consumer<SimEvent> onSelect = event -> { };
    private SimEvent selected;
    private String lastInfo = "";

    public EventJournal() {
        entries.setStyle("-fx-background-color: #0B1120;");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setStyle("-fx-background: #0B1120; -fx-background-color: #0B1120;");
        ToggleGroup group = new ToggleGroup();
        chip(all, group, null);
        all.setSelected(true);
        filters.getChildren().add(all);
        for (Category category : Category.values()) {
            ToggleButton button = new ToggleButton(category.label.charAt(0) + category.label.substring(1).toLowerCase());
            chip(button, group, category);
            chips.put(category, button);
            filters.getChildren().add(button);
        }
        filters.setPadding(new Insets(2, 10, 6, 10));
        filters.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().addAll(filters, scroller);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        root.setStyle("-fx-background-color: #0B1120;");
        rebuild();
    }

    private void chip(ToggleButton button, ToggleGroup group, Category category) {
        button.getStyleClass().add("log-chip");
        button.setToggleGroup(group);
        button.setFocusTraversable(false);
        button.setOnAction(event -> {
            button.setSelected(true);       // un filtre reste toujours choisi
            filter = category;
            rebuild();
        });
    }

    public Region getNode() {
        return root;
    }

    /** Compact : une ligne par événement, cliquable (la vue avec Genesis). */
    public void setCompact(boolean compact) {
        this.compact = compact;
        rebuild();
    }

    /** Ce qu'on fait quand une ligne est cliquée (Genesis l'explique). */
    public void setOnSelect(Consumer<SimEvent> action) {
        onSelect = action;
    }

    /**
     * Le dernier événement qui mérite une explication : parmi ceux du dernier instant, le plus important (le noyau qui se
     * transforme avant la lumière, la lumière avant un électron, tout cela avant une simple information).
     */
    public SimEvent latest() {
        SimEvent best = null;
        for (SimEvent event : events) {
            if (best != null && event.getTimeS() != events.get(0).getTimeS()) {
                break;
            }
            if (best == null || rank(event) > rank(best)) {
                best = event;
            }
        }
        return best;
    }

    private static int rank(SimEvent event) {
        return switch (category(event)) {
            case NUCLEUS -> 3;
            case LIGHT -> 2;
            case ELECTRON -> 1;
            default -> 0;
        };
    }

    public int size() {
        return events.size();
    }

    static Category category(SimEvent event) {
        return switch (event.getKind()) {
            case "emitted", "absorbed", "passed", "photo_ionized" -> Category.LIGHT;
            case "captured", "ionized", "rejected", "excited" -> Category.ELECTRON;
            case "decay", "nucleon" -> Category.NUCLEUS;
            default -> Category.INFO;
        };
    }

    public void add(SimEvent event) {
        // Le tir d'un photon n'est pas un événement : son sort (absorbé, arraché, traverse) en est un.
        if (event.getKind().equals("photon_fired")) {
            return;
        }
        // Une même information répétée n'apprend rien : une seule ligne.
        if (event.getKind().equals("info") && event.getTitle().equals(lastInfo)) {
            return;
        }
        lastInfo = event.getKind().equals("info") ? event.getTitle() : "";
        events.add(0, event);
        if (events.size() > MAX_ENTRIES) {
            events.subList(MAX_ENTRIES, events.size()).clear();
        }
        if (filter == null || filter == category(event)) {
            if (entries.getChildren().size() == 1 && entries.getChildren().get(0) instanceof Label) {
                entries.getChildren().clear();
            }
            entries.getChildren().add(0, row(event));
            scroller.setVvalue(0);
        }
        counts();
    }

    private void counts() {
        Map<Category, Integer> count = new EnumMap<>(Category.class);
        for (SimEvent event : events) {
            count.merge(category(event), 1, Integer::sum);
        }
        all.setText(I18n.text("Tout ") + events.size());
        for (Category category : Category.values()) {
            String name = category.label.charAt(0) + category.label.substring(1).toLowerCase();
            chips.get(category).setText(name + " " + count.getOrDefault(category, 0));
        }
    }

    private void rebuild() {
        entries.getChildren().clear();
        for (SimEvent event : events) {
            if (filter == null || filter == category(event)) {
                entries.getChildren().add(row(event));
            }
        }
        if (entries.getChildren().isEmpty()) {
            Label empty = new Label(events.isEmpty()
                    ? "Rien pour l'instant. Tire un photon, ajoute une particule ou fais couler le temps : chaque événement s'écrira ici, avec son calcul."
                    : "Aucun événement de cette catégorie.");
            empty.setWrapText(true);
            empty.setStyle("-fx-text-fill: #5B6B86; -fx-font-size: 11.5px;");
            empty.setPadding(new Insets(12, 14, 12, 14));
            entries.getChildren().add(empty);
        }
        counts();
    }

    /** Une ligne du journal : complète (catégorie, heure, titre, calcul, explication) ou compacte (une ligne, cliquable). */
    private Region row(SimEvent event) {
        Category category = category(event);
        Color accent = category == Category.LIGHT && event.getWavelengthNm() > 0
                ? PhotonPacket.colour(event.getWavelengthNm()) : Color.web(category.colour);
        String hex = Visuals.hex(accent);
        boolean info = category == Category.INFO;

        Label tag = new Label(category.label);
        tag.setStyle("-fx-text-fill: " + hex + "; -fx-font-size: 9px; -fx-font-weight: bold; -fx-background-color: " + hex
                + "22; -fx-background-radius: 4; -fx-padding: 1 6 1 6;");
        tag.setMinWidth(Region.USE_PREF_SIZE);
        Label time = new Label(TimelineBar.compact(event.getTimeS()));
        time.setStyle("-fx-text-fill: #5B6B86; -fx-font-family: Consolas, monospace; -fx-font-size: 10px;");
        time.setMinWidth(Region.USE_PREF_SIZE);
        time.setTooltip(I18n.tooltip("temps simulé : " + Visuals.duration(event.getTimeS())));
        Label title = label(event.getTitle(), "-fx-text-fill: " + (info ? "#A9B6CB" : "#E6EDF7") + "; -fx-font-size: "
                + (info ? "11.5" : "12.5") + "px;" + (info ? "" : " -fx-font-weight: bold;"));
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        VBox body = new VBox(4);
        if (compact) {
            title.setWrapText(false);
            HBox line = new HBox(8, tag, title, grow, time);
            line.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(title, Priority.SOMETIMES);
            title.setMinWidth(40);
            body.getChildren().add(line);
        } else {
            HBox head = new HBox(8, tag, grow, time);
            head.setAlignment(Pos.CENTER_LEFT);
            body.getChildren().addAll(head, title);
            if (!event.getFormula().isEmpty()) {
                Label formula = label(event.getFormula(),
                        "-fx-text-fill: #FDE68A; -fx-font-family: Consolas, monospace; -fx-font-size: 11px;"
                                + " -fx-background-color: #0F1626; -fx-background-radius: 6; -fx-padding: 5 8 5 8;");
                body.getChildren().add(formula);
            }
            if (!event.getDetail().isEmpty()) {
                body.getChildren().add(label(event.getDetail(), "-fx-text-fill: #94A3B8; -fx-font-size: 11.5px;"));
            }
        }
        body.setPadding(new Insets(compact ? 5 : 9, 12, compact ? 5 : 10, 11));
        Region bar = new Region();
        bar.setMinWidth(3);
        bar.setPrefWidth(3);
        bar.setStyle("-fx-background-color: " + hex + (info ? "66" : "") + ";");
        HBox row = new HBox(bar, body);
        HBox.setHgrow(body, Priority.ALWAYS);
        String base = "-fx-border-color: transparent transparent #162034 transparent; -fx-border-width: 0 0 1 0;";
        row.setStyle(base + (event == selected ? " -fx-background-color: #131C30;" : ""));
        if (compact) {
            row.setStyle(row.getStyle() + " -fx-cursor: hand;");
            Tooltip.install(row, I18n.tooltip("clic : Genesis explique cet événement"));
            row.setOnMouseClicked(click -> {
                selected = event;
                rebuild();
                onSelect.accept(event);
            });
        }
        return row;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle(style);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }
}
