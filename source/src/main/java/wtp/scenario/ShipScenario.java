package wtp.scenario;

import com.fs.starfarer.api.combat.CombatEngineAPI;
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
 * guidance shows; everything else faces a stationary one. PD_ALSO weapons also face missile salvos from the dummy,
 * engaged on autofire.
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

    /** PD_ALSO salvos: what the target launches, how many, how often. */
    private static final String[] SALVO_MISSILES = {"harpoon", "annihilator", "sabot"};
    private static final int[] SALVO_SIZES = {3, 4, 2};
    private static final float FIRST_SALVO = 3f;
    private static final float SALVO_PERIOD = 8f;
    private static final float SALVO_STAGGER = 0.2f;
    /** Enemy missiles this close (in weapon ranges) hand the weapon to its autofire AI. */
    private static final float THREAT_RANGE_FACTOR = 1.25f;

    /** The weapon's shots home in (its tooltip lists a tracking rating other than "None"). */
    private final boolean tracking;
    private final ShipVariantAPI dummy;
    private final ShieldMode shieldMode;
    private final boolean salvos;
    private ShipAPI target;
    private Vector2f targetHome;
    private float respawnTimer = -1f;
    private float exhaustedTimer;
    private int kills;

    private float salvoTimer = FIRST_SALVO;
    private int salvoIndex;
    private int salvoPending;
    private float salvoStaggerTimer;
    private boolean autofire;
    private int salvosLaunched;

    ShipScenario(CombatEngineAPI engine, WeaponSpecAPI spec, RefitShip refit, Vector2f origin, ShipVariantAPI dummy,
                 ShieldMode shieldMode, boolean salvos) {
        super(engine, spec, refit, origin);
        this.tracking = tracks(spec);
        this.dummy = dummy;
        this.shieldMode = shieldMode;
        this.salvos = salvos;
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
    }

    @Override
    protected String describe() {
        return String.format(Locale.ROOT, "dummy %s at x=%.0f, shield %s, tracking '%s'%s%s", dummy.getHullVariantId(),
                targetHome.x, shieldMode, spec.getTrackingStr(), tracking ? " (drifting)" : "",
                salvos ? ", PD_ALSO salvos" : "");
    }

    @Override
    public ShipAPI target() {
        return target;
    }

    @Override
    public Vector2f targetHome() {
        return targetHome;
    }

    @Override
    public boolean canSplit() {
        return true;
    }

    @Override
    public int kills() {
        return kills;
    }

    @Override
    public void beforeStep(float dt) {
        super.beforeStep(dt);
        guideMissiles();
        if (salvos) runSalvos(dt);
        if (!autofire) aimAndFire(dt);
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
        return String.format(Locale.ROOT, "target hull=%.0f flux=%.2f shield=%s overload=%s alive=%s kills=%d salvos=%d | %s",
                target.getHitpoints(), target.getFluxLevel(), target.getShield() != null && target.getShield().isOn(),
                target.getFluxTracker().isOverloaded(), target.isAlive(), kills, salvosLaunched, super.diagnostics());
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
     * PD_ALSO: every few seconds the target launches a salvo at the host. While enemy missiles are close, the weapon
     * runs on its own autofire AI (real PD target selection); otherwise it is aimed at the target as usual.
     */
    private void runSalvos(float dt) {
        if (target != null && target.isAlive()) {
            salvoTimer -= dt;
            if (salvoTimer <= 0f) {
                salvoTimer = SALVO_PERIOD;
                salvoPending = SALVO_SIZES[salvoIndex % SALVO_SIZES.length];
                salvoStaggerTimer = 0f;
                salvosLaunched++;
            }
            if (salvoPending > 0) {
                salvoStaggerTimer -= dt;
                if (salvoStaggerTimer <= 0f) {
                    salvoStaggerTimer = SALVO_STAGGER;
                    salvoPending--;
                    launchSalvoMissile(SALVO_MISSILES[salvoIndex % SALVO_MISSILES.length]);
                    if (salvoPending == 0) salvoIndex++;
                }
            }
        }
        boolean threat = enemyMissileWithin(weapon.getRange() * THREAT_RANGE_FACTOR);
        if (threat != autofire) {
            autofire = threat;
            setAutofire(threat);
        }
    }

    private void launchSalvoMissile(String weaponId) {
        float towardHost = angleTo(target.getLocation(), host.getLocation());
        float side = (float) (Math.random() * 2 - 1);
        Vector2f at = polar(target.getLocation(), target.getCollisionRadius() * 0.6f, towardHost + side * 35f);
        launchMissile(target, weaponId, at, towardHost + side * 12f, host);
    }

    private boolean enemyMissileWithin(float range) {
        Vector2f mount = weapon.getLocation();
        float r2 = range * range;
        for (MissileAPI m : engine.getMissiles()) {
            if (m.getOwner() != ENEMY_OWNER || m.isFizzling() || m.didDamage()) continue;
            float dx = m.getLocation().x - mount.x, dy = m.getLocation().y - mount.y;
            if (dx * dx + dy * dy < r2) return true;
        }
        return false;
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

    /** Turns the weapon toward the target at its real turn rate, inside its arc, and fires once lined up. */
    private void aimAndFire(float dt) {
        if (target == null || !target.isAlive()) return;
        float desired = angleTo(weapon.getLocation(), target.getLocation());
        if (weapon.getArc() < 359f) {
            float centre = host.getFacing() + weapon.getArcFacing();
            float half = weapon.getArc() / 2f;
            desired = centre + Math.max(-half, Math.min(half, shortestAngle(centre, desired)));
        }
        float current = weapon.getCurrAngle();
        float maxTurn = weapon.getTurnRate() * dt;
        float next = current + Math.max(-maxTurn, Math.min(maxTurn, shortestAngle(current, desired)));
        weapon.setCurrAngle(next);
        if (Math.abs(shortestAngle(next, desired)) < AIM_TOLERANCE_DEGREES) weapon.setForceFireOneFrame(true);
    }
}
