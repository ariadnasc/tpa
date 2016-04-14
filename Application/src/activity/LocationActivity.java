package activity;

import rendering.*;
import resources.ResourceManager;
import resources.SimpleResourceManager;
import tpa.application.Context;
import tpa.application.Window;
import tpa.graphics.geometry.Attribute;
import tpa.graphics.geometry.Mesh;
import tpa.graphics.geometry.MeshUsage;
import tpa.graphics.geometry.Primitive;
import tpa.graphics.render.*;
import tpa.graphics.shader.ShaderProgram;
import tpa.graphics.shader.UniformType;
import tpa.graphics.texture.*;
import tpa.joml.Matrix4f;
import tpa.joml.Vector2f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/**
 * Created by germangb on 13/04/16.
 */
public abstract class LocationActivity extends Activity {

    /** low res scale */
    private static int SCALE = 1;

    /** resource manager for the location, to load textures, meshes, sound, etc */
    protected ResourceManager resources = new SimpleResourceManager();

    /** Camera of the location */
    protected Camera camera = new Camera();

    /** Geometry visible on the scene */
    private Set<GeometryActor> geometry = new HashSet<>();

    /** Decals visible on the screen */
    private Set<DecalActor> decals = new HashSet<>();

    /** Framebuffer for early Z pass */
    private Framebuffer zPass;

    /** depth texture from early z pass */
    protected Texture depth;

    /** framebuffer half as small as the window */
    private Framebuffer lowresPass;

    /** dithering texture */
    private Texture dither;

    /** box for decals */
    private Mesh box;

    /** quad for rendering framebuffers */
    private Mesh quad;

    /** material for rendering framebuffer */
    private CompositeMaterial composite;

    /** load location specific resources here */
    public abstract void onLoad (Context context);

    /** Called when resources have ben loaded */
    public abstract void onFinishLoad (Context context);

    /** Called when the scene is entered */
    public abstract void onEntered (Context context);

    /** called once per frame */
    public abstract void onTick (Context context);

    /** Called when the scene is left */
    public abstract void onLeft (Context context);

    @Override
    public void onInit(Context context) {
        // create needed resources
        Window win = context.window;
        zPass = new Framebuffer(win.getWidth()/SCALE, win.getHeight()/SCALE, new TextureFormat[]{}, true);
        depth = zPass.getDepth();
        lowresPass = new Framebuffer(win.getWidth()/SCALE, win.getHeight()/SCALE, new TextureFormat[]{TextureFormat.Rgb}, true);
        lowresPass.getTargets()[0].setMag(TextureFilter.Nearest);
        resources.load("res/dither.png", Texture.class);
        resources.load("res/box.json", Mesh.class);
        resources.load("res/quad.json", Mesh.class);
        resources.finishLoading();
        box = resources.get("res/box.json", Mesh.class);
        quad = resources.get("res/quad.json", Mesh.class);
        dither = resources.get("res/dither.png", Texture.class);
        dither.setMag(TextureFilter.Nearest);
        dither.setWrapU(TextureWrap.Repeat);
        dither.setWrapV(TextureWrap.Repeat);

        composite = new CompositeMaterial(lowresPass.getTargets()[0], dither, new Vector2f(context.window.getWidth(), context.window.getHeight()));

        onLoad(context);
        resources.finishLoading();
        onFinishLoad(context);
    }

    /**
     * Add a geometry actor to the scene
     * @param actor geometry actor
     */
    public void addGeometry (GeometryActor actor) {
        this.geometry.add(actor);
    }

    /**
     * Add a decal to the scene
     * @param actor decal actor
     */
    public void addDecal (DecalActor actor) {
        this.decals.add(actor);
    }

    @Override
    public void onBegin(Context context) {
        onEntered(context);
    }

    @Override
    public void onEnd(Context context) {
        onLeft(context);
        geometry.clear();
        decals.clear();
    }

    @Override
    public void onUpdate(Context context) {
        onTick(context);

        // update camera
        camera.update();

        Window window = context.window;
        Renderer renderer = context.renderer;

        // perfom z pass
        renderer.setFramebuffer(zPass);
        renderer.clearDepthBuffer();
        renderer.setViewport(0, 0, zPass.getWidth(), zPass.getHeight());

        // set state for z pass
        RendererState stateZpass = new RendererState();
        stateZpass.depthTest = true;
        stateZpass.redMask = stateZpass.greenMask = stateZpass.blueMask = stateZpass.alphaMask = false;
        renderer.setState(stateZpass);

        for (GeometryActor actor : geometry) {
            Material mat = actor.getMaterial();
            mat.render(renderer, camera, actor.getMesh(), actor.model);
        }

        // lowres pass
        renderer.setFramebuffer(lowresPass);
        renderer.setViewport(0, 0, lowresPass.getWidth(), lowresPass.getHeight());
        renderer.setClearColor(camera.clearColor.x, camera.clearColor.y, camera.clearColor.z, 1);
        renderer.clearBuffers();

        // set low res state
        RendererState stateLow = new RendererState();
        stateLow.depthTest = true;
        renderer.setState(stateLow);

        // render geometry
        for (GeometryActor actor : geometry) {
            Material mat = actor.getMaterial();
            mat.render(renderer, camera, actor.getMesh(), actor.model);
        }

        // render decaks
        for (DecalActor decal : decals) {
            Material mat = decal.getMaterial();
            mat.render(context.renderer, camera, box, decal.model);
        }

        // window pass
        renderer.setFramebuffer(null);
        renderer.setViewport(0, 0, window.getWidth(), window.getHeight());
        composite.render(context.renderer, null, quad, null);
    }
}
