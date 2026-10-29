package wtp.scenario;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import org.lwjgl.util.vector.Vector2f;
import wtp.Log;

import java.util.Locale;

/**
 * The weapon against a weaponless dummy (the player's pick, Hammerhead by default) at its max range: shield on, off or
 * cycling, real damage until destroyed, then a fresh dummy. Weapons whose shots track get a drifting dummy so the
 * guidance shows; everything else faces a stationary one. Point defense weapons also face missile salvos and fighter
 * wings launched by the dummy ({@link ThreatWaves}): while any are close, the weapon engages them first (nearest
 * missile, then fighter), then goes back to the dummy.
 */
final class ShipScenario extends Scenario {

    private static final float SHIELD_PHASE = 6f;
    private static final float RESPAWN_DELAY = 3f;
    private static final float DRIFT_PERIOD = 6f;
    private static final float DRIFT_AMPLITUDE = 120f;
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

    /** The weapon's shots home in (its tooltip lists a tracking rating other than "None"). */
    private final boolean tracking;
    private final ShipVariantAPI dummy;
    private final ShieldMode shieldMode;
    private final boolean pointDefense;
    private ShipAPI target;
    private Vector2f targetHome;
    private float respawnTimer = -1f;
    private float exhaustedTimer;
    private int kills;

    /** Point defense only. */
    private ThreatWaves waves;
    /** The missile or fighter the weapon is on; null while it is on the dummy. */
    private CombatEntityAPI engaged;

    ShipScenario(CombatEngineAPI engine, WeaponSpecAPI spec, RefitShip refit, Vector2f origin, ShipVariantAPI dummy,
                 ShieldMode shieldMode, boolean pointDefense) {
        super(engine, spec, refit, origin);
        this.tracking = tracks(spec);
        this.dummy = dummy;
        this.shieldMode = shieldMode;
        this.pointDefense = pointDefense;
    }

    private static boolean tracks(WeaponSpecAPI spec) {
        String rating = spec.getTrackingStr();
        return rating != null && !rating.trim().isEmpty() && !rating.trim().equalsIgnoreCase("None");
    }

    @Override
    protected void spawnScene() {
        // Place the target before spawning it: moving a ship afterwards leaves the engine's spatial grid stale,
        // and weapon AI / missile guidance look targets up through that grid.
        Vector2f mount = weapon.getSlot().computePosition(host);
        ShipHullSpecAPI targetHull = dummy.getHullSpec();
        float shieldRadius = targetHull.getShieldSpec() != null && targetHull.getShieldSpec().getRadius() > 0f
                ? targetHull.getShieldSpec().getRadius() : targetHull.getCollisionRadius();
        targetHome = new Vector2f(mount.x + weapon.getRange() + shieldRadius * (1f - RANGE_OVERLAP), mount.y);
        spawnTarget();
        setAutofire(false);

        float hostRadius = host.getCollisionRadius();
        float targetRadius = Math.max(shieldRadius, target.getCollisionRadius());
        frameMinX = host.getLocation().x - hostRadius * 1.15f;
        frameMaxX = targetHome.x + targetRadius * 1.15f;
        frameCenterY = (host.getLocation().y + targetHome.y) / 2f;
        frameHalfHeight = 1.2f * Math.max(hostRadius, targetRadius) + (tracking ? DRIFT_AMPLITUDE : 0f);

        if (pointDefense) waves = new ThreatWaves(this);
    }

    @Override
    protected String describe() {
        return String.format(Locale.ROOT, "dummy %s at x=%.0f, shield %s, tracking '%s'%s%s", dummy.getHullVariantId(),
                targetHome.x, shieldMode, spec.getTrackingStr(), tracking ? " (drifting)" : "",
                waves != null ? ", point defense: " + waves.describe() : "");
    }

    @Override
    public ShipAPI target() {
        return target;
    }

    @Override
    public Vector2f targetHome() {
        return targetHome;
    }

    /** Not with threat waves: the split view would hide the space between host and dummy, where they are engaged. */
    @Override
    public boolean canSplit() {
        return waves == null;
    }

    @Override
    public int kills() {
        return kills;
    }

    @Override
    public void beforeStep(float dt) {
        super.beforeStep(dt);
        guideMissiles();
        if (waves != null) waves.advance(dt);
        aimAndFire(dt);
        handleAmmoExhaustion(dt);

        if (target != null && !target.isAlive()) {
            if (respawnTimer < 0f) {
                respawnTimer = RESPAWN_DELAY;
                kills++;
                Log.debug("%s: target destroyed (kill #%d)", weapon.getId(), kills);
            }
            respawnTimer -= dt;
            if (respawnTimer <= 0f) {
                engine.removeEntity(target);
                respawnTimer = -1f;
                spawnTarget();
                setupCycle++;
            }
        }
    }

    @Override
    public String diagnostics() {
        return String.format(Locale.ROOT, "target hull=%.0f flux=%.2f shield=%s overload=%s alive=%s kills=%d%s | %s",
                target.getHitpoints(), target.getFluxLevel(), target.getShield() != null && target.getShield().isOn(),
                target.getFluxTracker().isOverloaded(), target.isAlive(), kills,
                waves != null ? " engaging=" + describeEngaged() + " " + waves.diagnostics() : "", super.diagnostics());
    }

    private String describeEngaged() {
        if (engaged instanceof MissileAPI) return "missile " + ((MissileAPI) engaged).getProjectileSpecId();
        if (engaged instanceof ShipAPI) return "fighter " + ((ShipAPI) engaged).getHullSpec().getHullId();
        return "dummy";
    }

    private void spawnTarget() {
        target = spawnEnemyShip(dummy, targetHome, 180f);
        target.setShipAI(new PreviewAIs.TargetAI(target, host, targetHome, tracking, shieldMode, SHIELD_PHASE,
                DRIFT_PERIOD, DRIFT_AMPLITUDE));
        host.setShipTarget(target);
    }

    /**
     * MissileAI reads its target from the source ship when constructed, but the weapon group's autofire pass swaps the
     * ship target for the autofire plugin's around the firing call. Hand our guided missiles the target.
     */
    private void guideMissiles() {
        if (target == null || !target.isAlive()) return;
        for (MissileAPI m : engine.getMissiles()) {
            if (m.getWeapon() != weapon || !(m.getMissileAI() instanceof GuidedMissileAI)) continue;
            GuidedMissileAI ai = (GuidedMissileAI) m.getMissileAI();
            if (ai.getTarget() != target) ai.setTarget(target);
        }
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
        if (target != null) engine.removeEntity(target);
        respawnTimer = -1f;
        spawnTarget();
        setupCycle++;
        Log.debug("%s magazine exhausted: new setup", weapon.getId());
    }

    private boolean ownShotsInFlight() {
        for (DamagingProjectileAPI p : engine.getProjectiles()) {
            if (p.getWeapon() == weapon) return true;
        }
        return false;
    }

    /**
     * Point defense first: the threat the waves pick, led for the weapon's shots and fired at once in range and inside
     * the arc. Otherwise the dummy.
     */
    private void aimAndFire(float dt) {
        engaged = waves != null ? waves.pick(weapon.getLocation(), weapon.getRange() * THREAT_RANGE_FACTOR) : null;
        if (engaged != null) {
            Vector2f aim = lead(engaged);
            float distance = Vector2f.sub(engaged.getLocation(), weapon.getLocation(), new Vector2f()).length();
            boolean inRange = distance <= weapon.getRange() + engaged.getCollisionRadius();
            // Small, close targets: anywhere on their silhouette will do.
            float tolerance = Math.max(AIM_TOLERANCE_DEGREES,
                    (float) Math.toDegrees(Math.atan2(engaged.getCollisionRadius(), Math.max(1f, distance))));
            turnAndFire(aim, dt, inRange && inArc(angleTo(weapon.getLocation(), aim)), tolerance);
        } else if (target != null && target.isAlive()) {
            turnAndFire(target.getLocation(), dt, true, AIM_TOLERANCE_DEGREES);
        }
    }

    /** Turns the weapon toward {@code point} at its real turn rate, inside its arc, and fires once lined up. */
    private void turnAndFire(Vector2f point, float dt, boolean mayFire, float tolerance) {
        float desired = angleTo(weapon.getLocation(), point);
        if (weapon.getArc() < 359f) {
            float centre = host.getFacing() + weapon.getArcFacing();
            float half = weapon.getArc() / 2f;
            desired = centre + Math.max(-half, Math.min(half, shortestAngle(centre, desired)));
        }
        float current = weapon.getCurrAngle();
        float maxTurn = weapon.getTurnRate() * dt;
        float next = current + Math.max(-maxTurn, Math.min(maxTurn, shortestAngle(current, desired)));
        weapon.setCurrAngle(next);
        if (mayFire && Math.abs(shortestAngle(next, desired)) < tolerance) weapon.setForceFireOneFrame(true);
    }

    private boolean inArc(float angle) {
        if (weapon.getArc() >= 359f) return true;
        return Math.abs(shortestAngle(host.getFacing() + weapon.getArcFacing(), angle)) <= weapon.getArc() / 2f;
    }

    /**
     * Where to aim to meet a moving entity with this weapon's shots (the host holds still): the earliest t with
     * |r + v·t| = speed·t. Beams, and targets the shots cannot catch, are aimed at directly.
     */
    private Vector2f lead(CombatEntityAPI e) {
        Vector2f p = e.getLocation(), v = e.getVelocity();
        float speed = weapon.getProjectileSpeed();
        if (weapon.isBeam() || speed <= 1f) return p;
        float rx = p.x - weapon.getLocation().x, ry = p.y - weapon.getLocation().y;
        float a = v.x * v.x + v.y * v.y - speed * speed;
        float b = 2f * (rx * v.x + ry * v.y);
        float c = rx * rx + ry * ry;
        float t;
        if (Math.abs(a) < 1e-3f) {
            t = b < 0f ? -c / b : -1f;
        } else {
            float disc = b * b - 4f * a * c;
            if (disc < 0f) return p;
            float root = (float) Math.sqrt(disc);
            float t1 = (-b - root) / (2f * a), t2 = (-b + root) / (2f * a);
            t = t1 > 0f && (t2 <= 0f || t1 < t2) ? t1 : t2;
        }
        return t > 0f ? new Vector2f(p.x + v.x * t, p.y + v.y * t) : p;
    }
}
