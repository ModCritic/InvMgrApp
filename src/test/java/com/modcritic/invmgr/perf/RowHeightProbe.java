package com.modcritic.invmgr.perf;

import com.modcritic.invmgr.ui.Tokens;
import com.modcritic.invmgr.ui.TouchType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Why does an unparented row report the wrong height for a name that wraps?
 *
 * <p>M6.7b's virtualized item list builds its spacers from the height of rows that do not exist,
 * and the first version asked an off-scene copy of the row for {@code prefHeight(width)}. It came
 * back 20 px for a row that renders at 79. This asks the same question four ways to find out which
 * part is lying, because the answer decides how the real thing measures.
 *
 * <p>Not a test. Run it deliberately: {@code mvn -B test -Dtest=RowHeightProbe}.
 */
class RowHeightProbe extends ApplicationTest {

    private static final String LONG =
            "a name long enough that it has to wrap onto a second line in this panel";

    private HBox host;

    @Override
    public void start(Stage stage) {
        host = new HBox();
        stage.setScene(new javafx.scene.Scene(host, 400, 300));
        stage.show();
    }

    @Test
    void whichWayOfAskingIsRight() {
        WaitForAsyncUtils.waitForAsyncFx(10000, () -> {
            double width = Tokens.LIST_PANEL_WIDTH;
            Label label = new Label(LONG);
            label.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.listRowFont()));
            label.setWrapText(true);
            label.setMinWidth(0);
            HBox.setHgrow(label, Priority.ALWAYS);

            Circle dot = new Circle(Tokens.LIST_DOT_RADIUS);
            HBox row = new HBox(Tokens.LIST_ROW_GAP, dot, label);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(TouchType.listRowPaddingV(), TouchType.listRowPaddingH(),
                    TouchType.listRowPaddingV(), TouchType.listRowPaddingH()));

            System.out.printf("PROBE panel width %.1f, dot radius %.1f, gap %.1f, padding %.1f/%.1f%n",
                    width, Tokens.LIST_DOT_RADIUS, Tokens.LIST_ROW_GAP,
                    TouchType.listRowPaddingH(), TouchType.listRowPaddingV());
            System.out.printf("PROBE row.prefHeight(width)            -> %.1f%n",
                    row.prefHeight(width));
            System.out.printf("PROBE row.prefHeight(-1)               -> %.1f%n",
                    row.prefHeight(-1));
            System.out.printf("PROBE row contentBias                  -> %s%n",
                    row.getContentBias());
            System.out.printf("PROBE label contentBias                -> %s%n",
                    label.getContentBias());
            System.out.printf("PROBE label.prefWidth(-1)              -> %.1f%n",
                    label.prefWidth(-1));

            double inner = width - TouchType.listRowPaddingH() * 2
                    - Tokens.LIST_DOT_RADIUS * 2 - Tokens.LIST_ROW_GAP;
            System.out.printf("PROBE label.prefHeight(inner %.1f)     -> %.1f%n",
                    inner, label.prefHeight(inner));
            System.out.printf("PROBE that plus padding                -> %.1f%n",
                    label.prefHeight(inner) + TouchType.listRowPaddingV() * 2);

            // And the answer the real thing has to agree with: laid out at that width for real.
            row.setPrefWidth(width);
            row.setMaxWidth(width);
            row.resize(width, row.prefHeight(width));
            row.applyCss();
            row.layout();
            System.out.printf("PROBE after resize+layout, prefHeight  -> %.1f%n",
                    row.prefHeight(width));
            System.out.printf("PROBE after resize+layout, height      -> %.1f%n", row.getHeight());
            System.out.printf("PROBE label width after layout         -> %.1f%n", label.getWidth());

            // Now the same row INSIDE a scene, invisible and unmanaged, which is what the panel
            // would do with its ruler. A Label measures text through its skin, and a control with
            // no Scene has never been given one.
            row.setVisible(false);
            row.setManaged(false);
            host.getChildren().add(row);
            row.applyCss();
            System.out.println("PROBE ---- in the scene, invisible and unmanaged ----");
            System.out.printf("PROBE label.prefWidth(-1)              -> %.1f%n",
                    label.prefWidth(-1));
            System.out.printf("PROBE row.prefHeight(width %.1f)       -> %.1f%n",
                    width, row.prefHeight(width));
            System.out.printf("PROBE row.prefHeight(500)              -> %.1f%n",
                    row.prefHeight(500));
            return null;
        });
    }
}
