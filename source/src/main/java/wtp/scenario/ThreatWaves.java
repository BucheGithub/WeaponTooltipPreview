package wtp.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;
import wtp.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What point defense is shown against, besides the dummy itself: the dummy launches missile salvos and fighter wings at
 * the host, and the scene turns the weapon onto them first (see {@link #pick}). The game's autofire AI is not used for
 * this: with the dummy in range it keeps shooting the dummy. The host keeps its shield down (and takes no damage), so
 * leakers visibly hit it. Everything starts at the dummy, inside the usual frame; nothing is launched while it is
 * destroyed and replaced.
 */
final class ThreatWaves {

    private static final String[] MISSILES = {"harpoon", "swarmer", "annihilator", "sabot"};
    private static final int[] MISSILE_COUNTS = {3, 5, 4, 2};
    private static final String[] WINGS = {"talon_wing", "broadsword_wing", "piranha_wing", "warthog_wing"};
    /** Wave patterns: M = missile salvo, F = fighter wing. Anti-fighter weapons see more fighters. */
    private static final String PATTERN = "MMF";
    private static final String PATTERN_ANTI_FIGHTER = "FM";
    /** The weapon fires at the dummy before the first wave and between waves. */
    private static final float FIRST_WAVE = 2.5f;
    private static final float WAVE_PERIOD = 6f;
    private static final float MISSILE_STAGGER = 0.18f;
    /** Launch points spread over the dummy's side facing the host, within this angle. */
    private static final float LAUNCH_SPREAD_DEGREES = 35f;
    private static final float FIGHTER_PASS_OFFSET = 160f;
    /** Fighters pass the host within this fraction of the frame's half height, so they stay on screen. */
    private static final float FIGHTER_PASS_FRAME_FRACTION = 0.75f;
    private static final float FIGHTER_MAX_AGE = 14f;

    private final Scenario scene;
    private final boolean antiFighter;
    private float waveTimer = FIRST_WAVE;
    private int waveIndex;
    private int missileKind;
    private int wingKind;
    private int pendingMissiles;
    private String pendingMissileId;
    private float staggerTimer;
    private float fighterSide = 1f;
    private final List<ShipAPI> fighters = new ArrayList<>();
    private final List<Float> fighterBirth = new ArrayList<>();
    private int missilesLaunched;
    private int wingsLaunched;
    private String wingSpawnPath;

    ThreatWaves(Scenario scene) {
        this.scene = scene;
        antiFighter = scene.spec.getAIHints().contains(AIHints.ANTI_FTR);
    }

    String describe() {
        return String.format(Locale.ROOT, "waves from the dummy (%s), anti-fighter=%s",
                antiFighter ? PATTERN_ANTI_FIGHTER : PATTERN, antiFighter);
    }

    String diagnostics() {
        int enemyMissiles = 0;
        for (MissileAPI m : scene.engine.getMissiles()) {
            if (m.getOwner() == Scenario.ENEMY_OWNER) enemyMissiles++;
        }
        return String.format(Locale.ROOT, "waves=%d missilesLaunched=%d wings=%d (%s) enemyMissiles=%d fighters=%d",
                waveIndex, missilesLaunched, wingsLaunched, wingSpawnPath, enemyMissiles, fighters.size());
    }

    /** Per-frame upkeep: the next wave when due, staggered missile launches, fighters that left the frame. */
    void advance(float dt) {
        ShipAPI dummy = scene.target();
        if (dummy != null && dummy.isAlive()) {
            waveTimer -= dt;
            if (waveTimer <= 0f) {
                waveTimer = WAVE_PERIOD;
                String pattern = antiFighter ? PATTERN_ANTI_FIGHTER : PATTERN;
                if (pattern.charAt(waveIndex++ % pattern.length()) == 'F') spawnWing(dummy);
                else startMissileSalvo();
            }
            if (pendingMissiles > 0) {
                staggerTimer -= dt;
                if (staggerTimer <= 0f) {
                    staggerTimer = MISSILE_STAGGER;
                    pendingMissiles--;
                    launchWaveMissile(dummy);
                }
            }
        } else {
            pendingMissiles = 0;
        }
        retireFighters();
    }

    /**
     * What a point defense weapon at {@code from} engages: the nearest enemy missile still on its way within
     * {@code range} (found through the engine's missile grid), else the nearest live fighter (fighters first for
     * anti-fighter weapons); null if there is none. Called while the sandbox is swapped in.
     */
    CombatEntityAPI pick(Vector2f from, float range) {
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
        for (ShipAPI f : fighters) {
            if (!f.isAlive() || !MathUtils.isWithinRange(from, f.getLocation(), range)) continue;
            float d2 = MathUtils.getDistanceSquared(from, f.getLocation());
            if (d2 < fighterD2) {
                fighterD2 = d2;
                fighter = f;
            }
        }
        if (antiFighter) return fighter != null ? fighter : missile;
        return missile != null ? missile : fighter;
    }

    /** A point on the dummy's side facing the host, {@code side} (-1..1) across the launch spread. */
    private Vector2f launchPoint(ShipAPI dummy, float side) {
        float towardHost = VectorUtils.getAngle(dummy.getLocation(), scene.host.getLocation());
        return MathUtils.getPointOnCircumference(dummy.getLocation(), dummy.getCollisionRadius() * 0.6f,
                towardHost + side * LAUNCH_SPREAD_DEGREES);
    }

    // ------------------------------------------------------------------ missiles

    private void startMissileSalvo() {
        int kind = missileKind++ % MISSILES.length;
        pendingMissileId = MISSILES[kind];
        pendingMissiles = MISSILE_COUNTS[kind];
        staggerTimer = 0f;
    }

    private void launchWaveMissile(ShipAPI dummy) {
        ShipAPI host = scene.host;
        float side = MathUtils.getRandomNumberInRange(-1f, 1f);
        Vector2f at = launchPoint(dummy, side);
        float facing = VectorUtils.getAngle(dummy.getLocation(), host.getLocation()) + side * 12f;
        if (scene.launchMissile(dummy, pendingMissileId, at, facing, host) != null) missilesLaunched++;
    }

    // ------------------------------------------------------------------ fighters

    /** One enemy wing from the dummy, flying an attack run past the host and out of frame. */
    private void spawnWing(ShipAPI dummy) {
        ShipAPI host = scene.host;
        String wingId = WINGS[wingKind++ % WINGS.length];
        Vector2f at = launchPoint(dummy, 0f);
        Vector2f hostLoc = host.getLocation();
        float pass = fighterSide * Math.min(host.getCollisionRadius() + FIGHTER_PASS_OFFSET,
                scene.frameHalfHeight * FIGHTER_PASS_FRAME_FRACTION);
        fighterSide = -fighterSide;
        Vector2f passBy = new Vector2f(hostLoc.x + host.getCollisionRadius(), hostLoc.y + pass);
        Vector2f exit = new Vector2f(scene.frameMinX - 2000f, hostLoc.y + pass);
        ShipAPI leader = spawnEnemyWing(wingId, at, VectorUtils.getAngle(at, passBy));
        if (leader == null) return;
        List<ShipAPI> members = leader.getWing() != null ? leader.getWing().getWingMembers()
                : Collections.singletonList(leader);
        for (ShipAPI f : members) {
            Scenario.enforceOwner(f, Scenario.ENEMY_OWNER);
            f.setShipAI(new PreviewAIs.FlyByAI(f, passBy, exit));
            fighters.add(f);
            fighterBirth.add(scene.time);
        }
        wingsLaunched++;
    }

    /**
     * Deploys the wing from a fleet member given the enemy owner first, like every other ship here. Falls back to
     * spawnShipOrWing (owner fixed after the fact) if the fleet manager refuses a wing member.
     */
    private ShipAPI spawnEnemyWing(String wingId, Vector2f at, float facing) {
        scene.engine.getFleetManager(FleetSide.ENEMY).setSuppressDeploymentMessages(true);
        try {
            FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.FIGHTER_WING, wingId);
            Scenario.prepareForDeployment(member, Scenario.DEPLOY_CR, Scenario.ENEMY_OWNER);
            ShipAPI leader = scene.engine.getFleetManager(FleetSide.ENEMY).spawnFleetMember(member, new Vector2f(at), facing, 0f);
            if (leader != null) {
                wingSpawnPath = "member";
                return leader;
            }
        } catch (RuntimeException e) {
            if (wingSpawnPath == null) Log.error("wing member spawn failed for " + wingId + "; trying spawnShipOrWing", e);
        }
        wingSpawnPath = "spawnShipOrWing";
        return scene.engine.getFleetManager(FleetSide.ENEMY).spawnShipOrWing(wingId, new Vector2f(at), facing);
    }

    /** Drops destroyed fighters and removes the ones that flew out of frame. */
    private void retireFighters() {
        for (int i = fighters.size() - 1; i >= 0; i--) {
            ShipAPI f = fighters.get(i);
            boolean gone = !scene.engine.isEntityInPlay(f);
            boolean out = f.getLocation().x < scene.frameMinX - 300f || scene.time - fighterBirth.get(i) > FIGHTER_MAX_AGE;
            if (!gone && f.isAlive() && out) scene.engine.removeEntity(f);
            if (gone || !f.isAlive() || out) {
                fighters.remove(i);
                fighterBirth.remove(i);
            }
        }
    }
}
