package wtp.ui;

import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import org.lwjgl.opengl.GL11;
import wtp.Log;
import wtp.scenario.NoPreviewException;
import wtp.scenario.PreviewSubject;
import wtp.scenario.RefitShip;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The panel injected into a weapon or fighter LPC tooltip: steps the preview session in advance() and draws it in
 * render(). A failure ends the session, shows "Preview unavailable" and leaves that weapon's or wing's tooltips vanilla
 * from then on.
 */
final class PreviewPanel implements CustomUIPanelPlugin {

    private static final long STALE_NANOS = 500_000_000L;
    private static final float MAX_STEP = 1f / 30f;
    private static final float BRACKET = 10f;
    /** Weapons and wings whose preview failed this game session. */
    private static final Set<PreviewSubject> FAILED = new HashSet<>();

    private final int tooltipId;
    private final PreviewSubject subject;
    private final RefitShip refit;
    private PositionAPI pos;
    private PreviewSession session;
    private UIComponentAPI statusLine;
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

    @Override
    public void positionChanged(PositionAPI position) {
        pos = position;
    }

    @Override
    public void advance(float amount) {
        if (disposed || failed || pos == null) return;
        lastAdvanceNanos = System.nanoTime();
        try {
            if (session == null) session = PreviewSession.start(subject, refit);
            session.tick(Math.min(amount, MAX_STEP));
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
        GL11.glColor4f(0.02f, 0.035f, 0.055f, alphaMult);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(pos.getX(), pos.getY());
        GL11.glVertex2f(pos.getX() + pos.getWidth(), pos.getY());
        GL11.glVertex2f(pos.getX() + pos.getWidth(), pos.getY() + pos.getHeight());
        GL11.glVertex2f(pos.getX(), pos.getY() + pos.getHeight());
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    @Override
    public void render(float alphaMult) {
        if (pos == null) return;
        float x = pos.getX(), y = pos.getY(), w = pos.getWidth(), h = pos.getHeight();
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
