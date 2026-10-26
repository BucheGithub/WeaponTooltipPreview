package wtp.scenario;

import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import java.util.Set;

/** Which scene shows a weapon best, from its AI hints. */
public enum PreviewKind {
    /** Fires at a Hammerhead at max range (guided missiles: the target drifts so the tracking shows). */
    SHIP,
    /** PD_ALSO: the ship scene, plus missile salvos the weapon engages on autofire. */
    SHIP_WITH_SALVOS,
    /** PD / PD_ONLY: waves of missiles and fighters, engaged by the weapon's own autofire AI. */
    SWARM;

    public static PreviewKind of(WeaponSpecAPI spec) {
        Set<AIHints> hints = spec.getAIHints();
        if (hints.contains(AIHints.PD_ALSO)) return SHIP_WITH_SALVOS;
        if (hints.contains(AIHints.PD) || hints.contains(AIHints.PD_ONLY)) return SWARM;
        return SHIP;
    }
}
