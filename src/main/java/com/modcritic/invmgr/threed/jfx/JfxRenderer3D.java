package com.modcritic.invmgr.threed.jfx;

import com.modcritic.invmgr.model.AppState;
import com.modcritic.invmgr.threed.Box3D;
import com.modcritic.invmgr.threed.BoxGeometry;
import com.modcritic.invmgr.threed.CameraPose;
import com.modcritic.invmgr.threed.Geometry3D;
import com.modcritic.invmgr.threed.GridTile;
import com.modcritic.invmgr.threed.Perspective;
import com.modcritic.invmgr.threed.Picking;
import com.modcritic.invmgr.threed.Renderer3D;
import com.modcritic.invmgr.threed.RoomGeometry;
import com.modcritic.invmgr.ui.Tokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

/**
 * The one thing that actually draws the 3D view, using JavaFX's own 3D support.
 *
 * <p>Everything JavaFX-specific about 3D is in this file and its two neighbors. That is the point
 * of {@link Renderer3D}; see the note there about why the renderer choice has to stay swappable.
 *
 * <p><b>There is no light in this room, and the one light that exists is a trick.</b> The scene
 * holds a single white ambient light, which is JavaFX's way of saying "show every surface at
 * exactly the color it was painted". No direction, no falloff, no shadows. A box is lighter on
 * top because its top triangles were pointed at a lighter part of their color strip, not because
 * anything is shining on it. Materials also have their shine switched off, or the graphics card
 * would add a highlight nothing asked for.
 */
public final class JfxRenderer3D implements Renderer3D {

    /**
     * How far the vignette floats above the grid it darkens, in feet.
     *
     * <p>An eighth of an inch, and both bounds on it were measured by {@code RoomSharpnessProbe}.
     * Below it: laid exactly coplanar the two lose about 12% of the overlay to the depth buffer
     * across a 200 ft room, while 0.001 ft already wins cleanly, so this has a tenfold margin.
     * Above it: the vignette is a gradient spanning tens of feet, so shifting it an eighth of an
     * inch out of the plane it darkens cannot be seen.
     */
    private static final double LAYER_LIFT_FT = 0.01;

    /** How close to the camera something can be before it is clipped away, in feet. */
    private static final double NEAR_CLIP_FT = 0.05;

    /** How far away something can be before it is clipped away, in feet. */
    private static final double FAR_CLIP_FT = 2000;

    private final Group world = new Group();
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Translate position = new Translate();
    private final Rotate yaw = new Rotate(0, Rotate.Y_AXIS);
    private final Rotate pitch = new Rotate(0, Rotate.X_AXIS);

    private SubScene subScene;
    private AppState state;
    private double widthPx;
    private double heightPx;

    /**
     * The same boxes {@link #build} turned into meshes, kept so that picking never has to dig them
     * back out of the scene graph.
     *
     * <p>{@code Geometry3D.boxesFor} is already called once per build; holding on to what it
     * returned costs one list reference and means answering "what is under the pointer" touches no
     * JavaFX at all.
     */
    private List<Box3D> boxes = List.of();

    @Override
    public void init(double widthPx, double heightPx) {
        this.widthPx = Math.max(1, widthPx);
        this.heightPx = Math.max(1, heightPx);

        camera.setVerticalFieldOfView(true);
        camera.setFieldOfView(Perspective.verticalFovDegrees(this.heightPx));
        camera.setNearClip(NEAR_CLIP_FT);
        camera.setFarClip(FAR_CLIP_FT);
        // Position first, then turn: the rotations are about the camera's own axes, so a
        // translate placed after them would move it along a direction it has already turned.
        camera.getTransforms().setAll(position, yaw, pitch);

        world.getChildren().add(new AmbientLight(Color.WHITE));

        // The depth buffer (the `true`) is what makes a nearer box hide a further one. Without
        // it the room draws in whatever order the list happened to be in.
        //
        // ANTIALIASING IS OFF, and it is a decision rather than an omission, the user's, on
        // 2026-08-05, after they reported the transition as laggy on weaker hardware.
        //
        // Multisampling smooths the edges of *geometry*: the silhouette of a box against a wall.
        // It does nothing at all for the grid, which is painted into the wall textures and gets
        // the canvas's own smoothing whatever this says. Measured both ways in this container,
        // which has no GPU and so stands in reasonably for the machines in question: median frame
        // 16 ms down to 13, mean 13.3 down to 11.8, and **4,368 of 1,440,000 pixels looked any
        // different**, 0.3% of the screen. A sixth of every frame to smooth a third of a percent
        // of it.
        //
        // It also settles a divergence that was coming anyway: Android refuses SceneAntialiasing
        // outright (SPIKE-OD-1), so the phone was always going to render without it. Desktop and
        // mobile now agree.
        subScene = new SubScene(world, this.widthPx, this.heightPx, true,
                SceneAntialiasing.DISABLED);
        subScene.setCamera(camera);
        subScene.setFill(Tokens.CANVAS_WRAP_BG);
    }

    @Override
    public void build(AppState appState) {
        this.state = appState;
        // Keep the light; drop everything else. Rebuilding is how a changed room gets on screen.
        world.getChildren().removeIf(node -> !(node instanceof AmbientLight));

        GridTile tile = GridTile.forGrid(appState.metricMode);
        Image gridTile = SurfaceTexture.gridTile(appState.metricMode);
        float[] center = RoomGeometry.centerOf(appState.room);
        for (RoomGeometry.Surface surface : RoomGeometry.surfacesOf(appState.room)) {
            for (MeshView view : surfaceQuads(surface, tile, gridTile, center)) {
                // Without this, a ray cast to find out what you are pointing at hits the floor
                // before it ever reaches a box, and nothing in the room is ever pickable.
                view.setMouseTransparent(true);
                world.getChildren().add(view);
            }
        }

        boxes = Geometry3D.boxesFor(appState);

        // ONE picture and ONE material for every box in the room. See FaceStrip.atlas for the
        // measurements: a picture per box cost a machine with no graphics card 780 ms on the frame
        // the room first appeared, and that charge is per picture rather than per pixel.
        //
        // Insertion order is the contract. LinkedHashMap because the row a color lands in is the
        // row its boxes will read, and a HashMap would hand out rows in an order nothing controls.
        Map<String, Integer> rowOfColor = new LinkedHashMap<>();
        for (Box3D box : boxes) {
            rowOfColor.computeIfAbsent(box.color, color -> rowOfColor.size());
        }
        List<String> colors = new ArrayList<>(rowOfColor.keySet());
        PhongMaterial shared = flatMaterial(FaceStrip.atlas(colors));
        for (Box3D box : boxes) {
            world.getChildren().add(
                    boxView(box, shared, rowOfColor.get(box.color), Math.max(1, colors.size())));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Three lines, because all the work is in {@link Picking} where it can be tested without a
     * window. This method's only job is knowing how big the drawing surface is.
     */
    @Override
    public String pickItem(double xPx, double yPx, CameraPose camera) {
        return Picking.nearestHit(boxes, camera,
                Picking.rayThrough(xPx, yPx, widthPx, heightPx, camera));
    }

    /**
     * Puts the camera where the pose says, in JavaFX's axes.
     *
     * <p><b>Both angles pass through untouched, and no half turn is applied.</b> That is worth
     * writing down, because the obvious reading of every line here is wrong in an interesting way
     * and the M5.1 build got two of the three questions right by accident.
     *
     * <p>An untransformed JavaFX camera looks along +z, with up at −y and right at +x. The
     * geometry arrives having had its y and z negated, so north (−z in the specification) is +z
     * here, up (+y) is −y here, and east (+x) is unchanged. Those are the camera's forward, up and
     * right exactly, so zero rotation already faces north the right way up with east on the
     * right, and there is nothing to correct.
     *
     * <p>M5.1 negated y and <em>not</em> z, which faced the camera at the south wall, and turned
     * it half a turn to compensate. That fixed which wall it looked at and left the room reflected
     * east-to-west: the half turn is a rotation and the missing negation was a mirror, so the one
     * could never undo the other. Whether it looks at the right wall and whether the room is the
     * right way round are two separate questions, and only the first is visible in an empty room.
     *
     * <p>Yaw's sign then comes out right on its own: a positive JavaFX rotation about y takes +z
     * toward +x, which is north toward east, a clockwise turn seen from above, which is what a
     * positive yaw means. And pitch passes through because a positive rotation about x takes +z
     * toward −y, and −y is up.
     */
    @Override
    public void render(CameraPose pose) {
        position.setX(pose.x);
        position.setY(-pose.y);
        position.setZ(-pose.z);
        yaw.setAngle(Math.toDegrees(pose.yaw));
        pitch.setAngle(Math.toDegrees(pose.pitch));
    }

    @Override
    public void resize(double widthPx, double heightPx) {
        this.widthPx = Math.max(1, widthPx);
        this.heightPx = Math.max(1, heightPx);
        subScene.setWidth(this.widthPx);
        subScene.setHeight(this.heightPx);
        // The field of view follows the window height, because the original's did: it came from
        // a perspective distance measured as a fraction of that height.
        camera.setFieldOfView(Perspective.verticalFovDegrees(this.heightPx));
    }

    @Override
    public void dispose() {
        world.getChildren().clear();
        state = null;
        // Letting go of the boxes, not guarding the answer. `View3D.pickItem` already refuses to
        // ask anything once the view is closed, so this line cannot change a pick: the mutation
        // sweep says so, and it survives deleting this on purpose. What it does buy is memory: at
        // the save format's cap that is five hundred objects kept alive for as long as the
        // renderer is, for a room nobody is looking at any more.
        boxes = List.of();

        // Hand the old drawing surface a fresh, empty root before letting go of it.
        //
        // <b>Without this, the 3D view opens exactly once per run of the app.</b> A JavaFX node
        // can have one parent and one only, and being a SubScene's root counts as having one. The
        // old SubScene goes out of scope here, but nothing has told it to release `world`, so the
        // next init() builds a new SubScene around a Group that is still spoken for and JavaFX
        // throws "already set as root of another scene or subScene", from inside a button
        // handler, where an exception is printed to the console and otherwise swallowed. The
        // button simply stops working, with no visible reason.
        //
        // Reported by the user against the M5.1 jar: press 3D, come back, press 3D again, nothing.
        //
        // The camera is owned exactly as jealously as the root and has to be handed back too,
        // found by fixing only the root and getting the identical failure one line further on,
        // this time "PerspectiveCamera is already set as camera in other scene or subscene".
        if (subScene != null) {
            subScene.setCamera(null);
            subScene.setRoot(new Group());
            subScene = null;
        }
    }

    /** The node to put on screen. Deliberately not on {@link Renderer3D}; see the note there. */
    public SubScene node() {
        return subScene;
    }

    /**
     * One box's mesh, reading its own row of the room's shared color atlas.
     *
     * @param shared the one material every box in this room uses
     * @param row which row of the atlas holds this box's five shades
     * @param rowCount how many rows the atlas has, so the row's middle can be worked out
     */
    private MeshView boxView(Box3D box, PhongMaterial shared, int row, int rowCount) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().setAll(BoxGeometry.points(box));
        mesh.getTexCoords().setAll(BoxGeometry.texCoords(row, rowCount));
        mesh.getFaces().setAll(BoxGeometry.faces(box));

        MeshView view = new MeshView(mesh);
        view.setMaterial(shared);
        // Culling stays ON. The OD-1 spike switched it off to sidestep the winding problem, which
        // hides the mistake and makes the card draw every surface twice.
        view.setCullFace(CullFace.BACK);
        view.setId(box.itemId);
        return view;
    }

    /**
     * The two quads one wall or floor is made of: the grid, and the darkening over it.
     *
     * <p>They are returned in the order they must be added in, and that order is load bearing. The
     * vignette is nearer the camera, so if it went into the scene first it would write its depth
     * and the grid would fail the depth test behind it: the room would come out as five gray
     * gradients with no grid at all.
     *
     * <p>The grid quad asks for the tile {@code span} times over rather than once, which is the
     * whole of the sharpness fix; see {@link GridTile}. The vignette quad asks for its picture
     * exactly once, stretched over the surface, which is what it has always done.
     */
    private List<MeshView> surfaceQuads(RoomGeometry.Surface surface, GridTile tile,
            Image gridTile, float[] center) {
        double spanX = tile.span(surface.widthFt);
        double spanY = tile.span(surface.heightFt);
        double originX = tile.originX(surface.widthFt, surface.mirroredGridX);

        MeshView grid = quad(surface.corners, gridTile, originX, 0, spanX, spanY);
        grid.setId(surface.name);

        MeshView vignette = quad(
                RoomGeometry.liftedTowards(surface.corners, center, LAYER_LIFT_FT),
                SurfaceTexture.vignette(surface.widthFt, surface.heightFt), 0, 0, 1, 1);
        vignette.setId(surface.name + "-vignette");

        return List.of(grid, vignette);
    }

    private MeshView quad(float[] corners, Image texture, double originX, double originY,
            double spanX, double spanY) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().setAll(corners);
        mesh.getTexCoords().setAll(
                (float) originX, (float) originY,
                (float) (originX + spanX), (float) originY,
                (float) (originX + spanX), (float) (originY + spanY),
                (float) originX, (float) (originY + spanY));
        mesh.getFaces().setAll(0, 0, 1, 1, 2, 2, 0, 0, 2, 2, 3, 3);

        MeshView view = new MeshView(mesh);
        view.setMaterial(flatMaterial(texture));
        view.setCullFace(CullFace.BACK);
        return view;
    }

    /**
     * A material that shows a texture exactly as painted, with no shine.
     *
     * <p><b>Nothing switches the shine off, because it is already off.</b> The obvious line here is
     * {@code setSpecularColor(null)}, and it was here, until the mutation sweep pointed out that
     * breaking it changed nothing. Checked directly rather than argued about: a fresh
     * {@code PhongMaterial} reports a specular color of {@code null} to begin with, so the call
     * was setting a property to the value it already had. That is dead code, so it is gone, and
     * {@code Room3dAppearanceTest} now pins the property itself, which is the thing actually
     * depended on, however it comes to be true.
     */
    private PhongMaterial flatMaterial(Image texture) {
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseMap(texture);
        return material;
    }
}
