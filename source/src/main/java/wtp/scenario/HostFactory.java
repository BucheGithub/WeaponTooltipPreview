package wtp.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import wtp.Log;

import java.util.ArrayList;
import java.util.List;

/** Picks the ship that carries the previewed weapon: a copy of the refit ship, else a generic vanilla hull. */
final class HostFactory {

    /**
     * Generic host per weapon size. Each mounts every type of its size at that size, ballistic and energy on wide
     * turrets so point defense can track: Wayfarer (ballistic 160°, hybrid 140°, universal 150-160° turrets),
     * Vigilance (hybrid turret 270°, missile hardpoint), Prometheus Mk.II (hybrid turrets 220°, missile hardpoint).
     * Any hull with a fitting slot is the fallback (modded weapons with unusual mounts).
     */
    private static final String[] HOSTS = {"wayfarer", "vigilance", "prometheus2"};

    static final class HostPlan {
        final ShipVariantAPI variant;
        /** Slot angle relative to the ship; the host is spawned rotated by its opposite so the arc faces +x. */
        final float slotAngle;
        final String source;
        /** True when the variant is a copy of the refit ship (its officer and fleet bonuses apply). */
        final boolean fromRefit;

        HostPlan(ShipVariantAPI variant, float slotAngle, String source, boolean fromRefit) {
            this.variant = variant;
            this.slotAngle = slotAngle;
            this.source = source;
            this.fromRefit = fromRefit;
        }
    }

    private HostFactory() {
    }

    static HostPlan plan(WeaponSpecAPI spec, RefitShip refit) {
        HostPlan plan = refit != null ? fromRefit(spec, refit) : null;
        if (plan == null) plan = generic(spec);
        if (plan == null) throw new NoPreviewException("no hull has a slot for " + spec.getWeaponId());
        return plan;
    }

    /**
     * The ship being refitted, stripped of every removable weapon and wing, with the previewed weapon in the clicked
     * slot (or its best fitting slot). Hullmods, s-mods and built-ins stay, so the weapon gets the ship's real stats.
     */
    private static HostPlan fromRefit(WeaponSpecAPI spec, RefitShip refit) {
        ShipVariantAPI variant = refit.variant.clone();
        if (!variant.getModuleSlots().isEmpty()) {
            Log.debug("refit host %s has modules; using a generic host", variant.getHullVariantId());
            return null;
        }
        for (String slotId : new ArrayList<>(variant.getFittedWeaponSlots())) {
            WeaponSlotAPI slot = variant.getSlot(slotId);
            if (slot == null || !slot.isBuiltIn()) variant.clearSlot(slotId);
        }
        for (int i = 0; i < variant.getWings().size(); i++) {
            try {
                variant.setWingId(i, null);
            } catch (RuntimeException ignored) {
            }
        }
        WeaponSlotAPI slot = refit.slot != null ? variant.getSlot(refit.slot.getId()) : null;
        String how = "clicked";
        if (slot == null || !fits(slot, spec)) {
            slot = bestSlot(variant.getHullSpec(), spec);
            how = "best fit";
        }
        if (slot == null) {
            Log.debug("refit host %s has no slot for %s; using a generic host", variant.getHullVariantId(), spec.getWeaponId());
            return null;
        }
        variant.addWeapon(slot.getId(), spec.getWeaponId());
        soloWeaponGroup(variant, slot.getId());
        return new HostPlan(variant, slot.getAngle(), "refit " + refit.member.getShipName() + ", " + how + " slot", true);
    }

    private static HostPlan generic(WeaponSpecAPI spec) {
        List<ShipHullSpecAPI> candidates = new ArrayList<>();
        try {
            candidates.add(Global.getSettings().getHullSpec(HOSTS[Math.min(spec.getSize().ordinal(), HOSTS.length - 1)]));
        } catch (RuntimeException ignored) {
        }
        candidates.addAll(Global.getSettings().getAllShipHullSpecs());
        for (ShipHullSpecAPI hull : candidates) {
            if (hull == null || hull.isDHull() || hull.getHullSize() == HullSize.FIGHTER
                    || hull.getHints().contains(ShipTypeHints.STATION) || hull.getHints().contains(ShipTypeHints.MODULE)
                    || hull.getHints().contains(ShipTypeHints.SHIP_WITH_MODULES)) {
                continue;
            }
            WeaponSlotAPI slot = bestSlot(hull, spec);
            if (slot == null) continue;
            ShipVariantAPI variant = Global.getSettings().createEmptyVariant(Scenario.MOD_ID + "_host", hull);
            variant.addWeapon(slot.getId(), spec.getWeaponId());
            soloWeaponGroup(variant, slot.getId());
            return new HostPlan(variant, slot.getAngle(), "generic", false);
        }
        return null;
    }

    /**
     * The previewed weapon alone in one group: built-ins stay out of it (they are silenced anyway), and the combat
     * arc renderer, which draws a whole group, shows only this weapon.
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

    /** The game's own mounting rule (type compatibility, same size or one size up), minus non-weapon slots. */
    private static boolean fits(WeaponSlotAPI slot, WeaponSpecAPI spec) {
        if (!slot.isWeaponSlot() || slot.isBuiltIn() || slot.isDecorative() || slot.isSystemSlot() || slot.isHidden()) {
            return false;
        }
        return slot.weaponFits(spec);
    }
}
