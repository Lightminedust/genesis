package org.genesis.fusion;

import org.genesis.app.I18n;
import com.studio.genesis.grpc.LabElement;
import com.studio.genesis.grpc.LabIsotope;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/** The complete isotope inventory supplied by the physics engine. */
public final class CollisionPicker {
    private final Dialog<Integer> dialog=new Dialog<>();
    private final FlowPane isotopes=new FlowPane(5,5);
    private final Label selected=new Label();
    private final Label mass=new Label();
    private final GridPane table=new GridPane();
    private LabElement element;
    private int isotope;
    private final ButtonType use=new ButtonType("Utiliser ce noyau",ButtonBar.ButtonData.OK_DONE);

    CollisionPicker(List<LabElement> catalog,int z,int a,String side,IntConsumer consumer) {
        dialog.setTitle(I18n.text("Noyau ")+side+I18n.text(" · tableau périodique et isotopes"));
        dialog.getDialogPane().getStyleClass().add("fusion-lab");
        var css=CollisionPicker.class.getResource("/genesis.css");
        if(css!=null)dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        dialog.getDialogPane().setStyle("-fx-background-color:#0B1422;");
        dialog.getDialogPane().getButtonTypes().addAll(use,ButtonType.CANCEL);
        TextField search=new TextField();search.setPromptText("Rechercher un nom, un symbole ou un numéro atomique…");
        search.setId("collision-element-search");
        Label heading=new Label("118 ÉLÉMENTS  /  ISOTOPES DU CATALOGUE");heading.setStyle("-fx-text-fill:#95ACCA;-fx-font-size:11px;");
        table.setHgap(3);table.setVgap(3);
        for(LabElement item:catalog) {
            Label number=new Label(Integer.toString(item.getZ()));number.setStyle("-fx-text-fill:#8393A8;-fx-font-size:8px;");
            Label symbol=new Label(item.getSymbol());symbol.setStyle("-fx-text-fill:"+color(item)+";-fx-font-size:15px;-fx-font-weight:bold;");
            VBox graphics=new VBox(-1,number,symbol);graphics.setAlignment(Pos.CENTER);
            Button tile=new Button();tile.setGraphic(graphics);tile.setUserData(item);tile.setId("collision-element-"+item.getZ());
            tile.setMinSize(39,36);tile.setPrefSize(39,36);tile.setPadding(Insets.EMPTY);
            tile.setStyle("-fx-background-color:#152235;-fx-background-radius:5;-fx-cursor:hand;");
            tile.setAccessibleText(item.getName()+I18n.text(", numéro atomique ")+item.getZ());
            tile.setTooltip(I18n.tooltip(item.getName()+" · Z = "+item.getZ()+" · "+item.getIsotopesCount()+" isotopes"));
            tile.setOnAction(e->showIsotopes(item,item.getDefaultA()));
            int row=item.getRow();table.add(tile,Math.max(0,item.getCol()-1),Math.max(0,row>=9?row-1:row-1));
        }
        search.textProperty().addListener((o,b,q)->{
            String needle=fold(q);
            table.getChildren().forEach(node->{LabElement item=(LabElement)node.getUserData();
                boolean matches=needle.isBlank()||fold(item.getName()).contains(needle)
                        ||fold(I18n.text(item.getName())).contains(needle)
                        ||fold(item.getSymbol()).equals(needle)||Integer.toString(item.getZ()).equals(needle);
                node.setOpacity(matches?1:.16);node.setDisable(!matches);
            });
        });
        selected.setStyle("-fx-text-fill:#EDF5FF;-fx-font-size:17px;-fx-font-weight:bold;");
        mass.setStyle("-fx-text-fill:#91A4C0;-fx-font-size:11px;");mass.setWrapText(true);
        isotopes.setPrefWrapLength(750);isotopes.setPadding(new Insets(0,0,5,0));
        ScrollPane isotopeScroll=new ScrollPane(isotopes);isotopeScroll.setFitToWidth(true);isotopeScroll.setPrefViewportHeight(84);
        isotopeScroll.setStyle("-fx-background:#0B1422;-fx-background-color:#0B1422;");
        VBox content=new VBox(10,heading,search,table,selected,mass,isotopeScroll);content.setPadding(new Insets(14));
        dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefWidth(805);
        dialog.getDialogPane().lookupButton(use).setId("collision-use-isotope");
        dialog.setResultConverter(button->button==use&&element!=null?element.getZ()*1000+isotope:null);
        dialog.setOnHidden(e->{if(dialog.getResult()!=null)consumer.accept(dialog.getResult());});
        catalog.stream().filter(item->item.getZ()==z).findFirst().ifPresent(item->showIsotopes(item,a));
        if(element==null&&!catalog.isEmpty())showIsotopes(catalog.get(0),catalog.get(0).getDefaultA());
    }
    void show(){dialog.show();}
    private void showIsotopes(LabElement item,int chosen) {
        element=item;isotopes.getChildren().clear();
        ToggleGroup group=new ToggleGroup();
        for(LabIsotope data:item.getIsotopesList()) {
            ToggleButton button=new ToggleButton(item.getSymbol()+"–"+data.getA()+(data.getStable()?" •":""));
            button.setToggleGroup(group);button.setId("collision-isotope-"+item.getZ()+"-"+data.getA());
            button.setStyle("-fx-font-size:11px;-fx-padding:6 9;");
            button.setTooltip(I18n.tooltip(data.getStable()?"Stable":data.getHalfLifeS()>0?"Demi-vie : "+String.format(I18n.locale(),"%.3g s",data.getHalfLifeS()):"Radioactif · demi-vie non renseignée"));
            button.setOnAction(e->{button.setSelected(true);select(item,data);});isotopes.getChildren().add(button);
            if(data.getA()==chosen){button.setSelected(true);select(item,data);}
        }
        if(isotope!=chosen&&item.getIsotopesCount()>0){((ToggleButton)isotopes.getChildren().get(0)).fire();}
    }
    private void select(LabElement item,LabIsotope data) {
        isotope=data.getA();selected.setText(item.getName()+" · "+item.getSymbol()+"–"+isotope+"     "+item.getZ()+" protons / "+(isotope-item.getZ())+" neutrons");
        mass.setText(String.format(I18n.locale(),I18n.text("Masse atomique du catalogue : %.6f u   ·   %s   ·   Noyau entièrement ionisé (+%de)"),data.getMassU(),data.getStable()?"stable":"radioactif",item.getZ()));
    }
    private static String color(LabElement item){return item.getColor().isBlank()?"#AECDEB":item.getColor();}
    private static String fold(String text){return Normalizer.normalize(text.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","");}
}
