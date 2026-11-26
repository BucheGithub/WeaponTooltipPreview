package weapontooltippreview.ui;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.codex.CodexEntryPlugin;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.ScrollPanelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.codex2.CodexDialog;
import com.fs.state.AppDriver;
import com.fs.state.AppState;
import weapontooltippreview.Log;
import weapontooltippreview.WeaponTooltipPreviewSettings;
import weapontooltippreview.bridge.StarfarerBridge;
import weapontooltippreview.scenario.PreviewSubject;
import weapontooltippreview.scenario.ThreatSelection;
import weapontooltippreview.sim.EngineSandbox;

import java.util.Collections;
import java.util.List;

/**
 * Watches the Codex detail panel (across Campaign, Title Screen, and Combat) for weapon and fighter wing entries and
 * injects the live preview panel directly above the Description box, matching tooltip delay/expand behaviour and
 * clipping to the Codex scroll pane.
 */
final class CodexWatcher {

    private static final CodexWatcher SHARED = new CodexWatcher();

    private static final float PREVIEW_HEIGHT = 150f;
    /** Matches CodexDetailPanel's 10px spacing between detail sections. */
    private static final float GAP = 10f;
    private static final float STATUS_LINE_HEIGHT = 20f;
    private static final float EXPAND_DURATION = 0.18f;
    private static final float MIN_EXPAND_HEIGHT = 2f;
    private static final int SCAN_DEPTH = 2;

    static CodexWatcher shared() {
        return SHARED;
    }

    private CodexWatcher() {
    }

    private static final class ExpansionLayout {
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

        ExpansionLayout(UIPanelAPI content, UIComponentAPI descriptionBox, float panelWidth, float targetPanelHeight,
                        float targetShift, float baseContentHeight, float baseColumnBottomFromTop,
                        float boxXFromLeft, float boxYFromTop, float progress) {
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

        if (StarfarerBridge.codexDialogClass == null
                || !supportedState(currentState)
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

        if (!WeaponTooltipPreviewSettings.codexPreviewsEnabled(currentState)) {
            uninjectLive();
            disposeActive();
            return;
        }

        if (injectedPanel == null) {
            if (noPreviewFor(subject, currentState)) return;
            if (!opened) {
                if (WeaponTooltipPreviewSettings.delayEnabled()) {
                    entryElapsed += dt;
                    if (entryElapsed < WeaponTooltipPreviewSettings.previewDelay()) return;
                    opened = true;
                    inject(dialog, scroller, mainText, PreviewScene.Opening.EXPAND_STATIC);
                    return;
                }
                opened = true;
                inject(dialog, scroller, mainText, PreviewScene.Opening.IMMEDIATE);
                return;
            }
            inject(dialog, scroller, mainText, PreviewScene.Opening.IMMEDIATE);
        } else if (layout != null && layout.progress < 1f) {
            advanceExpansion(dt);
        }
    }

    void disposeAll() {
        reset();
    }

    private void reset() {
        disposeActive();
        trackedMainText = null;
        subject = null;
        entryElapsed = 0f;
        opened = false;
        injectedPanel = null;
        layout = null;
    }

    private void disposeActive() {
        if (activePreview != null) {
            activePreview.dispose();
            activePreview = null;
        }
    }

    /** Removes an active preview panel from the currently displayed entry if previews are toggled off mid-view. */
    private void uninjectLive() {
        if (injectedPanel == null || layout == null) return;
        UIPanelAPI content = layout.content;
        CustomPanelAPI panel = injectedPanel;
        ExpansionLayout l = layout;
        injectedPanel = null;
        layout = null;
        opened = false;
        entryElapsed = 0f;
        if (isDescendant(content, panel, 1)) {
            content.removeComponent(panel);
            l.descriptionBox.getPosition().inTL(l.boxXFromLeft, l.boxYFromTop).setXAlignOffset(0f);
            PositionAPI cp = content.getPosition();
            cp.setSize(cp.getWidth(), l.baseContentHeight);
        }
    }

    private void inject(Object dialog, ScrollPanelAPI scroller, UIComponentAPI mainText,
                        PreviewScene.Opening opening) {
        UIPanelAPI content = scroller.getContentContainer();
        if (content == null) return;
        UIComponentAPI box = findDescriptionBox(content, mainText);
        if (box == null) {
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
        PreviewScene preview = new PreviewScene(id, subject, null, PREVIEW_HEIGHT, opening);
        preview.setClipSource(scroller);
        TextFieldAPI searchField = searchFieldOf(dialog);
        if (searchField != null) preview.setInputSuppressed(searchField::hasFocus);

        boolean threatHint = ThreatSelection.appliesTo(subject);
        CustomPanelAPI panel = Global.getSettings().createCustom(width,
                PREVIEW_HEIGHT + (threatHint ? PreviewScene.THREAT_HINT_HEIGHT : 0f), preview);
        TooltipMakerAPI status = panel.createUIElement(width, STATUS_LINE_HEIGHT, false);
        LabelAPI line = status.addPara("Preview unavailable", Misc.getGrayColor(), 0f);
        line.setAlignment(Alignment.MID);
        panel.addUIElement(status).inTL(0f, (PREVIEW_HEIGHT - STATUS_LINE_HEIGHT) / 2f);
        preview.setStatusLine(status);
        if (threatHint) preview.addThreatHint(panel);

        activePreview = preview;
        injectedPanel = panel;

        float targetPanelH = panel.getPosition().getHeight();
        float targetShift = targetPanelH + GAP;
        boolean animate = opening == PreviewScene.Opening.EXPAND_STATIC;
        layout = new ExpansionLayout(content, box, width, targetPanelH, targetShift, baseContentHeight,
                baseColumnBottomFromTop, x0, yFromTop, animate ? 0f : 1f);

        content.addComponent(panel).inTL(x0, yFromTop);
        applyExpansion(layout.progress);

        Log.info("codex #%d: preview for %s injected above description (%.0fx%.0f, %s)", id, subject, width,
                targetPanelH, opening);
    }

    private void advanceExpansion(float dt) {
        layout.progress = Math.min(1f, layout.progress + dt / EXPAND_DURATION);
        applyExpansion(layout.progress);
        if (layout.progress >= 1f && activePreview != null) {
            activePreview.finishExpanding();
        }
    }

    private void applyExpansion(float progress) {
        if (layout == null || injectedPanel == null) return;
        float t = Math.max(0f, Math.min(1f, progress));
        float inv = 1f - t;
        float ease = 1f - inv * inv * inv;
        float nextShift = t >= 1f ? layout.targetShift : Math.max(MIN_EXPAND_HEIGHT, layout.targetShift * ease);
        float nextPanelH = t >= 1f ? layout.targetPanelHeight
                : Math.max(MIN_EXPAND_HEIGHT, Math.min(layout.targetPanelHeight, nextShift - GAP * ease));

        injectedPanel.getPosition().setSize(layout.panelWidth, nextPanelH);
        // Anchor directly to content (not injectedPanel, which sits after descriptionBox in content's child list) so
        // scrolling recomputes descriptionBox and its dependent factions panel in the same pass with zero lag.
        layout.descriptionBox.getPosition().inTL(layout.boxXFromLeft, layout.boxYFromTop + nextShift)
                .setXAlignOffset(0f);
        PositionAPI cp = layout.content.getPosition();
        cp.setSize(cp.getWidth(), Math.max(layout.baseContentHeight, layout.baseColumnBottomFromTop + nextShift));
    }

    /** Lowest bottom edge (distance from content top) of the description box and anything below it in its column. */
    private static float columnBottomFromTop(UIPanelAPI content, PositionAPI boxPos, float contentTop, float boxTop) {
        float boxLeft = boxPos.getX();
        float boxRight = boxLeft + boxPos.getWidth();
        float maxBottomFromTop = contentTop - boxPos.getY();
        for (UIComponentAPI child : children(content)) {
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
        for (UIComponentAPI child : children(content)) {
            if (child == mainText || isDescendant(child, mainText, 3)) return child;
        }
        return null;
    }

    private static boolean supportedState(GameState state) {
        return state == GameState.CAMPAIGN || state == GameState.TITLE || state == GameState.COMBAT;
    }

    private static Object findCodexDialog() {
        AppState state = AppDriver.getInstance().getCurrentState();
        Object overlay = StarfarerBridge.codexOverlay(state);
        if (overlay instanceof UIPanelAPI) {
            Object dialog = findByClass(overlay, StarfarerBridge.codexDialogClass, 0);
            if (dialog != null) return dialog;
        }
        // Title Screen and ESC-menu "Codex" buttons mount CodexDialog under screenPanel instead of codexOverlay.
        UIPanelAPI screen = Global.getSettings().getScreenPanel();
        return screen != null ? findByClass(screen, StarfarerBridge.codexDialogClass, 0) : null;
    }

    private static Object findByClass(Object node, Class<?> type, int depth) {
        if (type.isInstance(node)) return node;
        if (depth >= SCAN_DEPTH) return null;
        for (UIComponentAPI c : children(node)) {
            Object hit = findByClass(c, type, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private static TextFieldAPI searchFieldOf(Object dialog) {
        if (StarfarerBridge.codexSearchField == null) return null;
        Object f = StarfarerBridge.codexSearchField.get(dialog);
        return f instanceof TextFieldAPI ? (TextFieldAPI) f : null;
    }

    private static PreviewSubject subjectOf(CodexEntryPlugin plugin) {
        if (plugin.hasCustomDetailPanel()) return null;
        if (plugin.isLocked() && !CodexDialog.showLocked()) return null;
        Object param = plugin.getParam();
        if (param instanceof WeaponSpecAPI) return new PreviewSubject(((WeaponSpecAPI) param).getWeaponId(), null);
        if (param instanceof FighterWingSpecAPI) return new PreviewSubject(null, ((FighterWingSpecAPI) param).getId());
        return null;
    }

    private static boolean noPreviewFor(PreviewSubject subject, GameState state) {
        return !WeaponTooltipPreviewSettings.codexPreviewsEnabled(state) || EngineSandbox.isDisabled()
                || PreviewScene.hasFailed(subject, false);
    }

    private static List<UIComponentAPI> children(Object node) {
        return node instanceof UIPanelAPI ? ((UIPanelAPI) node).getChildrenCopy2()
                : Collections.<UIComponentAPI>emptyList();
    }

    private static boolean isDescendant(Object ancestor, Object node, int maxDepth) {
        if (ancestor == node) return true;
        if (maxDepth <= 0) return false;
        for (UIComponentAPI c : children(ancestor)) {
            if (isDescendant(c, node, maxDepth - 1)) return true;
        }
        return false;
    }
}
