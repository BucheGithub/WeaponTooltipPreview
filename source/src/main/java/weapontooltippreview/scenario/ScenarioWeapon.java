package weapontooltippreview.scenario;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.Locale;

/**
 * The weapon against a weaponless dummy at its max range.
 * Dummy gets damaged and destroyed.
 * For PD weapons, dummy throws missiles, wings, or both.
 */
final class ScenarioWeapon extends Scenario {

    /** How far the range arc cuts into the target's shield bubble, as a fraction of the shield radius. */
    private static final float RANGE_OVERLAP = 0.65f;

    /** Weapons reaching this far may show as a split view; shorter ones are framed at true scale in one pane. */
    private static final float SPLIT_VIEW_MIN_RANGE = 2000f;

    ScenarioWeapon(CombatEngineAPI engine, WeaponHost weaponHost) {
        super(engine, weaponHost);
    }

    /** The weapon's shots home in (its tooltip lists a tracking rating other than "None"). */
    private static boolean tracks(WeaponSpecAPI spec) {
        String rating = spec.getTrackingStr();
        return rating != null && !rating.trim().isEmpty() && !rating.trim().equalsIgnoreCase("None");
    }

    @Override
    protected void spawnScene() {
        WeaponAPI weapon = weaponHost.weapon();
        ShipVariantAPI dummyVariant = TargetDummy.stockVariantWithoutWeapons();
        boolean drifts = tracks(weapon.getSpec());
        Vector2f mount = weapon.getSlot().computePosition(weaponHost.ship());
        float shieldRadius = TargetDummy.shieldRadius(dummyVariant.getHullSpec());
        dummyPosition = new Vector2f(mount.x + weapon.getRange() + shieldRadius * (1f - RANGE_OVERLAP), mount.y);
        targetDummy = new TargetDummy(engine, weaponHost.ship(), dummyVariant, dummyPosition, drifts,
                weaponHost.pointDefense() ? TargetDummy.Loadout.THREATS : TargetDummy.Loadout.NONE);
        frameScene(0f, drifts ? TargetDummy.DRIFT_AMPLITUDE : 0f);

        // Long-range weapons only split the screen to showcase high range
        canSplit = !weaponHost.pointDefense() && weapon.getRange() >= SPLIT_VIEW_MIN_RANGE;
    }

    @Override
    protected String spawnLogDetails() {
        return String.format(Locale.ROOT, "tracking '%s'%s", weaponHost.weapon().getSpec().getTrackingStr(),
                weaponHost.pointDefense() ? ", point defense" : "");
    }
}
