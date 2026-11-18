package weapontooltippreview.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.util.Misc;
import org.dark.shaders.util.GraphicsLibSettings;
import org.dark.shaders.util.ShaderHook;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.lwjgl.util.vector.Vector2f;
import weapontooltippreview.Log;
import weapontooltippreview.WeaponTooltipPreviewSettings;
import weapontooltippreview.scenario.NoPreviewException;
import weapontooltippreview.scenario.PreviewSubject;
import weapontooltippreview.scenario.RefitShip;
import weapontooltippreview.scenario.Scenario;
import weapontooltippreview.scenario.ThreatSelection;
import weapontooltippreview.scenario.WeaponHost;
import weapontooltippreview.sim.CombatArcRenderer;
import weapontooltippreview.sim.EngineSandbox;

import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The panel injected into a weapon or LPC tooltip and the scene running inside it. Acquires the Engine, steps the Scenario and renders the viewports & HUD overlays. */
final class PreviewScene implements CustomUIPanelPlugin {

    /** The threat key line: its box and the gap above it. */
    static final float THREAT_HINT_HEIGHT = 24f;
    /** As tall as the game's own F1 hint box under the tooltip: the font's line (10) plus 8. */
    private static final float THREAT_HINT_BOX_HEIGHT = 18f;
    /** The text sits in the box as in the game's hint: 4 below the top, 7 in from the left. */
    private static final float THREAT_HINT_PAD = 4f;
    private static final float THREAT_HINT_INDENT = 7f;

    private static final float MAX_STEP = 1f / 30f;
    private static final Vector2f FAR_AWAY = new Vector2f(1e7f, 1e7f);
    private static final int LOG_EVERY_FRAMES = 300;
    /** The throwaway scene of prewarm(): a stock weapon, on a generic host, against the player's dummy. */
    private static final String PREWARM_WEAPON_ID = "lightac";
    private static final int PREWARM_STEPS = 10;
    /** About a tooltip's preview, at the bottom left of the screen; the engine's render puts the screen back. */
    private static final float PREWARM_WIDTH = 400f, PREWARM_HEIGHT = 150f;
    private static final String GRAPHICSLIB_MOD_ID = "shaderLib";
    private static final boolean GRAPHICSLIB_ENABLED =
            Global.getSettings().getModManager().isModEnabled(GRAPHICSLIB_MOD_ID);

    /** Weapons and wings whose preview failed this game session. */
    private static final Set<PreviewSubject> FAILED = new HashSet<>();
    /** Shared HUD renderer: keeps sprites, wing icons and text buffers warm across hovers. */
    private static final PreviewHud HUD = new PreviewHud();

    static final class Pane extends EngineSandbox.View {
        float uiX, uiY, uiW, uiH;
    }

    private final int tooltipId;
    private final PreviewSubject subject;
    private final RefitShip refit;
    private PositionAPI pos;
    private UIComponentAPI statusLine;
    /** The threat key line, rewritten when a key changes it; null without the line. */
    private LabelAPI threatHint;
    private int threatHintVersion;
    /** Room under the scene taken by the threat key line. */
    private float hintHeight;

    private EngineSandbox sandbox;
    private Scenario scenario;
    private final Pane[] panes = {new Pane(), new Pane()};
    private int paneCount;
    private boolean split;
    /** The game's in-combat arc renderer; null if it could not be set up, in which case no arc is drawn. */
    private CombatArcRenderer arcRenderer;
    private boolean arcUnavailable = !CombatArcRenderer.isAvailable();
    /** The dummy the arc renderer was made for. */
    private ShipAPI arcTarget;

    private long frames;
    private long renders;
    private double simMs, renderMs;
    private boolean failed;
    private boolean disposed;

    PreviewScene(int tooltipId, PreviewSubject subject, RefitShip refit) {
        this.tooltipId = tooltipId;
        this.subject = subject;
        this.refit = refit;
    }

    static boolean hasFailed(PreviewSubject subject) {
        return FAILED.contains(subject);
    }

    static void clearFailures() {
        FAILED.clear();
    }

    /** Runs a throwaway scene while the game loads, so the first preview after it does not pay for building the engine. */
    static void prewarm() {
        long t0 = System.nanoTime();
        PreviewScene scene = new PreviewScene(0, new PreviewSubject(PREWARM_WEAPON_ID, null), null);
        try {
            scene.startScene();
            try {
                for (int i = 0; i < PREWARM_STEPS; i++) scene.step(MAX_STEP, false);
                scene.drawScene(0f, 0f, PREWARM_WIDTH, PREWARM_HEIGHT, null);
            } catch (RuntimeException | Error e) {
                scene.endScene(true);
                throw e;
            }
            scene.endScene(false);
            PreviewHud.loadFont();
            Log.info("preview engine prewarmed in %.0f ms", (System.nanoTime() - t0) / 1e6);
        } catch (Throwable t) {
            Log.error("prewarming the preview engine failed; the first preview builds it instead", t);
        }
    }

    /** The "Preview unavailable" line, hidden until a failure. */
    void setStatusLine(UIComponentAPI line) {
        statusLine = line;
        line.setOpacity(0f);
    }

    /** The threat keys on one line at the bottom of the panel. */
    void addThreatHint(CustomPanelAPI panel) {
        hintHeight = THREAT_HINT_HEIGHT;
        TooltipMakerAPI element = panel.createUIElement(panel.getPosition().getWidth() - THREAT_HINT_INDENT,
                THREAT_HINT_BOX_HEIGHT, false);
        element.setParaFont(Fonts.ORBITRON_12);
        threatHint = element.addPara("", THREAT_HINT_PAD);
        panel.addUIElement(element).inBL(THREAT_HINT_INDENT, 0f);
        writeThreatHint();
    }

    private boolean showsThreatHint() {
        return threatHint != null && !failed;
    }

    /** Side by side and in the colours of the F1/F2 hint */
    private void writeThreatHint() {
        ThreatSelection.Mode mode = ThreatSelection.mode();
        Color key = Misc.getHighlightColor(), active = Misc.getGrayColor(), inactive = active.darker();
        String[] parts = {
                Keyboard.getKeyName(WeaponTooltipPreviewSettings.threatModeKey()), mode.label,
                Keyboard.getKeyName(WeaponTooltipPreviewSettings.threatMissileKey()), ThreatSelection.missileLabel(),
                Keyboard.getKeyName(WeaponTooltipPreviewSettings.threatWingKey()), ThreatSelection.wingLabel()};
        threatHint.setText(String.format("%s %s  %s %s  %s %s", (Object[]) parts));
        threatHint.setHighlight(parts);
        threatHint.setHighlightColors(key, active, key, mode.missiles ? active : inactive, key,
                mode.wings ? active : inactive);
        threatHintVersion = ThreatSelection.version();
    }

    @Override
    public void positionChanged(PositionAPI position) {
        pos = position;
    }

    @Override
    public void advance(float amount) {
        if (disposed || failed || pos == null) return;
        if (threatHint != null && threatHintVersion != ThreatSelection.version()) writeThreatHint();
        try {
            if (sandbox == null) startScene();
            step(amount, true);
        } catch (NoPreviewException e) {
            fail(e.getMessage(), null);
        } catch (Throwable t) {
            fail("preview failed", t);
        }
    }

    @Override
    public void renderBelow(float alphaMult) {
        if (pos == null) return;
        HUD.drawBackground(pos.getX(), pos.getY() + hintHeight, pos.getWidth(), pos.getHeight() - hintHeight, alphaMult);
    }

    /** The scene takes the panel above the threat key line. */
    @Override
    public void render(float alphaMult) {
        if (pos == null) return;
        float x = pos.getX(), y = pos.getY() + hintHeight, w = pos.getWidth(), h = pos.getHeight() - hintHeight;
        if (sandbox != null && frames > 0) {
            try {
                drawScene(x, y, w, h, arcRenderer != null ? this::drawArc : null);
                HUD.drawOverlays(scenario, panes, paneCount, split, x, y, w, h, alphaMult);
            } catch (Throwable t) {
                fail("render failed", t);
            }
        }
        HUD.drawFrame(x, y, w, h, showsThreatHint(), pos.getY(), THREAT_HINT_BOX_HEIGHT, alphaMult);
    }

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (disposed || failed) return;
        if (GRAPHICSLIB_ENABLED) processGraphicsLibToggleKey(events);
        if (showsThreatHint()) processThreatKeys(events);
    }

    /** While a point defense preview shows its threat keys, each one cycles its part of what the dummy launches. */
    private static void processThreatKeys(List<InputEventAPI> events) {
        for (InputEventAPI event : events) {
            if (event.isConsumed() || !event.isKeyDownEvent()) continue;
            int key = event.getEventValue();
            if (key == WeaponTooltipPreviewSettings.threatModeKey()) ThreatSelection.cycleMode();
            else if (key == WeaponTooltipPreviewSettings.threatMissileKey()) ThreatSelection.cycleMissile();
            else if (key == WeaponTooltipPreviewSettings.threatWingKey()) ThreatSelection.cycleWing();
            else continue;
            event.consume();
        }
    }

    /** GraphicsLib only reads its toggle key from a combat engine's input, which a preview's engine never gets. */
    private static void processGraphicsLibToggleKey(List<InputEventAPI> events) {
        for (InputEventAPI event : events) {
            if (event.isConsumed() || !event.isKeyDownEvent()) continue;
            if (event.getEventValue() != GraphicsLibSettings.toggleKey()) continue;
            ShaderHook.enableShaders = !ShaderHook.enableShaders;
            event.consume();
            return;
        }
    }

    @Override
    public void buttonPressed(Object buttonId) {
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        endScene(false);
    }

    boolean isDisposed() {
        return disposed;
    }

    // ------------------------------------------------------------------ scene simulation & camera

    /** Takes the shared engine and builds the weapon's or wing's scene in it. */
    private void startScene() {
        long t0 = System.nanoTime();
        EngineSandbox acquired = EngineSandbox.acquire();
        sandbox = acquired;
        try {
            acquired.swapIn();
            try {
                scenario = Scenario.spawn(acquired.api(), subject, refit, acquired.beginScene());
                rearmArcRenderer();
            } finally {
                acquired.swapOut();
            }
        } catch (NoPreviewException e) {
            sandbox = null;
            EngineSandbox.release();
            throw e;
        } catch (RuntimeException | Error e) {
            sandbox = null;
            EngineSandbox.discard("scene setup failed for " + subject);
            throw e;
        }
        Log.info("scene for %s started in %.1f ms (scene %d of this engine)", subject, (System.nanoTime() - t0) / 1e6,
                acquired.scenes());
    }

    /** (Re)creates the game's arc renderer for the weapon's group. Swapped in only. */
    private void rearmArcRenderer() {
        arcTarget = scenario.targetDummy().ship();
        WeaponHost weaponHost = scenario.weaponHost();
        if (arcUnavailable || weaponHost.weapon() == null) return;
        try {
            CombatArcRenderer arc = new CombatArcRenderer();
            arc.select(weaponHost.ship().getWeaponGroupFor(weaponHost.weapon()));
            arcRenderer = arc;
        } catch (Throwable t) {
            Log.error("combat arc renderer failed; previews draw no arc", t);
            arcRenderer = null;
            arcUnavailable = true;
        }
    }

    /**
     * True: the scenario directs the step (aim, fire, the dummy's moves and launches) with the listener in range.
     * False: Nothing fires, and the listener stays far away.
     */
    private void step(float dt, boolean directed) {
        sandbox.swapIn();
        try {
            Global.getSoundPlayer().setListenerPosOverrideOneFrame(
                    directed && WeaponTooltipPreviewSettings.sound() ? scenario.listenerPosition() : FAR_AWAY);
            long t0 = System.nanoTime();
            float step = Math.min(dt, MAX_STEP);
            if (directed) scenario.beforeStep(step);
            sandbox.advance(step);
            if (scenario.targetDummy().ship() != arcTarget) rearmArcRenderer();
            if (arcRenderer != null) arcRenderer.advance(step);
            simMs += (System.nanoTime() - t0) / 1e6;
        } finally {
            sandbox.swapOut();
        }
        frames++;
        if (Log.isEnabled() && (frames == 1 || frames % LOG_EVERY_FRAMES == 0)) {
            Log.info("%s frame %d (%s): avg sim %.2f ms, avg render %.2f ms | %s", subject, frames,
                    split ? "split" : "single", simMs / frames, renders > 0 ? renderMs / renders : 0,
                    scenario.frameLogDetails());
        }
    }

    /** The engine's picture of the scene in the UI rectangle, then HUD overlay. */
    private void drawScene(float x, float y, float w, float h, Runnable overlay) {
        layout(x, y, w, h);
        long t0 = System.nanoTime();
        sandbox.swapIn();
        try {
            sandbox.render(panes, paneCount, PreviewHud.BACKGROUND, overlay);
        } finally {
            sandbox.swapOut();
        }
        renderMs += (System.nanoTime() - t0) / 1e6;
        renders++;
    }

    private void layout(float x, float y, float w, float h) {
        Scenario s = scenario;
        float span = s.frameMaxX - s.frameMinX;
        float paneW = (w - PreviewHud.SPLIT_GAP_UI) / 2f;
        float paneH = h - PreviewHud.SPLIT_STRIP_UI;
        float paneWorldH = 2f * s.frameHalfHeight;
        float paneWorldW = paneWorldH * paneW / paneH;
        split = s.canSplit && 2f * paneWorldW < span;

        if (split) {
            paneCount = 2;
            Pane left = panes[0];
            left.uiX = x;
            left.uiY = y + PreviewHud.SPLIT_STRIP_UI;
            left.uiW = paneW;
            left.uiH = paneH;
            left.worldWidth = paneWorldW;
            left.worldHeight = paneWorldH;
            left.worldX = s.frameMinX;
            left.worldY = s.weaponHost().ship().getLocation().y - paneWorldH / 2f;
            placeInPixels(left);

            Pane right = panes[1];
            right.uiX = x + w - paneW;
            right.uiY = y + PreviewHud.SPLIT_STRIP_UI;
            right.uiW = paneW;
            right.uiH = paneH;
            right.worldWidth = paneWorldW;
            right.worldHeight = paneWorldH;
            right.worldX = s.frameMaxX - paneWorldW;
            right.worldY = s.dummyPosition.y - paneWorldH / 2f;
            placeInPixels(right);

            // Both panes are cut from one render of the whole scene, which has to fit on the screen at their zoom;
            // a scene too long for that is shown in one pane.
            split = EngineSandbox.fitsOneRender(panes, paneCount);
            if (split) return;
        }
        float singleUnitsPerUi = Math.max(span / w, 2f * s.frameHalfHeight / h);
        paneCount = 1;
        Pane p = panes[0];
        p.uiX = x;
        p.uiY = y;
        p.uiW = w;
        p.uiH = h;
        p.worldWidth = w * singleUnitsPerUi;
        p.worldHeight = h * singleUnitsPerUi;
        p.worldX = s.frameCenterX - p.worldWidth / 2f;
        p.worldY = s.frameCenterY - p.worldHeight / 2f;
        placeInPixels(p);
    }

    private static void placeInPixels(Pane p) {
        p.pixelX = toPixels(p.uiX);
        p.pixelY = toPixels(p.uiY);
        p.pixelWidth = toPixels(p.uiW);
        p.pixelHeight = toPixels(p.uiH);
    }

    private static int toPixels(float uiUnits) {
        return Math.round(uiUnits * Global.getSettings().getScreenScaleMult() * Display.getPixelScaleFactor());
    }

    /** The in-combat arc renderer; stops drawing if it ever fails. */
    private void drawArc() {
        if (arcRenderer == null) return;
        try {
            arcRenderer.render();
        } catch (Throwable t) {
            Log.error("combat arc renderer failed; previews draw no arc", t);
            arcRenderer = null;
            arcUnavailable = true;
        }
    }

    /** An expected "cannot preview this weapon"; otherwise the engine is rebuilt for safety. */
    private void fail(String what, Throwable t) {
        failed = true;
        FAILED.add(subject);
        if (t != null) Log.error("tooltip #" + tooltipId + " (" + subject + "): " + what, t);
        else Log.info("no preview for %s: %s", subject, what);
        endScene(t != null);
        if (statusLine != null) statusLine.setOpacity(1f);
        if (threatHint != null) threatHint.setOpacity(0f);
    }

    private void endScene(boolean failure) {
        if (sandbox == null) return;
        sandbox = null;
        HUD.clearTarget();
        if (failure) EngineSandbox.discard("preview of " + subject + " failed");
        else EngineSandbox.release();
        Log.info("scene for %s ended after %d frames (avg sim %.2f ms, render %.2f ms); %s", subject, frames,
                frames > 0 ? simMs / frames : 0, renders > 0 ? renderMs / renders : 0, Log.heap());
    }
}
