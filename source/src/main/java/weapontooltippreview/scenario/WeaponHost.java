package weapontooltippreview.scenario;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.util.Misc;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;

import java.util.Locale;

/**
 * The ship showing the previewed weapon, or the previewed wing.
 * Holds still, never dies, never runs out of CR or flux, and keeps its shield down.
 * Only the previewed weapon fires, aimed here rather than by the game's autofire AI.
 */
public final class WeaponHost {

    /** Super high damage reduction so the weapon host is targetable but never dies */
    private static final float DAMAGE_MULT = 1e-6f;
    /** Negating emp damage */
    private static final float EMP_MULT = 0f;
    /** Negating armor damage */
    private static final float ARMOR_MULT = 0f;
    /** Extending CR */
    private static final float CR_FLAT = 1e6f;
    /** Extending SightRadius */
    private static final float SIGHT_FLAT = 8500f;
    private static final float AIM_TOLERANCE_DEGREES = 2f;
    private static final float THREAT_RANGE_FACTOR = 1.25f;

    private final CombatEngineAPI engine;
    private final ShipAPI ship;
    /** The previewed weapon; null on a carrier. */
    private final WeaponAPI weapon;
    /** The previewed wing; null when a weapon is previewed. */
    private final FighterWingSpecAPI wing;
    /** Where the ship comes from (refit copy, generic hull...), for the logs. */
    private final String source;
    /** Point defense: engages the dummy's missiles and fighters before the dummy. */
    private final boolean pointDefense;
    /** Engages fighters before missiles. */
    private final boolean antiFighter;
    /** The wing's engagement range is 0: it escorts the carrier instead of flying out to the dummy. */
    private final boolean escorts;

    WeaponHost(CombatEngineAPI engine, ShipAPI ship, WeaponAPI weapon, FighterWingSpecAPI wing, String source) {
        this.engine = engine;
        this.ship = ship;
        this.weapon = weapon;
        this.wing = wing;
        this.source = source;
        MutableShipStatsAPI stats = ship.getMutableStats();
        stats.getHullDamageTakenMult().modifyMult(Scenario.PREVIEW_SOURCE_ID, DAMAGE_MULT);
        stats.getArmorDamageTakenMult().modifyMult(Scenario.PREVIEW_SOURCE_ID, ARMOR_MULT);
        stats.getEmpDamageTakenMult().modifyMult(Scenario.PREVIEW_SOURCE_ID, EMP_MULT);
        stats.getPeakCRDuration().modifyFlat(Scenario.PREVIEW_SOURCE_ID, CR_FLAT);
        stats.getSightRadiusMod().modifyFlat(Scenario.PREVIEW_SOURCE_ID, SIGHT_FLAT);
        if (ship.getShield() != null && ship.getShield().isOn()) ship.getShield().toggleOff();
        // Only the previewed weapon may fire, the others stay disabled for the whole scene.
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w != weapon && !w.isDecorative()) w.setForceDisabled(true);
        }
        if (weapon != null) Scenario.takeOffAutofire(ship, weapon);
        pointDefense = weapon != null && ThreatSelection.isPointDefense(weapon.getSpec());
        antiFighter = weapon != null && weapon.getSpec().getAIHints().contains(AIHints.ANTI_FTR);
        escorts = wing != null && engagementRange() < 1f;
    }

    public ShipAPI ship() {
        return ship;
    }

    public WeaponAPI weapon() {
        return weapon;
    }

    FighterWingSpecAPI wing() {
        return wing;
    }

    boolean pointDefense() {
        return pointDefense;
    }

    /** How far the wing flies out: its own range, as the carrier changes it. */
    float engagementRange() {
        return ship.getMutableStats().getFighterWingRange().computeEffective(wing.getRange());
    }

    /** Holds still with empty flux, and fires the weapon or sends the wings at the dummy. */
    void step(float dt, ShipAPI dummy) {
        ship.getVelocity().set(0f, 0f);
        ship.setAngularVelocity(0f);
        ship.getFluxTracker().setHardFlux(0f);
        ship.getFluxTracker().setCurrFlux(0f);
        if (weapon != null) {
            aimAndFire(dt, dummy);
            return;
        }
        if (dummy.isAlive()) Scenario.sendWingsAt(ship, dummy);
        if (escorts) holdInFront();
    }

    /** A weapon that cannot reload has fired its whole magazine, and its last shot is gone. */
    boolean magazineSpent() {
        if (weapon == null || !weapon.usesAmmo() || weapon.getAmmo() > 0
                || weapon.getAmmoTracker().getAmmoPerSecond() > 0f) {
            return false;
        }
        for (DamagingProjectileAPI p : engine.getProjectiles()) {
            if (p.getWeapon() == weapon) return false;
        }
        return true;
    }

    void refillMagazine() {
        if (weapon != null && weapon.usesAmmo()) weapon.resetAmmo();
    }

    /** Point defense first, otherwise the dummy.*/
    private void aimAndFire(float dt, ShipAPI dummy) {
        CombatEntityAPI threat = pointDefense
                ? pickThreat(weapon.getLocation(), weapon.getRange() * THREAT_RANGE_FACTOR) : null;
        if (threat != null) {
            Vector2f aim = lead(threat);
            float radius = Misc.getTargetingRadius(weapon.getLocation(), threat, true);
            float distance = MathUtils.getDistance(weapon.getLocation(), threat.getLocation());
            boolean inRange = distance <= weapon.getRange() + radius;
            // Small, close targets: anywhere on their silhouette will do.
            float tolerance = Math.max(AIM_TOLERANCE_DEGREES, Misc.computeAngleSpan(radius, Math.max(1f, distance)));
            turnAndFire(aim, dt, inRange && weapon.distanceFromArc(aim) == 0f, tolerance);
        } else if (dummy.isAlive()) {
            turnAndFire(dummy.getLocation(), dt, true, AIM_TOLERANCE_DEGREES);
        }
    }

    /** What PD engages. The nearest enemy missile, else the nearest live enemy fighter. Inverted for anti-fighters.*/
    private CombatEntityAPI pickThreat(Vector2f from, float range) {
        CombatEntityAPI missile = null, fighter = null;
        float missileD2 = Float.MAX_VALUE, fighterD2 = Float.MAX_VALUE;
        for (MissileAPI m : CombatUtils.getMissilesWithinRange(from, range)) {
            if (m.getOwner() != Scenario.ENEMY_OWNER || m.isFizzling() || m.didDamage()) continue;
            float d2 = MathUtils.getDistanceSquared(from, m.getLocation());
            if (d2 < missileD2) {
                missileD2 = d2;
                missile = m;
            }
        }
        for (ShipAPI s : CombatUtils.getShipsWithinRange(from, range)) {
            if (!s.isFighter() || s.getOwner() != Scenario.ENEMY_OWNER || !s.isAlive()) continue;
            float d2 = MathUtils.getDistanceSquared(from, s.getLocation());
            if (d2 < fighterD2) {
                fighterD2 = d2;
                fighter = s;
            }
        }
        if (antiFighter) return fighter != null ? fighter : missile;
        return missile != null ? missile : fighter;
    }

    /** Turns the weapon toward the target point. */
    private void turnAndFire(Vector2f point, float dt, boolean mayFire, float tolerance) {
        float desired = VectorUtils.getAngle(weapon.getLocation(), point);
        if (weapon.getArc() < 359f) {
            float centre = ship.getFacing() + weapon.getArcFacing();
            float half = weapon.getArc() / 2f;
            desired = centre + MathUtils.clamp(MathUtils.getShortestRotation(centre, desired), -half, half);
        }
        float maxTurn = weapon.getTurnRate() * dt;
        float current = weapon.getCurrAngle();
        weapon.setCurrAngle(current + MathUtils.clamp(MathUtils.getShortestRotation(current, desired), -maxTurn, maxTurn));
        boolean linedUp = weapon.getTurnRate() <= 0f || Misc.getAngleDiff(weapon.getCurrAngle(), desired) < tolerance;
        if (mayFire && linedUp) weapon.setForceFireOneFrame(true);
    }

    /** Where to aim to meet a moving entity with this weapon's shots. */
    private Vector2f lead(CombatEntityAPI e) {
        float speed = weapon.getProjectileSpeed();
        if (weapon.isBeam() || speed <= 1f) return e.getLocation();
        Vector2f intercept = AIUtils.getBestInterceptPoint(weapon.getLocation(), speed, e.getLocation(), e.getVelocity());
        return intercept != null ? intercept : e.getLocation();
    }

    /** Forcing escort fighters in front. */
    private void holdInFront() {
        for (FighterLaunchBayAPI bay : ship.getLaunchBaysCopy()) {
            FighterWingAPI w = bay.getWing();
            if (w == null || !wing.getId().equals(w.getWingId())) continue;
            for (ShipAPI fighter : w.getWingMembers()) {
                if (!fighter.hasTag(Tags.WING_STAY_IN_FRONT_OF_SHIP)) fighter.addTag(Tags.WING_STAY_IN_FRONT_OF_SHIP);
            }
        }
    }

    /** Summary for the spawn log line. */
    String spawnLogDetails() {
        String details = String.format(Locale.ROOT, "weaponHost=%s (%s) at (%.0f,%.0f)", ship.getHullSpec().getHullId(),
                source, ship.getLocation().x, ship.getLocation().y);
        if (weapon == null) return details;
        return details + String.format(Locale.ROOT, ", slot=%s range=%.0f", weapon.getSlot().getId(), weapon.getRange());
    }

    /** Periodic diagnostics: the weapon's state, or the carrier's bays. */
    String frameLogDetails() {
        if (weapon != null) {
            return String.format(Locale.ROOT, "weapon disabled=%s firing=%s ammo=%d/%d", weapon.isDisabled(),
                    weapon.isFiring(), weapon.usesAmmo() ? weapon.getAmmo() : -1,
                    weapon.usesAmmo() ? weapon.getMaxAmmo() : -1);
        }
        StringBuilder bays = new StringBuilder("bays:");
        for (FighterLaunchBayAPI bay : ship.getLaunchBaysCopy()) {
            FighterWingAPI w = bay.getWing();
            if (w == null) continue;
            bays.append(String.format(Locale.ROOT, " %s %d/%d rate=%.0f%% next=%.1fs lost=%d;", w.getWingId(),
                    w.getWingMembers().size(), w.getSpec().getNumFighters(), 100f * bay.getCurrRate(),
                    bay.getTimeUntilNextReplacement(), bay.getNumLost()));
        }
        return bays.toString();
    }
}
