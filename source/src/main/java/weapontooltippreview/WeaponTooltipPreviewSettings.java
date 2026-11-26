package weapontooltippreview;

import com.fs.starfarer.api.GameState;
import lunalib.lunaSettings.LunaSettings;
import org.lwjgl.input.Keyboard;

import java.util.Locale;

/**
 * The player's choices from the LunaLib settings menu (data/config/LunaSettings.csv), cached and refreshed whenever
 * LunaLib reports a change. Previews and scene settings apply from the next hover; sound, keys and logs apply at once.
 */
public final class WeaponTooltipPreviewSettings {

    /** How the dummy uses its shield. */
    public enum ShieldMode {
        ALWAYS_ON,
        ALWAYS_OFF,
        FLUCTUATING
    }

    private static final String MOD_ID = "weapon_tooltip_preview";
    private static final float DEFAULT_PREVIEW_DELAY = 0.5f;
    private static final float MAX_PREVIEW_DELAY = 2f;
    private static final String DEFAULT_DUMMY_VARIANT_ID = "gemini_Standard";
    /**
     * Radio option (as written in the CSV) → stock variant used as the dummy. Small carriers whose forward missile
     * slots are plain small or medium ones, so point defense previews can fill them all with missiles and wings.
     */
    private static final String[][] DUMMIES = {
            {"Drover", "drover_Strike"},
            {"Condor", "condor_Attack"},
            {"Gemini", "gemini_Standard"},
    };

    private static boolean previewsEnabled = true;
    private static boolean tooltipPreviewsEnabled = true;
    private static boolean campaignCodexPreviewsEnabled = true;
    private static boolean battleCodexPreviewsEnabled = true;
    private static boolean delayEnabled;
    private static float previewDelay = DEFAULT_PREVIEW_DELAY;
    private static boolean sound = true;
    private static String dummyVariantId = DEFAULT_DUMMY_VARIANT_ID;
    private static ShieldMode shieldMode = ShieldMode.FLUCTUATING;
    private static int threatModeKey = Keyboard.KEY_F5;
    private static int threatMissileKey = Keyboard.KEY_F6;
    private static int threatWingKey = Keyboard.KEY_F7;
    private static boolean listening;

    private WeaponTooltipPreviewSettings() {
    }

    /** True when the master toggle and at least one preview mode are enabled. */
    public static boolean anyPreviewEnabled() {
        return previewsEnabled && (tooltipPreviewsEnabled || campaignCodexPreviewsEnabled || battleCodexPreviewsEnabled);
    }

    /** Weapon and wing hover tooltips get a preview. */
    public static boolean tooltipPreviewsEnabled() {
        return previewsEnabled && tooltipPreviewsEnabled;
    }

    /** Codex weapon and wing pages get a preview in the given game state. */
    public static boolean codexPreviewsEnabled(GameState state) {
        if (!previewsEnabled || state == null) return false;
        if (state == GameState.COMBAT) return battleCodexPreviewsEnabled;
        if (state == GameState.CAMPAIGN || state == GameState.TITLE) return campaignCodexPreviewsEnabled;
        return false;
    }

    /** Delays opening the preview on hover, then expands with a short static animation. */
    public static boolean delayEnabled() {
        return delayEnabled;
    }

    /** Seconds to wait while hovering before expanding the preview (when {@link #delayEnabled()} is true). */
    public static float previewDelay() {
        return previewDelay;
    }

    /** The preview plays its sound, at the player's sound effects volume. */
    public static boolean sound() {
        return sound;
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
            LunaSettings.addSettingsListener(modID -> {
                if (MOD_ID.equals(modID)) read();
            });
            listening = true;
        }
        read();
    }

    private static void read() {
        Log.setEnabled(Boolean.TRUE.equals(LunaSettings.getBoolean(MOD_ID, "WeaponTooltipPreview_enableLogs")));

        previewsEnabled = !Boolean.FALSE.equals(LunaSettings.getBoolean(MOD_ID, "WeaponTooltipPreview_enablePreviews"));
        tooltipPreviewsEnabled = !Boolean.FALSE.equals(
                LunaSettings.getBoolean(MOD_ID, "WeaponTooltipPreview_enableTooltipPreviews"));
        campaignCodexPreviewsEnabled = !Boolean.FALSE.equals(
                LunaSettings.getBoolean(MOD_ID, "WeaponTooltipPreview_enableCampaignCodexPreviews"));
        battleCodexPreviewsEnabled = !Boolean.FALSE.equals(
                LunaSettings.getBoolean(MOD_ID, "WeaponTooltipPreview_enableBattleCodexPreviews"));
        delayEnabled = Boolean.TRUE.equals(LunaSettings.getBoolean(MOD_ID, "WeaponTooltipPreview_enableDelay"));
        Double delay = LunaSettings.getDouble(MOD_ID, "WeaponTooltipPreview_previewDelay");
        previewDelay = delay != null ? Math.max(0f, Math.min(MAX_PREVIEW_DELAY, delay.floatValue())) : DEFAULT_PREVIEW_DELAY;
        sound = !Boolean.FALSE.equals(LunaSettings.getBoolean(MOD_ID, "WeaponTooltipPreview_sound"));

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
        Log.info("settings: mod %s (tooltips %s, campaign codex %s, battle codex %s), delay %s (%.2fs), sound %s, dummy %s, shield %s, threat keys %s %s %s",
                previewsEnabled ? "on" : "off", tooltipPreviewsEnabled ? "on" : "off",
                campaignCodexPreviewsEnabled ? "on" : "off", battleCodexPreviewsEnabled ? "on" : "off",
                delayEnabled ? "on" : "off", previewDelay, sound ? "on" : "off",
                dummyVariantId, shieldMode, Keyboard.getKeyName(threatModeKey), Keyboard.getKeyName(threatMissileKey),
                Keyboard.getKeyName(threatWingKey));
    }

    private static int keySetting(String fieldId, int defaultKey) {
        Integer key = LunaSettings.getInt(MOD_ID, fieldId);
        return key != null ? key : defaultKey;
    }
}
