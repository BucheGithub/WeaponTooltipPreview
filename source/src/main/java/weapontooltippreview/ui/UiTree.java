package weapontooltippreview.ui;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.ui.impl.StandardTooltipV2;

import java.util.Collections;
import java.util.List;

/** Helpers for walking the obfuscated UI tree through its public API interfaces. */
final class UiTree {

    private UiTree() {
    }

    /** The campaign screen panel weapon tooltips are attached to; null outside the campaign. */
    static UIPanelAPI screenPanel() {
        return Global.getCurrentState() == GameState.CAMPAIGN ? Global.getSettings().getScreenPanel() : null;
    }

    /** True while the Codex is open; previews stay out of it. */
    static boolean isCodexShowing() {
        return Global.getSettings().isShowingCodex();
    }

    /** A copy of the panel's child list, so the tree may change while it is iterated; empty for non-panels. */
    static List<UIComponentAPI> children(Object node) {
        return node instanceof UIPanelAPI ? ((UIPanelAPI) node).getChildrenCopy2() : Collections.<UIComponentAPI>emptyList();
    }

    static boolean isDescendant(Object ancestor, Object node, int maxDepth) {
        if (ancestor == node) return true;
        if (maxDepth <= 0) return false;
        for (Object c : children(ancestor)) {
            if (isDescendant(c, node, maxDepth - 1)) return true;
        }
        return false;
    }

    /** Every game widget implements UIComponentAPI; null for anything else or at the root. */
    static UIPanelAPI parent(Object widget) {
        return widget instanceof UIComponentAPI ? ((UIComponentAPI) widget).getParent() : null;
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
