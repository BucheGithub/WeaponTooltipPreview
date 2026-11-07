package weapontooltippreview.scenario;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.util.Misc;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;
import weapontooltippreview.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * The weapon against a weaponless dummy (the player's pick, Drover by default) at its max range: shield on, off or
 * cycling, real damage until destroyed, then a fresh dummy. Weapons whose shots track get a drifting dummy so the
 * guidance shows; everything else faces a stationary one. Point defense weapons also face the missiles and wing the
 * dummy launches ({@link ThreatSelection}): while any are close, the weapon engages them first (see
 * {@link #pickThreat}), then goes back to the dummy. The game's autofire AI is not used for this: with the dummy in
 * range it keeps shooting the dummy. The weapon host keeps its shield down (and takes no damage), so leakers visibly
 * hit it.
 */
final class ShipScenario extends Scenario {

    /** How far the range arc cuts into the target's shield bubble, as a fraction of the shield radius. */
    private static final float RANGE_OVERLAP = 0.65f;
    private static final float AIM_TOLERANCE_DEGREES = 2f;
    /** After a non-reloading weapon runs dry and its last shot is gone, wait this long, then start a new setup. */
    private static final float EXHAUSTED_RESET_DELAY = 2f;

    /**
     * Enemy missiles or fighters this close (in weapon ranges) take the weapon off the dummy; the margin lets the turret
     * swing onto them before they reach its range, where it fires.
     */
    private static final float THREAT_RANGE_FACTOR = 1.25f;

    /** Weapons reaching this far may show as a split view; shorter ones are framed at true scale in one pane. */
    private static final float SPLIT_VIEW_MIN_RANGE = 2000f;

    private final WeaponSpecAPI spec;
    /** Point defense: the dummy launches missiles and wings at the weapon host. */
    private boolean launchesThreats;
    /** Engages fighters before missiles. */
    private boolean antiFighter;
    private float exhaustedTimer;

    ShipScenario(CombatEngineAPI engine, WeaponSpecAPI spec, WeaponHostFactory.WeaponHostPlan weaponHostPlan,
                 RefitShip refit, Vector2f origin) {
        super(engine, weaponHostPlan, refit, origin);
        this.spec = spec;
    }

    /** The weapon's shots home in (its tooltip lists a tracking rating other than "None"). */
    private static boolean tracks(WeaponSpecAPI spec) {
        String rating = spec.getTrackingStr();
        return rating != null && !rating.trim().isEmpty() && !rating.trim().equalsIgnoreCase("None");
    }

    @Override
    protected void spawnScene() {
        weapon = previewedWeapon();
        if (weaponHostPlan.wingId != null) settleFighter();
        launchesThreats = ThreatSelection.isPointDefense(spec);
        antiFighter = spec.getAIHints().contains(AIHints.ANTI_FTR);

        // Place the target before spawning it: moving a ship afterwards leaves the engine's spatial grid stale,
        // and weapon AI / missile guidance look targets up through that grid.
        ShipVariantAPI dummyVariant = TargetDummy.stockVariantWithoutWeapons();
        boolean drifts = tracks(spec);
        Vector2f mount = weapon.getSlot().computePosition(weaponHost);
        float shieldRadius = TargetDummy.shieldRadius(dummyVariant.getHullSpec());
        dummyPosition = new Vector2f(mount.x + weapon.getRange() + shieldRadius * (1f - RANGE_OVERLAP), mount.y);
        dummy = new TargetDummy(engine, weaponHost, dummyVariant, dummyPosition, drifts, launchesThreats);
        aimWeaponManually();
        frameScene(0f, drifts ? PreviewAIs.TargetAI.DRIFT_AMPLITUDE : 0f);

        // Long-range weapons only, and not against threats: the split view would hide the space between weapon host
        // and dummy, where they are engaged.
        canSplit = !launchesThreats && weapon.getRange() >= SPLIT_VIEW_MIN_RANGE;
    }

    private WeaponAPI previewedWeapon() {
        for (WeaponAPI w : weaponHost.getAllWeapons()) {
            if (w.getSlot().getId().equals(weaponHostPlan.slotId) && w.getId().equals(spec.getWeaponId())) return w;
        }
        throw new IllegalStateException("weapon missing on spawned weapon host");
    }

    /**
     * A wing deploys whole, and every deployed ship is advanced for a moment before the fleet manager hands it over. The
     * scene keeps one fighter, put back on its spot with the weapon along the line to the dummy (+x): weapons that cannot
     * turn stay at whatever angle they had.
     */
    private void settleFighter() {
        for (ShipAPI other : new ArrayList<>(weaponHost.getWing().getWingMembers())) {
            if (other != weaponHost) engine.removeEntity(other);
        }
        weaponHost.getLocation().set(origin);
        weaponHost.getVelocity().set(0f, 0f);
        weaponHost.setAngularVelocity(0f);
        weaponHost.setFacing(-weaponHostPlan.slotAngle);
        weapon.setCurrAngle(0f);
    }

    /** Takes the weapon away from its group's autofire AI: the scene aims and fires it. */
    private void aimWeaponManually() {
        WeaponGroupAPI group = weaponHost.getWeaponGroupFor(weapon);
        if (group != null && group.isAutofiring()) group.toggleOff();
        weapon.setSuspendAutomaticTurning(true);
    }

    @Override
    protected String spawnLogDetails() {
        return String.format(Locale.ROOT, "slot=%s range=%.0f, %s, tracking '%s'%s", weapon.getSlot().getId(),
                weapon.getRange(), dummy.spawnLogDetails(), spec.getTrackingStr(), launchesThreats ? ", point defense" : "");
    }

    @Override
    protected void stepScene(float dt) {
        if (launchesThreats) dummy.replaceIfThreatsChanged();
        aimAndFire(dt);
        handleAmmoExhaustion(dt);
    }

    @Override
    public String frameLogDetails() {
        return String.format(Locale.ROOT, "%s | weapon disabled=%s firing=%s ammo=%d/%d proj=%d missiles=%d beams=%d",
                dummy.frameLogDetails(), weapon.isDisabled(), weapon.isFiring(),
                weapon.usesAmmo() ? weapon.getAmmo() : -1, weapon.usesAmmo() ? weapon.getMaxAmmo() : -1,
                engine.getProjectiles().size(), engine.getMissiles().size(), engine.getBeams().size());
    }

    /**
     * Real ammo: weapons that reload keep reloading at their own rate. A weapon that cannot reload (Harpoon...) fires
     * its magazine; once the last shot is gone, the preview starts a new setup with a full magazine and fresh target.
     */
    private void handleAmmoExhaustion(float dt) {
        boolean exhausted = weapon.usesAmmo() && weapon.getAmmo() <= 0
                && weapon.getAmmoTracker().getAmmoPerSecond() <= 0f;
        if (!exhausted || ownShotsInFlight()) {
            exhaustedTimer = 0f;
            return;
        }
        exhaustedTimer += dt;
        if (exhaustedTimer < EXHAUSTED_RESET_DELAY) return;
        exhaustedTimer = 0f;
        weapon.resetAmmo();
        dummy.replace();
        Log.info("%s magazine exhausted: new setup", weapon.getId());
    }

    private boolean ownShotsInFlight() {
        for (DamagingProjectileAPI p : engine.getProjectiles()) {
            if (p.getWeapon() == weapon) return true;
        }
        return false;
    }

    /**
     * Point defense first: the threat picked, led for the weapon's shots and fired at once in range and with the aim
     * point inside the arc. Otherwise the dummy. Range and aim tolerance use the game's targeting radius.
     */
    private void aimAndFire(float dt) {
        float threatRange = weapon.getRange() * THREAT_RANGE_FACTOR;
        CombatEntityAPI threat = launchesThreats ? pickThreat(weapon.getLocation(), threatRange) : null;
        if (threat != null) {
            Vector2f aim = lead(threat);
            float radius = Misc.getTargetingRadius(weapon.getLocation(), threat, true);
            float distance = MathUtils.getDistance(weapon.getLocation(), threat.getLocation());
            boolean inRange = distance <= weapon.getRange() + radius;
            // Small, close targets: anywhere on their silhouette will do.
            float tolerance = Math.max(AIM_TOLERANCE_DEGREES, Misc.computeAngleSpan(radius, Math.max(1f, distance)));
            turnAndFire(aim, dt, inRange && weapon.distanceFromArc(aim) == 0f, tolerance);
        } else if (dummy.ship().isAlive()) {
            turnAndFire(dummy.ship().getLocation(), dt, true, AIM_TOLERANCE_DEGREES);
        }
    }

    /**
     * What a point defense weapon at {@code from} engages: the nearest enemy missile still on its way within
     * {@code range} (found through the engine's grids), else the nearest live enemy fighter (fighters first for
     * anti-fighter weapons); null if there is none.
     */
    private CombatEntityAPI pickThreat(Vector2f from, float range) {
        CombatEntityAPI missile = null, fighter = null;
        float missileD2 = Float.MAX_VALUE, fighterD2 = Float.MAX_VALUE;
        for (MissileAPI m : CombatUtils.getMissilesWithinRange(from, range)) {
            if (m.getOwner() != ENEMY_OWNER || m.isFizzling() || m.didDamage()) continue;
            float d2 = MathUtils.getDistanceSquared(from, m.getLocation());
            if (d2 < missileD2) {
                missileD2 = d2;
                missile = m;
            }
        }
        for (ShipAPI s : CombatUtils.getShipsWithinRange(from, range)) {
            if (!s.isFighter() || s.getOwner() != ENEMY_OWNER || !s.isAlive()) continue;
            float d2 = MathUtils.getDistanceSquared(from, s.getLocation());
            if (d2 < fighterD2) {
                fighterD2 = d2;
                fighter = s;
            }
        }
        if (antiFighter) return fighter != null ? fighter : missile;
        return missile != null ? missile : fighter;
    }

    /**
     * Turns the weapon toward {@code point} at its real turn rate, the short way round and inside its arc, and fires
     * once lined up; one that cannot turn (a bomb bay) fires as it is. Not LazyLib's WeaponUtils.aimTowardsPoint: it turns
     * by the raw angle difference, so a target just across 0°/360° from the weapon (anything straight ahead) sends it the
     * long way round, into the edge of its arc.
     */
    private void turnAndFire(Vector2f point, float dt, boolean mayFire, float tolerance) {
        float desired = VectorUtils.getAngle(weapon.getLocation(), point);
        if (weapon.getArc() < 359f) {
            float centre = weaponHost.getFacing() + weapon.getArcFacing();
            float half = weapon.getArc() / 2f;
            desired = centre + MathUtils.clamp(MathUtils.getShortestRotation(centre, desired), -half, half);
        }
        float maxTurn = weapon.getTurnRate() * dt;
        float current = weapon.getCurrAngle();
        weapon.setCurrAngle(current + MathUtils.clamp(MathUtils.getShortestRotation(current, desired), -maxTurn, maxTurn));
        boolean linedUp = weapon.getTurnRate() <= 0f || Misc.getAngleDiff(weapon.getCurrAngle(), desired) < tolerance;
        if (mayFire && linedUp) weapon.setForceFireOneFrame(true);
    }

    /**
     * Where to aim to meet a moving entity with this weapon's shots (the weapon host holds still). Beams, and targets
     * the shots cannot catch, are aimed at directly.
     */
    private Vector2f lead(CombatEntityAPI e) {
        float speed = weapon.getProjectileSpeed();
        if (weapon.isBeam() || speed <= 1f) return e.getLocation();
        Vector2f intercept = AIUtils.getBestInterceptPoint(weapon.getLocation(), speed, e.getLocation(), e.getVelocity());
        return intercept != null ? intercept : e.getLocation();
    }
}
