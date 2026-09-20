package com.modcritic.invmgr.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.transform.Scale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Loads the app's four hand-drawn icons out of their original {@code .svg} files.
 *
 * <p>The icons are the user's own artwork and the ground rules are explicit: <b>use the files, never
 * reconstruct them from a description</b>. JavaFX cannot open an SVG, so rather than copying the
 * shapes into Java by hand (which is reconstructing them, just slowly), the files ship inside the
 * app and their shapes are read out at startup. Edit the {@code .svg} and the button changes.
 *
 * <p><b>The two files are not the same shape inside, and that is the trap.</b> {@code btn-3d.svg}
 * is three {@code <path>} elements with {@code fill} and {@code fill-opacity} as ordinary
 * attributes. {@code btn-2d.svg} is two {@code <rect>} elements with the same information buried in
 * a {@code style="..."} string instead. Anyone assuming "SVG means path data with fill attributes"
 * gets an empty button that draws nothing and reports no error.
 *
 * <p><b>Only the direct children of the drawing are used.</b> {@code btn-2d.svg} also carries a
 * {@code <clipPath>} tucked inside {@code <defs>}, left over from how it was drawn. It is a
 * definition, not a shape; including it would paint a third square nobody asked for.
 *
 * <p><b>An icon is measured by its artboard, not by its ink</b>, and it is wrapped in a second
 * group so that the scaling counts toward its measured size. Both of those look like fussy detail
 * and neither is; see {@link #load} for what happens without them.
 */
public final class Icons {

    private static final String RESOURCE_DIR = "/com/modcritic/invmgr/ui/icons/";

    private Icons() {
    }

    /** The isometric cube that opens the 3D view. */
    public static Node threeD(double sizePx) {
        return load("btn-3d.svg", sizePx);
    }

    /** The nested squares that come back to the flat view. */
    public static Node twoD(double sizePx) {
        return load("btn-2d.svg", sizePx);
    }

    /** The stack of squares on the layer slider's drawer tab. */
    public static Node layerSlider(double sizePx) {
        return load("btn-layerslider.svg", sizePx);
    }

    /** The three lines on the item list's drawer tab. */
    public static Node itemList(double sizePx) {
        return load("btn-itemlist.svg", sizePx);
    }

    /**
     * Reads one icon and scales it to the size asked for.
     *
     * <p>Fails loudly rather than quietly returning nothing. An icon that silently does not load
     * leaves a button that looks broken with no clue why, and the same reasoning already applies to
     * the bundled fonts; see {@link Fonts}.
     */
    static Node load(String fileName, double sizePx) {
        Document document = parse(fileName);
        Element svg = document.getDocumentElement();
        List<Node> shapes = new ArrayList<>();

        NodeList children = svg.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element element)) {
                continue;
            }
            switch (element.getTagName()) {
                case "path" -> shapes.add(path(element));
                case "rect" -> shapes.add(rect(element));
                case "line" -> shapes.add(line(element));
                default -> { }
            }
        }

        if (shapes.isEmpty()) {
            throw new IllegalStateException(
                    "no drawable shapes found in " + fileName + ": the file's structure changed");
        }

        // The artboard, as a rectangle nothing can see. It is not decoration: without it the icon
        // measures the size of its own ink instead of the square it was drawn in, so the cube (a
        // wide, short shape) and the nested squares would come out different sizes from the same
        // request, and neither would be centered where the artist put it.
        double board = artboardOf(svg.getAttribute("viewBox"), fileName);
        Rectangle artboard = new Rectangle(0, 0, board, board);
        artboard.setFill(Color.TRANSPARENT);
        shapes.add(0, artboard);

        Group content = new Group(shapes.toArray(new Node[0]));
        double scale = sizePx / board;
        content.getTransforms().add(new Scale(scale, scale));

        // The outer wrapper is the entire reason the icon lands on its button, and it looks like
        // it does nothing. A Group's own transforms are NOT part of its layout bounds: those are
        // the union of its children's bounds, measured before the Group's own scale. So a scaled
        // Group reports the size it was drawn at, 128 px, while painting 18. A button laid the
        // cube out as 128 wide inside 30 px of space, centered that by starting it 49 px to the
        // left of the button, and drew all 18 visible pixels off the edge.
        //
        // Wrapping it makes the scale a CHILD's transform, which layout does count. Reported by
        // the user as "a copied Add button with no icon", and it was very nearly that.
        Group scaled = new Group(content);
        // The button underneath must still receive the click.
        scaled.setMouseTransparent(true);
        return scaled;
    }

    /**
     * How big a square the icon was drawn in, taken from the file's {@code viewBox} rather than
     * assumed.
     *
     * <p>All four icons are 128 px, so hardcoding that would work today and go on working until
     * somebody drew one at a different size, at which point it would come out the wrong size on
     * screen with nothing to say why. Reading it costs two lines.
     *
     * <p>A {@code viewBox} is four numbers: a corner and a size. Only the size is used, and the
     * larger of its two halves at that, so a drawing that is not square still lands in a square
     * the way a button expects.
     */
    static double artboardOf(String viewBox, String fileName) {
        String[] parts = viewBox.trim().split("[\\s,]+");
        if (parts.length != 4) {
            throw new IllegalStateException(fileName + " has no usable viewBox (\"" + viewBox
                    + "\"), so there is no way to tell how big it was drawn");
        }
        return Math.max(number(parts[2]), number(parts[3]));
    }

    private static SVGPath path(Element element) {
        SVGPath shape = new SVGPath();
        shape.setContent(element.getAttribute("d"));
        shape.setFill(fillOf(element));
        applyStroke(shape, element);
        return shape;
    }

    private static Rectangle rect(Element element) {
        Rectangle shape = new Rectangle(
                number(element.getAttribute("x")),
                number(element.getAttribute("y")),
                number(element.getAttribute("width")),
                number(element.getAttribute("height")));
        shape.setFill(fillOf(element));
        applyStroke(shape, element);
        return shape;
    }

    /**
     * A bare line, which is the whole of {@code btn-itemlist.svg}: three of them, round-capped.
     *
     * <p>It has no fill at all, and that is not the same as a white one. A shape whose outline is
     * everything it is must be told so explicitly, or the default takes over and paints a filled
     * triangle between the ends of anything that is not perfectly straight.
     */
    private static Line line(Element element) {
        Line shape = new Line(
                number(element.getAttribute("x1")),
                number(element.getAttribute("y1")),
                number(element.getAttribute("x2")),
                number(element.getAttribute("y2")));
        shape.setFill(null);
        applyStroke(shape, element);
        return shape;
    }

    /**
     * The outline a shape is drawn with, if it has one.
     *
     * <p>Added at M6.4 for the two drawer tabs, and needed by both in different ways: the item
     * list's icon is <em>nothing but</em> stroke, and the layer slider's middle square is a filled
     * shape with a 3 px outline at 74.5% opacity, which is what makes it read as the selected layer
     * rather than as one more square.
     *
     * <p>Silent about shapes that have no stroke, which is every shape in the two icons that were
     * here first; {@code btn-3d.svg} and {@code btn-2d.svg} are unchanged by this.
     *
     * <p>⚠ Note that {@code btn-layerslider.svg} carries {@code stroke-width: 500.001} on three of
     * its four paths <em>with no stroke color</em>, left over from how it was drawn. A width
     * without a color draws nothing, and reading the width first and asking about the color
     * afterwards would have painted three enormous white slabs over the icon.
     */
    private static void applyStroke(Shape shape, Element element) {
        String color = property(element, "stroke");
        if (color.isEmpty() || color.equals("none")) {
            return;
        }

        Color base = Color.web(color);
        String opacity = property(element, "stroke-opacity");
        shape.setStroke(opacity.isEmpty() ? base
                : Color.color(base.getRed(), base.getGreen(), base.getBlue(), number(opacity)));

        String width = property(element, "stroke-width");
        if (!width.isEmpty()) {
            shape.setStrokeWidth(number(width));
        }
        if ("round".equals(property(element, "stroke-linecap"))) {
            shape.setStrokeLineCap(StrokeLineCap.ROUND);
        }
    }

    /**
     * The color a shape is painted, from whichever of the two spellings the file happens to use:
     * plain attributes, or a CSS declaration block in {@code style}.
     */
    private static Color fillOf(Element element) {
        String color = property(element, "fill");
        String opacity = property(element, "fill-opacity");

        Color base = color.isEmpty() ? Color.WHITE : Color.web(color);
        if (opacity.isEmpty()) {
            return base;
        }
        return Color.color(base.getRed(), base.getGreen(), base.getBlue(), number(opacity));
    }

    private static String property(Element element, String name) {
        String attribute = element.getAttribute(name);
        if (!attribute.isEmpty()) {
            return attribute;
        }
        for (String declaration : element.getAttribute("style").split(";")) {
            int colon = declaration.indexOf(':');
            if (colon > 0 && declaration.substring(0, colon).trim().equals(name)) {
                return declaration.substring(colon + 1).trim();
            }
        }
        return "";
    }

    private static double number(String text) {
        return text.isEmpty() ? 0 : Double.parseDouble(text);
    }

    private static Document parse(String fileName) {
        try (InputStream in = Icons.class.getResourceAsStream(RESOURCE_DIR + fileName)) {
            if (in == null) {
                throw new IllegalStateException("icon missing from the app: " + fileName
                        + ": check the <resources> block in pom.xml");
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // The icons are our own files and never come from outside, but a parser that will
            // fetch whatever a document tells it to is worth switching off on principle; the app
            // makes no network calls at runtime, ever, and that includes its XML parser.
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(in);
        } catch (IOException | ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("could not read the icon " + fileName, e);
        }
    }
}
