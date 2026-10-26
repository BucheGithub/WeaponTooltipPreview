package wtp;

import lunalib.lunaSettings.LunaSettings;
import lunalib.lunaSettings.LunaSettingsListener;
import wtp.scenario.ShieldMode;

import java.util.Locale;

/**
 * The player's choices from the LunaLib settings menu (data/config/LunaSettings.csv), cached and refreshed whenever
 * LunaLib reports a change. Scene settings apply from the next hover; the sound level applies at once.
 */
public final class WtpSettings {

    private static final String MOD_ID = "weapon_tooltip_preview";
    private static final String DEFAULT_DUMMY_VARIANT = "hammerhead_Balanced";
    /** Radio option (as written in the CSV) → stock variant used as the dummy. */
    private static final String[][] DUMMIES = {
            {"Lasher", "lasher_Standard"},
            {"Wolf", "wolf_Assault"},
            {"Hammerhead", "hammerhead_Balanced"},
            {"Enforcer", "enforcer_Balanced"},
            {"Medusa", "medusa_Attack"},
            {"Dominator", "dominator_Assault"},
            {"Eagle", "eagle_Balanced"},
            {"Aurora", "aurora_Balanced"},
            {"Onslaught", "onslaught_Standard"},
            {"Paragon", "paragon_Elite"},
    };

    private static float soundLevel = 0.3f;
    private static String dummyVariant = DEFAULT_DUMMY_VARIANT;
    private static ShieldMode shieldMode = ShieldMode.FLUCTUATING;
    private static boolean listening;

    private WtpSettings() {
    }

    /** Preview volume as a fraction of the game's sound effects volume; 0 means muted. */
    public static float soundLevel() {
        return soundLevel;
    }

    public static String dummyVariant() {
        return dummyVariant;
    }

    public static ShieldMode shieldMode() {
        return shieldMode;
    }

    /** Reads the current values and subscribes to changes (once). Defaults stay if LunaLib is unavailable. */
    public static void load() {
        try {
            if (!listening) {
                LunaSettings.addSettingsListener(new Listener());
                listening = true;
            }
            read();
        } catch (Throwable t) {
            Log.error("LunaLib settings unavailable; using defaults", t);
        }
    }

    private static void read() {
        Integer sound = LunaSettings.getInt(MOD_ID, "wtp_soundLevel");
        if (sound != null) soundLevel = Math.max(0, Math.min(100, sound)) / 100f;

        String dummy = LunaSettings.getString(MOD_ID, "wtp_dummyShip");
        dummyVariant = DEFAULT_DUMMY_VARIANT;
        for (String[] option : DUMMIES) {
            if (dummy != null && option[0].equalsIgnoreCase(dummy.trim())) dummyVariant = option[1];
        }

        String shield = LunaSettings.getString(MOD_ID, "wtp_shieldMode");
        String key = shield == null ? "" : shield.trim().toLowerCase(Locale.ROOT);
        shieldMode = key.equals("always on") ? ShieldMode.ALWAYS_ON
                : key.equals("always off") ? ShieldMode.ALWAYS_OFF : ShieldMode.FLUCTUATING;
        Log.info("settings: sound %d%%, dummy %s, shield %s", Math.round(soundLevel * 100), dummyVariant, shieldMode);
    }

    private static final class Listener implements LunaSettingsListener {
        @Override
        public void settingsChanged(String modID) {
            if (MOD_ID.equals(modID)) read();
        }
    }
}
