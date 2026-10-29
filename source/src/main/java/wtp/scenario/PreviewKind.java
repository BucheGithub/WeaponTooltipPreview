package wtp.scenario;

import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import java.util.Set;

/** Which scene shows a weapon best, from its AI hints. */
public enum PreviewKind {
    /** Fires at the dummy at max range (guided missiles: the dummy drifts so the tracking shows). */
    SHIP,
    /** PD, PD_ONLY, PD_ALSO: the ship scene, plus waves of missiles and fighters the weapon engages first. */
    POINT_DEFENSE;

    public static PreviewKind of(WeaponSpecAPI spec) {
        Set<AIHints> hints = spec.getAIHints();
        if (hints.contains(AIHints.PD) || hints.contains(AIHints.PD_ONLY) || hints.contains(AIHints.PD_ALSO)) {
            return POINT_DEFENSE;
        }
        return SHIP;
    }
}
