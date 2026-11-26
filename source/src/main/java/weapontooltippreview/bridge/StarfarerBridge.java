package weapontooltippreview.bridge;

import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.campaign.CampaignState;
import com.fs.starfarer.codex2.CodexDetailPanel;
import com.fs.starfarer.codex2.CodexDialog;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.CombatState;
import com.fs.starfarer.combat.systems.WeaponGroup;
import com.fs.starfarer.coreui.refit.WeaponPickerDialog;
import com.fs.starfarer.title.TitleScreenState;
import com.fs.starfarer.ui.impl.StandardTooltipV2;
import com.fs.graphics.anim.AnimationManager;
import com.fs.state.AppState;
import weapontooltippreview.Log;

/**
 * What the mod needs from the game's core and cannot call directly, resolved once at application load.
 * Compatibility security: a required entry missing disables the mod and leaves vanilla tooltips.
 * Each platform's build is obfuscated separately; where possible members are matched by signature rather than name.
 */
public final class StarfarerBridge {

    /** In a parameter list: any type (the obfuscated class being looked for). */
    private static final Class<?> ANY = null;

    // required
    public static Refl.ReflectedField engineInstance;
    public static Refl.ReflectedMethod engineSetCombatUI;
    public static Refl.ReflectedMethod engineSetCombatMap;
    public static Refl.ReflectedMethod engineAdvance;
    public static Class<?> mapClass;
    public static Refl.ReflectedMethod mapSetBounds;
    public static Class<?> inputClass;
    public static Refl.ReflectedField animManagers;
    public static Refl.ReflectedMethod tooltipContents;
    public static Refl.ReflectedField tooltipExpanded;
    public static Refl.ReflectedField tooltipExpandLabel;

    // optional: the game's weapon arc, failure = no arc drawn
    public static Class<?> arcRendererClass;
    public static Refl.ReflectedMethod arcSelectGroup;
    public static Refl.ReflectedMethod arcAdvance;
    public static Refl.ReflectedMethod arcRender;

    // optional: the refit screen's ship and slot, failure = always uses generic ship
    public static Class<?> refitPanelClass;
    public static Refl.ReflectedMethod tooltipWidget;
    public static Refl.ReflectedMethod refitMember;
    public static Refl.ReflectedMethod refitShipDisplay;
    public static Refl.ReflectedMethod refitCurrentVariant;
    public static Refl.ReflectedMethod buttonGetRenderer;

    // optional: the codex detail panel (campaign, title screen, combat), failure = no codex previews
    public static Class<?> codexDialogClass;
    public static Refl.ReflectedMethod campaignCodexOverlay;
    public static Refl.ReflectedMethod titleCodexOverlay;
    public static Refl.ReflectedMethod combatCodexOverlay;
    public static Refl.ReflectedField codexDetailPanel;
    public static Refl.ReflectedField codexSearchField;
    public static Refl.ReflectedField codexPlugin;
    public static Refl.ReflectedField codexScroller;
    public static Refl.ReflectedField codexMainText;

    private static boolean ready;

    private StarfarerBridge() {
    }

    public static boolean isReady() {
        return ready;
    }

    /** Resolves every entry; each group's availability field is set last, so a failed group stays unavailable. */
    public static void init() {
        try {
            engineInstance = Refl.fieldOfType(CombatEngine.class, CombatEngine.class, true);
            if (engineInstance == null) throw new IllegalStateException("CombatEngine has no instance field");
            engineSetCombatUI = method(CombatEngine.class, "setCombatUI", ANY);
            engineSetCombatMap = method(CombatEngine.class, "setCombatMap", ANY);
            engineAdvance = method(CombatEngine.class, "advance", float.class, ANY);
            mapClass = engineSetCombatMap.parameterTypes[0];
            inputClass = engineAdvance.parameterTypes[1];
            mapSetBounds = method(mapClass, (String) null, float.class, float.class, float.class, float.class);
            animManagers = field(AnimationManager.class, "managers");
            // Return types are obfuscated per platform, so invoke via reflection.
            tooltipContents = method(StandardTooltipV2.class, "getContents");
            tooltipExpanded = field(StandardTooltipV2.class, "expanded");
            tooltipExpandLabel = field(StandardTooltipV2.class, "expandLabel");
            ready = true;
            Log.info("bridge: all required entries resolved");
        } catch (Throwable t) {
            Log.error("bridge: a required entry is missing, previews DISABLED", t);
        }

        try {
            Class<?> renderer = field(CombatState.class, "arcRenderer").type;
            // 0.98.5a has a second (WeaponGroup) setter, o00000, that skips the fade; CombatState only calls new
            arcSelectGroup = method(renderer, new String[]{"Ò00000", "new"}, WeaponGroup.class);
            arcAdvance = method(renderer, (String) null, float.class);
            arcRender = method(renderer, new String[]{"String", "Ô00000"});
            arcRendererClass = renderer;
        } catch (Throwable t) {
            Log.error("bridge: the game's weapon arc renderer is missing; previews draw no arc", t);
        }

        try {
            Class<?> panel = findRefitPanelClass();
            tooltipWidget = method(StandardTooltipV2.class, "getWidgetAttachedTo");
            refitMember = method(panel, "getMember");
            refitShipDisplay = method(panel, "getShipDisplay");
            refitCurrentVariant = method(refitShipDisplay.returnType, "getCurrentVariant");
            buttonGetRenderer = method(method(WeaponPickerDialog.class, "getSlotButton").returnType, "getRenderer");
            refitPanelClass = panel;
        } catch (Throwable t) {
            Log.error("bridge: the refit panel is missing; previews use a generic ship", t);
        }

        try {
            campaignCodexOverlay = method(CampaignState.class, "getOverlayPanelForCodex");
            titleCodexOverlay = Refl.method(TitleScreenState.class, "getOverlayPanelForCodex");
            combatCodexOverlay = Refl.method(CombatState.class, "getOverlayPanelForCodex");
            codexDetailPanel = Refl.fieldOfType(CodexDialog.class, CodexDetailPanel.class, false);
            if (codexDetailPanel == null) throw new IllegalStateException("CodexDialog has no CodexDetailPanel field");
            codexSearchField = Refl.fieldOfType(CodexDialog.class, TextFieldAPI.class, false);
            codexPlugin = field(CodexDetailPanel.class, "plugin");
            codexScroller = field(CodexDetailPanel.class, "scroller");
            codexMainText = field(CodexDetailPanel.class, "mainText");
            codexDialogClass = CodexDialog.class;
        } catch (Throwable t) {
            Log.error("bridge: the codex detail panel is missing; codex previews disabled", t);
        }
    }

    /** Returns the active state's codex overlay panel, or null if unsupported. */
    public static Object codexOverlay(AppState state) {
        if (state instanceof CampaignState && campaignCodexOverlay != null) {
            return campaignCodexOverlay.invoke(state);
        }
        if (state instanceof TitleScreenState && titleCodexOverlay != null) {
            return titleCodexOverlay.invoke(state);
        }
        if (state instanceof CombatState && combatCodexOverlay != null) {
            return combatCodexOverlay.invoke(state);
        }
        return null;
    }

    /** Finds the obfuscated refit panel class from WeaponPickerDialog's fields by its unobfuscated methods. */
    private static Class<?> findRefitPanelClass() {
        for (Refl.ReflectedField f : Refl.fields(WeaponPickerDialog.class)) {
            if (!f.isStatic && Refl.method(f.type, "getMember") != null
                    && Refl.method(f.type, "getShipDisplay") != null) {
                return f.type;
            }
        }
        throw new IllegalStateException("no refit panel field on WeaponPickerDialog");
    }

    private static Refl.ReflectedField field(Class<?> owner, String name) {
        Refl.ReflectedField field = Refl.fieldNamed(owner, name);
        if (field == null) throw new IllegalStateException("no field " + owner.getName() + "." + name);
        return field;
    }

    private static Refl.ReflectedMethod method(Class<?> owner, String name, Class<?>... parameterTypes) {
        return method(owner, new String[]{name}, parameterTypes);
    }

    private static Refl.ReflectedMethod method(Class<?> owner, String[] names, Class<?>... parameterTypes) {
        for (String name : names) {
            Refl.ReflectedMethod method = Refl.method(owner, name, parameterTypes);
            if (method != null) return method;
        }
        throw new IllegalStateException("no method " + owner.getName() + "." + String.join("|", names));
    }
}
