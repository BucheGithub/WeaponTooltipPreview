package weapontooltippreview.ui;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.input.InputEventAPI;
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
import com.fs.starfarer.ui.impl.StandardTooltipV2;
import com.fs.starfarer.ui.impl.StandardTooltipV2Expandable;
import weapontooltippreview.Log;
import weapontooltippreview.WeaponTooltipPreviewSettings;
import weapontooltippreview.bridge.Refl;
import weapontooltippreview.bridge.StarfarerBridge;
import weapontooltippreview.scenario.PreviewSubject;
import weapontooltippreview.scenario.RefitShip;
import weapontooltippreview.scenario.ThreatSelection;
import weapontooltippreview.sim.EngineSandbox;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Finds live weapon and fighter LPC tooltips and injects the preview panel between the description and the weapon or wing data, growing the tooltip downward. */
final class TooltipWatcher {

    private static final float PREVIEW_HEIGHT = 150f;
    private static final float GAP = 6f;
    private static final float SCREEN_MARGIN = 4f;
    private static final float STATUS_LINE_HEIGHT = 20f;
    private static final float EXPAND_DURATION = 0.18f;
    private static final float MIN_EXPAND_HEIGHT = 2f;
    private static final int SCAN_DEPTH = 2;

    /** Expansion state for an injected preview panel. All layout mutations use incremental deltas so other tooltip mods stay intact. */
    private static final class InjectionLayout {
        final WeakReference<UIPanelAPI> parent;
        final float originalTop;
        final float hintBelow;
        final float targetPanelHeight;
        final float targetShift;
        float progress;
        float appliedShift;

        InjectionLayout(UIPanelAPI parent, float originalTop, float hintBelow,
                        float targetPanelHeight, float targetShift, float progress) {
            this.parent = new WeakReference<>(parent);
            this.originalTop = originalTop;
            this.hintBelow = hintBelow;
            this.targetPanelHeight = targetPanelHeight;
            this.targetShift = targetShift;
            this.progress = progress;
        }
    }

    private static final class TipState {
        final int id;
        /** The weapon or wing the tooltip's current page is about; null for other tooltips. */
        PreviewSubject subject;
        boolean visible;
        /** Seconds hovered while visible on the current hover; resets on hide. */
        float hoverElapsed;
        /** True once this hover has passed the delay and opened its preview. */
        boolean opened;
        /** Weak: the panel's parents lead back to the tooltip, the key this state is stored under, so a strong reference would keep the tooltip and the whole UI around it alive for good. The tooltip itself holds the panel. */
        WeakReference<CustomPanelAPI> injected;
        InjectionLayout layout;
        float originalTop = Float.NaN;
        float lastKnownTipHeight = Float.NaN;
        float desiredBottom = Float.NaN;
        PreviewScene preview;
        /** The page index of a wing tooltip that no longer rebuilds itself (see turnPage); null for the others. */
        Refl.ReflectedField pageField;
        int page;
        boolean stoppedRecreateEveryFrame;

        TipState(int id, PreviewSubject subject) {
            this.id = id;
            this.subject = subject;
        }
    }

    private final Map<UIComponentAPI, TipState> states = new WeakHashMap<>();
    private final List<StandardTooltipV2> found = new ArrayList<>();
    /** Strong reference to the running preview so the engine is always released even if the tooltip is collected. */
    private PreviewScene activePreview;
    private long lastTickNanos;
    private int nextId = 1;

    void tick() {
        long now = System.nanoTime();
        float dt = lastTickNanos == 0L ? 0f : Math.min(0.1f, (now - lastTickNanos) / 1e9f);
        lastTickNanos = now;

        found.clear();
        if (Global.getCurrentState() == GameState.CAMPAIGN && !Global.getSettings().isShowingCodex()) {
            UIPanelAPI screen = Global.getSettings().getScreenPanel();
            if (screen != null) scan(screen, 0);
        }
        boolean activeStillVisible = false;
        for (StandardTooltipV2 tip : found) {
            TipState s = handle(tip, dt);
            if (s.visible && s.preview != null && s.preview == activePreview) activeStillVisible = true;
        }
        for (Map.Entry<UIComponentAPI, TipState> e : states.entrySet()) {
            TipState s = e.getValue();
            if (s.visible && !found.contains(e.getKey())) {
                onHide(e.getKey(), s);
            }
        }
        if (activePreview != null && !activeStillVisible) {
            disposeActive();
        }
    }

    void processInput(List<InputEventAPI> events) {
        if (activePreview != null && !activePreview.isDisposed()) activePreview.processInput(events);
    }

    void disposeAll() {
        disposeActive();
    }

    private void disposeActive() {
        if (activePreview != null) {
            activePreview.dispose();
            activePreview = null;
        }
    }

    private void disposePreview(TipState s) {
        if (s.preview != null) {
            if (s.preview == activePreview) activePreview = null;
            s.preview.dispose();
            s.preview = null;
        }
    }

    private void onHide(UIComponentAPI tip, TipState s) {
        s.visible = false;
        s.hoverElapsed = 0f;
        s.opened = false;
        disposePreview(s);
        uninject(tip, s);
    }

    private static void uninject(UIComponentAPI tip, TipState s) {
        InjectionLayout layout = s.layout;
        CustomPanelAPI panel = s.injected != null ? s.injected.get() : null;
        s.layout = null;
        s.injected = null;
        s.originalTop = Float.NaN;
        s.lastKnownTipHeight = Float.NaN;
        s.desiredBottom = Float.NaN;

        if (layout != null) {
            UIPanelAPI parent = layout.parent.get();
            if (parent != null && panel != null && isDescendant(parent, panel, 1)) {
                float panelTop = top(panel);
                parent.removeComponent(panel);
                applyLayoutDelta(tip, parent, null, panelTop, -layout.appliedShift, 0f);
            }
        }
        if (s.stoppedRecreateEveryFrame) {
            s.stoppedRecreateEveryFrame = false;
            s.pageField = null;
            if (tip instanceof StandardTooltipV2Expandable) {
                ((StandardTooltipV2Expandable) tip).setRecreateEveryFrame(true);
            }
        }
    }

    private void scan(Object node, int depth) {
        if (node instanceof StandardTooltipV2) {
            StandardTooltipV2 tip = (StandardTooltipV2) node;
            if (!found.contains(tip)) found.add(tip);
            return;
        }
        if (depth >= SCAN_DEPTH) return;
        for (Object c : children(node)) scan(c, depth + 1);
    }

    private TipState handle(StandardTooltipV2 tip, float dt) {
        TipState s = states.get(tip);
        if (s == null) {
            s = new TipState(nextId++, subjectOf(tip));
            states.put(tip, s);
        }

        boolean visibleNow = tip.getOpacity() * tip.getFader().getBrightness() > 0.01f;
        if (visibleNow != s.visible) {
            if (!visibleNow) {
                onHide(tip, s);
            } else {
                s.visible = true;
                s.hoverElapsed = 0f;
                s.opened = false;
            }
        }
        if (s.subject == null || !visibleNow) return s;

        if (s.pageField != null) {
            int page = (Integer) s.pageField.get(tip);
            if (page != s.page) turnPage(tip, s, page);
        }

        CustomPanelAPI panel = s.injected != null ? s.injected.get() : null;
        if (panel == null || !isDescendant(tip, panel, 6)) {
            if (s.injected != null) {
                Log.info("tooltip #%d lost its preview panel (rebuilt); re-injecting", s.id);
                s.injected = null;
                s.layout = null;
            }
            if (!s.opened) {
                // While waiting during the delay, an LPC tooltip may still cycle pages on its own.
                s.subject = subjectOf(tip);
                if (s.subject == null || noPreviewFor(s.subject)) return s;
                if (WeaponTooltipPreviewSettings.delayEnabled()) {
                    float delay = WeaponTooltipPreviewSettings.previewDelay();
                    s.hoverElapsed += dt;
                    if (s.hoverElapsed < delay) return s;
                    s.opened = true;
                    inject(tip, s, null, top(tip), PreviewScene.Opening.EXPAND_STATIC);
                    return s;
                }
                s.opened = true;
                inject(tip, s, null, top(tip), PreviewScene.Opening.IMMEDIATE);
                return s;
            }
            if (noPreviewFor(s.subject)) {
                enforcePlacement(tip, s);
                return s;
            }
            inject(tip, s, null, top(tip), PreviewScene.Opening.IMMEDIATE);
        } else if (s.layout != null && s.layout.progress < 1f) {
            advanceExpansion(tip, s, panel, dt);
        } else {
            enforcePlacement(tip, s);
        }
        return s;
    }

    /**
     * The "cycle weapon info" key only moves a wing tooltip's page index, as the preview logic stops RecreatingEveryFrame, it is recreated manually. */
    private void turnPage(UIComponentAPI tip, TipState s, int page) {
        CustomPanelAPI running = s.injected != null ? s.injected.get() : null;
        boolean fullyExpanded = s.layout == null || s.layout.progress >= 1f;
        PreviewSubject previous = s.subject;
        float top = top(tip);
        rebuildOnce((StandardTooltipV2Expandable) tip);
        s.page = page;
        s.subject = subjectOf(tip);
        s.injected = null;
        s.layout = null;
        Log.info("tooltip #%d turned to page %d: %s", s.id, page, s.subject);
        if (noPreviewFor(s.subject)) {
            disposePreview(s);
            placeOnScreen(tip, s, top, hintOverhang(tip));
            return;
        }
        boolean keepRunning = fullyExpanded && s.subject.equals(previous) && s.preview != null && !s.preview.isDisposed();
        PreviewScene.Opening opening = keepRunning ? PreviewScene.Opening.IMMEDIATE
                : WeaponTooltipPreviewSettings.delayEnabled() ? PreviewScene.Opening.STATIC_FLICKER
                : PreviewScene.Opening.IMMEDIATE;
        inject(tip, s, keepRunning ? running : null, top, opening);
    }

    /** create() only resizes and places the tooltip for its new content with the flag on. */
    private static void rebuildOnce(StandardTooltipV2Expandable tip) {
        boolean expanded = Boolean.TRUE.equals(StarfarerBridge.tooltipExpanded.get(tip));
        tip.setRecreateEveryFrame(true);
        try {
            tip.create(expanded);
        } finally {
            tip.setRecreateEveryFrame(false);
        }
    }

    /** Weapon tooltips hold the weapon's spec, fighter LPC tooltips the wing's. A wing tooltip's later pages describe the weapons of its fighters. */
    private static PreviewSubject subjectOf(Object tip) {
        Refl.ReflectedField f = Refl.fieldOfType(tip.getClass(), WeaponSpecAPI.class, false);
        Object spec = f != null ? f.get(tip) : null;
        if (spec instanceof WeaponSpecAPI) return new PreviewSubject(((WeaponSpecAPI) spec).getWeaponId(), null);
        f = Refl.fieldOfType(tip.getClass(), FighterWingSpecAPI.class, false);
        spec = f != null ? f.get(tip) : null;
        if (!(spec instanceof FighterWingSpecAPI)) return null;
        FighterWingSpecAPI wing = (FighterWingSpecAPI) spec;
        return new PreviewSubject(weaponOnPage(tip), wing.getId());
    }

    /** Previews switched off in the settings, the engine given up on, or this weapon's or wing's preview failed. */
    private static boolean noPreviewFor(PreviewSubject subject) {
        return !WeaponTooltipPreviewSettings.tooltipPreviewsEnabled() || EngineSandbox.isDisabled()
                || PreviewScene.hasFailed(subject, inRefit());
    }

    /** The page's weapon. */
    private static String weaponOnPage(Object tip) {
        Refl.ReflectedField pageField = pageField(tip);
        Refl.ReflectedField weaponsField = Refl.fieldOfType(tip.getClass(), List.class, false);
        if (pageField == null || weaponsField == null) return null;
        int page = (Integer) pageField.get(tip);
        List<?> weapons = (List<?>) weaponsField.get(tip);
        if (page < 1 || page > weapons.size()) return null;
        Object weapon = weapons.get(page - 1);
        return weapon instanceof WeaponSpecAPI ? ((WeaponSpecAPI) weapon).getWeaponId() : null;
    }

    /** The page index of a tooltip with pages (fighter LPC tooltips); null for the others. */
    private static Refl.ReflectedField pageField(Object tip) {
        Refl.ReflectedField f = Refl.fieldNamed(tip.getClass(), "index");
        return f != null && f.type == int.class ? f : null;
    }

    // ------------------------------------------------------------------ injection

    /** running: a preview panel to put back; null starts a new preview. */
    private void inject(UIComponentAPI tip, TipState s, CustomPanelAPI running, float originalTop,
                        PreviewScene.Opening opening) {
        stopRecreatingEveryFrame(tip, s);
        // The preview goes at the bottom of the description segment, above the data.
        UIPanelAPI parent = (UIPanelAPI) StarfarerBridge.tooltipContents.invoke(tip);
        UIComponentAPI data = firstNonLabel(parent);
        if (data == null) {
            Log.info("tooltip #%d: no data below the description, cannot inject", s.id);
            return;
        }

        float hintBelow = hintOverhang(tip);
        PositionAPI pp = parent.getPosition();
        float parentLeft = pp.getX();
        float parentTop = pp.getY() + pp.getHeight();
        float anchorTop = top(data);
        float x0 = data.getPosition().getX() - parentLeft;
        float width = Math.max(100f, pp.getWidth() - 2f * x0);
        float panelYFromTop = parentTop - anchorTop;

        CustomPanelAPI panel = running != null ? running : newPreview(tip, s, width, opening);
        float targetPanelH = panel.getPosition().getHeight();
        float targetShift = targetPanelH + GAP;
        boolean animate = opening == PreviewScene.Opening.EXPAND_STATIC && running == null;

        InjectionLayout layout = new InjectionLayout(parent, originalTop, hintBelow, targetPanelH, targetShift,
                animate ? 0f : 1f);

        parent.addComponent(panel).inTL(x0, panelYFromTop);
        s.injected = new WeakReference<>(panel);
        s.layout = layout;
        applyExpansion(tip, s, panel, layout, layout.progress);

        Log.info("tooltip #%d: preview for %s injected above %s (%.0f tall, %.0f hint, screen %.0f, %s)", s.id,
                s.subject, data.getClass().getSimpleName(), tip.getPosition().getHeight() + (targetShift - layout.appliedShift),
                hintBelow, Global.getSettings().getScreenHeight(), opening);
    }

    private static void advanceExpansion(UIComponentAPI tip, TipState s, CustomPanelAPI panel, float dt) {
        InjectionLayout layout = s.layout;
        layout.progress = Math.min(1f, layout.progress + dt / EXPAND_DURATION);
        applyExpansion(tip, s, panel, layout, layout.progress);
        if (layout.progress >= 1f && s.preview != null) {
            s.preview.finishExpanding();
        }
    }

    private static void applyExpansion(UIComponentAPI tip, TipState s, CustomPanelAPI panel,
                                       InjectionLayout layout, float progress) {
        UIPanelAPI parent = layout.parent.get();
        if (parent == null) return;
        float t = Math.max(0f, Math.min(1f, progress));
        float inv = 1f - t;
        float ease = 1f - inv * inv * inv;
        float nextShift = t >= 1f ? layout.targetShift : Math.max(MIN_EXPAND_HEIGHT, layout.targetShift * ease);
        float nextPanelH = t >= 1f ? layout.targetPanelHeight
                : Math.max(MIN_EXPAND_HEIGHT, Math.min(layout.targetPanelHeight, nextShift - GAP * ease));
        float delta = nextShift - layout.appliedShift;
        layout.appliedShift = nextShift;

        applyLayoutDelta(tip, parent, panel, top(panel), delta, nextPanelH);
        placeOnScreen(tip, s, layout.originalTop, layout.hintBelow);
    }

    /**
     * Adjusts lower tooltip children and parent/tip height by an incremental delta, preserving any heights or offsets
     * added by other tooltip mods. When panel is non-null, its height is also updated in place from its current top edge.
     */
    private static void applyLayoutDelta(UIComponentAPI tip, UIPanelAPI parent, CustomPanelAPI panel,
                                         float splitTop, float delta, float panelHeight) {
        PositionAPI pp = parent.getPosition();
        float parentLeft = pp.getX();
        float parentTop = pp.getY() + pp.getHeight();
        float panelX = 0f, panelYFromTop = 0f;
        if (panel != null) {
            PositionAPI panelPos = panel.getPosition();
            panelX = panelPos.getX() - parentLeft;
            panelYFromTop = parentTop - (panelPos.getY() + panelPos.getHeight());
        }

        if (Math.abs(delta) >= 0.001f) {
            for (Object c : children(parent)) {
                if (!(c instanceof UIComponentAPI) || c == panel) continue;
                PositionAPI cp = ((UIComponentAPI) c).getPosition();
                float cTop = cp.getY() + cp.getHeight();
                if (cTop <= splitTop + 0.5f) {
                    cp.inTL(cp.getX() - parentLeft, Math.max(0f, parentTop - cTop + delta));
                }
            }
            pp.setSize(pp.getWidth(), Math.max(10f, pp.getHeight() + delta));
            if (parent != tip) {
                PositionAPI tp = tip.getPosition();
                tp.setSize(tp.getWidth(), Math.max(10f, tp.getHeight() + delta));
            }
            if (tip instanceof TooltipMakerAPI) {
                TooltipMakerAPI maker = (TooltipMakerAPI) tip;
                maker.setHeightSoFar(Math.max(0f, maker.getHeightSoFar() + delta));
            }
        }
        if (panel != null) {
            PositionAPI panelPos = panel.getPosition();
            panelPos.setSize(panelPos.getWidth(), panelHeight);
            panelPos.inTL(panelX, panelYFromTop);
        }
    }

    private CustomPanelAPI newPreview(Object tip, TipState s, float width, PreviewScene.Opening opening) {
        // One live engine at a time: any other preview belongs to a tooltip that is gone or going.
        disposeActive();
        RefitShip refit = inRefit() ? RefitLookup.find(tip) : null;
        PreviewScene preview = new PreviewScene(s.id, s.subject, refit, PREVIEW_HEIGHT, opening);
        s.preview = preview;
        activePreview = preview;
        boolean threatHint = ThreatSelection.appliesTo(s.subject);
        CustomPanelAPI panel = Global.getSettings().createCustom(width,
                PREVIEW_HEIGHT + (threatHint ? PreviewScene.THREAT_HINT_HEIGHT : 0f), preview);
        TooltipMakerAPI status = panel.createUIElement(width, STATUS_LINE_HEIGHT, false);
        LabelAPI line = status.addPara("Preview unavailable", Misc.getGrayColor(), 0f);
        line.setAlignment(Alignment.MID);
        panel.addUIElement(status).inTL(0f, (PREVIEW_HEIGHT - STATUS_LINE_HEIGHT) / 2f);
        preview.setStatusLine(status);
        if (threatHint) preview.addThreatHint(panel);
        return panel;
    }

    /** How far the "F2 open Codex" line hangs below the tooltip's frame; 0 without one. */
    private static float hintOverhang(UIComponentAPI tip) {
        Object label = StarfarerBridge.tooltipExpandLabel.get(tip);
        if (!(label instanceof UIComponentAPI) || !children(tip).contains(label)) return 0f;
        PositionAPI lp = ((UIComponentAPI) label).getPosition();
        return Math.max(tip.getPosition().getY() - lp.getY(), lp.getHeight());
    }

    /** Fighter LPC tooltips are built to recreate their content every frame. It has to be stopped. */
    private static void stopRecreatingEveryFrame(Object tip, TipState s) {
        if (!(tip instanceof StandardTooltipV2Expandable)) return;
        StandardTooltipV2Expandable expandable = (StandardTooltipV2Expandable) tip;
        if (!expandable.isRecreateEveryFrame()) return;
        expandable.setRecreateEveryFrame(false);
        s.stoppedRecreateEveryFrame = true;
        Refl.ReflectedField pageField = pageField(tip);
        if (pageField != null) {
            s.pageField = pageField;
            s.page = (Integer) pageField.get(tip);
        }
        Log.info("tooltip #%d recreates its content every frame; turned off for the preview", s.id);
    }

    /** Keep the top edge where the game placed it and grow downward, like a native tooltip; push up only as far as needed to stay on screen. */
    private static void placeOnScreen(UIComponentAPI tip, TipState s, float originalTop, float below) {
        float height = tip.getPosition().getHeight();
        float screenH = Global.getSettings().getScreenHeight();
        float bottom = Math.max(originalTop - height, SCREEN_MARGIN + below);
        if (bottom + height > screenH - SCREEN_MARGIN) bottom = screenH - SCREEN_MARGIN - height;
        s.originalTop = originalTop;
        s.lastKnownTipHeight = height;
        s.desiredBottom = bottom;
        applyBottom(tip, bottom);
    }

    /** Re-apply our placement if the game moved the tooltip (e.g. on mouse move), or refit if another mod resized it. */
    private static void enforcePlacement(UIComponentAPI tip, TipState s) {
        if (Float.isNaN(s.desiredBottom)) return;
        PositionAPI tp = tip.getPosition();
        float height = tp.getHeight();
        if (!Float.isNaN(s.lastKnownTipHeight) && Math.abs(height - s.lastKnownTipHeight) > 0.5f) {
            float origTop = Float.isNaN(s.originalTop) ? top(tip) : s.originalTop;
            placeOnScreen(tip, s, origTop, hintOverhang(tip));
            return;
        }
        if (Math.abs(tp.getY() - s.desiredBottom) < 0.5f) return;
        applyBottom(tip, s.desiredBottom);
    }

    private static void applyBottom(UIComponentAPI tip, float bottom) {
        PositionAPI tp = tip.getPosition();
        UIPanelAPI parent = tip.getParent();
        float px = parent != null ? parent.getPosition().getX() : 0f;
        float py = parent != null ? parent.getPosition().getY() : 0f;
        tp.inBL(tp.getX() - px, bottom - py);
    }

    private static UIComponentAPI firstNonLabel(UIPanelAPI contents) {
        for (Object c : children(contents)) {
            if (c instanceof UIComponentAPI && !(c instanceof LabelAPI)) return (UIComponentAPI) c;
        }
        return null;
    }

    private static List<UIComponentAPI> children(Object node) {
        return node instanceof UIPanelAPI ? ((UIPanelAPI) node).getChildrenCopy2() : Collections.<UIComponentAPI>emptyList();
    }

    private static boolean isDescendant(Object ancestor, Object node, int maxDepth) {
        if (ancestor == node) return true;
        if (maxDepth <= 0) return false;
        for (Object c : children(ancestor)) {
            if (isDescendant(c, node, maxDepth - 1)) return true;
        }
        return false;
    }

    private static boolean inRefit() {
        return Global.getSector().getCampaignUI().getCurrentCoreTab() == CoreUITabId.REFIT;
    }

    private static float top(UIComponentAPI c) {
        PositionAPI p = c.getPosition();
        return p.getY() + p.getHeight();
    }
}
