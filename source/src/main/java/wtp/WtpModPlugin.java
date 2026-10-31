package wtp;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import wtp.bridge.ObfBridge;
import wtp.ui.PreviewController;

public class WtpModPlugin extends BaseModPlugin {

    /** The game build the obfuscated lookups were written against; others still run if the tests pass. */
    private static final String BUILT_FOR = "0.98a-RC8";

    @Override
    public void onApplicationLoad() {
        WtpSettings.load();
        String version = Global.getSettings().getVersionString();
        Log.info("loaded; game version %s", version);
        if (!BUILT_FOR.equals(version)) {
            Log.info("built for %s; running on %s, the bridge test decides whether previews are enabled", BUILT_FOR, version);
        }
        ObfBridge.init();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        WtpSettings.load();
        PreviewController.reset();
        if (!ObfBridge.isReady()) return;
        PreviewController controller = new PreviewController();
        Global.getSector().addTransientScript(controller);
        Global.getSector().getListenerManager().addListener(controller, true);
    }
}
