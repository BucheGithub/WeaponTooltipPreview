package weapontooltippreview.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * What the dummy launches at a point defense weapon.
 * Players can switch with F5, F6, F7.
 * Weapons and wings are added to the dummy, so cycling requires a new setup.
 */
public final class ThreatSelection {

    public enum Mode {
        MISSILES_ONLY("Missiles Only", true, false),
        WINGS_ONLY("Wings Only", false, true),
        MISSILES_AND_WINGS("Missiles & Wings", true, true);

        public final String label;
        public final boolean missiles;
        public final boolean wings;

        Mode(String label, boolean missiles, boolean wings) {
            this.label = label;
            this.missiles = missiles;
            this.wings = wings;
        }
    }

    /**Label, then the launcher ids from small to medium. */
    private static final String[][] MISSILES = {
            {"Harpoon", "harpoon", "harpoonpod"},
            {"Swarmer", "swarmer"},
            {"Annihilator", "annihilator", "annihilatorpod"},
            {"Reaper", "reaper", "typhoon"},
            {"Hammer", "hammer", "jackhammer"},
    };
    private static final String[] WINGS = {"broadsword_wing", "talon_wing", "wasp_wing"};

    private static Mode mode = Mode.MISSILES_ONLY;
    private static int missileIndex;
    private static int wingIndex;
    /** Goes up with every change, so the CTA redraws the next label. */
    private static int version;

    private ThreatSelection() {
    }

    /** Point defense weapons are shown against missiles and wings, not only the dummy. */
    public static boolean appliesTo(PreviewSubject subject) {
        return subject.weaponId != null && isPointDefense(Global.getSettings().getWeaponSpec(subject.weaponId));
    }

    static boolean isPointDefense(WeaponSpecAPI spec) {
        Set<AIHints> hints = spec.getAIHints();
        return hints.contains(AIHints.PD) || hints.contains(AIHints.PD_ONLY) || hints.contains(AIHints.PD_ALSO);
    }

    public static void cycleMode() {
        mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
        version++;
    }

    public static void cycleMissile() {
        missileIndex = (missileIndex + 1) % MISSILES.length;
        version++;
    }

    public static void cycleWing() {
        wingIndex = (wingIndex + 1) % WINGS.length;
        version++;
    }

    public static int version() {
        return version;
    }

    public static Mode mode() {
        return mode;
    }

    public static String missileLabel() {
        return MISSILES[missileIndex][0];
    }

    /** The fighter's name */
    public static String wingLabel() {
        return Global.getSettings().getFighterWingSpec(WINGS[wingIndex]).getVariant().getHullSpec().getHullName();
    }

    /** The missiles the dummy carries, sending the list so the dummy handles the small/med pick */
    static List<String> missileLaunchers() {
        String[] missile = MISSILES[missileIndex];
        return mode.missiles ? Arrays.asList(missile).subList(1, missile.length) : null;
    }

    /** The wing the dummy carries */
    static String wingId() {
        return mode.wings ? WINGS[wingIndex] : null;
    }
}
