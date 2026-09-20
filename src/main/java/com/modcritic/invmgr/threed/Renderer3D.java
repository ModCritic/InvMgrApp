package com.modcritic.invmgr.threed;

import com.modcritic.invmgr.model.AppState;

/**
 * Everything the 3D view asks of whatever is actually drawing it.
 *
 * <p><b>This interface is the reason the 3D renderer choice can still be changed.</b> Deciding to
 * use JavaFX's own 3D support (`OD-1`) came with an accepted risk written into the ground rules:
 * the toolchain that builds the phone version is lightly maintained, was published in 2022, and its
 * vendor documents no 3D support at all. It works (it was measured working on a real phone), but
 * if it ever stops, the fallback is a different graphics library, and the cost of that swap is
 * whatever has leaked out of this boundary.
 *
 * <p>So nothing here mentions a JavaFX type. The camera, the movement, the transitions and the
 * shading all sit in front of this interface and know nothing about what is behind it. The
 * original kept the same shape for the same reason: it had three different renderers behind it at
 * various points.
 *
 * <p>The one thing deliberately <em>not</em> on the interface is how the drawing gets on screen.
 * That is a JavaFX node, and pretending otherwise would be a fiction: the view asks the concrete
 * renderer for it by name, in one line, which is honest about where the seam really is.
 */
public interface Renderer3D {

    /** Sets up the drawing surface at this size in pixels. Called once, when the view opens. */
    void init(double widthPx, double heightPx);

    /**
     * Builds the room and everything in it. Called after {@link #init}, and again whenever the
     * room or its contents change.
     */
    void build(AppState state);

    /** Draws one frame from this camera. */
    void render(CameraPose camera);

    /** The window changed size. */
    void resize(double widthPx, double heightPx);

    /** Lets everything go. After this the renderer is finished with. */
    void dispose();

    /**
     * Which item is under this point on the drawing surface, or {@code null} for none. Nearest hit
     * along the ray wins.
     *
     * <p>Returns the item's <b>id</b> rather than the item itself. {@link Box3D} already carries
     * one, so an id is what a renderer naturally has to hand; turning it back into an
     * {@code Item} means knowing about the model, and this side of the boundary deliberately does
     * not.
     *
     * <p>The camera is a parameter for the same reason {@link #render} takes one: a renderer
     * draws from whatever pose it is handed and never keeps its own.
     *
     * <p>Every room surface is mouse-transparent, so nothing this returns can be a wall or the
     * floor. That is not luck; it was done at M5.1 for this method.
     */
    String pickItem(double xPx, double yPx, CameraPose camera);
}
