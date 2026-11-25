package org.genesis.app;

import javafx.scene.image.Image;
import javafx.stage.Stage;

/** Les icônes de Genesis (assets/brand : le G_ sur son carré de nuit), embarquées par Gradle pour toutes les fenêtres. */
public final class Branding {
    private static final Image[] ICONS = {
            load("/brand/web/favicon-16.png"), load("/brand/web/favicon-32.png"), load("/brand/web/favicon-96.png"),
            load("/brand/icone-app/genesis-icone-app-256.png"), load("/brand/icone-app/genesis-icone-app-512.png")};

    private Branding() {
    }

    private static Image load(String name) {
        var resource = Branding.class.getResource(name);
        if (resource == null) {
            throw new IllegalStateException("Asset Genesis absent : " + name);
        }
        return new Image(resource.toExternalForm());
    }

    /** L'icône de l'application (256 px). */
    public static Image logo() {
        return ICONS[3];
    }

    public static void applyWindowIcons(Stage stage) {
        stage.getIcons().addAll(ICONS);
    }
}
