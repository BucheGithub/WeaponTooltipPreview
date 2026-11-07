package weapontooltippreview.ui;

import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import weapontooltippreview.Log;
import weapontooltippreview.WeaponTooltipPreviewSettings;
import weapontooltippreview.scenario.NoPreviewException;
import weapontooltippreview.scenario.PreviewSubject;
import weapontooltippreview.scenario.RefitShip;
import weapontooltippreview.scenario.ThreatSelection;

import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The panel injected into a weapon or fighter LPC tooltip: steps the preview session in advance() and draws it in
 * render(). A failure ends the session, shows "Preview unavailable" and leaves that weapon's or wing's tooltips vanilla
 * from then on. Point defense previews add the threat keys on a line under the scene.
 */
final class PreviewPanel implements CustomUIPanelPlugin {

    /** The threat key line: its box and the gap above it. */
    static final float THREAT_HINT_HEIGHT = 24f;
    /** As tall as the game's own F1 hint box under the tooltip: the font's line (10) plus 8. */
    private static final float THREAT_HINT_BOX_HEIGHT = 18f;
    /** The text sits in the box as in the game's hint: 4 below the top, 7 in from the left. */
    private static final float THREAT_HINT_PAD = 4f;
    private static final float THREAT_HINT_INDENT = 7f;
    private static final long STALE_NANOS = 500_000_000L;
    private static final float BRACKET = 10f;
    /** Weapons and wings whose preview failed this game session. */
    private static final Set<PreviewSubject> FAILED = new HashSet<>();

    private final int tooltipId;
    private final PreviewSubject subject;
    private final RefitShip refit;
    private PositionAPI pos;
    private PreviewSession session;
    private UIComponentAPI statusLine;
    /** The threat key line, rewritten when a key changes it; null without the line. */
    private LabelAPI threatHint;
    private int threatHintVersion;
    /** Room under the scene taken by the threat key line. */
    private float hintHeight;
    private boolean failed;
    private boolean disposed;
    private long lastAdvanceNanos;

    PreviewPanel(int tooltipId, PreviewSubject subject, RefitShip refit) {
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

    /** The "Preview unavailable" line, hidden until a failure. */
    void setStatusLine(UIComponentAPI line) {
        statusLine = line;
        line.setOpacity(0f);
    }

    /**
     * The threat keys on one line at the bottom of {@code panel}, which has {@link #THREAT_HINT_HEIGHT} of room under
     * the scene. In the font of the game's own F1/F2 hint.
     */
    void addThreatHint(CustomPanelAPI panel) {
        hintHeight = THREAT_HINT_HEIGHT;
        TooltipMakerAPI element = panel.createUIElement(panel.getPosition().getWidth() - THREAT_HINT_INDENT,
                THREAT_HINT_BOX_HEIGHT, false);
        element.setParaFont(Fonts.ORBITRON_12);
        threatHint = element.addPara("", THREAT_HINT_PAD);
        panel.addUIElement(element).inBL(THREAT_HINT_INDENT, 0f);
        writeThreatHint();
    }

    boolean showsThreatHint() {
        return threatHint != null && !failed;
    }

    /**
     * Side by side and in the colours of the F1/F2 hint: each key and what it picked, darker where the mode leaves it
     * out.
     */
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
        lastAdvanceNanos = System.nanoTime();
        if (threatHint != null && threatHintVersion != ThreatSelection.version()) writeThreatHint();
        try {
            if (session == null) session = PreviewSession.start(subject, refit);
            session.tick(amount);
        } catch (NoPreviewException e) {
            fail(e.getMessage(), null);
        } catch (Throwable t) {
            fail("preview failed", t);
        }
    }

    @Override
    public void renderBelow(float alphaMult) {
        if (pos == null) return;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Misc.renderQuad(pos.getX(), pos.getY() + hintHeight, pos.getWidth(), pos.getHeight() - hintHeight,
                PreviewSession.BACKGROUND_COLOR, alphaMult);
        GL11.glPopAttrib();
    }

    /** The scene takes the panel above the threat key line. */
    @Override
    public void render(float alphaMult) {
        if (pos == null) return;
        float x = pos.getX(), y = pos.getY() + hintHeight, w = pos.getWidth(), h = pos.getHeight() - hintHeight;
        if (session != null && session.hasFrame()) {
            try {
                session.render(x, y, w, h, alphaMult);
            } catch (Throwable t) {
                fail("render failed", t);
            }
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LINE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        drawBrackets(x, y, w, h, alphaMult);
        if (showsThreatHint()) drawBrackets(x, pos.getY(), w, THREAT_HINT_BOX_HEIGHT, alphaMult);
        GL11.glPopAttrib();
    }

    @Override
    public void processInput(List<InputEventAPI> events) {
    }

    @Override
    public void buttonPressed(Object buttonId) {
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        endSession(false);
    }

    boolean isDisposed() {
        return disposed;
    }

    /** The tooltip stops advancing its children once hidden; that is the signal to free the engine. */
    boolean isStale(long nowNanos) {
        return lastAdvanceNanos != 0 && nowNanos - lastAdvanceNanos > STALE_NANOS;
    }

    /** {@code t == null}: an expected "cannot preview this weapon"; otherwise the engine is rebuilt for safety. */
    private void fail(String what, Throwable t) {
        failed = true;
        FAILED.add(subject);
        if (t != null) Log.error("tooltip #" + tooltipId + " (" + subject + "): " + what, t);
        else Log.info("no preview for %s: %s", subject, what);
        endSession(t != null);
        if (statusLine != null) statusLine.setOpacity(1f);
        if (threatHint != null) threatHint.setOpacity(0f);
    }

    private void endSession(boolean failure) {
        if (session == null) return;
        try {
            session.dispose(failure);
        } catch (Throwable t) {
            Log.error("ending the preview of " + subject + " failed", t);
        } finally {
            session = null;
        }
    }

    /** Corner brackets in the style of the mockup. */
    private static void drawBrackets(float x, float y, float w, float h, float alpha) {
        GL11.glLineWidth(1f);
        GL11.glColor4f(0.35f, 0.75f, 0.85f, 0.9f * alpha);
        GL11.glBegin(GL11.GL_LINES);
        float[][] corners = {{x, y, 1, 1}, {x + w, y, -1, 1}, {x, y + h, 1, -1}, {x + w, y + h, -1, -1}};
        for (float[] c : corners) {
            GL11.glVertex2f(c[0], c[1]);
            GL11.glVertex2f(c[0] + c[2] * BRACKET, c[1]);
            GL11.glVertex2f(c[0], c[1]);
            GL11.glVertex2f(c[0], c[1] + c[3] * BRACKET);
        }
        GL11.glEnd();
    }
}
