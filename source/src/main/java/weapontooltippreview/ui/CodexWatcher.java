package weapontooltippreview.ui;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.codex.CodexEntryPlugin;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.ScrollPanelAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.codex2.CodexDialog;
import com.fs.state.AppDriver;
import com.fs.state.AppState;
import weapontooltippreview.Log;
import weapontooltippreview.WeaponTooltipPreviewSettings;
import weapontooltippreview.bridge.StarfarerBridge;
import weapontooltippreview.scenario.PreviewSubject;
import weapontooltippreview.sim.EngineSandbox;

/**
 * Watches the Codex detail panel (across Campaign, Title Screen, and Combat) for weapon and fighter wing entries and
 * injects the live preview panel directly above the Description box, matching tooltip delay/expand behaviour and
 * clipping to the Codex scroll pane.
 */
final class CodexWatcher {

    private static final CodexWatcher SHARED = new CodexWatcher();

    /** Matches CodexDetailPanel's 10px spacing between detail sections. */
    private static final float GAP = 10f;

    static CodexWatcher shared() {
        return SHARED;
    }

    private CodexWatcher() {
    }

    private static final class ExpansionLayout {
        final ScrollPanelAPI scroller;
        final UIPanelAPI content;
        final UIComponentAPI descriptionBox;
        final float panelWidth;
        final float targetPanelHeight;
        final float targetShift;
        final float baseContentHeight;
        final float baseColumnBottomFromTop;
        final float boxXFromLeft;
        final float boxYFromTop;
        float progress;

        ExpansionLayout(ScrollPanelAPI scroller, UIPanelAPI content, UIComponentAPI descriptionBox, float panelWidth,
                        float targetPanelHeight, float targetShift, float baseContentHeight,
                        float baseColumnBottomFromTop, float boxXFromLeft, float boxYFromTop, float progress) {
            this.scroller = scroller;
            this.content = content;
            this.descriptionBox = descriptionBox;
            this.panelWidth = panelWidth;
            this.targetPanelHeight = targetPanelHeight;
            this.targetShift = targetShift;
            this.baseContentHeight = baseContentHeight;
            this.baseColumnBottomFromTop = baseColumnBottomFromTop;
            this.boxXFromLeft = boxXFromLeft;
            this.boxYFromTop = boxYFromTop;
            this.progress = progress;
        }
    }

    private long lastTickNanos;
    private int nextId = 1;
    private GameState trackedState;
    /** Identity of the currently rendered CodexDetailPanel.mainText; changes on every entry switch or reload. */
    private Object trackedMainText;
    private PreviewSubject subject;
    private float entryElapsed;
    private boolean opened;
    private boolean uninjectable;
    private PreviewScene activePreview;
    private CustomPanelAPI injectedPanel;
    private ExpansionLayout layout;

    /** True while a Codex preview panel is currently live. */
    boolean isActive() {
        return activePreview != null && !activePreview.isDisposed();
    }

    void tick() {
        long now = System.nanoTime();
        float dt = lastTickNanos == 0L ? 0f : Math.min(0.1f, (now - lastTickNanos) / 1e9f);
        lastTickNanos = now;

        GameState currentState = Global.getCurrentState();
        if (currentState != trackedState) {
            reset();
            trackedState = currentState;
        }

        if (!StarfarerBridge.isCodexReady()
                || !WeaponTooltipPreviewSettings.codexPreviewsEnabled(currentState)
                || !Global.getSettings().isShowingCodex()) {
            reset();
            return;
        }

        Object dialog = findCodexDialog();
        if (dialog == null) {
            reset();
            return;
        }
        Object detailPanel = StarfarerBridge.codexDetailPanel.get(dialog);
        if (detailPanel == null) {
            reset();
            return;
        }
        Object pluginObj = StarfarerBridge.codexPlugin.get(detailPanel);
        Object mainTextObj = StarfarerBridge.codexMainText.get(detailPanel);
        Object scrollerObj = StarfarerBridge.codexScroller.get(detailPanel);
        PreviewSubject currentSubject = pluginObj instanceof CodexEntryPlugin
                ? subjectOf((CodexEntryPlugin) pluginObj) : null;
        if (currentSubject == null || !(mainTextObj instanceof UIComponentAPI)
                || !(scrollerObj instanceof ScrollPanelAPI)) {
            reset();
            return;
        }

        UIComponentAPI mainText = (UIComponentAPI) mainTextObj;
        ScrollPanelAPI scroller = (ScrollPanelAPI) scrollerObj;
        if (mainText != trackedMainText) {
            reset();
            trackedMainText = mainText;
            subject = currentSubject;
        }

        if (injectedPanel == null) {
            if (uninjectable || EngineSandbox.isDisabled() || PreviewScene.hasFailed(subject, false)) return;
            if (!opened) {
                if (WeaponTooltipPreviewSettings.delayEnabled()) {
                    entryElapsed += dt;
                    if (entryElapsed < WeaponTooltipPreviewSettings.previewDelay()) return;
                    opened = true;
                    inject(scroller, mainText, PreviewScene.Opening.EXPAND_STATIC);
                    return;
                }
                opened = true;
                inject(scroller, mainText, PreviewScene.Opening.IMMEDIATE);
                return;
            }
            inject(scroller, mainText, PreviewScene.Opening.IMMEDIATE);
        } else if (layout != null && layout.progress < 1f) {
            advanceExpansion(dt);
        }
    }

    void reset() {
        disposeActive();
        trackedMainText = null;
        subject = null;
        entryElapsed = 0f;
        opened = false;
        uninjectable = false;
        injectedPanel = null;
        layout = null;
    }

    private void disposeActive() {
        if (activePreview != null) {
            activePreview.dispose();
            activePreview = null;
        }
    }

    private void inject(ScrollPanelAPI scroller, UIComponentAPI mainText, PreviewScene.Opening opening) {
        UIPanelAPI content = scroller.getContentContainer();
        if (content == null) {
            uninjectable = true;
            return;
        }
        UIComponentAPI box = findDescriptionBox(content, mainText);
        if (box == null) {
            uninjectable = true;
            Log.info("codex: could not locate description box for %s", subject);
            return;
        }

        PositionAPI cp = content.getPosition();
        PositionAPI bp = box.getPosition();
        float contentLeft = cp.getX();
        float contentTop = cp.getY() + cp.getHeight();
        float boxTop = bp.getY() + bp.getHeight();
        float x0 = bp.getX() - contentLeft;
        float yFromTop = contentTop - boxTop;
        float width = Math.max(100f, bp.getWidth());
        float baseContentHeight = cp.getHeight();
        float baseColumnBottomFromTop = columnBottomFromTop(content, bp, contentTop, boxTop);

        disposeActive();
        int id = nextId++;
        PreviewScene preview = new PreviewScene(id, subject, null, PreviewScene.PREVIEW_HEIGHT, opening);
        preview.setClipSource(scroller);
        CustomPanelAPI panel = preview.createPanel(width);

        activePreview = preview;
        injectedPanel = panel;

        float targetPanelH = panel.getPosition().getHeight();
        float targetShift = targetPanelH + GAP;
        boolean animate = opening == PreviewScene.Opening.EXPAND_STATIC;
        layout = new ExpansionLayout(scroller, content, box, width, targetPanelH, targetShift, baseContentHeight,
                baseColumnBottomFromTop, x0, yFromTop, animate ? 0f : 1f);

        content.addComponent(panel).inTL(x0, yFromTop);
        applyExpansion(layout.progress);

        Log.info("codex #%d: preview for %s injected above description (%.0fx%.0f, %s)", id, subject, width,
                targetPanelH, opening);
    }

    private void advanceExpansion(float dt) {
        layout.progress = Math.min(1f, layout.progress + dt / PreviewScene.EXPAND_DURATION);
        applyExpansion(layout.progress);
        if (layout.progress >= 1f && activePreview != null) {
            activePreview.finishExpanding();
        }
    }

    private void applyExpansion(float progress) {
        if (layout == null || injectedPanel == null) return;
        float t = Math.max(0f, Math.min(1f, progress));
        float ease = PreviewScene.easeOutCubic(t);
        float nextShift = t >= 1f ? layout.targetShift : Math.max(PreviewScene.MIN_EXPAND_HEIGHT, layout.targetShift * ease);
        float nextPanelH = t >= 1f ? layout.targetPanelHeight
                : Math.max(PreviewScene.MIN_EXPAND_HEIGHT, Math.min(layout.targetPanelHeight, nextShift - GAP * ease));

        injectedPanel.getPosition().setSize(layout.panelWidth, nextPanelH);
        // Anchor directly to content (not injectedPanel, which sits after descriptionBox in content's child list) so
        // scrolling recomputes descriptionBox and its dependent factions panel in the same pass with zero lag.
        layout.descriptionBox.getPosition().inTL(layout.boxXFromLeft, layout.boxYFromTop + nextShift)
                .setXAlignOffset(0f);
        float contentW = layout.content.getPosition().getWidth();
        layout.scroller.setContentSize(contentW,
                Math.max(layout.baseContentHeight, layout.baseColumnBottomFromTop + nextShift));
        layout.scroller.clampOffset();
    }

    /** Lowest bottom edge (distance from content top) of the description box and anything below it in its column. */
    private static float columnBottomFromTop(UIPanelAPI content, PositionAPI boxPos, float contentTop, float boxTop) {
        float boxLeft = boxPos.getX();
        float boxRight = boxLeft + boxPos.getWidth();
        float maxBottomFromTop = contentTop - boxPos.getY();
        for (UIComponentAPI child : PreviewScene.children(content)) {
            PositionAPI p = child.getPosition();
            if (p == null) continue;
            float cLeft = p.getX();
            float cRight = cLeft + p.getWidth();
            float cTop = p.getY() + p.getHeight();
            if (cRight <= boxLeft + 1f || cLeft >= boxRight - 1f) continue;
            if (cTop > boxTop + 1f) continue;
            maxBottomFromTop = Math.max(maxBottomFromTop, contentTop - p.getY());
        }
        return maxBottomFromTop;
    }

    private static UIComponentAPI findDescriptionBox(UIPanelAPI content, UIComponentAPI mainText) {
        for (UIComponentAPI child : PreviewScene.children(content)) {
            if (child == mainText || PreviewScene.isDescendant(child, mainText, 3)) return child;
        }
        return null;
    }

    private static Object findCodexDialog() {
        AppState state = AppDriver.getInstance().getCurrentState();
        Object overlay = StarfarerBridge.codexOverlay(state);
        if (overlay instanceof UIPanelAPI) {
            Object dialog = findByClass(overlay, CodexDialog.class, 0);
            if (dialog != null) return dialog;
        }
        // Title Screen and ESC-menu "Codex" buttons mount CodexDialog under screenPanel instead of codexOverlay.
        UIPanelAPI screen = Global.getSettings().getScreenPanel();
        return screen != null ? findByClass(screen, CodexDialog.class, 0) : null;
    }

    private static Object findByClass(Object node, Class<?> type, int depth) {
        if (type.isInstance(node)) return node;
        if (depth >= PreviewScene.SCAN_DEPTH) return null;
        for (UIComponentAPI c : PreviewScene.children(node)) {
            Object hit = findByClass(c, type, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private static PreviewSubject subjectOf(CodexEntryPlugin plugin) {
        if (plugin.hasCustomDetailPanel()) return null;
        if (plugin.isLocked() && !CodexDialog.showLocked()) return null;
        Object param = plugin.getParam();
        if (param instanceof WeaponSpecAPI) return new PreviewSubject(((WeaponSpecAPI) param).getWeaponId(), null);
        if (param instanceof FighterWingSpecAPI) return new PreviewSubject(null, ((FighterWingSpecAPI) param).getId());
        return null;
    }
}
