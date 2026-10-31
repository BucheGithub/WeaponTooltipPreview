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
    public static Refl.ReflectedField engineInstance;
    public static Refl.ReflectedMethod engineGetInstance;
    public static Refl.ReflectedMethod engineDestroyInstance;
    public static Refl.ReflectedMethod engineSetCombatUI;
    public static Refl.ReflectedMethod engineSetCombatMap;
    public static Refl.ReflectedMethod engineCreateDummyFleets;
    public static Refl.ReflectedMethod engineInit;
    public static Refl.ReflectedMethod engineSetPaused;
    public static Refl.ReflectedMethod enginePreCombat;
    public static Refl.ReflectedMethod engineAdvance;
    public static Refl.ReflectedMethod engineRender;
    /** Combat map; {@link #mapSetBounds} is its (minX, maxX, minY, maxY) setter. */
    public static Class<?> mapClass;
    public static Refl.ReflectedMethod mapSetBounds;
    /** The (empty) input event list engine.advance() takes. */
    public static Class<?> inputClass;
    public static Class<?> animClass;
    public static Refl.ReflectedField animManagers;
    public static Refl.ReflectedMethod animAdvanceAll;
    public static Refl.ReflectedMethod viewportSetXRatio;
    public static Refl.ReflectedMethod viewportSetYRatio;
    /** Interface the engine's combat UI host implements (TitleScreenState is the headless one). */
    public static Class<?> combatUiInterface;
    public static Class<?> appDriverClass;
    public static Refl.ReflectedMethod appDriverGetInstance;
    public static Refl.ReflectedMethod appDriverCurrentState;
    public static Refl.ReflectedMethod appDriverGetSession;
    public static Refl.ReflectedField appDriverStates;

    // ---------------------------------------------------------------- UI (required)
    public static Class<?> tooltipClass;
    public static Refl.ReflectedMethod campaignScreenPanel;

    // ---------------------------------------------------------------- optional
    /** The in-combat weapon arc renderer; null → a plain arc is drawn. */
    public static Class<?> arcRendererClass;
    public static Refl.ReflectedMethod arcSelectGroup;
    public static Refl.ReflectedMethod arcAdvance;
    public static Refl.ReflectedMethod arcRender;
    /** Refit panel and the tooltip → widget link; null → refit tooltips use a generic host. */
    public static Class<?> refitPanelClass;
    public static Refl.ReflectedMethod tooltipWidget;
    public static Refl.ReflectedMethod refitMember;
    public static Refl.ReflectedMethod refitShipDisplay;
    /** CampaignState.showingCodex; null → the Codex cannot be detected (its tooltips are never scanned anyway). */
    public static Refl.ReflectedField campaignShowingCodex;
    /**
     * The game's sound effects volume (sound.ooOO on macOS, sound.C on Windows: getInstance, two volume channels with
     * getter/setter). Only the
     * options and title screens set it. Null → the preview cannot be made quieter, so it is muted below 100%.
     */
    public static Refl.ReflectedMethod soundInstance;
    public static Refl.ReflectedMethod soundVolumeGet;
    public static Refl.ReflectedMethod soundVolumeSet;
    public static Refl.ReflectedMethod soundVolume2Get;
    public static Refl.ReflectedMethod soundVolume2Set;
    /**
     * CampaignFleetMemberView.renderWeapons(member, x, y, angle, scale, alpha, flag): a fleet member's weapon sprites
     * over its hull, as fleet and fighter icons draw them. Null → fighter icons in the wing widget show bare hulls.
     */
    public static Refl.ReflectedMethod renderMemberWeapons;

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

        if (engineClass != null) {
            Class<?> e = engineClass;
            engineInstance = (Refl.ReflectedField) need(missing, "CombatEngine instance field", () -> Refl.fieldOfType(e, e, true));
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
            if (engineSetCombatUI != null) combatUiInterface = engineSetCombatUI.parameterTypes[0];
            if (engineSetCombatMap != null) mapClass = engineSetCombatMap.parameterTypes[0];
            if (engineAdvance != null) inputClass = engineAdvance.parameterTypes[1];
        }
        if (mapClass != null) {
            mapSetBounds = method(missing, mapClass, "super", float.class, float.class, float.class, float.class);
        }
        if (animClass != null) {
            Class<?> a = animClass;
            animManagers = (Refl.ReflectedField) need(missing, "AnimationManager.managers", () -> Refl.fieldNamed(a, "managers"));
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
            appDriverGetSession = method(missing, d, "getSession");
            appDriverStates = (Refl.ReflectedField) need(missing, "AppDriver.states", () -> Refl.fieldNamed(d, "states"));
        }
        if (campaignState != null) {
            campaignScreenPanel = method(missing, campaignState, "getScreenPanel");
            campaignShowingCodex = (Refl.ReflectedField) need(degraded, "CampaignState.showingCodex",
                    () -> Refl.fieldNamed(campaignState, "showingCodex"));
        }
        if (tooltipClass != null) {
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
        if (!degraded.isEmpty()) Log.error("bridge: optional entries missing (feature degraded): " + degraded);
        if (ready) Log.info("bridge: all required entries resolved");
        else Log.error("bridge: required entries missing, preview DISABLED: " + missing);
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

    private static Refl.ReflectedMethod method(List<String> missing, Class<?> cls, String name, Class<?>... params) {
        String simple = cls.getName().substring(cls.getName().lastIndexOf('.') + 1);
        return (Refl.ReflectedMethod) need(missing, simple + "." + name, () -> Refl.method(cls, name, params));
    }

    /** The first of {@code names} (one per build) that names a method with these parameters. */
    private static Refl.ReflectedMethod method(List<String> missing, Class<?> cls, String[] names, Class<?>... params) {
        String simple = cls.getName().substring(cls.getName().lastIndexOf('.') + 1);
        return (Refl.ReflectedMethod) need(missing, simple + "." + String.join("|", names), () -> {
            for (String name : names) {
                Refl.ReflectedMethod m = Refl.method(cls, name, params);
                if (m != null) return m;
            }
            return null;
        });
    }

    /** The declared type of an unobfuscated field holding an obfuscated class. */
    private static Class<?> fieldType(List<String> missing, String what, Class<?> cls, String field) {
        return (Class<?>) need(missing, what, () -> {
            Refl.ReflectedField f = Refl.fieldNamed(cls, field);
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
    private static Refl.ReflectedMethod typed(List<String> missing, Class<?> cls, Class<?> returnType, String name,
                                      Class<?>... params) {
        String simple = cls.getName().substring(cls.getName().lastIndexOf('.') + 1);
        return (Refl.ReflectedMethod) need(missing, simple + "." + name, () -> Refl.method(cls, returnType, name, params));
    }
}
