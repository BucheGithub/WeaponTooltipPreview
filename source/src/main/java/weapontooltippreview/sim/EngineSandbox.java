package weapontooltippreview.sim;

import com.fs.graphics.anim.AnimationManager;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.CombatViewport;
import com.fs.starfarer.title.TitleScreenState;
import com.fs.state.AppDriver;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.dark.shaders.util.ShaderHook;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.util.vector.Vector2f;
import weapontooltippreview.Log;
import weapontooltippreview.bridge.Refl;
import weapontooltippreview.bridge.StarfarerBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A private CombatEngine living next to the campaign's idle one. The engine is a JVM-wide singleton, so every
 * interaction happens between swapIn and swapOut, which exchange the static instance.
 * One sandbox serves many previews: clear the previous scene and hand out a different origin,
 * so particles and explosions still fading out from the last preview are off camera.
 */
public final class EngineSandbox {

    private static final String[] ANIM_GROUPS = {"CEanim", "CENonPauseanim"};
    /**
     * Combat plugins set up for a real battle, screen-wide: BoxUtil installs its shader passes (blur, bloom) and cleans
     * up its campaign rendering, UIUtil clears its global UI state and takes over as the combat UI.
     */
    private static final String[] STRIPPED_PLUGIN_PREFIXES = {"org.boxutil.", "org.luoxb.uiutil."};
    /**
     * The session entry CombatEngine.isSimulation() looks for. Held while swapped in: a preview runs inside the live
     * campaign, and mods keep their rewards, kill counts and battle-end handling out of a simulation.
     */
    private static final String SIMULATOR_SESSION_KEY = "run combat simulator";
    /**
     * The game's shield textures. It loads them without mipmaps, and shrunk to a preview's zoom they pixelate, so
     * render() gives them mipmaps while the engine draws.
     */
    private static final String[] SHIELD_TEXTURES = {"graphics/fx/shields256.png", "graphics/fx/shields256ringd.png",
            "graphics/fx/shields128c.png", "graphics/fx/shields128ringc.png", "graphics/fx/shields64.png",
            "graphics/fx/shields64ring.png"};
    private static final float MAP_HALF_SIZE = 15000f;
    /** Scenes extend toward +x from their origin (weapon host at the origin, targets and waves at weapon range). */
    private static final float SCENE_ORIGIN_X = -5000f;
    private static final float[] SCENE_ORIGIN_Y = {0f, 5000f, -5000f, 10000f, -10000f};
    private static boolean loggedInheritedState;

    private CombatEngine engine;
    private final Object input = Refl.newInstance(StarfarerBridge.inputClass);
    private final AnimationManager[] ownAnims = new AnimationManager[ANIM_GROUPS.length];
    private final List<String> strippedPlugins = new ArrayList<>();
    private int scenes;
    /** Texture holding the screen while render() draws over it; 0 until the first render. */
    private int screenCopy;
    /** Texture holding the engine's render while the screen is put back; 0 until the first render. */
    private int sceneCopy;

    private boolean swapped;
    private Object savedInstance;
    private CombatEngineAPI savedGlobal;
    private final AnimationManager[] savedAnims = new AnimationManager[ANIM_GROUPS.length];
    private Object savedSimulatorEntry;

    private EngineSandbox() {
    }

    /** Builds and initialises a private engine; the campaign's engine is back in place when this returns. */
    public static EngineSandbox create() {
        EngineSandbox sandbox = new EngineSandbox();
        for (int i = 0; i < ANIM_GROUPS.length; i++) sandbox.ownAnims[i] = new AnimationManager();
        sandbox.swapIn();
        boolean ok = false;
        try {
            sandbox.engine = CombatEngine.getInstance();
            // Ship.applyDamageInner needs a non-null combat UI. The title screen's state is the one its background battle
            // uses, with stub callbacks; the app keeps every state registered for good.
            StarfarerBridge.engineSetCombatUI.invoke(sandbox.engine,
                    AppDriver.getInstance().getState(TitleScreenState.STATE_ID));
            Object map = Refl.newInstance(StarfarerBridge.mapClass);
            StarfarerBridge.mapSetBounds.invoke(map, -MAP_HALF_SIZE, MAP_HALF_SIZE, -MAP_HALF_SIZE, MAP_HALF_SIZE);
            StarfarerBridge.engineSetCombatMap.invoke(sandbox.engine, map);
            sandbox.stripPlugins(map);
            // GraphicsLib would otherwise unload every texture the battle's fleets do not use on the first step; a
            // preview's ships load their maps on first use instead. The key is a compile-time constant, so this line
            // runs without GraphicsLib.
            sandbox.engine.getCustomData().put(ShaderHook.TEXTURE_PRELOAD_KEY, true);
            sandbox.engine.createDummyFleetsIfNeeded();
            sandbox.engine.init();
            sandbox.engine.setPaused(false);
            sandbox.engine.preCombat();
            sandbox.engine.setDoNotEndCombat(true);
            // A deployed flagship becomes the player's ship until the player has controlled one; a weapon host copied
            // from the refit flagship counts as the flagship, as it carries the refit fleet.
            sandbox.engine.setPlayerHasControlledShip(true);
            ok = true;
        } finally {
            try {
                if (!ok) sandbox.destroyWhileSwapped();
            } finally {
                sandbox.swapOut();
            }
        }
        return sandbox;
    }

    public CombatEngineAPI api() {
        return engine;
    }

    public List<String> strippedPlugins() {
        return strippedPlugins;
    }

    public int scenes() {
        return scenes;
    }

    public void swapIn() {
        if (swapped) throw new IllegalStateException("sandbox already swapped in");
        savedInstance = StarfarerBridge.engineInstance.get(null);
        savedGlobal = Global.getCombatEngine();
        Map<String, AnimationManager> managers = animManagers();
        for (int i = 0; i < ANIM_GROUPS.length; i++) {
            savedAnims[i] = managers.get(ANIM_GROUPS[i]);
            managers.put(ANIM_GROUPS[i], ownAnims[i]);
        }
        StarfarerBridge.engineInstance.set(null, engine);
        if (engine != null) Global.setCombatEngine(engine);
        Map<Object, Object> session = session();
        savedSimulatorEntry = session.get(SIMULATOR_SESSION_KEY);
        session.put(SIMULATOR_SESSION_KEY, Boolean.TRUE);
        swapped = true;
    }

    public void swapOut() {
        if (!swapped) return;
        StarfarerBridge.engineInstance.set(null, savedInstance);
        Global.setCombatEngine(savedGlobal);
        Map<String, AnimationManager> managers = animManagers();
        for (int i = 0; i < ANIM_GROUPS.length; i++) {
            if (savedAnims[i] != null) managers.put(ANIM_GROUPS[i], savedAnims[i]);
            else managers.remove(ANIM_GROUPS[i]);
            savedAnims[i] = null;
        }
        Map<Object, Object> session = session();
        if (savedSimulatorEntry != null) session.put(SIMULATOR_SESSION_KEY, savedSimulatorEntry);
        else session.remove(SIMULATOR_SESSION_KEY);
        savedSimulatorEntry = null;
        savedInstance = null;
        savedGlobal = null;
        swapped = false;
    }

    /** Clears the previous scene and returns the origin for the next one. Call while swapped in. */
    public Vector2f beginScene() {
        requireSwapped();
        clearScene();
        float y = SCENE_ORIGIN_Y[scenes % SCENE_ORIGIN_Y.length];
        scenes++;
        return new Vector2f(SCENE_ORIGIN_X, y);
    }

    /**
     * Removes every ship, projectile, missile and beam. Removal is applied by the next engine step; nothing else needs
     * to happen in between since the next scene is built elsewhere on the map. Call while swapped in.
     */
    public void clearScene() {
        requireSwapped();
        CombatEngineAPI e = api();
        // Missiles are projectiles too; each entity is removed once.
        Set<CombatEntityAPI> doomed = Collections.newSetFromMap(new IdentityHashMap<CombatEntityAPI, Boolean>());
        doomed.addAll(e.getShips());
        doomed.addAll(e.getProjectiles());
        doomed.addAll(e.getMissiles());
        doomed.addAll(e.getAsteroids());
        for (CombatEntityAPI entity : doomed) e.removeEntity(entity);
        for (BeamAPI beam : new ArrayList<>(e.getBeams())) e.removeObject(beam);
    }

    /** One simulation step; call while swapped in. */
    public void advance(float dt) {
        requireSwapped();
        ownAnims[1].advanceAll(dt);
        ownAnims[0].advanceAll(dt);
        StarfarerBridge.engineAdvance.invoke(engine, dt, input);
    }

    /** A rectangle of the world shown in a rectangle of screen pixels. */
    public static class View {
        public int pixelX, pixelY, pixelWidth, pixelHeight;
        public float worldX, worldY, worldWidth, worldHeight;
    }

    /**
     * Whether render() can draw the views from one engine render: the world rectangle covering them all has to fit on
     * the screen at the first view's zoom.
     */
    public static boolean fitsOneRender(View[] views, int count) {
        float[] bounds = worldBounds(views, count);
        View first = views[0];
        return (bounds[2] - bounds[0]) * first.pixelWidth / first.worldWidth <= screenWidthPixels()
                && (bounds[3] - bounds[1]) * first.pixelHeight / first.worldHeight <= screenHeightPixels();
    }

    /** The world rectangle (left, bottom, right, top) covering every view. */
    private static float[] worldBounds(View[] views, int count) {
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < count; i++) {
            View view = views[i];
            bounds[0] = Math.min(bounds[0], view.worldX);
            bounds[1] = Math.min(bounds[1], view.worldY);
            bounds[2] = Math.max(bounds[2], view.worldX + view.worldWidth);
            bounds[3] = Math.max(bounds[3], view.worldY + view.worldHeight);
        }
        return bounds;
    }

    /**
     * Shows each view's rectangle of the world in its rectangle of screen pixels, then runs {@code worldOverlay} once per
     * view in that view's world projection, scissored to its rectangle. The views share the first one's zoom and must
     * pass {@link #fitsOneRender}. Mirrors the title screen's projection helper. Call while swapped in.
     * <p>
     * The engine fills the whole screen because GraphicsLib's shaders assume it does: lights go by screen position, ships
     * are drawn to screen-sized buffers, and bloom works on the whole screen in passes a scissor would cut. An offscreen
     * target would not help: GraphicsLib ends its passes by binding framebuffer 0 itself, then reads the scene from there
     * and draws its result there. So the screen is saved, the engine draws the world from the views' lower left corner
     * once, and the screen is put back with each view's piece of that render in its rectangle. None of it is displayed
     * before that.
     */
    public void render(View[] views, int count, float[] clearRgb, Runnable worldOverlay) {
        requireSwapped();

        // Neither the attribute stack nor the client stack covers the bound shader program.
        boolean shaders = GLContext.getCapabilities().OpenGL20;
        int previousProgram = shaders ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            resetFixedFunctionState();
            int screenWidth = screenWidthPixels(), screenHeight = screenHeightPixels();
            float[] bounds = worldBounds(views, count);
            float unitsPerPixelX = views[0].worldWidth / views[0].pixelWidth;
            float unitsPerPixelY = views[0].worldHeight / views[0].pixelHeight;
            screenCopy = copyScreenCorner(screenCopy, screenWidth, screenHeight);
            aimCamera(0, 0, screenWidth, screenHeight, bounds[0], bounds[1], screenWidth * unitsPerPixelX,
                    screenHeight * unitsPerPixelY);
            GL11.glClearColor(clearRgb[0], clearRgb[1], clearRgb[2], 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            // GraphicsLib clears its buffers with whatever colour is set, and takes anything left in them for ripples
            // and ship surfaces.
            GL11.glClearColor(0f, 0f, 0f, 0f);
            setShieldMipmaps(true);
            try {
                engine.render(false);
            } finally {
                setShieldMipmaps(false);
                restoreScreen(views, count, bounds[0], bounds[1], unitsPerPixelX, unitsPerPixelY);
            }
            if (worldOverlay != null) {
                for (int i = 0; i < count; i++) {
                    View view = views[i];
                    aimCamera(view.pixelX, view.pixelY, view.pixelWidth, view.pixelHeight, view.worldX, view.worldY,
                            view.worldWidth, view.worldHeight);
                    GL11.glEnable(GL11.GL_SCISSOR_TEST);
                    GL11.glScissor(view.pixelX, view.pixelY, view.pixelWidth, view.pixelHeight);
                    worldOverlay.run();
                }
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
            if (shaders) GL20.glUseProgram(previousProgram);
        }
    }

    /** Points the viewport and the projection at the world rectangle, drawn into that rectangle of pixels. */
    private void aimCamera(int pixelX, int pixelY, int pixelWidth, int pixelHeight, float worldX, float worldY,
                           float worldWidth, float worldHeight) {
        CombatViewport viewport = engine.getViewport();
        // set() derives viewMult from the screen width; pick ratios so viewMult = world units per target pixel.
        viewport.setWorldXtoScreenX(Global.getSettings().getScreenWidth() / pixelWidth);
        viewport.setWorldYtoScreenY(Global.getSettings().getScreenHeight() / pixelHeight);
        viewport.set(worldX, worldY, worldWidth, worldHeight);
        GL11.glViewport(pixelX, pixelY, pixelWidth, pixelHeight);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(worldX, worldX + worldWidth, worldY, worldY + worldHeight, -4000, 4000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
    }

    private static int screenWidthPixels() {
        return (int) (Global.getSettings().getScreenWidthPixels() * Display.getPixelScaleFactor());
    }

    private static int screenHeightPixels() {
        return (int) (Global.getSettings().getScreenHeightPixels() * Display.getPixelScaleFactor());
    }

    /**
     * Copies the lower left (width, height) of the screen into a screen-sized texture, made here when {@code texture}
     * is 0. Returns the texture.
     */
    private static int copyScreenCorner(int texture, int width, int height) {
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (texture != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        } else {
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 0, 0, screenWidthPixels(), screenHeightPixels(), 0);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        return texture;
    }

    /**
     * Turns mipmapped filtering of the shield textures on or off. The mipmaps are rebuilt each time it is turned on, so
     * a texture the game reloaded since is never drawn from stale or missing levels.
     */
    private static void setShieldMipmaps(boolean on) {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (!caps.OpenGL30 && !caps.GL_ARB_framebuffer_object && !caps.GL_EXT_framebuffer_object) return;
        for (String path : SHIELD_TEXTURES) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, Global.getSettings().getSprite(path).getTextureId());
            if (on) {
                if (caps.OpenGL30) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                else if (caps.GL_ARB_framebuffer_object) ARBFramebufferObject.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                else EXTFramebufferObject.glGenerateMipmapEXT(GL11.GL_TEXTURE_2D);
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                    on ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
        }
    }

    /**
     * Puts {@link #screenCopy} back, then draws each view's piece of the engine's render (whose lower left corner shows
     * the world point (left, bottom)) in the view's rectangle. Also runs when the engine threw, which may have been
     * inside one of GraphicsLib's framebuffers, where the game would go on to draw everything.
     */
    private void restoreScreen(View[] views, int count, float left, float bottom, float unitsPerPixelX,
                               float unitsPerPixelY) {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (caps.OpenGL30) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        } else if (caps.GL_ARB_framebuffer_object) {
            ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, 0);
        } else if (caps.GL_EXT_framebuffer_object) {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 0);
        }
        resetFixedFunctionState();
        int screenWidth = screenWidthPixels(), screenHeight = screenHeightPixels();
        sceneCopy = copyScreenCorner(sceneCopy, screenWidth, screenHeight);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glViewport(0, 0, screenWidth, screenHeight);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, screenWidth, 0, screenHeight, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        drawScreenTexture(screenCopy, 0, 0, screenWidth, screenHeight, 0, 0);
        for (int i = 0; i < count; i++) {
            View view = views[i];
            // Rounded to whole pixels, so the piece is copied as is rather than blended across a pixel boundary.
            drawScreenTexture(sceneCopy, view.pixelX, view.pixelY, view.pixelWidth, view.pixelHeight,
                    Math.round((view.worldX - left) / unitsPerPixelX), Math.round((view.worldY - bottom) / unitsPerPixelY));
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        GL11.glEnable(GL11.GL_BLEND);
    }

    /** Draws the (sourceX, sourceY, width, height) pixels of a screen-sized texture at (x, y), one to one. */
    private static void drawScreenTexture(int texture, int x, int y, int width, int height, int sourceX, int sourceY) {
        float screenWidth = screenWidthPixels(), screenHeight = screenHeightPixels();
        float minU = sourceX / screenWidth, maxU = (sourceX + width) / screenWidth;
        float minV = sourceY / screenHeight, maxV = (sourceY + height) / screenHeight;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(minU, minV);
        GL11.glVertex2i(x, y);
        GL11.glTexCoord2f(maxU, minV);
        GL11.glVertex2i(x + width, y);
        GL11.glTexCoord2f(maxU, maxV);
        GL11.glVertex2i(x + width, y + height);
        GL11.glTexCoord2f(minU, maxV);
        GL11.glVertex2i(x, y + height);
        GL11.glEnd();
    }

    /**
     * The engine's renderers assume the game's baseline fixed-function state (no shader bound, texture unit 0,
     * MODULATE, standard alpha blending, no stray buffers). Whatever the campaign UI left behind is overridden here;
     * the caller's attribute pushes and program restore put it back afterwards.
     */
    private static void resetFixedFunctionState() {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (!loggedInheritedState) {
            loggedInheritedState = true;
            Log.info("inherited GL state: program=%d activeTexture=0x%x texEnv=0x%x blend=%s alphaTest=%s depth=%s arrayBuffer=%d",
                    caps.OpenGL20 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : -1,
                    caps.OpenGL13 ? GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) : -1,
                    GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE),
                    GL11.glIsEnabled(GL11.GL_BLEND), GL11.glIsEnabled(GL11.GL_ALPHA_TEST), GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                    caps.OpenGL15 ? GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) : -1);
        }
        if (caps.OpenGL20) GL20.glUseProgram(0);
        if (caps.OpenGL13) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        }
        if (caps.OpenGL15) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
        if (caps.OpenGL14) GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (int cap : new int[]{GL11.GL_DEPTH_TEST, GL11.GL_ALPHA_TEST, GL11.GL_LIGHTING, GL11.GL_CULL_FACE,
                GL11.GL_STENCIL_TEST, GL11.GL_FOG, GL11.GL_COLOR_LOGIC_OP, GL11.GL_SCISSOR_TEST}) {
            GL11.glDisable(cap);
        }
        GL11.glColorMask(true, true, true, true);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Tears the private engine down; safe to call whether or not it is swapped in. */
    public void destroy() {
        if (engine == null) return;
        if (screenCopy != 0) {
            GL11.glDeleteTextures(screenCopy);
            screenCopy = 0;
        }
        if (sceneCopy != 0) {
            GL11.glDeleteTextures(sceneCopy);
            sceneCopy = 0;
        }
        boolean wasSwapped = swapped;
        if (!wasSwapped) swapIn();
        try {
            destroyWhileSwapped();
        } finally {
            swapOut();
        }
    }

    private void destroyWhileSwapped() {
        try {
            if (engine != null) CombatEngine.destroyInstance();
        } finally {
            engine = null;
        }
    }

    private void requireSwapped() {
        if (!swapped || engine == null) throw new IllegalStateException("sandbox not swapped in");
    }

    private void stripPlugins(Object map) {
        for (EveryFrameCombatPlugin p : pluginsInMap(map)) {
            String name = p.getClass().getName();
            if (hasPrefix(name, STRIPPED_PLUGIN_PREFIXES)) {
                api().removePlugin(p);
                strippedPlugins.add(name);
            }
        }
    }

    private static boolean hasPrefix(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Every-frame plugins live in the combat map as wrappers around the API plugin. */
    private static List<EveryFrameCombatPlugin> pluginsInMap(Object map) {
        List<EveryFrameCombatPlugin> out = new ArrayList<>();
        for (Refl.ReflectedField field : Refl.fields(map.getClass())) {
            if (field.isStatic || !List.class.isAssignableFrom(field.type)) continue;
            Object list = field.get(map);
            if (!(list instanceof List)) continue;
            for (Object wrapper : (List<?>) list) {
                if (wrapper == null) continue;
                Refl.ReflectedField f = Refl.fieldOfType(wrapper.getClass(), EveryFrameCombatPlugin.class, false);
                Object plugin = f != null ? f.get(wrapper) : null;
                if (plugin instanceof EveryFrameCombatPlugin) out.add((EveryFrameCombatPlugin) plugin);
            }
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AnimationManager> animManagers() {
        return (Map<String, AnimationManager>) StarfarerBridge.animManagers.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> session() {
        return AppDriver.getInstance().getSession();
    }
}
