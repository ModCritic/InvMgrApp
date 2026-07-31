package com.modcritic.invmgr.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Cursor;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * The vertical slider control itself, drawn to match the original.
 *
 * <p><b>Why this is hand-built rather than a JavaFX {@code Slider}.</b> The original is an HTML
 * range input, which fills its track with the accent colour from the low end up to the handle
 * and leaves the rest plain. JavaFX's slider has a single uniform track with no notion of a
 * filled portion, so a standard slider cannot show it however it is styled — and the filled
 * track is the most visually prominent part of the whole drawer.
 *
 * <p>Every measurement below was read off {@code reference/desktop-04-item-on-canvas.png} by
 * sampling pixels, not estimated:
 *
 * <ul>
 *   <li>track 6 px wide, {@code #7ab} — sampled solid from x=42 to x=45 with antialiasing either side
 *   <li>thumb ~19 px across, fill {@code #676774}, ringed in white — sampled at the handle's centre
 * </ul>
 *
 * <p>The colour of the track <em>above</em> the handle initially had no reference to measure —
 * every screenshot in {@code reference/} has the handle parked at the very top, so that part of
 * the track is not visible in any pixel of any of them. The user pointed to a further screenshot
 * taken with the slider pulled down, which settled it at {@code #e9e9ed}. The provisional guess
 * before that was the app's own {@code #555} border colour, and it was badly wrong — the real
 * value is nearly white, not a dark grey.
 */
public final class LayerTrack extends Region {

    /** Sampled from the reference: the filled part of the track. */
    private static final Color FILLED_TRACK = Tokens.SLIDER_ACCENT;

    /**
     * The part of the track above the handle. Sampled as {@code rgb(233,233,237)} from a
     * screenshot taken with the slider pulled down — the same 6 px width as the filled part.
     *
     * <p>Deliberately not one of the app's own palette colours: this is the browser's default
     * range-input track showing through, which is why it is far lighter than anything else in
     * the interface.
     */
    static final Color UNFILLED_TRACK = Color.rgb(233, 233, 237);

    /** Sampled from the reference at the handle's centre. */
    private static final Color THUMB_FILL = Color.web("#676774");
    private static final Color THUMB_RING = Color.WHITE;

    private static final double TRACK_WIDTH = 6;

    /**
     * The track's ends are fully rounded — a pill shape, not a rectangle.
     *
     * <p>Derived from the width rather than written as 3, so the two cannot drift apart: a
     * radius of half the width is exactly what makes the end a semicircle. Confirmed by sampling
     * both ends in {@code desktop-05-items-and-planned.png}, where the corner columns fade out
     * about three rows before the middle ones do.
     */
    private static final double TRACK_RADIUS = TRACK_WIDTH / 2;

    private static final double THUMB_RADIUS = 9.5;
    private static final double THUMB_RING_WIDTH = 1.5;

    private final Region unfilled = new Region();
    private final Region filled = new Region();
    private final Circle thumb = new Circle(THUMB_RADIUS);

    private final DoubleProperty value = new SimpleDoubleProperty(0);
    private double min;
    private double max = 1;

    public LayerTrack() {
        unfilled.setBackground(solid(UNFILLED_TRACK));
        filled.setBackground(solid(FILLED_TRACK));
        thumb.setFill(THUMB_FILL);
        thumb.setStroke(THUMB_RING);
        thumb.setStrokeWidth(THUMB_RING_WIDTH);

        getChildren().addAll(unfilled, filled, thumb);
        setCursor(Cursor.HAND);
        setPrefWidth(Tokens.SLIDER_WIDTH);
        setMinWidth(Tokens.SLIDER_WIDTH);
        setMaxWidth(Tokens.SLIDER_WIDTH);
        // Must be allowed to stretch, or the HBox lays it out at its (zero) preferred height.
        setMaxHeight(Double.MAX_VALUE);

        setOnMousePressed(event -> {
            requestFocus();
            setValueFromY(event.getY());
        });
        setOnMouseDragged(event -> setValueFromY(event.getY()));

        // Arrow keys, because a slider that can only be driven by pointer is unusable for
        // anyone working by keyboard — and it costs four lines.
        setFocusTraversable(true);
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.RIGHT) {
                setValue(value.get() + 1);
                event.consume();
            } else if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.LEFT) {
                setValue(value.get() - 1);
                event.consume();
            }
        });

        value.addListener((observable, old, current) -> requestLayout());
    }

    public DoubleProperty valueProperty() {
        return value;
    }

    public double getValue() {
        return value.get();
    }

    /** Sets the handle position, snapped to whole steps and held inside the range. */
    public void setValue(double newValue) {
        double snapped = Math.round(Math.max(min, Math.min(max, newValue)));
        value.set(snapped);
    }

    public void setRange(double min, double max) {
        this.min = min;
        // A room with no height would make the range zero-width and every position identical;
        // one step keeps the arithmetic below meaningful.
        this.max = Math.max(max, min + 1);
        setValue(value.get());
        requestLayout();
    }

    public double getMax() {
        return max;
    }

    public double getMin() {
        return min;
    }

    /**
     * Converts a pointer position into a value.
     *
     * <p>The Y axis is inverted: the top of the drawer is the <em>highest</em> layer, but screen
     * coordinates count downward.
     */
    private void setValueFromY(double y) {
        double usable = Math.max(1, getHeight() - THUMB_RADIUS * 2);
        double fromTop = Math.max(0, Math.min(usable, y - THUMB_RADIUS));
        double fraction = 1 - fromTop / usable;
        setValue(min + fraction * (max - min));
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        double trackX = (width - TRACK_WIDTH) / 2;

        // The handle's centre never leaves the track, so it is inset by its own radius at each
        // end — otherwise half the circle would hang outside the drawer at the extremes.
        double usable = Math.max(1, height - THUMB_RADIUS * 2);
        double fraction = (value.get() - min) / (max - min);
        double thumbCentreY = THUMB_RADIUS + (1 - fraction) * usable;

        unfilled.resizeRelocate(trackX, THUMB_RADIUS, TRACK_WIDTH, usable);
        // Filled portion runs from the handle down to the bottom, which is the low end.
        double filledHeight = Math.max(0, THUMB_RADIUS + usable - thumbCentreY);
        filled.resizeRelocate(trackX, thumbCentreY, TRACK_WIDTH, filledHeight);

        thumb.setCenterX(width / 2);
        thumb.setCenterY(thumbCentreY);
    }

    /**
     * A track segment: a solid colour with both ends rounded.
     *
     * <p>Both segments are rounded, including where they meet under the handle. That join is
     * never visible — the handle is 19 px across and the track only 6 — so rounding both is
     * simpler than special-casing the two inner ends, and looks identical.
     */
    private static javafx.scene.layout.Background solid(Color color) {
        return new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(
                color, new javafx.scene.layout.CornerRadii(TRACK_RADIUS), null));
    }
}
