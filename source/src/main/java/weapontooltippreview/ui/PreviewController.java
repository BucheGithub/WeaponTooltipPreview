package weapontooltippreview.ui;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;
import org.dark.shaders.util.GraphicsLibSettings;
import org.dark.shaders.util.ShaderHook;
import weapontooltippreview.Log;
import weapontooltippreview.WeaponTooltipPreviewSettings;
import weapontooltippreview.scenario.ThreatSelection;
import weapontooltippreview.sim.PreviewSound;
import weapontooltippreview.sim.SandboxHost;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Campaign driver
 * watches weapon tooltips
 * frees previews whose tooltip closed
 * handles the shared engine and sound
 * handles key presses
 */
public final class PreviewController implements EveryFrameScript, CampaignInputListener {

    private static final int MAX_ERRORS = 20;
    private static final String GRAPHICSLIB_MOD_ID = "shaderLib";

    /** GraphicsLib is optional: its classes are only touched when it is enabled. */
    private final boolean graphicsLibEnabled = Global.getSettings().getModManager().isModEnabled(GRAPHICSLIB_MOD_ID);
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
                Log.error("preview disabled after " + errors + " errors");
                tooltips.disposeAll();
                SandboxHost.discard("controller disabled");
                PreviewSound.restoreNow();
            }
        }
    }

    @Override
    public int getListenerInputPriority() {
        return 0;
    }

    @Override
    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        try {
            if (graphicsLibEnabled && tooltips.isShowingPreview()) processGraphicsLibToggleKey(events);
            if (tooltips.isShowingThreatHint()) processThreatKeys(events);
        } catch (Throwable t) {
            Log.error("preview keys failed", t);
        }
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
    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    @Override
    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }
}
