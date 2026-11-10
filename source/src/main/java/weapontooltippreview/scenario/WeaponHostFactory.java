package weapontooltippreview.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.campaign.fleet.FleetData;
import com.fs.starfarer.campaign.fleet.FleetMember;
import org.lwjgl.util.vector.Vector2f;
import weapontooltippreview.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks and deploys the weapon host. For a weapon: a copy of the refit ship, else the generic host for its size; the
 * fighter for a weapon on a wing. For a wing: the refit ship when it has bays, else the generic carrier.
 */
final class WeaponHostFactory {

    /** Generic weapon host per weapon size fitting all types (small, medium, large). */
    private static final String[] WEAPON_HOSTS = {"wayfarer", "vigilance", "prometheus2"};
    private static final String GENERIC_WEAPON_HOST_VARIANT_ID = Scenario.PREVIEW_SOURCE_ID + "_weapon_host";
    /** Generic carrier: the Condor, a destroyer with two bays. */
    private static final String CARRIER = "condor";
    /** Fragment weapons only fire with this hullmod: the generic host gets it, a refit ship stays as fitted. */
    private static final String FRAGMENT_TAG = "fragment";
    private static final String FRAGMENT_HULLMOD = "fragment_swarm";

    private static final class WeaponHostPlan {
        final ShipVariantAPI variant;
        final String source;
        /** The previewed weapon's slot: the weapon host may carry the same weapon elsewhere (built-ins). */
        final String slotId;
        /** Slot angle relative to the ship; the weapon host is spawned rotated by its opposite so the arc faces +x. */
        final float slotAngle;
        /** True when the variant is a copy of the refit ship (its officer and fleet bonuses apply). */
        final boolean fromRefit;
        /** The wing to deploy when the weapon host is a fighter; null for a ship. */
        final String wingId;

        WeaponHostPlan(ShipVariantAPI variant, WeaponSlotAPI slot, String source, boolean fromRefit) {
            this.variant = variant;
            this.slotId = slot.getId();
            this.slotAngle = slot.getAngle();
            this.source = source;
            this.fromRefit = fromRefit;
            this.wingId = null;
        }

        /** A carrier: no previewed weapon, facing toward the dummy. */
        WeaponHostPlan(ShipVariantAPI variant, String source, boolean fromRefit) {
            this.variant = variant;
            this.slotId = null;
            this.slotAngle = 0f;
            this.source = source;
            this.fromRefit = fromRefit;
            this.wingId = null;
        }

        /** One of the wing's fighters, firing from one of its own slots. */
        WeaponHostPlan(FighterWingSpecAPI wing, WeaponSlotAPI slot) {
            this.variant = wing.getVariant();
            this.slotId = slot.getId();
            this.slotAngle = slot.getAngle();
            this.source = "fighter of " + wing.getId();
            this.fromRefit = false;
            this.wingId = wing.getId();
        }
    }

    private WeaponHostFactory() {
    }

    /** Plans first, so a subject that cannot be shown throws {@link NoPreviewException} before anything spawns. */
    static WeaponHost create(CombatEngineAPI engine, PreviewSubject subject, RefitShip refit, Vector2f origin) {
        FighterWingSpecAPI wing = subject.wingId != null
                ? Global.getSettings().getFighterWingSpec(subject.wingId) : null;
        WeaponHostPlan plan;
        if (subject.weaponId == null) {
            plan = planWing(wing, refit);
        } else {
            WeaponSpecAPI spec = Global.getSettings().getWeaponSpec(subject.weaponId);
            plan = wing != null ? planFighter(wing, spec) : plan(spec, refit);
        }
        ShipAPI ship = deploy(engine, plan, refit, origin);
        WeaponAPI weapon = subject.weaponId != null ? weaponAt(ship, plan.slotId, subject.weaponId) : null;
        if (plan.wingId != null) settleFighter(engine, ship, weapon, plan.slotAngle, origin);
        return new WeaponHost(engine, ship, weapon, subject.weaponId == null ? wing : null, plan.source);
    }

    /** With the refit ship's officer, fleet (its skills apply) and CR when the plan copies it. */
    private static ShipAPI deploy(CombatEngineAPI engine, WeaponHostPlan plan, RefitShip refit, Vector2f origin) {
        FleetMemberAPI member = plan.wingId != null
                ? Global.getFactory().createFleetMember(FleetMemberType.FIGHTER_WING, plan.wingId)
                : Global.getFactory().createFleetMember(FleetMemberType.SHIP, plan.variant);
        float cr = Scenario.DEPLOY_CR;
        if (plan.fromRefit) {
            member.setCaptain(refit.member.getCaptain());
            if (refit.member.getFleetData() != null) {
                ((FleetMember) member).setFleetData((FleetData) refit.member.getFleetData());
            }
            cr = Math.max(cr, refit.member.getRepairTracker().getCR());
        }
        return Scenario.deploy(engine, member, cr, Scenario.WEAPON_HOST_OWNER, origin, -plan.slotAngle);
    }

    private static WeaponAPI weaponAt(ShipAPI ship, String slotId, String weaponId) {
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.getSlot().getId().equals(slotId) && w.getId().equals(weaponId)) return w;
        }
        throw new IllegalStateException("weapon missing on spawned weapon host");
    }

    /**
     * A wing deploys whole, and every deployed ship is advanced for a moment before the fleet manager hands it over. The
     * scene keeps one fighter, put back on its spot with the weapon along the line to the dummy (+x): weapons that cannot
     * turn stay at whatever angle they had.
     */
    private static void settleFighter(CombatEngineAPI engine, ShipAPI fighter, WeaponAPI weapon, float slotAngle,
                                      Vector2f origin) {
        for (ShipAPI other : new ArrayList<>(fighter.getWing().getWingMembers())) {
            if (other != fighter) engine.removeEntity(other);
        }
        fighter.getLocation().set(origin);
        fighter.getVelocity().set(0f, 0f);
        fighter.setAngularVelocity(0f);
        fighter.setFacing(-slotAngle);
        weapon.setCurrAngle(0f);
    }

    private static WeaponHostPlan plan(WeaponSpecAPI spec, RefitShip refit) {
        WeaponHostPlan plan = refit != null ? fromRefit(spec, refit) : null;
        if (plan == null) plan = generic(spec);
        if (plan == null) throw new NoPreviewException("no weapon host has a slot for " + spec.getWeaponId());
        return plan;
    }

    /** The wing's fighter, with the weapon cycled to */
    private static WeaponHostPlan planFighter(FighterWingSpecAPI wing, WeaponSpecAPI spec) {
        ShipVariantAPI variant = wing.getVariant();
        WeaponSlotAPI best = null;
        for (String slotId : variant.getFittedWeaponSlots()) {
            WeaponSlotAPI slot = variant.getSlot(slotId);
            if (!spec.getWeaponId().equals(variant.getWeaponId(slotId))) continue;
            if (best == null || Math.abs(slot.getAngle()) < Math.abs(best.getAngle())) best = slot;
        }
        if (best == null) throw new NoPreviewException(wing.getId() + " does not carry " + spec.getWeaponId());
        return new WeaponHostPlan(wing, best);
    }

    /**
     * The ship being refitted, with its real stats: the weapon in the hovered slot when it is built in there or fits,
     * else in the best slot that fits.
     */
    private static WeaponHostPlan fromRefit(WeaponSpecAPI spec, RefitShip refit) {
        ShipVariantAPI variant = refit.variant.clone();
        stripModules(variant);
        stripRemovableWeapons(variant);
        for (int i = 0; i < variant.getWings().size(); i++) variant.setWingId(i, null);
        ShipHullSpecAPI hull = variant.getHullSpec();
        WeaponSlotAPI slot = refit.slot != null ? variant.getSlot(refit.slot.getId()) : null;
        String how = "clicked";
        boolean builtIn = slot != null && isBuiltInAt(hull, slot, spec);
        if (!builtIn && (slot == null || !fits(slot, spec))) {
            slot = bestSlot(hull, spec);
            how = "best fit";
        }
        if (slot == null) {
            Log.info("refit ship %s has no slot for %s; using a generic weapon host", variant.getHullVariantId(),
                    spec.getWeaponId());
            return null;
        }
        if (!builtIn) variant.addWeapon(slot.getId(), spec.getWeaponId());
        soloWeaponGroup(variant, slot.getId());
        return new WeaponHostPlan(variant, slot, "refit " + refit.member.getShipName() + ", " + how + " slot", true);
    }

    /** The generic weapon host for the weapon's size, the weapon in its best slot; null if none fits. */
    private static WeaponHostPlan generic(WeaponSpecAPI spec) {
        ShipHullSpecAPI host = Global.getSettings().getHullSpec(WEAPON_HOSTS[spec.getSize().ordinal()]);
        WeaponSlotAPI slot = bestSlot(host, spec);
        if (slot == null) return null;
        ShipVariantAPI variant = Global.getSettings().createEmptyVariant(GENERIC_WEAPON_HOST_VARIANT_ID, host);
        variant.addWeapon(slot.getId(), spec.getWeaponId());
        soloWeaponGroup(variant, slot.getId());
        boolean fragment = spec.hasTag(FRAGMENT_TAG);
        if (fragment) variant.addMod(FRAGMENT_HULLMOD);
        return new WeaponHostPlan(variant, slot, fragment ? "generic + " + FRAGMENT_HULLMOD : "generic", false);
    }

    // ------------------------------------------------------------------ carriers

    /**
     * A carrier for the wing, holding it in one bay: the ship being refitted when it has fighter bays (its carrier
     * skills and hullmods apply), else the generic carrier.
     */
    private static WeaponHostPlan planWing(FighterWingSpecAPI wing, RefitShip refit) {
        WeaponHostPlan plan = refit != null ? carrierFromRefit(wing, refit) : null;
        return plan != null ? plan : genericCarrier(wing);
    }

    /**
     * The ship being refitted, stripped like a weapon host, with the wing in its first bay that is not built in. A wing
     * built into the hull is previewed where it is; other built-in wings stay, as built-in weapons do.
     */
    private static WeaponHostPlan carrierFromRefit(FighterWingSpecAPI wing, RefitShip refit) {
        // Bays from hullmods (Converted Hangar...) count: the member's stats hold the total.
        int bays = Math.round(refit.member.getStats().getNumFighterBays().getModifiedValue());
        if (bays <= 0) return null;
        ShipVariantAPI variant = refit.variant.clone();
        stripModules(variant);
        stripRemovableWeapons(variant);
        ShipHullSpecAPI hull = variant.getHullSpec();
        boolean builtIn = hull.getBuiltInWings().contains(wing.getId());
        int bay = -1;
        for (int i = 0; i < bays; i++) {
            if (hull.isBuiltInWing(i)) continue;
            variant.setWingId(i, null);
            if (bay < 0) bay = i;
        }
        if (!builtIn) {
            if (bay < 0) return null;
            variant.setWingId(bay, wing.getId());
        }
        return new WeaponHostPlan(variant,
                "refit " + refit.member.getShipName() + (builtIn ? ", built-in wing" : ", bay " + bay), true);
    }

    /** The generic carrier, the wing in its first bay. */
    private static WeaponHostPlan genericCarrier(FighterWingSpecAPI wing) {
        ShipVariantAPI variant = Global.getSettings().createEmptyVariant(Scenario.PREVIEW_SOURCE_ID + "_carrier",
                Global.getSettings().getHullSpec(CARRIER));
        variant.setWingId(0, wing.getId());
        return new WeaponHostPlan(variant, "generic carrier", false);
    }

    /** Every weapon but the built-in ones. */
    private static void stripRemovableWeapons(ShipVariantAPI variant) {
        for (String slotId : new ArrayList<>(variant.getFittedWeaponSlots())) {
            WeaponSlotAPI slot = variant.getSlot(slotId);
            if (slot == null || !slot.isBuiltIn()) variant.clearSlot(slotId);
        }
    }

    /**
     * Ships with modules are previewed without them, as if they were already destroyed. A clone owns its module map
     * and module variants (HullVariantSpec.clone copies both), so the real ship keeps its modules.
     */
    private static void stripModules(ShipVariantAPI variant) {
        List<String> slots = new ArrayList<>(variant.getModuleSlots());
        if (slots.isEmpty()) return;
        for (String slotId : slots) variant.setModuleVariant(slotId, null);
        variant.getStationModules().clear();
        Log.info("refit ship %s: %d modules left out", variant.getHullVariantId(), slots.size());
    }

    /**
     * The previewed weapon alone in one group: other built-ins stay out of it (they are silenced anyway), and the
     * combat arc renderer, which draws a whole group, shows only this weapon.
     */
    private static void soloWeaponGroup(ShipVariantAPI variant, String slotId) {
        variant.getWeaponGroups().clear();
        WeaponGroupSpec group = new WeaponGroupSpec(WeaponGroupType.LINKED);
        group.addSlot(slotId);
        variant.addWeaponGroup(group);
    }

    /** A slot that accepts the weapon, preferring its own size (a one-size-up mount looks odd), then the most forward. */
    private static WeaponSlotAPI bestSlot(ShipHullSpecAPI hull, WeaponSpecAPI spec) {
        WeaponSlotAPI best = null;
        for (WeaponSlotAPI slot : hull.getAllWeaponSlotsCopy()) {
            if (!fits(slot, spec)) continue;
            if (best == null || better(slot, best, spec)) best = slot;
        }
        return best;
    }

    private static boolean better(WeaponSlotAPI a, WeaponSlotAPI b, WeaponSpecAPI spec) {
        boolean aExact = a.getSlotSize() == spec.getSize();
        boolean bExact = b.getSlotSize() == spec.getSize();
        if (aExact != bExact) return aExact;
        return Math.abs(a.getAngle()) < Math.abs(b.getAngle());
    }

    private static boolean isBuiltInAt(ShipHullSpecAPI hull, WeaponSlotAPI slot, WeaponSpecAPI spec) {
        return spec.getWeaponId().equals(hull.getBuiltInWeapons().get(slot.getId()));
    }

    /** The game's own mounting rule (type compatibility, same size or one size up), minus non-weapon slots. */
    static boolean fits(WeaponSlotAPI slot, WeaponSpecAPI spec) {
        if (!slot.isWeaponSlot() || slot.isBuiltIn() || slot.isDecorative() || slot.isSystemSlot() || slot.isHidden()) {
            return false;
        }
        return slot.weaponFits(spec);
    }
}
