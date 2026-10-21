package org.genesis.app;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Changer de langue dans une vraie scène : les textes suivent, sans toucher aux textes liés. */
public class I18nSceneTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @Test void switchingLanguageLeavesBoundMenuLabelsAlone() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            I18n.Language previous = I18n.language();
            try {
                I18n.setLanguage(I18n.Language.FR);
                MenuButton labs = new MenuButton("Laboratoires");
                labs.getItems().add(new MenuItem("Chambre vide"));
                // Comme le libellé interne d'un MenuButton : un texte lié à une source qu'on ne traduit pas soi-même.
                Label mirror = new Label();
                mirror.textProperty().bind(new SimpleStringProperty("Laboratoires"));
                Scene scene = new Scene(new StackPane(labs, mirror), 400, 200);
                scene.snapshot(null);
                I18n.attach(scene);
                I18n.setLanguage(I18n.Language.EN);   // levait « A bound value cannot be set »
                assertEquals("Laboratories", labs.getText());
                assertEquals("Laboratoires", mirror.getText());
                assertEquals("Empty chamber", labs.getItems().get(0).getText());
                I18n.setLanguage(I18n.Language.FR);
                assertEquals("Laboratoires", labs.getText());
            } finally {
                I18n.setLanguage(previous);
            }
            return null;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }
}
