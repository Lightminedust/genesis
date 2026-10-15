package org.genesis.app;

import javafx.scene.image.Image;
import javafx.stage.Stage;

/** Images fournies dans assets/, embarquées par Gradle pour toutes les fenêtres JavaFX. */
public final class Branding {
    private static final Image ICON_96 = load("/favicon-96x96.png");
    private static final Image ICON_192 = load("/web-app-manifest-192x192.png");
    private static final Image ICON_512 = load("/web-app-manifest-512x512.png");

    private Branding() {
    }

    private static Image load(String name) {
        var resource = Branding.class.getResource(name);
        if (resource == null) {
            throw new IllegalStateException("Asset Genesis absent : " + name);
        }
        return new Image(resource.toExternalForm());
    }

    public static Image logo() {
        return ICON_192;
    }

    public static void applyWindowIcons(Stage stage) {
        stage.getIcons().addAll(ICON_96, ICON_192, ICON_512);
    }
}
