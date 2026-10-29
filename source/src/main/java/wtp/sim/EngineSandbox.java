package wtp.sim;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.ViewportAPI;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.util.vector.Vector2f;
import wtp.Log;
import wtp.bridge.ObfBridge;
import wtp.bridge.Refl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A private CombatEngine living next to the campaign's idle one. The engine is a JVM-wide singleton, so every
 * interaction happens between {@link #swapIn()} and {@link #swapOut()}, which exchange the static instance,
 * Global.getCombatEngine() and the shared "CEanim"/"CENonPauseanim" animation groups.
 * <p>
 * One sandbox serves many previews: {@link #beginScene()} clears the previous scene and hands out the next of a few
 * well separated scene origins, so particles and explosions still fading out from the last preview are off camera.
 */
public final class EngineSandbox {

    private static final String[] ANIM_GROUPS = {"CEanim", "CENonPauseanim"};
    /**
     * Combat plugins set up for a real battle, screen-wide: GraphicsLib post-processes the full screen and reloads ship
     * textures, BoxUtil installs its shader passes (blur, bloom) and cleans up its campaign rendering, UIUtil clears its
     * global UI state and takes over as the combat UI.
     */
    private static final String[] STRIPPED_PLUGIN_PREFIXES = {"org.dark.shaders.", "org.dark.graphics.", "org.boxutil.",
            "org.luoxb.uiutil."};
    private static final float MAP_HALF_SIZE = 15000f;
    /** Scenes extend toward +x from their origin (host at the origin, targets and waves at weapon range). */
    private static final float SCENE_ORIGIN_X = -5000f;
    private static final float[] SCENE_ORIGIN_Y = {0f, 5000f, -5000f, 10000f, -10000f};
    private static boolean loggedInheritedState;

    private Object engine;
    private final Object input = Refl.newInstance(ObfBridge.inputClass);
    private final Object[] ownAnims = new Object[ANIM_GROUPS.length];
    private final List<String> plugins = new ArrayList<>();
    private final List<String> strippedPlugins = new ArrayList<>();
    private int scenes;

    private boolean swapped;
    private Object savedInstance;
    private CombatEngineAPI savedGlobal;
    private final Object[] savedAnims = new Object[ANIM_GROUPS.length];

    private EngineSandbox() {
    }

    /** Builds and initialises a private engine; the campaign's engine is back in place when this returns. */
    public static EngineSandbox create() {
        EngineSandbox sandbox = new EngineSandbox();
        for (int i = 0; i < ANIM_GROUPS.length; i++) sandbox.ownAnims[i] = Refl.newInstance(ObfBridge.animClass);
        sandbox.swapIn();
        boolean ok = false;
        try {
            sandbox.engine = ObfBridge.engineGetInstance.invoke(null);
            ObfBridge.engineSetCombatUI.invoke(sandbox.engine, combatUiHost());
            Object map = Refl.newInstance(ObfBridge.mapClass);
            ObfBridge.mapSetBounds.invoke(map, -MAP_HALF_SIZE, MAP_HALF_SIZE, -MAP_HALF_SIZE, MAP_HALF_SIZE);
            ObfBridge.engineSetCombatMap.invoke(sandbox.engine, map);
            // setCombatMap() instantiates the settings plugins and init() calls their init(): stripped in between, a
            // plugin never sets anything up.
            sandbox.stripPlugins(map);
            ObfBridge.engineCreateDummyFleets.invoke(sandbox.engine);
            ObfBridge.engineInit.invoke(sandbox.engine);
            ObfBridge.engineSetPaused.invoke(sandbox.engine, false);
            ObfBridge.enginePreCombat.invoke(sandbox.engine);
            sandbox.api().setDoNotEndCombat(true);
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
        return (CombatEngineAPI) engine;
    }

    public List<String> plugins() {
        return plugins;
    }

    public List<String> strippedPlugins() {
        return strippedPlugins;
    }

    public int scenes() {
        return scenes;
    }

    public void swapIn() {
        if (swapped) throw new IllegalStateException("sandbox already swapped in");
        savedInstance = ObfBridge.engineInstance.get(null);
        savedGlobal = Global.getCombatEngine();
        Map<Object, Object> managers = animManagers();
        for (int i = 0; i < ANIM_GROUPS.length; i++) {
            savedAnims[i] = managers.get(ANIM_GROUPS[i]);
            managers.put(ANIM_GROUPS[i], ownAnims[i]);
        }
        ObfBridge.engineInstance.set(null, engine);
        if (engine != null) Global.setCombatEngine((CombatEngineAPI) engine);
        swapped = true;
    }

    public void swapOut() {
        if (!swapped) return;
        ObfBridge.engineInstance.set(null, savedInstance);
        Global.setCombatEngine(savedGlobal);
        Map<Object, Object> managers = animManagers();
        for (int i = 0; i < ANIM_GROUPS.length; i++) {
            if (savedAnims[i] != null) managers.put(ANIM_GROUPS[i], savedAnims[i]);
            else managers.remove(ANIM_GROUPS[i]);
            savedAnims[i] = null;
        }
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
        ObfBridge.animAdvanceAll.invoke(ownAnims[1], dt);
        ObfBridge.animAdvanceAll.invoke(ownAnims[0], dt);
        ObfBridge.engineAdvance.invoke(engine, dt, input);
    }

    /**
     * Renders the world rectangle (llx, lly, w, h) into the scissored pixel rectangle (px, py, pw, ph) of the current
     * framebuffer, then runs {@code worldOverlay} in the same world projection. Mirrors the title screen's projection
     * helper. Rendering straight to the screen matters: ship sprites are composited with a destination-alpha mask, so
     * an offscreen target's alpha channel is garbage. Call while swapped in.
     */
    public void render(int px, int py, int pw, int ph, float llx, float lly, float w, float h, float[] clearRgb,
                       Runnable worldOverlay) {
        requireSwapped();
        ViewportAPI viewport = api().getViewport();
        // set() derives viewMult from the screen width; pick ratios so viewMult = world units per target pixel.
        float ratioX = Global.getSettings().getScreenWidth() / pw;
        float ratioY = Global.getSettings().getScreenHeight() / ph;
        ObfBridge.viewportSetXRatio.invoke(viewport, ratioX);
        ObfBridge.viewportSetYRatio.invoke(viewport, ratioY);
        viewport.set(llx, lly, w, h);

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
            GL11.glViewport(px, py, pw, ph);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(px, py, pw, ph);
            GL11.glColorMask(true, true, true, true);
            GL11.glClearColor(clearRgb[0], clearRgb[1], clearRgb[2], 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(llx, llx + w, lly, lly + h, -4000, 4000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            resetFixedFunctionState();
            GL11.glEnable(GL11.GL_SCISSOR_TEST); // resetFixedFunctionState() disables it; stay inside the rectangle
            ObfBridge.engineRender.invoke(engine, false);
            if (worldOverlay != null) worldOverlay.run();
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

    /**
     * The engine's renderers assume the game's baseline fixed-function state (no shader bound, texture unit 0,
     * MODULATE, standard alpha blending, no stray buffers). Whatever the campaign UI left behind is overridden here;
     * the caller's attribute pushes and program restore put it back afterwards.
     */
    private static void resetFixedFunctionState() {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (!loggedInheritedState) {
            loggedInheritedState = true;
            Log.debug("inherited GL state: program=%d activeTexture=0x%x texEnv=0x%x blend=%s alphaTest=%s depth=%s arrayBuffer=%d",
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
        boolean wasSwapped = swapped;
        if (!wasSwapped) swapIn();
        try {
            destroyWhileSwapped();
        } finally {
            swapOut();
        }
    }

    public boolean isDestroyed() {
        return engine == null;
    }

    private void destroyWhileSwapped() {
        try {
            if (engine != null) ObfBridge.engineDestroyInstance.invoke(null);
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
            plugins.add(name);
            for (String prefix : STRIPPED_PLUGIN_PREFIXES) {
                if (name.startsWith(prefix)) {
                    api().removePlugin(p);
                    strippedPlugins.add(name);
                    break;
                }
            }
        }
    }

    /** Every-frame plugins live in the combat map as wrappers around the API plugin. */
    private static List<EveryFrameCombatPlugin> pluginsInMap(Object map) {
        List<EveryFrameCombatPlugin> out = new ArrayList<>();
        for (Refl.RField field : Refl.fields(map.getClass())) {
            if (field.isStatic || !List.class.isAssignableFrom(field.type)) continue;
            Object list = field.get(map);
            if (!(list instanceof List)) continue;
            for (Object wrapper : (List<?>) list) {
                if (wrapper == null) continue;
                Refl.RField f = Refl.fieldOfType(wrapper.getClass(), EveryFrameCombatPlugin.class, false);
                Object plugin = f != null ? f.get(wrapper) : null;
                if (plugin instanceof EveryFrameCombatPlugin) out.add((EveryFrameCombatPlugin) plugin);
            }
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    /**
     * Ship.applyDamageInner needs a non-null combat UI. TitleScreenState is the headless implementation the
     * title-screen battle uses: its UI callbacks are stubs.
     */
    private static Object combatUiHost() {
        Object driver = ObfBridge.appDriverGetInstance.invoke(null);
        Map<?, ?> states = (Map<?, ?>) ObfBridge.appDriverStates.get(driver);
        Object fallback = null;
        for (Object state : states.values()) {
            if (!ObfBridge.combatUiInterface.isInstance(state)) continue;
            if (state.getClass().getName().endsWith("TitleScreenState")) return state;
            fallback = state;
        }
        if (fallback == null) throw new IllegalStateException("no AppState implements the combat UI interface");
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> animManagers() {
        return (Map<Object, Object>) ObfBridge.animManagers.get(null);
    }
}
