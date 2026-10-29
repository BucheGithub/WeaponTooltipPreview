package wtp.bridge;

import wtp.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Every lookup into the game's obfuscated core, resolved once at application load. Required entries missing (a game
 * update renamed them) disable the mod and leave vanilla tooltips untouched; optional entries degrade one feature.
 * Classes are found by their (stable) names, static fields by type, methods by name and exact signature.
 * <p>
 * Each platform's build is obfuscated separately, so the Windows and macOS jars give the same class different names.
 * Obfuscated classes are therefore reached through the unobfuscated methods and fields that use them; where nothing
 * does, and for obfuscated method names that differ, every known build's name is tried, macOS first, then Windows.
 */
public final class ObfBridge {

    /** In a parameter list: any type (the obfuscated class being looked for). */
    private static final Class<?> ANY = null;
    /** The sound manager has no unobfuscated anchor; its member names are the same in every build. */
    private static final String[] SOUND_MANAGER_CLASSES = {"sound.ooOO", "sound.C"};

    public static final String TOOLTIP_BASE_CLASS = "com.fs.starfarer.ui.impl.StandardTooltipV2";

    // ---------------------------------------------------------------- engine (required)
    public static Class<?> engineClass;
    /** The CombatEngine singleton's static instance field. */
    public static Refl.RField engineInstance;
    public static Refl.RMethod engineGetInstance;
    public static Refl.RMethod engineDestroyInstance;
    public static Refl.RMethod engineSetCombatUI;
    public static Refl.RMethod engineSetCombatMap;
    public static Refl.RMethod engineCreateDummyFleets;
    public static Refl.RMethod engineInit;
    public static Refl.RMethod engineSetPaused;
    public static Refl.RMethod enginePreCombat;
    public static Refl.RMethod engineAdvance;
    public static Refl.RMethod engineRender;
    /** Combat map; {@link #mapSetBounds} is its (minX, maxX, minY, maxY) setter. */
    public static Class<?> mapClass;
    public static Refl.RMethod mapSetBounds;
    /** The (empty) input event list engine.advance() takes. */
    public static Class<?> inputClass;
    public static Class<?> animClass;
    public static Refl.RField animManagers;
    public static Refl.RMethod animAdvanceAll;
    public static Refl.RMethod viewportSetXRatio;
    public static Refl.RMethod viewportSetYRatio;
    /** Interface the engine's combat UI host implements (TitleScreenState is the headless one). */
    public static Class<?> combatUiInterface;
    public static Class<?> appDriverClass;
    public static Refl.RMethod appDriverGetInstance;
    public static Refl.RMethod appDriverCurrentState;
    public static Refl.RField appDriverStates;

    // ---------------------------------------------------------------- UI (required)
    public static Class<?> tooltipClass;
    public static Refl.RMethod campaignScreenPanel;
    public static Refl.RMethod uiParent;

    // ---------------------------------------------------------------- optional
    /** The in-combat weapon arc renderer; null → a plain arc is drawn. */
    public static Class<?> arcRendererClass;
    public static Refl.RMethod arcSelectGroup;
    public static Refl.RMethod arcAdvance;
    public static Refl.RMethod arcRender;
    /** Refit panel and the tooltip → widget link; null → refit tooltips use a generic host. */
    public static Class<?> refitPanelClass;
    public static Refl.RMethod tooltipWidget;
    public static Refl.RMethod refitMember;
    public static Refl.RMethod refitShipDisplay;
    /** CampaignState.showingCodex; null → the Codex cannot be detected (its tooltips are never scanned anyway). */
    public static Refl.RField campaignShowingCodex;
    /**
     * The game's sound effects volume (sound.ooOO on macOS, sound.C on Windows: getInstance, two volume channels with
     * getter/setter). Only the
     * options and title screens set it. Null → the preview cannot be made quieter, so it is muted below 100%.
     */
    public static Refl.RMethod soundInstance;
    public static Refl.RMethod soundVolumeGet;
    public static Refl.RMethod soundVolumeSet;
    public static Refl.RMethod soundVolume2Get;
    public static Refl.RMethod soundVolume2Set;
    /**
     * CampaignFleetMemberView.renderWeapons(member, x, y, angle, scale, alpha, flag): a fleet member's weapon sprites
     * over its hull, as fleet and fighter icons draw them. Null → fighter icons in the wing widget show bare hulls.
     */
    public static Refl.RMethod renderMemberWeapons;

    private static boolean ready;

    private ObfBridge() {
    }

    public static boolean isReady() {
        return ready;
    }

    private interface Lookup {
        Object get();
    }

    /** Resolves everything; returns false (and logs what is missing) if any required entry is absent. */
    public static boolean init() {
        List<String> missing = new ArrayList<>();
        List<String> degraded = new ArrayList<>();

        engineClass = (Class<?>) need(missing, "CombatEngine", () -> Refl.classForName("com.fs.starfarer.combat.CombatEngine"));
        animClass = (Class<?>) need(missing, "AnimationManager", () -> Refl.classForName("com.fs.graphics.anim.AnimationManager"));
        appDriverClass = (Class<?>) need(missing, "AppDriver", () -> Refl.classForName("com.fs.state.AppDriver"));
        tooltipClass = (Class<?>) need(missing, "tooltip base", () -> Refl.classForName(TOOLTIP_BASE_CLASS));
        Class<?> viewport = (Class<?>) need(missing, "CombatViewport", () -> Refl.classForName("com.fs.starfarer.combat.CombatViewport"));
        Class<?> campaignState = (Class<?>) need(missing, "CampaignState", () -> Refl.classForName("com.fs.starfarer.campaign.CampaignState"));
        need(missing, "weapon tooltip", () -> Refl.classForName("com.fs.starfarer.ui.impl.CargoTooltipFactory$4"));

        if (engineClass != null) {
            Class<?> e = engineClass;
            engineInstance = (Refl.RField) need(missing, "CombatEngine instance field", () -> Refl.fieldOfType(e, e, true));
            engineGetInstance = method(missing, e, "getInstance");
            engineDestroyInstance = method(missing, e, "destroyInstance");
            engineCreateDummyFleets = method(missing, e, "createDummyFleetsIfNeeded");
            engineInit = method(missing, e, "init");
            engineSetPaused = method(missing, e, "setPaused", boolean.class);
            enginePreCombat = method(missing, e, "preCombat");
            engineRender = method(missing, e, "render", boolean.class);
            // The combat UI interface, combat map and input list are the obfuscated parameters of these methods.
            engineSetCombatUI = method(missing, e, "setCombatUI", ANY);
            engineSetCombatMap = method(missing, e, "setCombatMap", ANY);
            engineAdvance = method(missing, e, "advance", float.class, ANY);
            if (engineSetCombatUI != null) combatUiInterface = engineSetCombatUI.params[0];
            if (engineSetCombatMap != null) mapClass = engineSetCombatMap.params[0];
            if (engineAdvance != null) inputClass = engineAdvance.params[1];
        }
        if (mapClass != null) {
            mapSetBounds = method(missing, mapClass, "super", float.class, float.class, float.class, float.class);
        }
        if (animClass != null) {
            Class<?> a = animClass;
            animManagers = (Refl.RField) need(missing, "AnimationManager.managers", () -> Refl.fieldNamed(a, "managers"));
            animAdvanceAll = method(missing, a, "advanceAll", float.class);
        }
        if (viewport != null) {
            viewportSetXRatio = method(missing, viewport, "setWorldXtoScreenX", float.class);
            viewportSetYRatio = method(missing, viewport, "setWorldYtoScreenY", float.class);
        }
        if (appDriverClass != null) {
            Class<?> d = appDriverClass;
            appDriverGetInstance = method(missing, d, "getInstance");
            appDriverCurrentState = method(missing, d, "getCurrentState");
            appDriverStates = (Refl.RField) need(missing, "AppDriver.states", () -> Refl.fieldNamed(d, "states"));
        }
        if (campaignState != null) {
            campaignScreenPanel = method(missing, campaignState, "getScreenPanel");
            campaignShowingCodex = (Refl.RField) need(degraded, "CampaignState.showingCodex",
                    () -> Refl.fieldNamed(campaignState, "showingCodex"));
        }
        if (tooltipClass != null) {
            uiParent = method(missing, tooltipClass, "getParent");
            method(missing, tooltipClass, "getChildrenNonCopy");
            method(missing, tooltipClass, "getChildrenCopy");
            tooltipWidget = method(degraded, tooltipClass, "getWidgetAttachedTo");
        }

        // CombatState's own field holds the arc renderer.
        Class<?> combatState = (Class<?>) need(degraded, "CombatState", () -> Refl.classForName("com.fs.starfarer.combat.CombatState"));
        if (combatState != null) arcRendererClass = fieldType(degraded, "arc renderer", combatState, "arcRenderer");

        Class<?> group = (Class<?>) need(degraded, "WeaponGroup", () -> Refl.classForName("com.fs.starfarer.combat.systems.WeaponGroup"));
        if (arcRendererClass != null && group != null) {
            arcSelectGroup = method(degraded, arcRendererClass, "Ò00000", group);
            arcAdvance = method(degraded, arcRendererClass, new String[]{"super", "o00000"}, float.class);
            arcRender = method(degraded, arcRendererClass, new String[]{"String", "Ô00000"});
        }
        if (arcSelectGroup == null || arcAdvance == null || arcRender == null) arcRendererClass = null;

        refitPanelClass = (Class<?>) need(degraded, "refit panel", () -> Refl.classForName("com.fs.starfarer.coreui.refit.U"));
        if (refitPanelClass != null) {
            refitMember = method(degraded, refitPanelClass, "getMember");
            refitShipDisplay = method(degraded, refitPanelClass, "getShipDisplay");
        }
        if (refitMember == null || refitShipDisplay == null || tooltipWidget == null) refitPanelClass = null;

        Class<?> sound = (Class<?>) need(degraded, "sound manager", () -> firstClass(SOUND_MANAGER_CLASSES));
        if (sound != null) {
            soundInstance = typed(degraded, sound, sound, "ö00000");
            soundVolumeGet = typed(degraded, sound, float.class, "for");
            soundVolumeSet = typed(degraded, sound, void.class, "o00000", float.class);
            soundVolume2Get = typed(degraded, sound, float.class, "Object");
            soundVolume2Set = typed(degraded, sound, void.class, "Ò00000", float.class);
        }
        if (soundInstance == null || soundVolumeGet == null || soundVolumeSet == null || soundVolume2Get == null
                || soundVolume2Set == null) {
            soundInstance = null;
        }

        Class<?> memberView = (Class<?>) need(degraded, "CampaignFleetMemberView",
                () -> Refl.classForName("com.fs.starfarer.campaign.fleet.CampaignFleetMemberView"));
        if (memberView != null) {
            renderMemberWeapons = method(degraded, memberView, "renderWeapons", ANY, float.class, float.class,
                    float.class, float.class, float.class, boolean.class);
        }

        ready = missing.isEmpty();
        if (!degraded.isEmpty()) Log.info("bridge: optional entries missing (feature degraded): %s", degraded);
        if (ready) Log.info("bridge: all required entries resolved");
        else Log.info("bridge: required entries missing, preview DISABLED: %s", missing);
        return ready;
    }

    private static Object need(List<String> missing, String what, Lookup lookup) {
        try {
            Object result = lookup.get();
            if (result == null) missing.add(what);
            return result;
        } catch (Throwable t) {
            missing.add(what + " (" + t.getMessage() + ")");
            return null;
        }
    }

    private static Refl.RMethod method(List<String> missing, Class<?> cls, String name, Class<?>... params) {
        String simple = cls.getName().substring(cls.getName().lastIndexOf('.') + 1);
        return (Refl.RMethod) need(missing, simple + "." + name, () -> Refl.method(cls, name, params));
    }

    /** The first of {@code names} (one per build) that names a method with these parameters. */
    private static Refl.RMethod method(List<String> missing, Class<?> cls, String[] names, Class<?>... params) {
        String simple = cls.getName().substring(cls.getName().lastIndexOf('.') + 1);
        return (Refl.RMethod) need(missing, simple + "." + String.join("|", names), () -> {
            for (String name : names) {
                Refl.RMethod m = Refl.method(cls, name, params);
                if (m != null) return m;
            }
            return null;
        });
    }

    /** The declared type of an unobfuscated field holding an obfuscated class. */
    private static Class<?> fieldType(List<String> missing, String what, Class<?> cls, String field) {
        return (Class<?>) need(missing, what, () -> {
            Refl.RField f = Refl.fieldNamed(cls, field);
            return f != null ? f.type : null;
        });
    }

    /** The first of these classes (one name per build) that exists; null if none does. */
    private static Class<?> firstClass(String... names) {
        for (String name : names) {
            try {
                return Refl.classForName(name);
            } catch (RuntimeException notThisBuild) {
                // try the next build's name
            }
        }
        return null;
    }

    /** Name, parameters and return type: obfuscated names are reused across overloads. */
    private static Refl.RMethod typed(List<String> missing, Class<?> cls, Class<?> returnType, String name,
                                      Class<?>... params) {
        String simple = cls.getName().substring(cls.getName().lastIndexOf('.') + 1);
        return (Refl.RMethod) need(missing, simple + "." + name, () -> Refl.method(cls, returnType, name, params));
    }
}
