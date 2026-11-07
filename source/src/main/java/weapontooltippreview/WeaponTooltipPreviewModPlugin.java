package weapontooltippreview;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import weapontooltippreview.bridge.StarfarerBridge;
import weapontooltippreview.ui.PreviewController;

public class WeaponTooltipPreviewModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() {
        WeaponTooltipPreviewSettings.load();
        Log.info("loaded; game version %s", Global.getSettings().getVersionString());
        StarfarerBridge.init();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        WeaponTooltipPreviewSettings.load();
        PreviewController.reset();
        if (!StarfarerBridge.isReady()) return;
        PreviewController controller = new PreviewController();
        Global.getSector().addTransientScript(controller);
        Global.getSector().getListenerManager().addListener(controller, true);
    }
}
