package weapontooltippreview.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * What the dummy launches at a point defense weapon, picked by the player with three keys while such a preview shows:
 * missiles, wings or both, which missile and which wing. Kept for the game session, across tooltips. The dummy carries
 * the launchers and wings for real (see {@link TargetDummy}) and starts over whenever what it carries changes.
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

    /**
     * Label, then the launcher ids from small to medium (the Swarmer only comes small). The labels are short: the
     * game's own names ("Annihilator Rocket Launcher") are too long for the hint line.
     */
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
    /** Goes up with every change, so the hint lines know to redraw. */
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

    /** The fighter's name ("Broadsword"), shorter than the wing's ("Broadsword Wing"). */
    public static String wingLabel() {
        return Global.getSettings().getFighterWingSpec(WINGS[wingIndex]).getVariant().getHullSpec().getHullName();
    }

    /** The picked missile's launcher ids from small to medium; null in Wings Only. */
    static List<String> missileLaunchers() {
        String[] missile = MISSILES[missileIndex];
        return mode.missiles ? Arrays.asList(missile).subList(1, missile.length) : null;
    }

    /** The wing the dummy carries in every bay; null in Missiles Only. */
    static String wingId() {
        return mode.wings ? WINGS[wingIndex] : null;
    }
}
