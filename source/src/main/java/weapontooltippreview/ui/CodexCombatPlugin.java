package weapontooltippreview.ui;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import weapontooltippreview.Log;
import weapontooltippreview.bridge.StarfarerBridge;
import weapontooltippreview.sim.EngineSandbox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Combat-engine driver registered in {@code data/config/settings.json} so Codex previews also work on the Title Screen
 * (whose background demo runs a {@code CombatEngine}) and during Battle. Stripped from {@link EngineSandbox} so the
 * preview engine never recursively ticks this plugin.
 */
public final class CodexCombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final int MAX_ERRORS = 20;

    private final CodexWatcher codex = CodexWatcher.shared();
    private final Set<String> loggedErrors = new HashSet<>();
    private CombatEngineAPI engine;
    private boolean pausedTitleEngine;
    private int errors;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        this.pausedTitleEngine = false;
        this.errors = 0;
        this.loggedErrors.clear();
        if (!StarfarerBridge.isReady() || StarfarerBridge.codexDialogClass == null) return;
        codex.disposeAll();
        // Entering Title Screen (startup or returning from a save): clear any campaign sandbox and per-session failures.
        if (Global.getCurrentState() == GameState.TITLE) {
            PreviewScene.clearFailures();
            EngineSandbox.reset();
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (errors >= MAX_ERRORS || engine == null) return;
        if (!StarfarerBridge.isReady() || StarfarerBridge.codexDialogClass == null) return;
        GameState state = Global.getCurrentState();
        if (state != GameState.TITLE && state != GameState.COMBAT) return;
        try {
            codex.tick();
            EngineSandbox.tick();
            syncTitlePause(state);
        } catch (Throwable t) {
            errors++;
            if (loggedErrors.add(String.valueOf(t))) {
                Log.error("codex combat tick failed (" + errors + "/" + MAX_ERRORS + ")", t);
            }
            if (errors >= MAX_ERRORS) {
                Log.error("codex preview disabled in " + state + " after " + errors + " errors");
                codex.disposeAll();
                resumeTitleEngineIfPaused();
                EngineSandbox.discard("codex combat plugin disabled");
            }
        }
    }

    /**
     * TitleScreenState does not pause its background demo battle when the Codex opens. Pausing it while a Codex
     * preview is live prevents sound listener collisions and avoids advancing two combat engines at once.
     */
    private void syncTitlePause(GameState state) {
        if (state != GameState.TITLE) {
            pausedTitleEngine = false;
            return;
        }
        if (codex.isActive()) {
            if (!engine.isPaused()) {
                engine.setPaused(true);
                pausedTitleEngine = true;
            }
        } else {
            resumeTitleEngineIfPaused();
        }
    }

    private void resumeTitleEngineIfPaused() {
        if (pausedTitleEngine && engine != null) {
            engine.setPaused(false);
            pausedTitleEngine = false;
        }
    }
}
