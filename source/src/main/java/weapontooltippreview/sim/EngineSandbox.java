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
import org.dark.shaders.util.ShaderHook;
import org.dark.shaders.util.ShaderLib;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.util.vector.Vector2f;
import weapontooltippreview.Log;
import weapontooltippreview.bridge.Refl;
import weapontooltippreview.bridge.StarfarerBridge;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A private CombatEngine living next to the campaign's idle one. 
 * The engine is a JVM-wide singleton, so every interaction happens between swapIn and swapOut, which exchange the static instance with this one.
 * One sandbox serves all previews.
 */
public final class EngineSandbox {

    /** The engine's animation groups, in the order the combat state advances them */
    private static final String[] ANIMATION_GROUPS = {CombatEngine.ANIMATION_GROUP_NONPAUSABLE,
            CombatEngine.ANIMATION_GROUP};
    /** Plugins that take over the whole screen, the combat UI, or campaign post-battle cleanup */
    private static final String[] STRIPPED_PLUGIN_PREFIXES = {
            "org.boxutil.",
            "org.luoxb.uiutil.",
            "data.scripts.combat.hydrofoil.",
    };
    private static final String GRAPHICSLIB_MOD_ID = "shaderLib";
    private static final boolean GRAPHICSLIB_ENABLED =
            Global.getSettings().getModManager().isModEnabled(GRAPHICSLIB_MOD_ID);
    /** The session entry CombatEngine.isSimulation() looks for.
     */
    private static final String SIMULATOR_SESSION_KEY = "run combat simulator";
    /**
     * The game's shield textures. render() gives them mipmaps while the engine draws.
     */
    private static final String[] SHIELD_TEXTURES = {"graphics/fx/shields256.png", "graphics/fx/shields256ringd.png",
            "graphics/fx/shields128c.png", "graphics/fx/shields128ringc.png", "graphics/fx/shields64.png",
            "graphics/fx/shields64ring.png"};
    /** Cached texture IDs of {@link #SHIELD_TEXTURES} once their mipmaps have been generated. */
    private static final int[] SHIELD_TEXTURE_IDS = new int[SHIELD_TEXTURES.length];
    /** The engine's custom data key, followed by the ship id; nothing in the engine removes them. */
    private static final String NO_AI_FLAGS_KEY_PREFIX = "nonAI_SAIF_";
    private static final float MAP_HALF_SIZE = 15000f;
    /** Scenes extend toward +x from their origin */
    private static final float SCENE_ORIGIN_X = -5000f;
    /** Several origins to cycle through on Y axis to let particles & vfx clear */
    private static final float[] SCENE_ORIGIN_Y = {0f, 5000f, -5000f, 10000f, -10000f};
    private static final int MAX_SCENES = 40;
    private static final long IDLE_NANOS = 60_000_000_000L;
    /** Consecutive engine creation failures before previews are switched off for the rest of the game session. */
    private static final int MAX_CREATE_FAILURES = 3;

    private static EngineSandbox shared;
    private static boolean inUse;
    private static long idleSince;
    private static int createFailures;
    private static boolean disabled;

    private CombatEngine engine;
    private final Object input = Refl.newInstance(StarfarerBridge.inputClass);
    private final AnimationManager[] ownAnimationManagers = new AnimationManager[ANIMATION_GROUPS.length];
    private int scenes;
    /** Texture holding the screen while render() draws over it; 0 until the first render. */
    private int screenCopy;
    /** Texture holding the engine's render while the screen is put back; 0 until the first render. */
    private int sceneCopy;
    /** Dimensions of the previous GraphicsLib render region, so shrinking views clear any leftover FBO pixels once. */
    private int lastRenderWidth;
    private int lastRenderHeight;

    private boolean swapped;
    private Object savedInstance;
    private CombatEngineAPI savedGlobal;
    private final AnimationManager[] savedAnimationManagers = new AnimationManager[ANIMATION_GROUPS.length];
    private Object savedSimulatorEntry;

    private EngineSandbox() {
    }

    public static boolean isDisabled() {
        return disabled;
    }

    /** The shared sandbox for a new preview, built or recycled as needed. Throws if no engine can be built. */
    public static EngineSandbox acquire() {
        if (disabled) throw new IllegalStateException("preview engine disabled");
        if (shared != null && shared.scenes >= MAX_SCENES) discard("recycled after " + shared.scenes + " scenes");
        if (shared == null) {
            long t0 = System.nanoTime();
            try {
                shared = create();
                createFailures = 0;
            } catch (RuntimeException e) {
                if (++createFailures >= MAX_CREATE_FAILURES) {
                    disabled = true;
                    Log.error("preview engine failed to start " + createFailures
                            + " times in a row; previews disabled until the next game load");
                }
                throw e;
            }
            Log.info("sandbox created in %.1f ms; %s", (System.nanoTime() - t0) / 1e6, Log.heap());
        }
        inUse = true;
        return shared;
    }

    /** The preview is done and the engine waits, idle, for the next one. */
    public static void release() {
        if (shared == null || !inUse) return;
        inUse = false;
        idleSince = System.nanoTime();
    }

    /** Destroys the shared engine (failure, idle, game load). The next preview builds a fresh one. */
    public static void discard(String why) {
        if (shared == null) return;
        EngineSandbox s = shared;
        shared = null;
        inUse = false;
        try {
            s.destroy();
        } catch (Throwable t) {
            Log.error("sandbox destroy failed", t);
        }
        Log.info("sandbox discarded (%s) after %d scenes; %s", why, s.scenes, Log.heap());
    }

    /** Called every campaign frame: releases an engine nobody has used for a while. */
    public static void tick() {
        if (shared != null && !inUse && System.nanoTime() - idleSince > IDLE_NANOS) discard("idle");
    }

    /** New game session: drop the engine and give previews another chance if they had been switched off. */
    public static void reset() {
        discard("game load");
        disabled = false;
        createFailures = 0;
    }

    /** Builds and initialises a private engine */
    private static EngineSandbox create() {
        EngineSandbox sandbox = new EngineSandbox();
        for (int i = 0; i < ANIMATION_GROUPS.length; i++) sandbox.ownAnimationManagers[i] = new AnimationManager();
        sandbox.swapIn();
        try {
            sandbox.engine = CombatEngine.getInstance();
            // Damage needs a combat UI; the title screen's background battle uses this one.
            StarfarerBridge.engineSetCombatUI.invoke(sandbox.engine,
                    AppDriver.getInstance().getState(TitleScreenState.STATE_ID));
            Object map = Refl.newInstance(StarfarerBridge.mapClass);
            StarfarerBridge.mapSetBounds.invoke(map, -MAP_HALF_SIZE, MAP_HALF_SIZE, -MAP_HALF_SIZE, MAP_HALF_SIZE);
            StarfarerBridge.engineSetCombatMap.invoke(sandbox.engine, map);
            sandbox.stripPlugins();
            // Compile-time constant: safe without GraphicsLib. Stops it unloading textures this battle's fleets don't use.
            sandbox.engine.getCustomData().put(ShaderHook.TEXTURE_PRELOAD_KEY, true);
            sandbox.engine.createDummyFleetsIfNeeded();
            sandbox.engine.init();
            sandbox.engine.setPaused(false);
            sandbox.engine.preCombat();
            sandbox.engine.setDoNotEndCombat(true);
            // Otherwise a deployed flagship (the refit ship) becomes the player ship.
            sandbox.engine.setPlayerHasControlledShip(true);
        } catch (RuntimeException | Error e) {
            sandbox.destroyWhileSwapped();
            throw e;
        } finally {
            sandbox.swapOut();
        }
        return sandbox;
    }

    public CombatEngineAPI api() {
        return engine;
    }

    public int scenes() {
        return scenes;
    }

    public void swapIn() {
        if (swapped) throw new IllegalStateException("sandbox already swapped in");
        savedInstance = StarfarerBridge.engineInstance.get(null);
        savedGlobal = Global.getCombatEngine();
        Map<String, AnimationManager> managers = animManagers();
        for (int i = 0; i < ANIMATION_GROUPS.length; i++) {
            savedAnimationManagers[i] = managers.get(ANIMATION_GROUPS[i]);
            managers.put(ANIMATION_GROUPS[i], ownAnimationManagers[i]);
        }
        StarfarerBridge.engineInstance.set(null, engine);
        if (engine != null) Global.setCombatEngine(engine);
        Map<Object, Object> session = session();
        savedSimulatorEntry = session.get(SIMULATOR_SESSION_KEY);
        session.put(SIMULATOR_SESSION_KEY, Boolean.TRUE);
        swapped = true;
    }

    public void swapOut() {
        StarfarerBridge.engineInstance.set(null, savedInstance);
        Global.setCombatEngine(savedGlobal);
        Map<String, AnimationManager> managers = animManagers();
        for (int i = 0; i < ANIMATION_GROUPS.length; i++) {
            if (savedAnimationManagers[i] != null) managers.put(ANIMATION_GROUPS[i], savedAnimationManagers[i]);
            else managers.remove(ANIMATION_GROUPS[i]);
            savedAnimationManagers[i] = null;
        }
        Map<Object, Object> session = session();
        if (savedSimulatorEntry != null) session.put(SIMULATOR_SESSION_KEY, savedSimulatorEntry);
        else session.remove(SIMULATOR_SESSION_KEY);
        savedSimulatorEntry = null;
        savedInstance = null;
        savedGlobal = null;
        swapped = false;
    }

    /**
     * Clears the previous scene and returns the origin for the next one.
     * Call while swapped in.
     */
    public Vector2f beginScene() {
        requireSwapped();
        clearScene();
        float y = SCENE_ORIGIN_Y[scenes % SCENE_ORIGIN_Y.length];
        scenes++;
        return new Vector2f(SCENE_ORIGIN_X, y);
    }

    /**
     * Removes every ship, projectile, missile, asteroid and beam, and the AI flags kept for the ships. Removal is
     * applied by the next engine step.
     * Call while swapped in.
     */
    private void clearScene() {
        requireSwapped();
        List<CombatEntityAPI> doomed = new ArrayList<>(engine.getShips());
        doomed.addAll(engine.getProjectiles());
        doomed.addAll(engine.getAsteroids());
        for (CombatEntityAPI entity : doomed) engine.removeEntity(entity);
        for (BeamAPI beam : new ArrayList<>(engine.getBeams())) engine.removeObject(beam);
        engine.getCustomData().keySet().removeIf(key -> key.startsWith(NO_AI_FLAGS_KEY_PREFIX));
    }

    /** One simulation step; call while swapped in. */
    public void advance(float dt) {
        requireSwapped();
        for (AnimationManager manager : ownAnimationManagers) manager.advanceAll(dt);
        StarfarerBridge.engineAdvance.invoke(engine, dt, input);
    }

    /** A rectangle of the world shown in a rectangle of screen pixels. */
    public static class View {
        public int pixelX, pixelY, pixelWidth, pixelHeight;
        public float worldX, worldY, worldWidth, worldHeight;
    }

    /** Whether render() can draw the views from one full-screen GraphicsLib engine render. */
    private static boolean fitsOneRender(View[] views, int count) {
        float[] bounds = worldBounds(views, count);
        View first = views[0];
        return (bounds[2] - bounds[0]) * first.pixelWidth / first.worldWidth <= screenWidthPixels()
                && (bounds[3] - bounds[1]) * first.pixelHeight / first.worldHeight <= screenHeightPixels();
    }

    /** True when GraphicsLib is installed and its combat shader pipeline is active. */
    private static boolean graphicsLibActive() {
        return GRAPHICSLIB_ENABLED && isGraphicsLibShaderActive();
    }

    private static boolean isGraphicsLibShaderActive() {
        return ShaderHook.enableShaders && ShaderLib.areShadersAllowed();
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
     * Shows each view's rectangle of the world in its rectangle of screen pixels, then runs the overlay in each view,
     * scissored to it.
     * When GraphicsLib shaders are off or absent, each view is rendered directly into its scissored viewport.
     * When GraphicsLib shaders are active, the engine renders at (0, 0) with a full-screen viewport because GraphicsLib's
     * FBO passes assume it does, but scissored and copied only across the sub-rectangle covered by the views.
     */
    public void render(View[] views, int count, float[] backgroundRgb, Runnable worldOverlay) {
        requireSwapped();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            if (!graphicsLibActive()) {
                setShieldMipmaps(true);
                try {
                    GL11.glEnable(GL11.GL_SCISSOR_TEST);
                    for (int i = 0; i < count; i++) {
                        View view = views[i];
                        aimCamera(view.pixelX, view.pixelY, view.pixelWidth, view.pixelHeight, view.worldX, view.worldY,
                                view.worldWidth, view.worldHeight);
                        GL11.glScissor(view.pixelX, view.pixelY, view.pixelWidth, view.pixelHeight);
                        fillBackground(backgroundRgb, view.worldX, view.worldY, view.worldWidth, view.worldHeight);
                        engine.render(false);
                    }
                } finally {
                    setShieldMipmaps(false);
                }
            } else if (fitsOneRender(views, count)) {
                renderGraphicsLibSinglePass(views, count, backgroundRgb);
            } else {
                renderGraphicsLibMultiPass(views, count, backgroundRgb);
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
            GL11.glPopAttrib();
        }
    }

    /** Points the viewport and the projection at the world rectangle */
    private void aimCamera(int pixelX, int pixelY, int pixelWidth, int pixelHeight, float worldX, float worldY,
                           float worldWidth, float worldHeight) {
        CombatViewport viewport = engine.getViewport();
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

    /** Copies the (0, 0, copyWidth, copyHeight) region of the screen into (dstX, 0) of a screen-sized texture. */
    private static int copyScreen(int texture, int dstX, int copyWidth, int copyHeight) {
        if (texture == 0) {
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, screenWidthPixels(), screenHeightPixels(), 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, dstX, 0, 0, 0, copyWidth, copyHeight);
        return texture;
    }

    /** Turns mipmapped filtering of the shield textures on or off, generating mipmaps only once per texture ID. */
    private static void setShieldMipmaps(boolean on) {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (!caps.OpenGL30 && !caps.GL_ARB_framebuffer_object) return;
        for (int i = 0; i < SHIELD_TEXTURES.length; i++) {
            int id = on ? Global.getSettings().getSprite(SHIELD_TEXTURES[i]).getTextureId() : SHIELD_TEXTURE_IDS[i];
            if (id == 0) continue;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            if (on && id != SHIELD_TEXTURE_IDS[i]) {
                if (caps.OpenGL30) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                else ARBFramebufferObject.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                SHIELD_TEXTURE_IDS[i] = id;
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                    on ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
        }
    }

    /** Renders all views in one GraphicsLib engine pass when their combined world span fits on screen. */
    private void renderGraphicsLibSinglePass(View[] views, int count, float[] backgroundRgb) {
        int screenWidth = screenWidthPixels(), screenHeight = screenHeightPixels();
        float[] bounds = worldBounds(views, count);
        float unitsPerPixelX = views[0].worldWidth / views[0].pixelWidth;
        float unitsPerPixelY = views[0].worldHeight / views[0].pixelHeight;
        int renderWidth = 0, renderHeight = 0;
        for (int i = 0; i < count; i++) {
            View view = views[i];
            renderWidth = Math.max(renderWidth,
                    Math.round((view.worldX - bounds[0]) / unitsPerPixelX) + view.pixelWidth);
            renderHeight = Math.max(renderHeight,
                    Math.round((view.worldY - bounds[1]) / unitsPerPixelY) + view.pixelHeight);
        }
        renderWidth = Math.min(screenWidth, Math.max(1, renderWidth));
        renderHeight = Math.min(screenHeight, Math.max(1, renderHeight));
        int dirtyWidth = Math.min(screenWidth, Math.max(renderWidth, lastRenderWidth));
        int dirtyHeight = Math.min(screenHeight, Math.max(renderHeight, lastRenderHeight));
        lastRenderWidth = renderWidth;
        lastRenderHeight = renderHeight;

        screenCopy = copyScreen(screenCopy, 0, dirtyWidth, dirtyHeight);
        aimCamera(0, 0, screenWidth, screenHeight, bounds[0], bounds[1], screenWidth * unitsPerPixelX,
                screenHeight * unitsPerPixelY);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, 0, dirtyWidth, dirtyHeight);
        fillBackground(backgroundRgb, bounds[0], bounds[1], dirtyWidth * unitsPerPixelX,
                dirtyHeight * unitsPerPixelY);
        // GraphicsLib clears its buffers with this colour, which combat also sets before its engine render.
        GL11.glClearColor(0f, 0f, 0f, 0f);
        setShieldMipmaps(true);
        try {
            engine.render(false);
        } finally {
            setShieldMipmaps(false);
            prepareCompositeState();
            sceneCopy = copyScreen(sceneCopy, 0, renderWidth, renderHeight);
            int[] srcX = new int[count], srcY = new int[count];
            for (int i = 0; i < count; i++) {
                srcX[i] = Math.round((views[i].worldX - bounds[0]) / unitsPerPixelX);
                srcY[i] = Math.round((views[i].worldY - bounds[1]) / unitsPerPixelY);
            }
            compositeScreen(views, count, srcX, srcY, dirtyWidth, dirtyHeight);
        }
    }

    /**
     * Renders each view separately at (0, 0) when their combined world span is wider than the screen (e.g. very long
     * range split views), packing each rendered pane side-by-side into sceneCopy before restoring the screen once.
     */
    private void renderGraphicsLibMultiPass(View[] views, int count, float[] backgroundRgb) {
        int screenWidth = screenWidthPixels(), screenHeight = screenHeightPixels();
        int maxW = 1, maxH = 1;
        for (int i = 0; i < count; i++) {
            maxW = Math.max(maxW, views[i].pixelWidth);
            maxH = Math.max(maxH, views[i].pixelHeight);
        }
        maxW = Math.min(screenWidth, maxW);
        maxH = Math.min(screenHeight, maxH);
        int dirtyWidth = Math.min(screenWidth, Math.max(maxW, lastRenderWidth));
        int dirtyHeight = Math.min(screenHeight, Math.max(maxH, lastRenderHeight));
        lastRenderWidth = maxW;
        lastRenderHeight = maxH;

        screenCopy = copyScreen(screenCopy, 0, dirtyWidth, dirtyHeight);
        int[] srcX = new int[count], srcY = new int[count];
        int dstX = 0, copied = 0;
        setShieldMipmaps(true);
        try {
            for (int i = 0; i < count; i++) {
                View view = views[i];
                int copyW = Math.min(screenWidth - dstX, Math.min(screenWidth, view.pixelWidth));
                int copyH = Math.min(screenHeight, view.pixelHeight);
                if (copyW <= 0 || copyH <= 0) break;
                int clearW = i == 0 ? dirtyWidth : copyW;
                int clearH = i == 0 ? dirtyHeight : copyH;
                float unitsPerPixelX = view.worldWidth / view.pixelWidth;
                float unitsPerPixelY = view.worldHeight / view.pixelHeight;
                aimCamera(0, 0, screenWidth, screenHeight, view.worldX, view.worldY, screenWidth * unitsPerPixelX,
                        screenHeight * unitsPerPixelY);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(0, 0, clearW, clearH);
                fillBackground(backgroundRgb, view.worldX, view.worldY, clearW * unitsPerPixelX,
                        clearH * unitsPerPixelY);
                GL11.glClearColor(0f, 0f, 0f, 0f);
                engine.render(false);
                prepareCompositeState();
                sceneCopy = copyScreen(sceneCopy, dstX, copyW, copyH);
                srcX[copied] = dstX;
                srcY[copied] = 0;
                dstX += copyW;
                copied++;
            }
        } finally {
            setShieldMipmaps(false);
            prepareCompositeState();
            compositeScreen(views, copied, srcX, srcY, dirtyWidth, dirtyHeight);
        }
    }

    /** Binds the default framebuffer and sets plain opaque-textured quad state after GraphicsLib's render. */
    private static void prepareCompositeState() {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (caps.OpenGL30) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        } else if (caps.GL_ARB_framebuffer_object) {
            ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, 0);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColorMask(true, true, true, true);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Puts the modified region of screenCopy back, then draws each copied view from sceneCopy into its rectangle. */
    private void compositeScreen(View[] views, int count, int[] srcX, int[] srcY, int dirtyWidth, int dirtyHeight) {
        int screenWidth = screenWidthPixels(), screenHeight = screenHeightPixels();
        GL11.glViewport(0, 0, screenWidth, screenHeight);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, screenWidth, 0, screenHeight, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        drawScreenTexture(screenCopy, 0, 0, dirtyWidth, dirtyHeight, 0, 0);
        for (int i = 0; i < count; i++) {
            View view = views[i];
            drawScreenTexture(sceneCopy, view.pixelX, view.pixelY, view.pixelWidth, view.pixelHeight, srcX[i], srcY[i]);
        }
        GL11.glEnable(GL11.GL_BLEND);
    }

    /** Draws the (sourceX, sourceY, width, height) pixels of a screen-sized texture at (x, y). */
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
     * Paints a world rectangle in the background colour, then leaves texturing on and the colour white for the engine.
     * A quad rather than glClear: Fast Rendering applies glEnable(GL_SCISSOR_TEST) only at its next draw, so a glClear
     * right after it clears the whole screen. Unlike glClear, the quad goes through the alpha and stencil tests, which
     * ship rendering leaves on between views.
     */
    private static void fillBackground(float[] rgb, float worldX, float worldY, float worldWidth, float worldHeight) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glColor4f(rgb[0], rgb[1], rgb[2], 1f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(worldX, worldY);
        GL11.glVertex2f(worldX + worldWidth, worldY);
        GL11.glVertex2f(worldX + worldWidth, worldY + worldHeight);
        GL11.glVertex2f(worldX, worldY + worldHeight);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Destroy the engine; call while swapped out. */
    public void destroy() {
        if (screenCopy != 0) {
            GL11.glDeleteTextures(screenCopy);
            screenCopy = 0;
        }
        if (sceneCopy != 0) {
            GL11.glDeleteTextures(sceneCopy);
            sceneCopy = 0;
        }
        lastRenderWidth = 0;
        lastRenderHeight = 0;
        swapIn();
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

    /** The engine's plugins come from the combat map, so this runs once the map is set. */
    private void stripPlugins() {
        for (EveryFrameCombatPlugin p : engine.getPlugins()) {
            String name = p.getClass().getName();
            if (hasPrefix(name, STRIPPED_PLUGIN_PREFIXES)) {
                engine.removePlugin(p);
                Log.info("removed combat plugin %s from the preview engine", name);
            }
        }
    }

    private static boolean hasPrefix(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
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
