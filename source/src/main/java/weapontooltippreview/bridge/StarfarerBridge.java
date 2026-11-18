package weapontooltippreview.bridge;

import com.fs.graphics.anim.AnimationManager;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.CombatState;
import com.fs.starfarer.combat.systems.WeaponGroup;
import com.fs.starfarer.ui.impl.StandardTooltipV2;
import weapontooltippreview.Log;

/**
 * What the mod needs from the game's core and cannot call directly, resolved once at application load. 
 * Compatibility security : a required entry missing disables the mod and leaves vanilla tooltips.
 * Each platform's build is obfuscated separately, sometimes the Windows and macOS jars give the same member different names.
 * TODO Windows: the 0.98.5a names (map bounds, arc select, refit panel) were only checked against the macOS
 * starfarer_obf.jar. Check them against the Windows one.
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
            mapSetBounds = method(mapClass, new String[]{"super", "o00000"}, float.class, float.class, float.class, float.class);
            animManagers = field(AnimationManager.class, "managers");
            // The panel a tooltip builds its content in. Its return type is obfuscated, so no direct call.
            tooltipContents = method(StandardTooltipV2.class, "getContents");
            ready = true;
            Log.info("bridge: all required entries resolved");
        } catch (Throwable t) {
            Log.error("bridge: a required entry is missing, previews DISABLED", t);
        }

        try {
            Class<?> renderer = field(CombatState.class, "arcRenderer").type;
            // 0.98.5a has a second (WeaponGroup) setter, o00000, that skips the fade; CombatState only calls new
            arcSelectGroup = method(renderer, new String[]{"Ò00000", "new"}, WeaponGroup.class);
            arcAdvance = method(renderer, new String[]{"super", "o00000"}, float.class);
            arcRender = method(renderer, new String[]{"String", "Ô00000"});
            arcRendererClass = renderer;
        } catch (Throwable t) {
            Log.error("bridge: the game's weapon arc renderer is missing; previews draw no arc", t);
        }

        try {
            Class<?> panel = classNamed("com.fs.starfarer.coreui.refit.U", "com.fs.starfarer.coreui.refit.Objectsuper");
            tooltipWidget = method(StandardTooltipV2.class, "getWidgetAttachedTo");
            refitMember = method(panel, "getMember");
            refitShipDisplay = method(panel, "getShipDisplay");
            refitPanelClass = panel;
        } catch (Throwable t) {
            Log.error("bridge: the refit panel is missing; previews use a generic ship", t);
        }
    }

    /** The first of these classes that exists. */
    private static Class<?> classNamed(String... names) {
        for (String name : names) {
            try {
                return Refl.classForName(name);
            } catch (RuntimeException absent) {
                // try the next name
            }
        }
        throw new IllegalStateException("no class " + String.join("|", names));
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
