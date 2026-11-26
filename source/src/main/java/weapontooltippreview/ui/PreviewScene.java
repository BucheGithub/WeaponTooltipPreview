package weapontooltippreview.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The panel injected into a weapon/LPC tooltip or Codex page and the scene running inside it. Acquires the Engine, steps the Scenario and renders the viewports & HUD overlays. */
final class PreviewScene extends BaseCustomUIPanelPlugin {

    /** How a newly created preview panel opens. */
    enum Opening {
        /** Opens straight into the live preview (0s delay or rebuild of an open tooltip). */
        IMMEDIATE,
        /** Tooltip expands with static inside the frame, then fades static into the live preview. */
        EXPAND_STATIC,
        /** Already open (e.g. fighter LPC page turn): brief static flicker into the new subject. */
        STATIC_FLICKER
    }

    static final float PREVIEW_HEIGHT = 150f;
    static final float EXPAND_DURATION = 0.18f;
    static final float MIN_EXPAND_HEIGHT = 2f;
    static final int SCAN_DEPTH = 2;
    /** The threat key line: its box and the gap above it. */
    static final float THREAT_HINT_HEIGHT = 24f;
    private static final float STATUS_LINE_HEIGHT = 20f;
    /** As tall as the game's own F1 hint box under the tooltip: the font's line (10) plus 8. */
    private static final float THREAT_HINT_BOX_HEIGHT = 18f;
    /** The text sits in the box as in the game's hint: 4 below the top, 7 in from the left. */
    private static final float THREAT_HINT_PAD = 4f;
    private static final float THREAT_HINT_INDENT = 7f;
    /** Full-static hold and fade durations after expansion completes. */
    private static final float STATIC_HOLD = 0.06f;
    private static final float STATIC_FADE = 0.14f;
    /** Shorter static burst when cycling fighter LPC pages on an already-open preview. */
    private static final float FLICKER_HOLD = 0.04f;
    private static final float FLICKER_FADE = 0.11f;

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

    /** Weapons and wings whose preview threw an unexpected error this game session. */
    private static final Set<PreviewSubject> FAILED = new HashSet<>();
    /** Weapons and wings that have no generic preview outside the refit screen (still allowed on their refit ship). */
    private static final Set<PreviewSubject> NO_PREVIEW_OUTSIDE_REFIT = new HashSet<>();
    /** Shared HUD renderer: keeps sprites, wing icons and text buffers warm across hovers. */
    private static final PreviewHud HUD = new PreviewHud();

    static final class Pane extends EngineSandbox.View {
        float uiX, uiY, uiW, uiH;
    }

    private final int tooltipId;
    private final PreviewSubject subject;
    private final RefitShip refit;
    private PositionAPI pos;
    /**
     * Codex scroll pane ({@code CodexDetailPanel.scroller}) whose bounds clip the scene render as the entry scrolls.
     * {@code null} for floating tooltips, which are not hosted inside a scroll container.
     */
    private UIComponentAPI clipSource;
    private final EngineSandbox.Clip clip = new EngineSandbox.Clip();
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

    /** Height of the scene viewport above the optional threat hint bar; used while the panel is expanding. */
    private final float sceneTargetHeight;
    private boolean expanding;
    private float staticHoldRemaining;
    private float staticFadeDuration;
    private float staticFadeRemaining;
    private float staticClock;

    PreviewScene(int tooltipId, PreviewSubject subject, RefitShip refit, float sceneTargetHeight, Opening opening) {
        this.tooltipId = tooltipId;
        this.subject = subject;
        this.refit = refit;
        this.sceneTargetHeight = sceneTargetHeight;
        if (opening == Opening.EXPAND_STATIC) {
            this.expanding = true;
            this.staticHoldRemaining = STATIC_HOLD;
            this.staticFadeDuration = STATIC_FADE;
            this.staticFadeRemaining = STATIC_FADE;
        } else if (opening == Opening.STATIC_FLICKER) {
            this.staticHoldRemaining = FLICKER_HOLD;
            this.staticFadeDuration = FLICKER_FADE;
            this.staticFadeRemaining = FLICKER_FADE;
        }
    }

    /** Called by the watcher once the panel reaches its full expanded height. */
    void finishExpanding() {
        expanding = false;
    }

    /** Codex-only: clips scene rendering to the Codex scroll pane's bounds; unused ({@code null}) for tooltips. */
    void setClipSource(UIComponentAPI source) {
        clipSource = source;
    }

    /** Creates the CustomPanel hosting this scene, its hidden "Preview unavailable" label, and optional threat hint. */
    CustomPanelAPI createPanel(float width) {
        boolean threatHint = ThreatSelection.appliesTo(subject);
        CustomPanelAPI panel = Global.getSettings().createCustom(width,
                sceneTargetHeight + (threatHint ? THREAT_HINT_HEIGHT : 0f), this);
        TooltipMakerAPI status = panel.createUIElement(width, STATUS_LINE_HEIGHT, false);
        LabelAPI line = status.addPara("Preview unavailable", Misc.getGrayColor(), 0f);
        line.setAlignment(Alignment.MID);
        panel.addUIElement(status).inTL(0f, (sceneTargetHeight - STATUS_LINE_HEIGHT) / 2f);
        statusLine = status;
        status.setOpacity(0f);
        if (threatHint) addThreatHint(panel);
        return panel;
    }

    static float easeOutCubic(float progress) {
        float inv = 1f - Math.max(0f, Math.min(1f, progress));
        return 1f - inv * inv * inv;
    }

    static List<UIComponentAPI> children(Object node) {
        return node instanceof UIPanelAPI ? ((UIPanelAPI) node).getChildrenCopy2()
                : Collections.<UIComponentAPI>emptyList();
    }

    static boolean isDescendant(Object ancestor, Object node, int maxDepth) {
        if (ancestor == node) return true;
        if (maxDepth <= 0) return false;
        for (UIComponentAPI c : children(ancestor)) {
            if (isDescendant(c, node, maxDepth - 1)) return true;
        }
        return false;
    }

    /** Current static overlay opacity in [0, 1]. */
    private float staticIntensity() {
        if (failed) return 0f;
        if (expanding || staticHoldRemaining > 0f) return 1f;
        if (staticFadeDuration > 0f && staticFadeRemaining > 0f) {
            return Math.min(1f, staticFadeRemaining / staticFadeDuration);
        }
        return 0f;
    }

    static boolean hasFailed(PreviewSubject subject, boolean inRefit) {
        return FAILED.contains(subject) || (!inRefit && NO_PREVIEW_OUTSIDE_REFIT.contains(subject));
    }

    static void clearFailures() {
        FAILED.clear();
        NO_PREVIEW_OUTSIDE_REFIT.clear();
    }

    /** Runs a throwaway scene while the game loads, so the first preview after it does not pay for building the engine. */
    static void prewarm() {
        long t0 = System.nanoTime();
        PreviewScene scene = new PreviewScene(0, new PreviewSubject(PREWARM_WEAPON_ID, null), null,
                PREWARM_HEIGHT, Opening.IMMEDIATE);
        try {
            scene.startScene();
            try {
                for (int i = 0; i < PREWARM_STEPS; i++) scene.step(MAX_STEP, false);
                scene.drawScene(0f, 0f, PREWARM_WIDTH, PREWARM_HEIGHT, null, null);
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

    /** The threat keys on one line at the bottom of the panel. */
    private void addThreatHint(CustomPanelAPI panel) {
        hintHeight = THREAT_HINT_HEIGHT;
        TooltipMakerAPI element = panel.createUIElement(panel.getPosition().getWidth() - THREAT_HINT_INDENT,
                THREAT_HINT_BOX_HEIGHT, false);
        element.setParaFont(Fonts.ORBITRON_12);
        threatHint = element.addPara("", THREAT_HINT_PAD);
        panel.addUIElement(element).inBL(THREAT_HINT_INDENT, 0f);
        writeThreatHint();
        if (staticIntensity() > 0f) threatHint.setOpacity(1f - staticIntensity());
    }

    private boolean showsThreatHint() {
        return threatHint != null && !failed && !expanding && staticHoldRemaining <= 0f;
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
        float dt = Math.min(Math.max(amount, 0f), 0.1f);
        if (expanding || staticHoldRemaining > 0f || staticFadeRemaining > 0f) {
            staticClock += dt;
        }
        if (threatHint != null && threatHintVersion != ThreatSelection.version()) writeThreatHint();
        if (expanding) {
            if (threatHint != null) threatHint.setOpacity(0f);
            return;
        }
        try {
            if (sandbox == null) {
                startScene();
                if (staticHoldRemaining > 0f) {
                    // Populate the first frame silently while full static covers the viewport.
                    step(MAX_STEP, false);
                }
            }
            if (staticHoldRemaining > 0f) {
                staticHoldRemaining = Math.max(0f, staticHoldRemaining - dt);
                if (threatHint != null) threatHint.setOpacity(0f);
                return;
            }
            if (staticFadeRemaining > 0f) {
                staticFadeRemaining = Math.max(0f, staticFadeRemaining - dt);
            }
            if (threatHint != null) threatHint.setOpacity(1f - staticIntensity());
            step(amount, true);
        } catch (NoPreviewException e) {
            fail(e.getMessage(), null);
        } catch (Throwable t) {
            fail("preview failed", t);
        }
    }

    /** Scene viewport height within the panel: grows top-down to sceneTargetHeight before revealing the threat hint below. */
    private float sceneHeight() {
        if (expanding) return Math.min(pos.getHeight(), sceneTargetHeight);
        return pos.getHeight() - hintHeight;
    }

    /** Bottom Y of the scene viewport inside the panel. */
    private float sceneY(float h) {
        if (expanding) return pos.getY() + pos.getHeight() - h;
        return pos.getY() + hintHeight;
    }

    @Override
    public void renderBelow(float alphaMult) {
        if (pos == null) return;
        float h = sceneHeight();
        float y = sceneY(h);
        HUD.drawBackground(pos.getX(), y, pos.getWidth(), h, alphaMult);
    }

    /** The scene takes the panel above the threat key line. */
    @Override
    public void render(float alphaMult) {
        if (pos == null) return;
        float x = pos.getX(), w = pos.getWidth(), h = sceneHeight(), y = sceneY(h);
        EngineSandbox.Clip activeClip = currentClip();
        boolean sceneVisible = activeClip == null || intersectsClip(x, y, w, h, activeClip);
        float staticInt = staticIntensity();
        if (sceneVisible && !expanding && staticHoldRemaining <= 0f && sandbox != null && frames > 0) {
            try {
                drawScene(x, y, w, h, arcRenderer != null ? this::drawArc : null, activeClip);
                HUD.drawOverlays(scenario, panes, paneCount, split, x, y, w, h, alphaMult);
            } catch (Throwable t) {
                fail("render failed", t);
                staticInt = 0f;
            }
        }
        if (staticInt > 0f) {
            HUD.drawStatic(x, y, w, h, staticInt, staticClock, alphaMult);
        }
        float hintAlpha = showsThreatHint() ? (1f - staticInt) * alphaMult : 0f;
        HUD.drawFrame(x, y, w, h, hintAlpha, pos.getY(), THREAT_HINT_BOX_HEIGHT, alphaMult);
    }

    private EngineSandbox.Clip currentClip() {
        if (clipSource == null) return null;
        PositionAPI cp = clipSource.getPosition();
        if (cp == null) return null;
        clip.pixelX = toPixels(cp.getX());
        clip.pixelY = toPixels(cp.getY());
        clip.pixelWidth = toPixels(cp.getWidth());
        clip.pixelHeight = toPixels(cp.getHeight());
        return clip;
    }

    private static boolean intersectsClip(float x, float y, float w, float h, EngineSandbox.Clip c) {
        int x0 = toPixels(x), y0 = toPixels(y);
        int x1 = x0 + toPixels(w), y1 = y0 + toPixels(h);
        return Math.min(x1, c.pixelX + c.pixelWidth) > Math.max(x0, c.pixelX)
                && Math.min(y1, c.pixelY + c.pixelHeight) > Math.max(y0, c.pixelY);
    }

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (disposed || failed) return;
        if (Global.getFocusManager().getAllEventsFocusOwner() instanceof TextFieldAPI) return;
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
    private void drawScene(float x, float y, float w, float h, Runnable overlay, EngineSandbox.Clip activeClip) {
        layout(x, y, w, h);
        long t0 = System.nanoTime();
        sandbox.swapIn();
        try {
            sandbox.render(panes, paneCount, PreviewHud.BACKGROUND, overlay, activeClip);
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

            // Multi-pass rendering handles views whose combined span exceeds the screen width under GraphicsLib.
            return;
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
        expanding = false;
        staticHoldRemaining = 0f;
        staticFadeRemaining = 0f;
        if (t != null) {
            FAILED.add(subject);
            Log.error("tooltip #" + tooltipId + " (" + subject + "): " + what, t);
        } else {
            if (refit == null) NO_PREVIEW_OUTSIDE_REFIT.add(subject);
            Log.info("no preview for %s: %s", subject, what);
        }
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
