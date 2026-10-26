package wtp.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.mission.FleetSide;
import org.lwjgl.util.vector.Vector2f;
import wtp.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Point defense: no target ship. Waves of enemy missiles and fighter wings come at the host from beyond the weapon's
 * range, and the weapon engages them with its own autofire AI, so target selection is the game's real PD logic. The
 * host keeps its shield down (and takes no damage), so leakers visibly hit it.
 * <p>
 * Missiles need an enemy ship as their source (it sets their side and their guidance target): a weaponless,
 * invulnerable Hammerhead parked far outside the frame.
 */
final class SwarmScenario extends Scenario {

    private static final String[] MISSILES = {"harpoon", "swarmer", "annihilator", "sabot"};
    private static final int[] MISSILE_COUNTS = {3, 5, 4, 2};
    private static final String[] WINGS = {"talon_wing", "broadsword_wing", "piranha_wing", "warthog_wing"};
    /** Wave patterns: M = missile salvo, F = fighter wing. Anti-fighter weapons see more fighters. */
    private static final String PATTERN = "MMF";
    private static final String PATTERN_ANTI_FIGHTER = "FM";
    private static final float FIRST_WAVE = 0.5f;
    private static final float WAVE_PERIOD = 4.5f;
    private static final float MISSILE_STAGGER = 0.18f;
    /** Waves come from within this angle of the weapon's arc centre, keeping the frame short and wide. */
    private static final float CONE_DEGREES = 10f;
    private static final float FIGHTER_PASS_OFFSET = 160f;
    private static final float FIGHTER_MAX_AGE = 14f;
    private static final float LAUNCHER_DISTANCE = 4000f;

    private final boolean antiFighter;
    private ShipAPI launcher;
    private Vector2f mount;
    private float spawnRadius;
    private float arcCentre;
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

    SwarmScenario(CombatEngineAPI engine, WeaponSpecAPI spec, RefitShip refit, Vector2f origin) {
        super(engine, spec, refit, origin);
        this.antiFighter = spec.getAIHints().contains(AIHints.ANTI_FTR);
    }

    @Override
    protected void spawnScene() {
        mount = weapon.getSlot().computePosition(host);
        float range = weapon.getRange();
        spawnRadius = Math.max(range * 1.8f, range + 400f);
        arcCentre = weapon.getArc() >= 359f ? 0f : host.getFacing() + weapon.getArcFacing();

        launcher = spawnEnemyShip(enemyVariant(DEFAULT_ENEMY_VARIANT), new Vector2f(origin.x - LAUNCHER_DISTANCE, origin.y), 0f);
        launcher.setShipAI(new PreviewAIs.HoldAI(launcher));
        makeInvulnerable(launcher);
        if (launcher.getShield() != null && launcher.getShield().isOn()) launcher.getShield().toggleOff();
        setAutofire(true);

        float hostRadius = host.getCollisionRadius();
        frameMinX = host.getLocation().x - hostRadius * 1.15f - 60f;
        frameMaxX = mount.x + spawnRadius + 120f;
        frameCenterY = host.getLocation().y;
        float coneHalfHeight = spawnRadius * (float) Math.sin(Math.toRadians(CONE_DEGREES)) + 80f;
        frameHalfHeight = Math.max(Math.max(hostRadius * 1.3f, hostRadius + FIGHTER_PASS_OFFSET + 60f), coneHalfHeight);
    }

    @Override
    protected String describe() {
        return String.format(Locale.ROOT, "waves from r=%.0f (%s), anti-fighter=%s", spawnRadius,
                antiFighter ? PATTERN_ANTI_FIGHTER : PATTERN, antiFighter);
    }

    @Override
    public void beforeStep(float dt) {
        super.beforeStep(dt);
        waveTimer -= dt;
        if (waveTimer <= 0f) {
            waveTimer = WAVE_PERIOD;
            String pattern = antiFighter ? PATTERN_ANTI_FIGHTER : PATTERN;
            if (pattern.charAt(waveIndex++ % pattern.length()) == 'F') spawnWing();
            else startMissileSalvo();
        }
        if (pendingMissiles > 0) {
            staggerTimer -= dt;
            if (staggerTimer <= 0f) {
                staggerTimer = MISSILE_STAGGER;
                pendingMissiles--;
                launchWaveMissile();
            }
        }
        retireFighters();
    }

    @Override
    public String diagnostics() {
        int enemyMissiles = 0;
        for (MissileAPI m : engine.getMissiles()) {
            if (m.getOwner() == ENEMY_OWNER) enemyMissiles++;
        }
        return String.format(Locale.ROOT, "waves=%d missilesLaunched=%d wings=%d (%s) enemyMissiles=%d fighters=%d | %s",
                waveIndex, missilesLaunched, wingsLaunched, wingSpawnPath, enemyMissiles, fighters.size(),
                super.diagnostics());
    }

    // ------------------------------------------------------------------ missiles

    private void startMissileSalvo() {
        int kind = missileKind++ % MISSILES.length;
        pendingMissileId = MISSILES[kind];
        pendingMissiles = MISSILE_COUNTS[kind];
        staggerTimer = 0f;
    }

    private void launchWaveMissile() {
        float angle = arcCentre + (float) (Math.random() * 2 - 1) * CONE_DEGREES;
        Vector2f at = polar(mount, spawnRadius, angle);
        at.x += (float) (Math.random() * 2 - 1) * 60f;
        at.y += (float) (Math.random() * 2 - 1) * 40f;
        float facing = angleTo(at, host.getLocation()) + (float) (Math.random() * 2 - 1) * 3f;
        if (launchMissile(launcher, pendingMissileId, at, facing, host) != null) missilesLaunched++;
    }

    // ------------------------------------------------------------------ fighters

    /** One enemy wing, flying an attack run past the host and out of frame. */
    private void spawnWing() {
        String wingId = WINGS[wingKind++ % WINGS.length];
        float angle = arcCentre + (float) (Math.random() * 2 - 1) * CONE_DEGREES;
        Vector2f at = polar(mount, spawnRadius + 150f, angle);
        Vector2f hostLoc = host.getLocation();
        float pass = fighterSide * (host.getCollisionRadius() + FIGHTER_PASS_OFFSET);
        fighterSide = -fighterSide;
        Vector2f passBy = new Vector2f(hostLoc.x + host.getCollisionRadius(), hostLoc.y + pass);
        Vector2f exit = new Vector2f(frameMinX - 2000f, hostLoc.y + pass);
        ShipAPI leader = spawnEnemyWing(wingId, at, angleTo(at, passBy));
        if (leader == null) return;
        List<ShipAPI> members = leader.getWing() != null ? leader.getWing().getWingMembers()
                : Collections.singletonList(leader);
        for (ShipAPI f : members) {
            enforceOwner(f, ENEMY_OWNER);
            f.setShipAI(new PreviewAIs.FlyByAI(f, passBy, exit));
            fighters.add(f);
            fighterBirth.add(time);
        }
        wingsLaunched++;
    }

    /**
     * Deploys the wing from a fleet member given the enemy owner first, like every other ship here. Falls back to
     * spawnShipOrWing (owner fixed after the fact) if the fleet manager refuses a wing member.
     */
    private ShipAPI spawnEnemyWing(String wingId, Vector2f at, float facing) {
        engine.getFleetManager(FleetSide.ENEMY).setSuppressDeploymentMessages(true);
        try {
            FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.FIGHTER_WING, wingId);
            prepareForDeployment(member, DEPLOY_CR, ENEMY_OWNER);
            ShipAPI leader = engine.getFleetManager(FleetSide.ENEMY).spawnFleetMember(member, new Vector2f(at), facing, 0f);
            if (leader != null) {
                wingSpawnPath = "member";
                return leader;
            }
        } catch (RuntimeException e) {
            if (wingSpawnPath == null) Log.error("wing member spawn failed for " + wingId + "; trying spawnShipOrWing", e);
        }
        wingSpawnPath = "spawnShipOrWing";
        return engine.getFleetManager(FleetSide.ENEMY).spawnShipOrWing(wingId, new Vector2f(at), facing);
    }

    /** Drops destroyed fighters and removes the ones that flew out of frame. */
    private void retireFighters() {
        for (int i = fighters.size() - 1; i >= 0; i--) {
            ShipAPI f = fighters.get(i);
            boolean gone = !engine.isEntityInPlay(f);
            boolean out = f.getLocation().x < frameMinX - 300f || time - fighterBirth.get(i) > FIGHTER_MAX_AGE;
            if (!gone && f.isAlive() && out) engine.removeEntity(f);
            if (gone || !f.isAlive() || out) {
                fighters.remove(i);
                fighterBirth.remove(i);
            }
        }
    }
}
