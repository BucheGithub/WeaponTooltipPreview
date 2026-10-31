package wtp.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.codex.CodexDataV2;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import wtp.Log;
import wtp.bridge.ObfBridge;
import wtp.bridge.Refl;
import wtp.scenario.PreviewSubject;
import wtp.scenario.RefitShip;
import wtp.sim.SandboxHost;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Finds live weapon and fighter LPC tooltips (direct children of the campaign screen panel while shown) and injects the
 * preview panel between the description and the "You own a total of…" line, growing the tooltip downward.
 */
final class TooltipWatcher {

    private static final String OWN_LINE_PREFIX = "You own a total";
    private static final float PREVIEW_HEIGHT = 150f;
    private static final float GAP = 6f;
    private static final float SCREEN_MARGIN = 4f;
    private static final float STATUS_LINE_HEIGHT = 20f;
    private static final int SCAN_DEPTH = 2;

    private static final class TipState {
        final int id;
        /** The weapon or wing the tooltip's current page is about; null for other tooltips. */
        PreviewSubject subject;
        boolean visible;
        /**
         * Weak: the panel's parents lead back to the tooltip, the key this state is stored under, so a strong reference
         * would keep the tooltip and the whole UI around it alive for good. The tooltip itself holds the panel.
         */
        WeakReference<CustomPanelAPI> injected;
        float desiredBottom = Float.NaN;
        PreviewPanel preview;
        /** The page index of a wing tooltip that no longer rebuilds itself (see turnPage); null for the others. */
        Refl.ReflectedField pageField;
        int page;

        TipState(int id, PreviewSubject subject) {
            this.id = id;
            this.subject = subject;
        }
    }

    private final Map<Object, TipState> states = new WeakHashMap<>();
    /** Strong references so engines are always released, even if the tooltip itself is collected first. */
    private final List<PreviewPanel> livePreviews = new ArrayList<>();
    private final Map<Object, Boolean> found = new IdentityHashMap<>();
    private int nextId = 1;

    void tick() {
        found.clear();
        Object state = UiTree.currentAppState();
        UIPanelAPI screen = UiTree.screenPanel(state);
        if (screen != null && !UiTree.isCodexShowing(state)) scan(screen, 0);
        for (Object tip : found.keySet()) handle(tip);
        for (Map.Entry<Object, TipState> e : states.entrySet()) {
            TipState s = e.getValue();
            if (s.visible && !found.containsKey(e.getKey())) {
                s.visible = false;
                disposePreview(s);
            }
        }
    }

    /** Frees previews whose panel stopped being advanced (tooltip hidden by a path we did not observe). */
    void reapStale() {
        long now = System.nanoTime();
        for (int i = livePreviews.size() - 1; i >= 0; i--) {
            PreviewPanel p = livePreviews.get(i);
            if (!p.isDisposed() && p.isStale(now)) {
                Log.info("reaping stale preview");
                p.dispose();
            }
            if (p.isDisposed()) livePreviews.remove(i);
        }
    }

    boolean isShowingPreview() {
        for (PreviewPanel p : livePreviews) {
            if (!p.isDisposed()) return true;
        }
        return false;
    }

    void disposeAll() {
        for (PreviewPanel p : livePreviews) p.dispose();
        livePreviews.clear();
    }

    private void disposePreview(TipState s) {
        if (s.preview != null) {
            s.preview.dispose();
            s.preview = null;
        }
    }

    private void scan(Object node, int depth) {
        if (ObfBridge.tooltipClass.isInstance(node)) {
            found.put(node, Boolean.TRUE);
            return;
        }
        if (depth >= SCAN_DEPTH) return;
        for (Object c : UiTree.children(node)) scan(c, depth + 1);
    }

    private void handle(Object tip) {
        TipState s = states.get(tip);
        if (s == null) {
            s = new TipState(nextId++, subjectOf(tip));
            states.put(tip, s);
        }

        boolean visibleNow = UiTree.visibility(tip) > 0.01f;
        if (visibleNow != s.visible) {
            s.visible = visibleNow;
            if (!visibleNow) disposePreview(s);
        }
        if (s.subject == null || !visibleNow) return;

        if (s.pageField != null) {
            int page = (Integer) s.pageField.get(tip);
            if (page != s.page) turnPage(tip, s, page);
        }

        CustomPanelAPI panel = s.injected != null ? s.injected.get() : null;
        if (panel == null || !UiTree.isDescendant(tip, panel, 6)) {
            if (s.injected != null) Log.info("tooltip #%d lost its preview panel (rebuilt); re-injecting", s.id);
            if (SandboxHost.isDisabled() || PreviewPanel.hasFailed(s.subject)) {
                enforcePlacement(tip, s);
                return;
            }
            inject(tip, s, null, topOf(tip));
        } else {
            enforcePlacement(tip, s);
        }
    }

    /**
     * The "cycle weapon info" key only moves a wing tooltip's page index, for the next rebuild to show, and the preview
     * stopped those (see {@link #stopRecreatingEveryFrame}). So the page is rebuilt once here, which clears the preview,
     * and the preview goes back in: the running one while the page is still about the same wing, else a new one for the
     * page's wing or weapon. The top edge stays where it was: the game nudges taller pages through align offsets, which
     * mean nothing once placeOnScreen has re-anchored the tooltip.
     */
    private void turnPage(Object tip, TipState s, int page) {
        CustomPanelAPI running = s.injected != null ? s.injected.get() : null;
        PreviewSubject previous = s.subject;
        float top = topOf(tip);
        rebuildOnce(tip);
        s.page = page;
        s.subject = subjectOf(tip);
        s.injected = null;
        Log.info("tooltip #%d turned to page %d: %s", s.id, page, s.subject);
        if (SandboxHost.isDisabled() || PreviewPanel.hasFailed(s.subject)) {
            disposePreview(s);
            if (tip instanceof UIComponentAPI) placeOnScreen((UIComponentAPI) tip, s, top, hintOverhang(tip));
            return;
        }
        boolean keepRunning = s.subject.equals(previous) && s.preview != null && !s.preview.isDisposed();
        inject(tip, s, keepRunning ? running : null, top);
    }

    /** create() only resizes and places the tooltip for its new content with the flag on. */
    private static void rebuildOnce(Object tip) {
        Object expanded = Refl.fieldNamed(tip.getClass(), "expanded").get(tip);
        Refl.call(tip, "setRecreateEveryFrame", true);
        try {
            Refl.call(tip, "create", expanded);
        } finally {
            Refl.call(tip, "setRecreateEveryFrame", false);
        }
    }

    private static float topOf(Object tip) {
        return tip instanceof UIComponentAPI ? UiTree.top((UIComponentAPI) tip) : 0f;
    }

    /**
     * Weapon tooltips hold the weapon's spec, fighter LPC tooltips the wing's. A wing tooltip's later pages describe the
     * weapons of its fighters.
     */
    private static PreviewSubject subjectOf(Object tip) {
        Refl.ReflectedField f = Refl.fieldOfType(tip.getClass(), WeaponSpecAPI.class, false);
        Object spec = f != null ? f.get(tip) : null;
        if (spec instanceof WeaponSpecAPI) return PreviewSubject.weapon(((WeaponSpecAPI) spec).getWeaponId());
        f = Refl.fieldOfType(tip.getClass(), FighterWingSpecAPI.class, false);
        spec = f != null ? f.get(tip) : null;
        if (!(spec instanceof FighterWingSpecAPI)) return null;
        FighterWingSpecAPI wing = (FighterWingSpecAPI) spec;
        String weaponId = weaponOnPage(tip, wing);
        return weaponId != null ? PreviewSubject.fighterWeapon(weaponId, wing.getId()) : PreviewSubject.wing(wing.getId());
    }

    /** The tooltip points its Codex key at what the page describes; null on the wing's own pages. */
    private static String weaponOnPage(Object tip, FighterWingSpecAPI wing) {
        Object codexEntryId = Refl.call(tip, "getCodexEntryId");
        ShipVariantAPI variant = wing.getVariant();
        for (String slotId : variant.getFittedWeaponSlots()) {
            String weaponId = variant.getWeaponId(slotId);
            if (CodexDataV2.getWeaponEntryId(weaponId).equals(codexEntryId)) return weaponId;
        }
        return null;
    }

    // ------------------------------------------------------------------ injection

    private static final class Anchor {
        final UIPanelAPI parent;
        final UIComponentAPI node;
        final String how;

        Anchor(UIPanelAPI parent, UIComponentAPI node, String how) {
            this.parent = parent;
            this.node = node;
            this.how = how;
        }
    }

    /** {@code running}: a preview panel to put back; null starts a new preview. */
    private void inject(Object tip, TipState s, CustomPanelAPI running, float originalTop) {
        stopRecreatingEveryFrame(tip, s);
        Anchor a = findLabel(tip, null, OWN_LINE_PREFIX, 8);
        if (a == null) a = belowLongestLabel(tip);
        if (a == null) {
            Log.info("tooltip #%d: no anchor found, cannot inject", s.id);
            return;
        }

        float hintBelow = hintOverhang(tip);
        UIPanelAPI parent = a.parent;
        PositionAPI pp = parent.getPosition();
        float parentLeft = pp.getX();
        float parentTop = pp.getY() + pp.getHeight();
        float anchorTop = UiTree.top(a.node);
        float shift = PREVIEW_HEIGHT + GAP;

        for (Object c : UiTree.childrenCopy(parent)) {
            if (!(c instanceof UIComponentAPI)) continue;
            PositionAPI cp = ((UIComponentAPI) c).getPosition();
            float cTop = cp.getY() + cp.getHeight();
            if (cTop <= anchorTop + 0.5f) cp.inTL(cp.getX() - parentLeft, parentTop - cTop + shift);
        }

        float x0 = a.node.getPosition().getX() - parentLeft;
        float width = Math.max(100f, pp.getWidth() - 2f * x0);
        CustomPanelAPI panel = running != null ? running : newPreview(tip, s, width);
        parent.addComponent(panel).inTL(x0, parentTop - anchorTop);
        s.injected = new WeakReference<>(panel);

        pp.setSize(pp.getWidth(), pp.getHeight() + shift);
        if (parent != tip && tip instanceof UIComponentAPI) {
            PositionAPI tp = ((UIComponentAPI) tip).getPosition();
            tp.setSize(tp.getWidth(), tp.getHeight() + shift);
        }
        Refl.ReflectedField heightField = Refl.fieldNamed(tip.getClass(), "height");
        if (heightField != null && heightField.type == float.class) {
            heightField.set(tip, (Float) heightField.get(tip) + shift);
        }

        if (tip instanceof UIComponentAPI) placeOnScreen((UIComponentAPI) tip, s, originalTop, hintBelow);
        Log.info("tooltip #%d: preview for %s injected via %s (%.0f tall, %.0f hint, screen %.0f)", s.id, s.subject, a.how,
                tip instanceof UIComponentAPI ? ((UIComponentAPI) tip).getPosition().getHeight() : 0f, hintBelow,
                Global.getSettings().getScreenHeight());
    }

    private CustomPanelAPI newPreview(Object tip, TipState s, float width) {
        // One live engine at a time: any other preview belongs to a tooltip that is gone or going.
        disposePreview(s);
        disposeAll();
        RefitShip refit = Global.getSector().getCampaignUI().getCurrentCoreTab() == CoreUITabId.REFIT
                ? RefitLookup.find(tip) : null;
        PreviewPanel preview = new PreviewPanel(s.id, s.subject, refit);
        s.preview = preview;
        livePreviews.add(preview);
        CustomPanelAPI panel = Global.getSettings().createCustom(width, PREVIEW_HEIGHT, preview);
        TooltipMakerAPI status = panel.createUIElement(width, STATUS_LINE_HEIGHT, false);
        LabelAPI line = status.addPara("Preview unavailable", Misc.getGrayColor(), 0f);
        line.setAlignment(Alignment.MID);
        panel.addUIElement(status).inTL(0f, (PREVIEW_HEIGHT - STATUS_LINE_HEIGHT) / 2f);
        preview.setStatusLine(status);
        return panel;
    }

    /**
     * How far the "F2 open Codex" line hangs below the tooltip's frame; 0 without one. The game places it under the
     * content, outside the height the frame reports, so it is measured here, before the preview moves anything.
     */
    private static float hintOverhang(Object tip) {
        Refl.ReflectedField f = Refl.fieldNamed(tip.getClass(), "expandLabel");
        Object label = f != null ? f.get(tip) : null;
        if (!(label instanceof UIComponentAPI) || !(tip instanceof UIComponentAPI) || !UiTree.children(tip).contains(label)) {
            return 0f;
        }
        PositionAPI lp = ((UIComponentAPI) label).getPosition();
        return Math.max(((UIComponentAPI) tip).getPosition().getY() - lp.getY(), lp.getHeight());
    }

    /**
     * Fighter LPC tooltips are built to recreate their content every frame (StandardTooltipV2Expandable's
     * recreateEveryFrame, set through its constructor), which clears the preview panel before it is ever drawn. The
     * preview turns that off for the tooltip it goes into, and rebuilds it once whenever its "cycle weapon info" key
     * turns the page, see {@link #turnPage}.
     */
    private static void stopRecreatingEveryFrame(Object tip, TipState s) {
        try {
            if (Boolean.TRUE.equals(Refl.call(tip, "isRecreateEveryFrame"))) {
                Refl.call(tip, "setRecreateEveryFrame", false);
                Refl.ReflectedField pageField = Refl.fieldNamed(tip.getClass(), "index");
                if (pageField != null && pageField.type == int.class) {
                    s.pageField = pageField;
                    s.page = (Integer) pageField.get(tip);
                }
                Log.info("tooltip #%d recreates its content every frame; turned off for the preview", s.id);
            }
        } catch (RuntimeException notExpandable) {
            // Not an expandable tooltip: nothing recreates it.
        }
    }

    /**
     * Keep the top edge where the game placed it and grow downward, like a native tooltip; push up only as far as
     * needed to stay on screen with {@code below} free under the frame (top-align if taller than the screen, where
     * the bottom runs off it). setYAlignOffset is not usable here: it re-anchors the tooltip to the parent's corner
     * instead of nudging it.
     */
    private static void placeOnScreen(UIComponentAPI tip, TipState s, float originalTop, float below) {
        float height = tip.getPosition().getHeight();
        float screenH = Global.getSettings().getScreenHeight();
        float bottom = Math.max(originalTop - height, SCREEN_MARGIN + below);
        if (bottom + height > screenH - SCREEN_MARGIN) bottom = screenH - SCREEN_MARGIN - height;
        s.desiredBottom = bottom;
        applyBottom(tip, bottom);
    }

    /** Re-apply our placement if the game moved the tooltip (e.g. on mouse move). */
    private static void enforcePlacement(Object tip, TipState s) {
        if (Float.isNaN(s.desiredBottom) || !(tip instanceof UIComponentAPI)) return;
        PositionAPI tp = ((UIComponentAPI) tip).getPosition();
        if (Math.abs(tp.getY() - s.desiredBottom) < 0.5f) return;
        applyBottom((UIComponentAPI) tip, s.desiredBottom);
    }

    private static void applyBottom(UIComponentAPI tip, float bottom) {
        PositionAPI tp = tip.getPosition();
        Object parent = UiTree.parent(tip);
        float px = 0f, py = 0f;
        if (parent instanceof UIComponentAPI) {
            px = ((UIComponentAPI) parent).getPosition().getX();
            py = ((UIComponentAPI) parent).getPosition().getY();
        }
        tp.inBL(tp.getX() - px, bottom - py);
    }

    private static Anchor findLabel(Object node, UIPanelAPI parent, String prefix, int depth) {
        if (node instanceof LabelAPI && node instanceof UIComponentAPI && parent != null) {
            String text = ((LabelAPI) node).getText();
            if (text != null && text.startsWith(prefix)) return new Anchor(parent, (UIComponentAPI) node, "label '" + prefix + "'");
        }
        if (depth <= 0 || !(node instanceof UIPanelAPI)) return null;
        for (Object c : UiTree.children(node)) {
            Anchor a = findLabel(c, (UIPanelAPI) node, prefix, depth - 1);
            if (a != null) return a;
        }
        return null;
    }

    /** Fallback: the sibling directly below the longest label (assumed to be the description). */
    private static Anchor belowLongestLabel(Object tip) {
        Object[] best = new Object[2]; // {label, parent}
        int[] bestLen = {0};
        longestLabel(tip, null, 8, best, bestLen);
        if (best[0] == null) return null;
        UIComponentAPI desc = (UIComponentAPI) best[0];
        UIPanelAPI parent = (UIPanelAPI) best[1];
        float descBottom = desc.getPosition().getY();
        UIComponentAPI next = null;
        for (Object c : UiTree.children(parent)) {
            if (!(c instanceof UIComponentAPI) || c == desc) continue;
            float cTop = UiTree.top((UIComponentAPI) c);
            if (cTop <= descBottom + 0.5f && (next == null || cTop > UiTree.top(next))) next = (UIComponentAPI) c;
        }
        return next == null ? null : new Anchor(parent, next, "below longest label");
    }

    private static void longestLabel(Object node, UIPanelAPI parent, int depth, Object[] best, int[] bestLen) {
        if (node instanceof LabelAPI && node instanceof UIComponentAPI && parent != null) {
            String text = ((LabelAPI) node).getText();
            if (text != null && text.length() > bestLen[0]) {
                bestLen[0] = text.length();
                best[0] = node;
                best[1] = parent;
            }
        }
        if (depth <= 0 || !(node instanceof UIPanelAPI)) return;
        for (Object c : UiTree.children(node)) longestLabel(c, (UIPanelAPI) node, depth - 1, best, bestLen);
    }
}
