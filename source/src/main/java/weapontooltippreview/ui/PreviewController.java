package weapontooltippreview.ui;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CodexEventListener;
import com.fs.starfarer.api.input.InputEventAPI;
import weapontooltippreview.Log;
import weapontooltippreview.WeaponTooltipPreviewSettings;
import weapontooltippreview.bridge.StarfarerBridge;
import weapontooltippreview.sim.EngineSandbox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Campaign driver: registers the transient tooltip and codex watchers, ticks the shared preview engine, and forwards campaign input to the active tooltip preview scene.
 */
public final class PreviewController implements EveryFrameScript, CampaignInputListener, CodexEventListener {

    private static final int MAX_ERRORS = 20;

    private final TooltipWatcher tooltips = new TooltipWatcher();
    private final CodexWatcher codex = CodexWatcher.shared();
    private final Set<String> loggedErrors = new HashSet<>();
    private int errors;

    /**
     * Resets per-session state, registers the campaign controller, and prewarms the shared preview engine while
     * previews are on. The controller is registered either way, so switching previews on mid-game works at once.
     */
    public static void onGameLoad() {
        CodexWatcher.shared().disposeAll();
        PreviewScene.clearFailures();
        EngineSandbox.reset();
        if (!StarfarerBridge.isReady()) return;
        PreviewController controller = new PreviewController();
        Global.getSector().addTransientScript(controller);
        Global.getSector().getListenerManager().addListener(controller, true);
        if (WeaponTooltipPreviewSettings.anyPreviewEnabled()) PreviewScene.prewarm();
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
            // Release whichever watcher is becoming inactive before the other acquires the shared EngineSandbox.
            if (Global.getSettings().isShowingCodex()) {
                tooltips.tick();
                codex.tick();
            } else {
                codex.tick();
                tooltips.tick();
            }
            EngineSandbox.tick();
        } catch (Throwable t) {
            errors++;
            if (loggedErrors.add(String.valueOf(t))) Log.error("tick failed (" + errors + "/" + MAX_ERRORS + ")", t);
            if (errors >= MAX_ERRORS) {
                Log.error("preview disabled after " + errors + " errors");
                tooltips.disposeAll();
                codex.disposeAll();
                EngineSandbox.discard("controller disabled");
            }
        }
    }

    @Override
    public void reportAboutToOpenCodex() {
        tooltips.disposeAll();
    }

    @Override
    public void reportClosedCodex() {
        codex.disposeAll();
    }

    @Override
    public int getListenerInputPriority() {
        return 0;
    }

    @Override
    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        try {
            tooltips.processInput(events);
        } catch (Throwable t) {
            Log.error("preview keys failed", t);
        }
    }

    @Override
    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    @Override
    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }
}
