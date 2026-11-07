package weapontooltippreview.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import weapontooltippreview.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Picks the ship that carries the previewed weapon: a copy of the refit ship, else a generic vanilla hull, else the fighter. */
final class WeaponHostFactory {

    /** Generic weapon host per weapon size. Each mounts every type of its size at that size */
    private static final String[] WEAPON_HOSTS = {"wayfarer", "vigilance", "prometheus2"};
    private static final String GENERIC_WEAPON_HOST_VARIANT_ID = Scenario.PREVIEW_SOURCE_ID + "_weapon_host";
    /** Generic carriers, smallest first: Condor (destroyer, two bays), Drover, Heron. */
    private static final String[] CARRIERS = {"condor", "drover", "heron"};
    /** Threat hullmod required for threat weapons, added to generic hulls for preview */
    private static final String[][] REQUIRED_HULLMODS = {{"fragment", "fragment_swarm"}};

    static final class WeaponHostPlan {
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

    static WeaponHostPlan plan(WeaponSpecAPI spec, RefitShip refit) {
        WeaponHostPlan plan = refit != null ? fromRefit(spec, refit) : null;
        if (plan == null) plan = generic(spec, refit != null ? refit.variant.getHullSpec() : null);
        if (plan == null) throw new NoPreviewException("no hull has a slot for " + spec.getWeaponId());
        return plan;
    }

    /** The wing's fighter, with the weapon cycled to */
    static WeaponHostPlan planFighter(FighterWingSpecAPI wing, WeaponSpecAPI spec) {
        ShipVariantAPI variant = wing.getVariant();
        WeaponSlotAPI best = null;
        for (String slotId : variant.getFittedWeaponSlots()) {
            WeaponSlotAPI slot = variant.getSlot(slotId);
            if (slot == null || !spec.getWeaponId().equals(variant.getWeaponId(slotId))) continue;
            if (best == null || Math.abs(slot.getAngle()) < Math.abs(best.getAngle())) best = slot;
        }
        if (best == null) throw new NoPreviewException(wing.getId() + " does not carry " + spec.getWeaponId());
        return new WeaponHostPlan(wing, best);
    }

    /** The ship being refitted, with its real stats */
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
            slot = builtInSlot(hull, spec);
            builtIn = slot != null;
            how = "built-in";
            if (!builtIn) {
                slot = bestSlot(hull, spec);
                how = "best fit";
            }
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

    /**The refit ship's hull, tried first for a weapon built into it. */
    private static WeaponHostPlan generic(WeaponSpecAPI spec, ShipHullSpecAPI preferred) {
        List<ShipHullSpecAPI> candidates = new ArrayList<>();
        try {
            candidates.add(Global.getSettings().getHullSpec(
                    WEAPON_HOSTS[Math.min(spec.getSize().ordinal(), WEAPON_HOSTS.length - 1)]));
        } catch (RuntimeException ignored) {
        }
        candidates.addAll(Global.getSettings().getAllShipHullSpecs());
        for (ShipHullSpecAPI hull : candidates) {
            if (!deploysAlone(hull) || hull.isDHull() || hull.getHints().contains(ShipTypeHints.SHIP_WITH_MODULES)) continue;
            WeaponSlotAPI slot = bestSlot(hull, spec);
            if (slot == null) continue;
            ShipVariantAPI variant = Global.getSettings().createEmptyVariant(GENERIC_WEAPON_HOST_VARIANT_ID, hull);
            variant.addWeapon(slot.getId(), spec.getWeaponId());
            soloWeaponGroup(variant, slot.getId());
            return new WeaponHostPlan(variant, slot, "generic" + addRequiredHullmods(variant, spec), false);
        }
        // A weapon that only exists built in fits no slot
        candidates.add(0, preferred);
        for (ShipHullSpecAPI hull : candidates) {
            if (!deploysAlone(hull)) continue;
            WeaponSlotAPI slot = builtInSlot(hull, spec);
            if (slot == null) continue;
            ShipVariantAPI variant = Global.getSettings().createEmptyVariant(GENERIC_WEAPON_HOST_VARIANT_ID, hull);
            if (!spec.getWeaponId().equals(variant.getWeaponId(slot.getId()))) variant.addWeapon(slot.getId(), spec.getWeaponId());
            soloWeaponGroup(variant, slot.getId());
            return new WeaponHostPlan(variant, slot,
                    "built-in on " + hull.getHullId() + addRequiredHullmods(variant, spec), false);
        }
        return null;
    }

    /** Installs what the weapon needs to fire on a generic weapon host (a refit ship stays as the player fitted it) */
    private static String addRequiredHullmods(ShipVariantAPI variant, WeaponSpecAPI spec) {
        StringBuilder added = new StringBuilder();
        for (String[] rule : REQUIRED_HULLMODS) {
            if (!spec.hasTag(rule[0]) || variant.hasHullMod(rule[1])) continue;
            try {
                Global.getSettings().getHullModSpec(rule[1]);
                variant.addMod(rule[1]);
                added.append(" + ").append(rule[1]);
            } catch (RuntimeException e) {
                Log.info("%s needs hullmod %s, which is unavailable: %s", spec.getWeaponId(), rule[1], e);
            }
        }
        return added.toString();
    }

    // ------------------------------------------------------------------ carriers

    /**
     * A carrier for the wing, holding it in one bay: the ship being refitted when it has fighter bays (its carrier
     * skills and hullmods apply), else a generic vanilla carrier.
     */
    static WeaponHostPlan planWing(FighterWingSpecAPI wing, RefitShip refit) {
        WeaponHostPlan plan = refit != null ? carrierFromRefit(wing, refit) : null;
        if (plan == null) plan = genericCarrier(wing);
        if (plan == null) throw new NoPreviewException("no carrier for " + wing.getId());
        return plan;
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

    /** The first vanilla carrier there is (any hull with a free bay as a fallback), the wing in its first bay. */
    private static WeaponHostPlan genericCarrier(FighterWingSpecAPI wing) {
        List<ShipHullSpecAPI> candidates = new ArrayList<>();
        for (String id : CARRIERS) {
            try {
                candidates.add(Global.getSettings().getHullSpec(id));
            } catch (RuntimeException ignored) {
            }
        }
        candidates.addAll(Global.getSettings().getAllShipHullSpecs());
        for (ShipHullSpecAPI hull : candidates) {
            if (!deploysAlone(hull) || hull.isDHull() || hull.getHints().contains(ShipTypeHints.SHIP_WITH_MODULES)
                    || hull.getFighterBays() <= 0 || !hull.getBuiltInWings().isEmpty()) {
                continue;
            }
            ShipVariantAPI variant = Global.getSettings().createEmptyVariant(Scenario.PREVIEW_SOURCE_ID + "_carrier", hull);
            variant.setWingId(0, wing.getId());
            return new WeaponHostPlan(variant, "generic carrier", false);
        }
        return null;
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

    /** A hull that deploys as a ship of its own (not a fighter, station or module). */
    private static boolean deploysAlone(ShipHullSpecAPI hull) {
        return hull != null && hull.getHullSize() != HullSize.FIGHTER && !hull.getHints().contains(ShipTypeHints.STATION)
                && !hull.getHints().contains(ShipTypeHints.MODULE);
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

    /** The hull's slot with this weapon built in, the most forward if several; null if it has none. */
    private static WeaponSlotAPI builtInSlot(ShipHullSpecAPI hull, WeaponSpecAPI spec) {
        WeaponSlotAPI best = null;
        for (Map.Entry<String, String> e : hull.getBuiltInWeapons().entrySet()) {
            if (!spec.getWeaponId().equals(e.getValue())) continue;
            WeaponSlotAPI slot = hull.getWeaponSlotAPI(e.getKey());
            if (slot != null && (best == null || Math.abs(slot.getAngle()) < Math.abs(best.getAngle()))) best = slot;
        }
        return best;
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
