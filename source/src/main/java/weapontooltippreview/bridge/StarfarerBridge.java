package weapontooltippreview.bridge;

import com.fs.graphics.anim.AnimationManager;
import com.fs.starfarer.campaign.CampaignState;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.CombatState;
import com.fs.starfarer.combat.systems.WeaponGroup;
import com.fs.starfarer.ui.impl.StandardTooltipV2;
import weapontooltippreview.Log;

/**
 * What the mod needs from the game's core and cannot call directly, resolved once at application load. 
 * A required entry missing (a game update renamed it) disables the mod and leaves vanilla tooltips.
 * Each platform's build is obfuscated separately, sometimes the Windows and macOS jars give the same member different names.
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
    public static Refl.ReflectedMethod campaignScreenPanel;
    public static Refl.ReflectedMethod panelChildren;
    public static Refl.ReflectedMethod panelChildrenCopy;

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

    // optional: the sound effects volume, failure = no granularity in preview sound settings
    public static Refl.ReflectedMethod soundInstance;
    public static Refl.ReflectedMethod soundVolumeGet;
    public static Refl.ReflectedMethod soundVolumeSet;
    public static Refl.ReflectedMethod soundVolume2Get;
    public static Refl.ReflectedMethod soundVolume2Set;

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
            mapSetBounds = method(mapClass, "super", float.class, float.class, float.class, float.class);
            animManagers = field(AnimationManager.class, "managers");
            campaignScreenPanel = method(CampaignState.class, "getScreenPanel");
            panelChildren = method(StandardTooltipV2.class, "getChildrenNonCopy");
            panelChildrenCopy = method(StandardTooltipV2.class, "getChildrenCopy");
            ready = true;
            Log.info("bridge: all required entries resolved");
        } catch (Throwable t) {
            Log.error("bridge: a required entry is missing, previews DISABLED", t);
        }

        try {
            Class<?> renderer = field(CombatState.class, "arcRenderer").type;
            arcSelectGroup = method(renderer, "Ò00000", WeaponGroup.class);
            arcAdvance = method(renderer, new String[]{"super", "o00000"}, float.class);
            arcRender = method(renderer, new String[]{"String", "Ô00000"});
            arcRendererClass = renderer;
        } catch (Throwable t) {
            Log.error("bridge: the game's weapon arc renderer is missing; previews draw no arc", t);
        }

        try {
            Class<?> panel = Refl.classForName("com.fs.starfarer.coreui.refit.U");
            tooltipWidget = method(StandardTooltipV2.class, "getWidgetAttachedTo");
            refitMember = method(panel, "getMember");
            refitShipDisplay = method(panel, "getShipDisplay");
            refitPanelClass = panel;
        } catch (Throwable t) {
            Log.error("bridge: the refit panel is missing; previews use a generic ship", t);
        }

        try {
            // sound.C on Windows, sound.ooOO on macOS
            Class<?> sound;
            try {
                sound = Refl.classForName("sound.C");
            } catch (RuntimeException notWindows) {
                sound = Refl.classForName("sound.ooOO");
            }
            soundVolumeGet = method(sound, "for");
            soundVolumeSet = method(sound, "o00000", float.class);
            soundVolume2Get = method(sound, "Object");
            soundVolume2Set = method(sound, "Ò00000", float.class);
            soundInstance = method(sound, "ö00000");
        } catch (Throwable t) {
            Log.error("bridge: the sound effects volume is missing; previews below 100% sound are muted", t);
        }
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
