package com.modcritic.invmgr.ui;

import javafx.geometry.Insets;
import javafx.scene.layout.VBox;

/**
 * The frame the top bar sits in, and (on a phone) the island under it.
 *
 * <p>The original has this too, as {@code #top-wrap}, and puts two things on it that look like they
 * belong to the bar: the {@code #252525} background and the {@code #444} rule along the bottom.
 * Both were on {@code TopBar} here until M6.4, and both had to move, for two separate reasons.
 *
 * <h2>The bar folds away, and its color must not go with it</h2>
 *
 * <p>On a phone the top bar collapses to nothing. A background painted on the bar collapses with
 * it, which would leave the island floating on the window's darker {@code #1a1a1a} and a visible
 * seam where the two grays meet. Painted here, the color runs behind both pieces and stays put
 * whether the bar is out or folded.
 *
 * <h2>And the space behind the clock must not go with it either</h2>
 *
 * <p>M6.1b reserved room for the phone's status bar by growing the top bar's own top padding, so
 * the bar's color ran up behind the clock and the two read as one piece rather than showing a
 * seam. That was right, and it stops being right the moment the bar can fold: a folded bar has no
 * padding, so the island would slide up under the clock.
 *
 * <p>So the reservation moved here, one level out, where nothing folds. The reasoning is unchanged
 * and the pixels are unchanged (this is the same color extending up behind the same clock); it is
 * simply held by the piece that is always there.
 */
public final class TopWrap extends VBox {

    public TopWrap(TopBar bar) {
        reserveTop(0);
        setStyle("-fx-background-color: " + Tokens.hex(Tokens.TOP_BAR_BG) + ";"
                + "-fx-border-color: transparent transparent " + Tokens.hex(Tokens.BORDER)
                + " transparent;"
                + "-fx-border-width: 0 0 1 0;");
        getChildren().add(bar);
    }

    /**
     * Puts the six-button island under the bar. Touch only; a desktop never calls this.
     *
     * <p>Under rather than over, because the island is what stays when the bar folds and a strip
     * that jumped from below the bar to the top of the screen every time you pressed {@code ▼}
     * would be hard to aim at twice in a row.
     */
    public void addIsland(TouchIsland island) {
        getChildren().add(island);
    }

    /**
     * Grows upward to sit clear of the phone's status bar, keeping the bar's color behind the
     * clock.
     *
     * <p>Called with zero from the constructor so there is exactly one place that sets this
     * padding, and called again with a real number on Android. On a desktop it is only ever zero.
     *
     * @param extra design pixels to reserve above the top bar
     */
    public void reserveTop(double extra) {
        setPadding(new Insets(extra, 0, 0, 0));
    }
}
