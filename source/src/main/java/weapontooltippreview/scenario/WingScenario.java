package weapontooltippreview.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import java.util.Locale;

/**
 * A fighter wing harassing the dummy. 
 * The carrier holds position and keeps the dummy as its target, with its fighters ordered to engage. 
 * The dummy carries light point defense on autofire, to show wings durability
 */
final class WingScenario extends Scenario {

    /** Light PD on the carrier. */
    private static final String[] POINT_DEFENSE_WEAPONS = {"pdlaser", "vulcan"};
    /** Close enough so all fighters can attack, but beyond the dummy's PD range. */
    private static final float DUMMY_DISTANCE = 1200f;
    /** Wings with a shorter engagement range get the dummy this far into it. */
    private static final float ENGAGEMENT_SHARE = 0.9f;
    /**
     * Wings that stay with the carrier (engagement range 0) get the dummy this far into their weapons' range, counted
     * from the edge of the carrier, where they hold.
     */
    private static final float WEAPON_RANGE_SHARE = 0.8f;
    /** Least space between carrier and dummy. */
    private static final float MIN_GAP = 100f;
    /** Room past the dummy and around the line for fighters swinging round on their attack runs. */
    private static final float RUN_MARGIN = 250f;

    private final FighterWingSpecAPI wing;
    /** The wing's engagement range is 0: it escorts the carrier instead of flying out to the dummy. */
    private boolean escorts;

    WingScenario(CombatEngineAPI engine, FighterWingSpecAPI wing, WeaponHostFactory.WeaponHostPlan weaponHostPlan,
                 RefitShip refit, Vector2f origin) {
        super(engine, weaponHostPlan, refit, origin);
        this.wing = wing;
    }

    @Override
    protected void spawnScene() {
        escorts = engagementRange() < 1f;
        ShipVariantAPI dummyVariant = TargetDummy.stockVariantWithoutWeapons();
        armWithPointDefense(dummyVariant);
        float distance = dummyDistance(dummyVariant.getHullSpec().getCollisionRadius());
        dummyPosition = new Vector2f(weaponHost.getLocation().x + distance, weaponHost.getLocation().y);
        dummy = new TargetDummy(engine, weaponHost, dummyVariant, dummyPosition, false, false);
        frameScene(RUN_MARGIN, 0f);
    }

    /** How far the wing flies out: its own range, as the carrier changes it. */
    private float engagementRange() {
        return weaponHost.getMutableStats().getFighterWingRange().computeEffective(wing.getRange());
    }

    /**
     * How far out the dummy sits: {@link #DUMMY_DISTANCE}, or closer where the fighters would not reach it. Wings fly out
     * to their engagement range, the carrier's own (Defensive Targeting Array cuts it to 0). At 0 they hold by the
     * carrier, as support wings do, and only fire on what comes within their weapons' range.
     */
    private float dummyDistance(float dummyRadius) {
        float weaponHostRadius = weaponHost.getCollisionRadius();
        float closeIn = weaponHostRadius + dummyRadius + WEAPON_RANGE_SHARE * longestWeaponRange(wing);
        float distance = Math.max(ENGAGEMENT_SHARE * engagementRange(), closeIn);
        return MathUtils.clamp(distance, weaponHostRadius + dummyRadius + MIN_GAP, DUMMY_DISTANCE);
    }

    /**
     * One light PD weapon in every slot that takes one (small and medium energy or ballistic), all in one group that the
     * game sets to autofire when it builds the ship.
     */
    private static void armWithPointDefense(ShipVariantAPI variant) {
        WeaponGroupSpec group = new WeaponGroupSpec(WeaponGroupType.LINKED);
        group.setAutofireOnByDefault(true);
        for (WeaponSlotAPI slot : variant.getHullSpec().getAllWeaponSlotsCopy()) {
            for (String weaponId : POINT_DEFENSE_WEAPONS) {
                if (WeaponHostFactory.fits(slot, Global.getSettings().getWeaponSpec(weaponId))) {
                    variant.addWeapon(slot.getId(), weaponId);
                    group.addSlot(slot.getId());
                    break;
                }
            }
        }
        variant.addWeaponGroup(group);
    }

    /**
     * Escorting fighters take up stations around the carrier, which puts some wings (Xyphos) behind it: out of range
     * of the dummy, with the carrier in their line of fire. The game's own tag, which Sarissa and Mining Drone wings
     * carry and Defensive Targeting Array gives other fighters, keeps them in front, between carrier and dummy. The
     * escort maneuver reads it every frame, so fighters tagged as they launch take it up at once.
     */
    private void holdInFront() {
        for (FighterLaunchBayAPI bay : weaponHost.getLaunchBaysCopy()) {
            FighterWingAPI w = bay.getWing();
            if (w == null || !wing.getId().equals(w.getWingId())) continue;
            for (ShipAPI fighter : w.getWingMembers()) {
                if (!fighter.hasTag(Tags.WING_STAY_IN_FRONT_OF_SHIP)) fighter.addTag(Tags.WING_STAY_IN_FRONT_OF_SHIP);
            }
        }
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
        return String.format(Locale.ROOT, "wing %s (%d fighters, %s, engagement %.0f, weapons %.0f), %s, with PD",
                wing.getId(), wing.getNumFighters(), wing.getRole(), engagementRange(), longestWeaponRange(wing),
                dummy.spawnLogDetails());
    }

    @Override
    protected void stepScene(float dt) {
        // The carrier has no AI giving orders: keep its wings engaged on the dummy.
        weaponHost.setPullBackFighters(false);
        if (dummy.ship().isAlive()) {
            weaponHost.setForceCarrierTarget(dummy.ship());
            weaponHost.setForceCarrierTargetTime(1f);
        }
        if (escorts) holdInFront();
    }

    @Override
    public String frameLogDetails() {
        StringBuilder bays = new StringBuilder();
        for (FighterLaunchBayAPI bay : weaponHost.getLaunchBaysCopy()) {
            FighterWingAPI w = bay.getWing();
            if (w == null) continue;
            bays.append(String.format(Locale.ROOT, " %s %d/%d rate=%.0f%% next=%.1fs lost=%d;", w.getWingId(),
                    w.getWingMembers().size(), w.getSpec().getNumFighters(), 100f * bay.getCurrRate(),
                    bay.getTimeUntilNextReplacement(), bay.getNumLost()));
        }
        return dummy.frameLogDetails() + " | bays:" + bays;
    }
}
