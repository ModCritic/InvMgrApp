package com.modcritic.invmgr.threed.jfx;

import com.modcritic.invmgr.threed.BoxGeometry;
import com.modcritic.invmgr.threed.Shades;
import com.modcritic.invmgr.ui.Tokens;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

/**
 * One item's five colors, as a tiny picture the graphics card can look them up in.
 *
 * <p>A graphics card does not really have a notion of "paint this triangle plain blue"; it paints
 * triangles by reading a picture. So the five shades a box needs are written into a strip five
 * blocks wide, and every corner of every triangle is pointed at the middle of the block it wants.
 * All three corners of a triangle read the same pixel, so there is nothing to blend between and the
 * face comes out perfectly flat.
 *
 * <p>That flatness is the whole look. There is no lamp in the 3D scene and no shadows; a box is
 * lighter on top and darker on its sides purely because those triangles were pointed at different
 * blocks of this strip.
 *
 * <p>The strip is a few hundred bytes per item. That is worth saying out loud, because the obvious
 * alternative (drawing each face's color and its dark edge into a picture at real size) would be
 * something like a megabyte per item, and the room can legally hold five hundred of them.
 */
public final class FaceStrip {

    /**
     * How many pixels wide and tall each color's block is.
     *
     * <p>One pixel per color would do in principle. Eight is used because a graphics card asked
     * to shrink a picture will average neighboring pixels together, and with one-pixel blocks the
     * top face's color could bleed into the north face's. Eight pixels of margin puts the sampled
     * middle far enough from the join that it cannot happen.
     */
    public static final int BLOCK_PX = 8;

    private FaceStrip() {
    }

    /**
     * The strip for an item of this color, as one picture of its own.
     *
     * <p><b>Nothing in the app calls this any more.</b> It is how every box was painted until
     * M6.7b put the whole room on one shared atlas, and it survives as the baseline arm of
     * {@code FaceStripCostProbe}, which is the measurement that made the case for the atlas.
     * Deleting it would delete the before half of a before-and-after. Checked at M6.7c;
     * {@link #atlas} is what {@code JfxRenderer3D} calls.
     *
     * @param cssHsl the item's color as the save file stores it, e.g. {@code hsl(207,55%,42%)}
     */
    public static WritableImage forColor(String cssHsl) {
        return atlas(java.util.List.of(cssHsl));
    }

    /**
     * One picture holding a row of five blocks for each color handed in, in that order.
     *
     * <p><b>Why a room's boxes share one picture.</b> Every box used to carry its own 40 x 8 strip,
     * and on a machine with no graphics card Mesa charges about <b>1.55 ms for the first draw of
     * each distinct picture, whatever its size</b>. So a 200 item room stalled for 290 ms and a 500
     * item room for 780 ms on the single frame the 3D view first became visible, once per entry.
     * Measured three ways before this was written (M6.7b's {@code FirstDrawProbe}): sharing the
     * material alone fixed nothing, sharing the picture alone fixed all of it, and one picture of
     * 40 x 4000 cost the same as one of 40 x 8. <b>The charge is per picture, not per pixel</b>, so
     * packing every color into one picture removes it however many colors a room has. A real room
     * has as many colors as items, because {@code Items.randomColor} gives each new item a random
     * hue, so a cache keyed on color would have helped almost nobody.
     *
     * <p>On a machine with a working graphics card there was never a cost to remove: the same probe
     * on the Radeon RX 6800 XT in this machine reads 10.6 ms for an empty room and 13.3 ms for one
 * with 500 boxes in it. This
     * is not slower there either, so it is one picture everywhere rather than a decision at run
     * time.
     *
     * <p>⚠ <b>Row order is the contract.</b> {@code BoxGeometry.texCoords(row, rowCount)} picks a
     * row by index, so whoever builds the atlas has to hand a box the index its color went in at.
     * {@code JfxRenderer3D} keeps that map; nothing else should build one.
     *
     * @param colors the item colors as the save file stores them, e.g. {@code hsl(207,55%,42%)}
     */
    public static WritableImage atlas(java.util.List<String> colors) {
        WritableImage strip = new WritableImage(
                BoxGeometry.BLOCK_COUNT * BLOCK_PX, Math.max(1, colors.size()) * BLOCK_PX);
        PixelWriter pixels = strip.getPixelWriter();
        for (int row = 0; row < colors.size(); row++) {
            paintRow(pixels, row, colors.get(row));
        }
        return strip;
    }

    /** The five blocks for one color, written into one row of the atlas. */
    private static void paintRow(PixelWriter pixels, int row, String cssHsl) {
        Shades shades = Shades.of(cssHsl);
        int[] lightness = {
            shades.top,
            shades.northSouth,
            shades.eastWest,
            shades.bottom,
            shades.edge,
        };

        for (int block = 0; block < BoxGeometry.BLOCK_COUNT; block++) {
            // Tokens.hsl, not JavaFX's Color.hsb; the app stores web-style HSL, and brightness is
            // a different quantity from lightness. Shades has the same warning on it.
            Color color = Tokens.hsl(shades.hue, shades.saturation / 100.0,
                    lightness[block] / 100.0);
            int fromX = block * BLOCK_PX;
            int fromY = row * BLOCK_PX;
            for (int x = fromX; x < fromX + BLOCK_PX; x++) {
                for (int y = fromY; y < fromY + BLOCK_PX; y++) {
                    pixels.setColor(x, y, color);
                }
            }
        }
    }

    /** The color a given block of the strip holds, for tests and for sampling checks. */
    public static Color colorOfBlock(String cssHsl, int block) {
        Shades shades = Shades.of(cssHsl);
        int lightness = switch (block) {
            case BoxGeometry.BLOCK_TOP -> shades.top;
            case BoxGeometry.BLOCK_NORTH_SOUTH -> shades.northSouth;
            case BoxGeometry.BLOCK_EAST_WEST -> shades.eastWest;
            case BoxGeometry.BLOCK_BOTTOM -> shades.bottom;
            case BoxGeometry.BLOCK_EDGE -> shades.edge;
            default -> throw new IllegalArgumentException("no such color block: " + block);
        };
        return Tokens.hsl(shades.hue, shades.saturation / 100.0, lightness / 100.0);
    }
}
