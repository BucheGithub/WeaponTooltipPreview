package weapontooltippreview.ui;

import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.campaign.CampaignState;
import com.fs.starfarer.ui.impl.StandardTooltipV2;
import com.fs.state.AppDriver;
import weapontooltippreview.bridge.Refl;
import weapontooltippreview.bridge.StarfarerBridge;

import java.util.Collections;
import java.util.List;

/** Helpers for walking the obfuscated UI tree through its public interfaces plus a few known method names. */
final class UiTree {

    private UiTree() {
    }

    static Object currentAppState() {
        return AppDriver.getInstance().getCurrentState();
    }

    /** The campaign screen panel weapon tooltips are attached to; null outside the campaign. */
    static UIPanelAPI screenPanel(Object state) {
        if (!(state instanceof CampaignState)) return null;
        Object panel = StarfarerBridge.campaignScreenPanel.invoke(state);
        return panel instanceof UIPanelAPI ? (UIPanelAPI) panel : null;
    }

    /** True while the campaign Codex is open; previews stay out of it. */
    static boolean isCodexShowing(Object state) {
        return state instanceof CampaignState && ((CampaignState) state).isShowingCodex();
    }

    /** Live child list (no copy); do not mutate the tree while iterating it. */
    static List<?> children(Object node) {
        return childList(node, StarfarerBridge.panelChildren);
    }

    static List<?> childrenCopy(Object node) {
        return childList(node, StarfarerBridge.panelChildrenCopy);
    }

    private static List<?> childList(Object node, Refl.ReflectedMethod getChildren) {
        if (!getChildren.owner.isInstance(node)) return Collections.emptyList();
        return (List<?>) getChildren.invoke(node);
    }

    static boolean isDescendant(Object ancestor, Object node, int maxDepth) {
        if (ancestor == node) return true;
        if (maxDepth <= 0) return false;
        for (Object c : children(ancestor)) {
            if (isDescendant(c, node, maxDepth - 1)) return true;
        }
        return false;
    }

    /** Widgets come from several unrelated obfuscated classes; getParent() is looked up per class. */
    static Object parent(Object widget) {
        Refl.ReflectedMethod getParent = Refl.method(widget.getClass(), "getParent");
        return getParent != null ? getParent.invoke(widget) : null;
    }

    static float top(UIComponentAPI c) {
        PositionAPI p = c.getPosition();
        return p.getY() + p.getHeight();
    }

    /** Fade-in state of a tooltip (Fader brightness × opacity), 0 when hidden. */
    static float visibility(StandardTooltipV2 tip) {
        return tip.getOpacity() * tip.getFader().getBrightness();
    }
}
