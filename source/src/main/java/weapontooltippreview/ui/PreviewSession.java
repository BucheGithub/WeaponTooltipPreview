package weapontooltippreview.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;
import weapontooltippreview.Log;
import weapontooltippreview.WeaponTooltipPreviewSettings;
import weapontooltippreview.scenario.NoPreviewException;
import weapontooltippreview.scenario.PreviewSubject;
import weapontooltippreview.scenario.RefitShip;
import weapontooltippreview.scenario.Scenario;
import weapontooltippreview.scenario.WeaponHost;
import weapontooltippreview.sim.CombatArcRenderer;
import weapontooltippreview.sim.EngineSandbox;

import java.awt.Color;

/**
 * One preview: a scene in the shared sandbox engine, stepped from the panel's advance() and drawn by the sandbox onto
 * the panel's part of the screen. Long ranges switch to a split view: two close-up panes (weapon host, target) at a
 * readable zoom, a slash marking the broken axis between them, and a full-scale strip showing the shots travelling.
 */
final class PreviewSession {

    private static final float[] BACKGROUND = {0.02f, 0.035f, 0.055f};
    /** The scene's clear colour, for the panel's backing before the first frame. */
    static final Color BACKGROUND_COLOR = new Color(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2]);
    private static final Color STRIP_COLOR = new Color(BACKGROUND[0] * 0.6f, BACKGROUND[1] * 0.6f, BACKGROUND[2] * 0.6f);
    private static final Color WEAPON_HOST_MARKER = new Color(0.55f, 0.9f, 0.55f);
    private static final Color TARGET_MARKER = new Color(0.95f, 0.45f, 0.35f);
    private static final Color SHOT_BLIP = new Color(1f, 0.9f, 0.5f);
    private static final float MAX_STEP = 1f / 30f;
    private static final Vector2f FAR_AWAY = new Vector2f(1e7f, 1e7f);
    private static final float GAP_UI = 22f;
    private static final float STRIP_UI = 16f;
    private static final int MAX_BLIPS = 64;
    private static final int LOG_EVERY_FRAMES = 300;
    /** The throwaway scene of prewarm(): a stock weapon, on a generic host, against the player's dummy. */
    private static final String PREWARM_WEAPON_ID = "lightac";
    private static final int PREWARM_STEPS = 10;
    /** About a tooltip's preview, at the bottom left of the screen; the engine's render puts the screen back. */
    private static final float PREWARM_WIDTH = 400f, PREWARM_HEIGHT = 150f;
    /** Shared HUD renderer: keeps sprites, wing icons and text buffers warm across hovers. */
    private static final PreviewHud HUD = new PreviewHud();

    private static final class Pane extends EngineSandbox.View {
        float uiX, uiY, uiW, uiH;
    }

    private final PreviewSubject subject;
    private EngineSandbox sandbox;
    private Scenario scenario;
    private final Pane[] panes = {new Pane(), new Pane()};
    private int paneCount;
    private boolean split;
    private final float[] blips = new float[MAX_BLIPS];
    private int blipCount;
    private float weaponHostX, targetX, rangeEndX;
    /** The game's in-combat arc renderer; null if it could not be set up, in which case no arc is drawn. */
    private CombatArcRenderer arcRenderer;
    private boolean arcUnavailable = !CombatArcRenderer.isAvailable();
    /** The dummy the arc renderer was made for. */
    private ShipAPI arcTarget;
    private long frames;
    private long renders;
    private double simMs, renderMs;
    private boolean disposed;

    private PreviewSession(PreviewSubject subject) {
        this.subject = subject;
    }

    /**
     * Takes the shared engine and builds the weapon's or wing's scene in it. One that cannot be shown leaves the engine
     * intact ({@link NoPreviewException}); any other failure discards the engine, which may be in a bad state.
     */
    static PreviewSession start(PreviewSubject subject, RefitShip refit) {
        long t0 = System.nanoTime();
        PreviewSession session = new PreviewSession(subject);
        EngineSandbox sandbox = EngineSandbox.acquire();
        session.sandbox = sandbox;
        try {
            sandbox.swapIn();
            try {
                session.scenario = Scenario.spawn(sandbox.api(), subject, refit, sandbox.beginScene());
                session.rearmArcRenderer();
            } finally {
                sandbox.swapOut();
            }
        } catch (NoPreviewException e) {
            EngineSandbox.release();
            throw e;
        } catch (RuntimeException | Error e) {
            EngineSandbox.discard("scene setup failed for " + subject);
            throw e;
        }
        Log.info("session for %s started in %.1f ms (scene %d of this engine)", subject, (System.nanoTime() - t0) / 1e6,
                sandbox.scenes());
        return session;
    }

    /**
     * Runs a throwaway scene while a game loads, so the first preview after it does not pay for building the engine,
     * running its code for the first time, or the textures GraphicsLib loads on first draw. The steps are undirected, so
     * nothing fires, and only the engine's render is drawn, which leaves the screen as it was. Never throws.
     */
    static void prewarm() {
        long t0 = System.nanoTime();
        try {
            PreviewSession session = start(new PreviewSubject(PREWARM_WEAPON_ID, null), null);
            try {
                for (int i = 0; i < PREWARM_STEPS; i++) session.step(MAX_STEP, false);
                session.drawScene(0f, 0f, PREWARM_WIDTH, PREWARM_HEIGHT, null);
            } catch (RuntimeException | Error e) {
                session.dispose(true);
                throw e;
            }
            session.dispose(false);
            PreviewHud.loadFont();
            Log.info("preview engine prewarmed in %.0f ms", (System.nanoTime() - t0) / 1e6);
        } catch (Throwable t) {
            Log.error("prewarming the preview engine failed; the first preview builds it instead", t);
        }
    }

    /**
     * (Re)creates the game's arc renderer for the weapon's group. Once a weapon runs dry the renderer fades its arc
     * out and only fades in again on a fresh group selection, so every new setup (a new dummy) gets a new renderer.
     * Swapped in only.
     */
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

    boolean hasFrame() {
        return frames > 0;
    }

    /** Simulation step. Called from the panel's advance(). */
    void tick(float dt) {
        step(dt, true);
    }

    /**
     * {@code directed}: the scenario directs the step (aim, fire, the dummy's moves and launches) and the scene is heard.
     * Undirected, nothing fires, and the listener stays far away.
     */
    private void step(float dt, boolean directed) {
        sandbox.swapIn();
        try {
            applySound(directed && WeaponTooltipPreviewSettings.sound());
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
                    split ? "split" : "single", simMs / frames, renders > 0 ? renderMs / renders : 0, scenario.frameLogDetails());
        }
    }

    /**
     * Puts the sound listener at the scene, where the preview plays at the player's sound effects volume, or far away,
     * which mutes the scene.
     */
    private void applySound(boolean audible) {
        Global.getSoundPlayer().setListenerPosOverrideOneFrame(audible ? scenario.listenerPosition() : FAR_AWAY);
    }

    /** Draws the preview into the panel's UI rectangle. Called from the panel's render(). */
    void render(float x, float y, float w, float h, float alpha) {
        drawScene(x, y, w, h, arcRenderer != null ? this::drawArc : null);
        if (split) drawSplitDecorations(x, y, w, h, alpha);
        drawHud(alpha);
    }

    /** The engine's picture of the scene in the UI rectangle, then {@code overlay} in each pane (null for none). */
    private void drawScene(float x, float y, float w, float h, Runnable overlay) {
        layout(x, y, w, h);
        long t0 = System.nanoTime();
        sandbox.swapIn();
        try {
            sandbox.render(panes, paneCount, BACKGROUND, overlay);
            if (split) collectStripMarkers();
        } finally {
            sandbox.swapOut();
        }
        renderMs += (System.nanoTime() - t0) / 1e6;
        renders++;
    }

    /**
     * Under the weapon host (first pane): the weapon's readout, or the carrier's fighter wings. The dummy's in the
     * corner of the pane showing it.
     */
    private void drawHud(float alpha) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LINE_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            Pane weaponHostPane = panes[0];
            WeaponHost weaponHost = scenario.weaponHost();
            if (weaponHost.weapon() != null) {
                HUD.drawWeapon(weaponHost.weapon(), weaponHostPane.uiX, weaponHostPane.uiY, weaponHostPane.uiW,
                        weaponHostPane.uiH, alpha);
            } else {
                HUD.drawWings(weaponHost.ship(), weaponHostPane.uiX, weaponHostPane.uiY, weaponHostPane.uiW,
                        weaponHostPane.uiH, alpha);
            }
            ShipAPI target = scenario.targetDummy().ship();
            if (target.isAlive()) {
                Pane p = panes[paneCount - 1];
                HUD.drawTarget(target, p.uiX, p.uiY, p.uiW, p.uiH, alpha);
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    /** Gives the engine back: normally it waits for the next preview; after a failure it is discarded and rebuilt. */
    void dispose(boolean failed) {
        if (disposed) return;
        disposed = true;
        HUD.clearTarget();
        if (failed) EngineSandbox.discard("preview of " + subject + " failed");
        else EngineSandbox.release();
        Log.info("session for %s ended after %d frames (avg sim %.2f ms, render %.2f ms); %s", subject, frames,
                frames > 0 ? simMs / frames : 0, renders > 0 ? renderMs / renders : 0, Log.heap());
    }

    // ------------------------------------------------------------------ layout

    private void layout(float x, float y, float w, float h) {
        Scenario s = scenario;
        float span = s.frameMaxX - s.frameMinX;
        float paneW = (w - GAP_UI) / 2f;
        float paneH = h - STRIP_UI;
        float paneWorldH = 2f * s.frameHalfHeight;
        float paneWorldW = paneWorldH * paneW / paneH;
        split = s.canSplit && 2f * paneWorldW < span;

        if (split) {
            paneCount = 2;
            Pane left = panes[0];
            left.uiX = x;
            left.uiY = y + STRIP_UI;
            left.uiW = paneW;
            left.uiH = paneH;
            left.worldWidth = paneWorldW;
            left.worldHeight = paneWorldH;
            left.worldX = s.frameMinX;
            left.worldY = s.weaponHost().ship().getLocation().y - paneWorldH / 2f;
            placeInPixels(left);

            Pane right = panes[1];
            right.uiX = x + w - paneW;
            right.uiY = y + STRIP_UI;
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

    /** Sets the pane's rectangle of screen pixels from its UI rectangle. */
    private static void placeInPixels(Pane p) {
        p.pixelX = toPixels(p.uiX);
        p.pixelY = toPixels(p.uiY);
        p.pixelWidth = toPixels(p.uiW);
        p.pixelHeight = toPixels(p.uiH);
    }

    /** World x of the weapon host, target, range end and every shot of ours in flight, for the full-scale strip. */
    private void collectStripMarkers() {
        WeaponAPI weapon = scenario.weaponHost().weapon();
        weaponHostX = scenario.weaponHost().ship().getLocation().x;
        targetX = scenario.targetDummy().ship().getLocation().x;
        rangeEndX = weapon.getLocation().x + weapon.getRange();
        blipCount = 0;
        for (DamagingProjectileAPI p : sandbox.api().getProjectiles()) {
            if (p.getWeapon() != weapon || blipCount == MAX_BLIPS) continue;
            blips[blipCount++] = p.getLocation().x;
        }
    }

    // ------------------------------------------------------------------ split-view decorations (UI coordinates)

    private void drawSplitDecorations(float x, float y, float w, float h, float alpha) {
        float gapX = x + (w - GAP_UI) / 2f;
        float paneBottom = y + STRIP_UI;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LINE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Gap and strip background.
        Misc.renderQuad(gapX, paneBottom, GAP_UI, h - STRIP_UI, STRIP_COLOR, alpha);
        Misc.renderQuad(x, y, w, STRIP_UI, STRIP_COLOR, alpha);

        // Full-scale strip: baseline, range end tick, weapon host and target markers, shot blips.
        float pad = 6f;
        float sx0 = x + pad, sx1 = x + w - pad, sy = y + STRIP_UI / 2f;
        float minX = scenario.frameMinX, spanX = scenario.frameMaxX - scenario.frameMinX;
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1f);
        GL11.glColor4f(0.45f, 0.85f, 1f, 0.35f * alpha);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(sx0, sy);
        GL11.glVertex2f(sx1, sy);
        float rx = sx0 + (rangeEndX - minX) / spanX * (sx1 - sx0);
        GL11.glColor4f(0.45f, 0.85f, 1f, 0.8f * alpha);
        GL11.glVertex2f(rx, sy - 4f);
        GL11.glVertex2f(rx, sy + 4f);
        GL11.glEnd();

        // Broken axis: one clean slash across the gap, and the same on the strip where a shot leaves the weapon host
        // pane and enters the target pane.
        GL11.glLineWidth(1.5f);
        GL11.glColor4f(0.45f, 0.85f, 1f, 0.6f * alpha);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(gapX + 4f, paneBottom + 4f);
        GL11.glVertex2f(gapX + GAP_UI - 4f, y + h - 4f);
        for (float edge : new float[]{panes[0].worldX + panes[0].worldWidth, panes[1].worldX}) {
            float ex = sx0 + (edge - minX) / spanX * (sx1 - sx0);
            GL11.glVertex2f(ex - 2.5f, sy - 5f);
            GL11.glVertex2f(ex + 2.5f, sy + 5f);
        }
        GL11.glEnd();

        float hx = sx0 + (weaponHostX - minX) / spanX * (sx1 - sx0);
        Misc.renderQuad(hx - 2f, sy - 2f, 4f, 4f, WEAPON_HOST_MARKER, alpha);
        float tx = sx0 + (targetX - minX) / spanX * (sx1 - sx0);
        Misc.renderQuad(tx - 2.5f, sy - 2.5f, 5f, 5f, TARGET_MARKER, alpha);
        for (int i = 0; i < blipCount; i++) {
            float bx = sx0 + (blips[i] - minX) / spanX * (sx1 - sx0);
            Misc.renderQuad(bx - 1f, sy - 1f, 2f, 2f, SHOT_BLIP, alpha);
        }
        GL11.glPopAttrib();
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
}
