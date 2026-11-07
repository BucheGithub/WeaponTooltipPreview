package weapontooltippreview.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;
import weapontooltippreview.Log;
import weapontooltippreview.WeaponTooltipPreviewSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The ship being fired at in the preview.
 * Spawns without weapons or wings in most cases, except to showcase PD.
 * Takes damage and gets destroyed, cycling to a new dummy instance.
 */
final class TargetDummy {

    private static final float RESPAWN_DELAY = 3f;

    private final CombatEngineAPI engine;
    private final ShipAPI weaponHost;
    private final ShipVariantAPI variant;
    private final Vector2f dummyPosition;
    private final boolean drifts;
    private final boolean launchesThreats;
    private final ShieldMode shieldMode = WeaponTooltipPreviewSettings.shieldMode();

    private ShipAPI ship;
    private float respawnTimer = -1f;
    
    /** What the deployed dummy carries; null when it launches no missiles or no wing. */
    private List<String> missileLaunchers;
    private String wingId;

    /**
     * Deploys at once, facing the weapon host.
     * A drifting dummy sways across the line of fire to show guided shots.
     */
    TargetDummy(CombatEngineAPI engine, ShipAPI weaponHost, ShipVariantAPI variant, Vector2f dummyPosition,
                boolean drifts, boolean launchesThreats) {
        this.engine = engine;
        this.weaponHost = weaponHost;
        this.variant = variant;
        this.dummyPosition = dummyPosition;
        this.drifts = drifts;
        this.launchesThreats = launchesThreats;
        deploy();
    }

    /**
     * A copy of the stock variant picked in settings
     * Every weapon is removed, built-in ones too so they don't fire accidentally, and every wing
     */
    static ShipVariantAPI stockVariantWithoutWeapons() {
        ShipVariantAPI variant = Global.getSettings().getVariant(WeaponTooltipPreviewSettings.dummyVariantId()).clone();
        for (String slotId : new ArrayList<>(variant.getFittedWeaponSlots())) variant.clearSlot(slotId);
        variant.getWeaponGroups().clear();
        for (int i = 0; i < variant.getWings().size(); i++) variant.setWingId(i, null);
        return variant;
    }

    /** The shield's radius, or the hull's collision radius when it has no shield. */
    static float shieldRadius(ShipHullSpecAPI hull) {
        return hull.getShieldSpec() != null && hull.getShieldSpec().getRadius() > 0f
                ? hull.getShieldSpec().getRadius() : hull.getCollisionRadius();
    }

    ShipAPI ship() {
        return ship;
    }

    /** How far the dummy reaches from its centre: its shield or its hull, whichever is larger. */
    float radius() {
        return Math.max(shieldRadius(variant.getHullSpec()), ship.getCollisionRadius());
    }

    /** A fresh dummy; the old one's missiles and fighters go with it, so the next setup starts clean. */
    void replace() {
        for (MissileAPI m : new ArrayList<>(engine.getMissiles())) {
            if (m.getOwner() == Scenario.ENEMY_OWNER) engine.removeEntity(m);
        }
        for (ShipAPI s : new ArrayList<>(engine.getShips())) {
            if (s.isFighter() && s.getOwner() == Scenario.ENEMY_OWNER) engine.removeEntity(s);
        }
        engine.removeEntity(ship);
        respawnTimer = -1f;
        deploy();
    }

    /** Replaces a destroyed dummy after a short delay. */
    void replaceIfDestroyed(float dt) {
        if (ship.isAlive()) return;
        if (respawnTimer < 0f) {
            respawnTimer = RESPAWN_DELAY;
            Log.info("%s destroyed", variant.getHullVariantId());
        }
        respawnTimer -= dt;
        if (respawnTimer <= 0f) replace();
    }

    /** Starts over with the missile and wing picked since it deployed. */
    void replaceIfThreatsChanged() {
        if (Objects.equals(missileLaunchers, ThreatSelection.missileLaunchers())
                && Objects.equals(wingId, ThreatSelection.wingId())) {
            return;
        }
        replace();
    }

    private void deploy() {
        missileLaunchers = launchesThreats ? ThreatSelection.missileLaunchers() : null;
        wingId = launchesThreats ? ThreatSelection.wingId() : null;
        Map<String, String> launcherBySlot = forwardLaunchers();
        FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP,
                launchesThreats ? withThreats(launcherBySlot) : variant);
        ship = Scenario.deploy(engine, member, Scenario.DEPLOY_CR, Scenario.ENEMY_OWNER, dummyPosition, 180f);
        List<WeaponAPI> launchers = launchersAt(launcherBySlot.keySet());
        ship.setShipAI(new PreviewAIs.TargetAI(ship, weaponHost, dummyPosition, drifts, shieldMode, launchers));
        weaponHost.setShipTarget(ship);
        // A guided missile takes its source ship's target when launched.
        if (!launchers.isEmpty()) ship.setShipTarget(weaponHost);
        if (launchesThreats) {
            Log.info("dummy launches %s, wing %s in %d bays", launcherBySlot, wingId,
                    variant.getHullSpec().getFighterBays());
        }
    }

    /**
     * Every slot whose arc covers the bow, which faces the weapon host, with the largest of the picked launchers that
     * fits it; empty without missiles.
     */
    private Map<String, String> forwardLaunchers() {
        Map<String, String> launcherBySlot = new LinkedHashMap<>();
        if (missileLaunchers == null) return launcherBySlot;
        for (WeaponSlotAPI slot : variant.getHullSpec().getAllWeaponSlotsCopy()) {
            if (!Misc.isInArc(slot.getAngle(), slot.getArc(), 0f)) continue;
            for (int i = missileLaunchers.size() - 1; i >= 0; i--) {
                String launcherId = missileLaunchers.get(i);
                if (WeaponHostFactory.fits(slot, Global.getSettings().getWeaponSpec(launcherId))) {
                    launcherBySlot.put(slot.getId(), launcherId);
                    break;
                }
            }
        }
        return launcherBySlot;
    }

    /** A copy of the dummy's variant with the launchers, in a group of their own, and the picked wing in every bay. */
    private ShipVariantAPI withThreats(Map<String, String> launcherBySlot) {
        ShipVariantAPI loadout = variant.clone();
        if (!launcherBySlot.isEmpty()) {
            WeaponGroupSpec group = new WeaponGroupSpec(WeaponGroupType.LINKED);
            for (Map.Entry<String, String> launcher : launcherBySlot.entrySet()) {
                loadout.addWeapon(launcher.getKey(), launcher.getValue());
                group.addSlot(launcher.getKey());
            }
            loadout.addWeaponGroup(group);
        }
        if (wingId != null) {
            for (int bay = 0; bay < variant.getHullSpec().getFighterBays(); bay++) loadout.setWingId(bay, wingId);
        }
        return loadout;
    }

    /** Takes the launchers away from their group's autofire AI: the dummy's AI aims them and pulls the triggers. */
    private List<WeaponAPI> launchersAt(Set<String> slotIds) {
        List<WeaponAPI> launchers = new ArrayList<>();
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (!slotIds.contains(w.getSlot().getId())) continue;
            WeaponGroupAPI group = ship.getWeaponGroupFor(w);
            if (group != null && group.isAutofiring()) group.toggleOff();
            w.setSuspendAutomaticTurning(true);
            launchers.add(w);
        }
        return launchers;
    }

    /** Summary for the spawn log line. */
    String spawnLogDetails() {
        return String.format(Locale.ROOT, "dummy %s at x=%.0f, shield %s%s%s", variant.getHullVariantId(),
                dummyPosition.x, shieldMode, drifts ? ", drifting" : "", launchesThreats ? ", launching threats" : "");
    }

    /** Periodic diagnostics line. */
    String frameLogDetails() {
        String details = String.format(Locale.ROOT, "dummy hull=%.0f flux=%.2f shield=%s overload=%s alive=%s",
                ship.getHitpoints(), ship.getFluxLevel(), ship.getShield() != null && ship.getShield().isOn(),
                ship.getFluxTracker().isOverloaded(), ship.isAlive());
        if (!launchesThreats) return details;
        int enemyMissiles = 0;
        for (MissileAPI m : engine.getMissiles()) {
            if (m.getOwner() == Scenario.ENEMY_OWNER) enemyMissiles++;
        }
        int fighters = 0;
        for (FighterWingAPI wing : ship.getAllWings()) fighters += wing.getWingMembers().size();
        return details + String.format(Locale.ROOT, " missile=%s enemyMissiles=%d wing=%s fighters=%d",
                missileLaunchers, enemyMissiles, wingId, fighters);
    }
}
