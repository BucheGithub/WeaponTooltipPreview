package weapontooltippreview.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import weapontooltippreview.Log;
import weapontooltippreview.WeaponTooltipPreviewSettings;
import weapontooltippreview.WeaponTooltipPreviewSettings.ShieldMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The ship being fired at in the preview.
 * Spawns without weapons or wings, unless its loadout calls for threats (against PD) or light PD (against wings).
 * Drives its own shield, drift and salvos; the scenario replaces it once destroyed.
 */
public final class TargetDummy {

    /** What the dummy carries on its stripped hull. */
    enum Loadout {
        NONE,
        /** Every forward missile slot and fighter bay filled with the missile and wing picked in ThreatSelection. */
        THREATS,
        /** Light point defense on autofire, to show the durability of wings. */
        POINT_DEFENSE
    }

    static final float DRIFT_AMPLITUDE = 120f;
    private static final float DRIFT_PERIOD = 6f;
    private static final float SHIELD_PHASE = 6f;
    private static final float GAIN_POSITION = 1.5f;
    private static final float GAIN_VELOCITY = 0.9f;
    private static final float FIRST_SALVO_DELAY = 1f;
    private static final float SALVO_PERIOD = 6f;
    private static final float SALVO_LENGTH = 2.5f;
    private static final String[] POINT_DEFENSE_WEAPONS = {"pdlaser", "vulcan"};

    private final CombatEngineAPI engine;
    private final ShipAPI weaponHost;
    private final ShipVariantAPI variant;
    private final Vector2f dummyPosition;
    private final boolean drifts;
    private final Loadout loadout;
    private final ShieldMode shieldMode = WeaponTooltipPreviewSettings.shieldMode();

    private ShipAPI ship;
    private float time;
    private float salvoTimer;

    /** What the deployed dummy carries; null when it launches no missiles or no wing. */
    private List<String> missileLaunchers;
    private String wingId;
    private List<WeaponAPI> launchers;

    /**
     * Deploys at once, facing the weapon host.
     * A drifting dummy sways across the line of fire to show guided shots.
     */
    TargetDummy(CombatEngineAPI engine, ShipAPI weaponHost, ShipVariantAPI variant, Vector2f dummyPosition,
                boolean drifts, Loadout loadout) {
        this.engine = engine;
        this.weaponHost = weaponHost;
        this.variant = variant;
        this.dummyPosition = dummyPosition;
        this.drifts = drifts;
        this.loadout = loadout;
        deploy();
    }

    /**
     * A copy of the stock variant picked in settings
     * Every weapon and wings are removed, built-in ones too so they don't fire accidentally
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

    public ShipAPI ship() {
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
        deploy();
    }

    /** on player cycling keys. */
    boolean threatsChanged() {
        return loadout == Loadout.THREATS && !(Objects.equals(missileLaunchers, ThreatSelection.missileLaunchers())
                && Objects.equals(wingId, ThreatSelection.wingId()));
    }

    /**
     * Holds its shield on, off, or alternating, facing the weapon host, and sways or holds still.
     * Fires at the WeaponHost and keeps wings engaged.
     */
    void step(float dt) {
        if (!ship.isAlive()) return;
        time += dt;
        ShieldAPI shield = ship.getShield();
        if (shield != null) {
            boolean up = shieldMode == ShieldMode.ALWAYS_ON
                    || shieldMode == ShieldMode.FLUCTUATING && ((int) (time / SHIELD_PHASE)) % 2 == 0;
            if (up && !shield.isOn() && !ship.getFluxTracker().isOverloadedOrVenting()) shield.toggleOn();
            if (!up && shield.isOn()) shield.toggleOff();
            shield.forceFacing(VectorUtils.getAngle(ship.getLocation(), weaponHost.getLocation()));
        }
        fireOnTheBeat(dt);
        if (wingId != null) Scenario.sendWingsAt(ship, weaponHost);
        ship.setAngularVelocity(0f);
        if (!drifts) {
            ship.getVelocity().set(0f, 0f);
            return;
        }

        float wantY = dummyPosition.y + DRIFT_AMPLITUDE * (float) Math.sin(2 * Math.PI * time / DRIFT_PERIOD);
        float push = GAIN_POSITION * (wantY - ship.getLocation().y) - GAIN_VELOCITY * ship.getVelocity().y;
        if (push > 5f) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
        else if (push < -5f) ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
        ship.getVelocity().x = 0f;
    }

    /**
     * On each beat, refills every magazine and clears every cooldown then holds the trigger.
     */
    private void fireOnTheBeat(float dt) {
        salvoTimer -= dt;
        if (salvoTimer <= 0f) {
            salvoTimer = SALVO_PERIOD;
            for (WeaponAPI launcher : launchers) {
                launcher.resetAmmo();
                launcher.setRemainingCooldownTo(0f);
            }
        }
        if (salvoTimer < SALVO_PERIOD - SALVO_LENGTH) return;
        for (WeaponAPI launcher : launchers) {
            launcher.setCurrAngle(VectorUtils.getAngle(launcher.getLocation(), weaponHost.getLocation()));
            launcher.setForceFireOneFrame(true);
        }
    }

    private void deploy() {
        missileLaunchers = loadout == Loadout.THREATS ? ThreatSelection.missileLaunchers() : null;
        wingId = loadout == Loadout.THREATS ? ThreatSelection.wingId() : null;
        Map<String, String> launcherBySlot = forwardLaunchers();
        ShipVariantAPI loaded = loadout == Loadout.THREATS ? withThreats(launcherBySlot)
                : loadout == Loadout.POINT_DEFENSE ? withPointDefense() : variant;
        FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, loaded);
        ship = Scenario.deploy(engine, member, Scenario.DEPLOY_CR, Scenario.ENEMY_OWNER, dummyPosition, 180f);
        launchers = launchersAt(launcherBySlot.keySet());
        time = 0f;
        salvoTimer = FIRST_SALVO_DELAY;
        weaponHost.setShipTarget(ship);
        if (!launchers.isEmpty()) ship.setShipTarget(weaponHost);
        if (loadout == Loadout.THREATS) {
            Log.info("dummy launches %s, wing %s in %d bays", launcherBySlot, wingId,
                    variant.getHullSpec().getFighterBays());
        }
    }

    /** Every slot whose arc covers the bow, which faces the weapon host, with the largest possible missiles.*/
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
        ShipVariantAPI loaded = variant.clone();
        if (!launcherBySlot.isEmpty()) {
            WeaponGroupSpec group = new WeaponGroupSpec(WeaponGroupType.LINKED);
            for (Map.Entry<String, String> launcher : launcherBySlot.entrySet()) {
                loaded.addWeapon(launcher.getKey(), launcher.getValue());
                group.addSlot(launcher.getKey());
            }
            loaded.addWeaponGroup(group);
        }
        if (wingId != null) {
            for (int bay = 0; bay < variant.getHullSpec().getFighterBays(); bay++) loaded.setWingId(bay, wingId);
        }
        return loaded;
    }

    /** A copy of the dummy's variant with one light PD weapon in every slot that takes one. */
    private ShipVariantAPI withPointDefense() {
        ShipVariantAPI loaded = variant.clone();
        WeaponGroupSpec group = new WeaponGroupSpec(WeaponGroupType.LINKED);
        group.setAutofireOnByDefault(true);
        for (WeaponSlotAPI slot : variant.getHullSpec().getAllWeaponSlotsCopy()) {
            for (String weaponId : POINT_DEFENSE_WEAPONS) {
                if (WeaponHostFactory.fits(slot, Global.getSettings().getWeaponSpec(weaponId))) {
                    loaded.addWeapon(slot.getId(), weaponId);
                    group.addSlot(slot.getId());
                    break;
                }
            }
        }
        loaded.addWeaponGroup(group);
        return loaded;
    }

    /** The launchers at these slots, without autofire */
    private List<WeaponAPI> launchersAt(Set<String> slotIds) {
        List<WeaponAPI> found = new ArrayList<>();
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (!slotIds.contains(w.getSlot().getId())) continue;
            Scenario.takeOffAutofire(ship, w);
            found.add(w);
        }
        return found;
    }

    /** Summary for the spawn log line. */
    String spawnLogDetails() {
        return String.format(Locale.ROOT, "dummy %s at x=%.0f, shield %s, loadout %s%s", variant.getHullVariantId(),
                dummyPosition.x, shieldMode, loadout, drifts ? ", drifting" : "");
    }

    /** Periodic diagnostics line. */
    String frameLogDetails() {
        String details = String.format(Locale.ROOT, "dummy hull=%.0f flux=%.2f shield=%s overload=%s alive=%s",
                ship.getHitpoints(), ship.getFluxLevel(), ship.getShield() != null && ship.getShield().isOn(),
                ship.getFluxTracker().isOverloaded(), ship.isAlive());
        if (loadout != Loadout.THREATS) return details;
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
