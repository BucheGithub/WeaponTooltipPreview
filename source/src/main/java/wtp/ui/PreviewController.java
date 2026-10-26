package wtp.ui;

import com.fs.starfarer.api.EveryFrameScript;
import wtp.Log;
import wtp.sim.PreviewSound;
import wtp.sim.SandboxHost;

import java.util.HashSet;
import java.util.Set;

/**
 * Campaign driver (transient EveryFrameScript, runs in every core tab and while paused): watches weapon tooltips,
 * frees previews whose tooltip vanished, lets the shared engine go once it has been idle and gives the sound volume
 * back after a preview. Repeated unexpected
 * errors switch the mod off for the rest of the game session.
 */
public final class PreviewController implements EveryFrameScript {

    private static final int MAX_ERRORS = 20;

    private final TooltipWatcher tooltips = new TooltipWatcher();
    private final Set<String> loggedErrors = new HashSet<>();
    private int errors;

    /** New game session: forget per-session failures and start from a fresh engine. */
    public static void reset() {
        PreviewPanel.clearFailures();
        SandboxHost.reset();
        PreviewSound.restoreNow();
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }

    @Override
    public void advance(float amount) {
        if (errors >= MAX_ERRORS) return;
        try {
            tooltips.tick();
            tooltips.reapStale();
            SandboxHost.tick();
            PreviewSound.tick();
        } catch (Throwable t) {
            errors++;
            if (loggedErrors.add(String.valueOf(t))) Log.error("tick failed (" + errors + "/" + MAX_ERRORS + ")", t);
            if (errors >= MAX_ERRORS) {
                Log.info("preview disabled after %d errors", errors);
                tooltips.disposeAll();
                SandboxHost.discard("controller disabled");
                PreviewSound.restoreNow();
            }
        }
    }
}
