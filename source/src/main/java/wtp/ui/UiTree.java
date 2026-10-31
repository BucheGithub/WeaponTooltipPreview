package wtp.ui;

import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import wtp.bridge.ObfBridge;
import wtp.bridge.Refl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Helpers for walking the obfuscated UI tree through its public interfaces plus a few known method names. */
final class UiTree {

    private static final Map<Class<?>, Refl.ReflectedMethod> CHILDREN_COPY = new HashMap<>();
    private static final Map<Class<?>, Refl.ReflectedMethod> CHILDREN_NON_COPY = new HashMap<>();
    private static final Map<Class<?>, Refl.ReflectedMethod> FADERS = new HashMap<>();
    private static final Map<Class<?>, Refl.ReflectedMethod> PARENTS = new HashMap<>();

    private UiTree() {
    }

    static Object currentAppState() {
        return ObfBridge.appDriverCurrentState.invoke(ObfBridge.appDriverGetInstance.invoke(null));
    }

    /** The campaign screen panel weapon tooltips are attached to; null outside the campaign. */
    static UIPanelAPI screenPanel(Object state) {
        if (!ObfBridge.campaignScreenPanel.owner.isInstance(state)) return null;
        Object panel = ObfBridge.campaignScreenPanel.invoke(state);
        return panel instanceof UIPanelAPI ? (UIPanelAPI) panel : null;
    }

    /** True while the campaign Codex is open; previews stay out of it. */
    static boolean isCodexShowing(Object state) {
        if (ObfBridge.campaignShowingCodex == null || !ObfBridge.campaignShowingCodex.owner.isInstance(state)) return false;
        return Boolean.TRUE.equals(ObfBridge.campaignShowingCodex.get(state));
    }

    /** Live child list (no copy); do not mutate the tree while iterating it. */
    static List<?> children(Object node) {
        return childList(node, CHILDREN_NON_COPY, "getChildrenNonCopy");
    }

    static List<?> childrenCopy(Object node) {
        return childList(node, CHILDREN_COPY, "getChildrenCopy");
    }

    private static List<?> childList(Object node, Map<Class<?>, Refl.ReflectedMethod> cache, String name) {
        if (!(node instanceof UIPanelAPI)) return Collections.emptyList();
        Refl.ReflectedMethod m = cached(cache, node.getClass(), name);
        if (m == null) return Collections.emptyList();
        Object list = m.invoke(node);
        return list instanceof List ? (List<?>) list : Collections.<Object>emptyList();
    }

    private static Refl.ReflectedMethod cached(Map<Class<?>, Refl.ReflectedMethod> cache, Class<?> cls, String name) {
        Refl.ReflectedMethod m = cache.get(cls);
        if (m == null && !cache.containsKey(cls)) {
            m = Refl.method(cls, name);
            cache.put(cls, m);
        }
        return m;
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
        Refl.ReflectedMethod getParent = cached(PARENTS, widget.getClass(), "getParent");
        return getParent != null ? getParent.invoke(widget) : null;
    }

    static float top(UIComponentAPI c) {
        PositionAPI p = c.getPosition();
        return p.getY() + p.getHeight();
    }

    /** Fade-in state of a widget (Fader brightness × opacity), 0 when hidden. */
    static float visibility(Object widget) {
        float opacity = widget instanceof UIComponentAPI ? ((UIComponentAPI) widget).getOpacity() : 1f;
        Refl.ReflectedMethod getFader = cached(FADERS, widget.getClass(), "getFader");
        if (getFader == null) return opacity;
        Object fader = getFader.invoke(widget);
        return fader != null ? opacity * (Float) Refl.call(fader, "getBrightness") : opacity;
    }
}
