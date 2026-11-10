package weapontooltippreview.scenario;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import java.util.Locale;

/**
 * A fighter wing harassing the dummy.
 * The carrier holds position and keeps the dummy as its target, with its fighters ordered to engage.
 * The dummy carries light point defense on autofire, to show wings durability.
 */
final class ScenarioWing extends Scenario {

    /** Close enough so all fighters can attack, but beyond the dummy's PD range. */
    private static final float DUMMY_DISTANCE = 1200f;
    /** Wings with a shorter engagement range get the dummy this far into it. */
    private static final float ENGAGEMENT_SHARE = 0.9f;
    /** For Wings with Range = 0, this is the ratio for their weapon range */
    private static final float WEAPON_RANGE_SHARE = 0.8f;
    /** Least space between carrier and dummy. */
    private static final float MIN_GAP = 100f;
    /** Room past the dummy and around the line for fighters swinging round on their attack runs. */
    private static final float RUN_MARGIN = 250f;

    ScenarioWing(CombatEngineAPI engine, WeaponHost weaponHost) {
        super(engine, weaponHost);
    }

    @Override
    protected void spawnScene() {
        ShipAPI carrier = weaponHost.ship();
        ShipVariantAPI dummyVariant = TargetDummy.stockVariantWithoutWeapons();
        float distance = dummyDistance(dummyVariant.getHullSpec().getCollisionRadius());
        dummyPosition = new Vector2f(carrier.getLocation().x + distance, carrier.getLocation().y);
        targetDummy = new TargetDummy(engine, carrier, dummyVariant, dummyPosition, false, TargetDummy.Loadout.POINT_DEFENSE);
        frameScene(RUN_MARGIN, 0f);
    }

    /** How far out the dummy sits based on ranges and factors */
    private float dummyDistance(float dummyRadius) {
        float weaponHostRadius = weaponHost.ship().getCollisionRadius();
        float closeIn = weaponHostRadius + dummyRadius + WEAPON_RANGE_SHARE * longestWeaponRange(weaponHost.wing());
        float distance = Math.max(ENGAGEMENT_SHARE * weaponHost.engagementRange(), closeIn);
        return MathUtils.clamp(distance, weaponHostRadius + dummyRadius + MIN_GAP, DUMMY_DISTANCE);
    }

    /** The fighter's longest weapon range, as fitted in the wing's variant (before any carrier or skill bonus). */
    private static float longestWeaponRange(FighterWingSpecAPI wing) {
        ShipVariantAPI variant = wing.getVariant();
        float longest = 0f;
        for (String slotId : variant.getFittedWeaponSlots()) {
            WeaponSpecAPI weapon = variant.getWeaponSpec(slotId);
            if (weapon != null) longest = Math.max(longest, weapon.getMaxRange());
        }
        return longest;
    }

    @Override
    protected String spawnLogDetails() {
        FighterWingSpecAPI wing = weaponHost.wing();
        return String.format(Locale.ROOT, "wing %s (%d fighters, %s, engagement %.0f, weapons %.0f)", wing.getId(),
                wing.getNumFighters(), wing.getRole(), weaponHost.engagementRange(), longestWeaponRange(wing));
    }
}
