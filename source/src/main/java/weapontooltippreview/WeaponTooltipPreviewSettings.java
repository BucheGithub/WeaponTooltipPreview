package weapontooltippreview;

import lunalib.lunaSettings.LunaSettings;
import lunalib.lunaSettings.LunaSettingsListener;
import org.lwjgl.input.Keyboard;

import java.util.Locale;

/**
 * The player's choices from the LunaLib settings menu (data/config/LunaSettings.csv), cached and refreshed whenever
 * LunaLib reports a change. Scene settings apply from the next hover; the sound level, keys and logs apply at once.
 */
public final class WeaponTooltipPreviewSettings {

    /** How the dummy uses its shield. */
    public enum ShieldMode {
        ALWAYS_ON,
        ALWAYS_OFF,
        FLUCTUATING
    }

    private static final String MOD_ID = "weapon_tooltip_preview";
    private static final String DEFAULT_DUMMY_VARIANT_ID = "drover_Strike";
    /**
     * Radio option (as written in the CSV) → stock variant used as the dummy. Small carriers whose forward missile
     * slots are plain small or medium ones, so point defense previews can fill them all with missiles and wings.
     */
    private static final String[][] DUMMIES = {
            {"Drover", "drover_Strike"},
            {"Condor", "condor_Attack"},
            {"Gemini", "gemini_Standard"},
    };

    private static float soundLevel = 0.7f;
    private static String dummyVariantId = DEFAULT_DUMMY_VARIANT_ID;
    private static ShieldMode shieldMode = ShieldMode.FLUCTUATING;
    private static int threatModeKey = Keyboard.KEY_F5;
    private static int threatMissileKey = Keyboard.KEY_F6;
    private static int threatWingKey = Keyboard.KEY_F7;
    private static boolean listening;

    private WeaponTooltipPreviewSettings() {
    }

    public static float soundLevel() {
        return soundLevel;
    }

    public static String dummyVariantId() {
        return dummyVariantId;
    }

    public static ShieldMode shieldMode() {
        return shieldMode;
    }

    public static int threatModeKey() {
        return threatModeKey;
    }

    public static int threatMissileKey() {
        return threatMissileKey;
    }

    public static int threatWingKey() {
        return threatWingKey;
    }

    public static void load() {
        if (!listening) {
            LunaSettings.addSettingsListener(new Listener());
            listening = true;
        }
        read();
    }

    private static void read() {
        Log.setEnabled(Boolean.TRUE.equals(LunaSettings.getBoolean(MOD_ID, "WeaponTooltipPreview_enableLogs")));

        Integer sound = LunaSettings.getInt(MOD_ID, "WeaponTooltipPreview_soundLevel");
        if (sound != null) soundLevel = Math.max(0, Math.min(100, sound)) / 100f;

        String dummy = LunaSettings.getString(MOD_ID, "WeaponTooltipPreview_dummyShip");
        dummyVariantId = DEFAULT_DUMMY_VARIANT_ID;
        for (String[] option : DUMMIES) {
            if (dummy != null && option[0].equalsIgnoreCase(dummy.trim())) dummyVariantId = option[1];
        }

        String shield = LunaSettings.getString(MOD_ID, "WeaponTooltipPreview_shieldMode");
        String key = shield == null ? "" : shield.trim().toLowerCase(Locale.ROOT);
        shieldMode = key.equals("always on") ? ShieldMode.ALWAYS_ON
                : key.equals("always off") ? ShieldMode.ALWAYS_OFF : ShieldMode.FLUCTUATING;

        threatModeKey = keySetting("WeaponTooltipPreview_threatModeKey", Keyboard.KEY_F5);
        threatMissileKey = keySetting("WeaponTooltipPreview_threatMissileKey", Keyboard.KEY_F6);
        threatWingKey = keySetting("WeaponTooltipPreview_threatWingKey", Keyboard.KEY_F7);
        Log.info("settings: sound %d%%, dummy %s, shield %s, threat keys %s %s %s", Math.round(soundLevel * 100),
                dummyVariantId, shieldMode, Keyboard.getKeyName(threatModeKey), Keyboard.getKeyName(threatMissileKey),
                Keyboard.getKeyName(threatWingKey));
    }

    private static int keySetting(String fieldId, int defaultKey) {
        Integer key = LunaSettings.getInt(MOD_ID, fieldId);
        return key != null ? key : defaultKey;
    }

    private static final class Listener implements LunaSettingsListener {
        @Override
        public void settingsChanged(String modID) {
            if (MOD_ID.equals(modID)) read();
        }
    }
}
