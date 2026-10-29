package wtp.scenario;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import org.lwjgl.util.vector.Vector2f;
import wtp.Log;

import java.util.Locale;

/**
 * A fighter wing harassing the dummy: the carrier holds position and keeps the dummy as its target, with its fighters
 * ordered to engage. The dummy carries light point defense on autofire, so fighters are lost over time and the carrier's
 * bays replace them at the game's own replacement rate. A destroyed dummy is replaced, as in the weapon scenes.
 */
final class WingScenario extends Scenario {

    /** Close enough to frame carrier, dummy and attack runs in one view, beyond the dummy's PD range. */
    private static final float DUMMY_DISTANCE = 1200f;
    private static final float SHIELD_PHASE = 6f;
    private static final float RESPAWN_DELAY = 3f;
    /** Room past the dummy and around the line for fighters swinging round on their attack runs. */
    private static final float RUN_MARGIN = 250f;

    private final FighterWingSpecAPI wing;
    private final ShipVariantAPI dummy;
    private final ShieldMode shieldMode;
    private ShipAPI target;
    private Vector2f targetHome;
    private float respawnTimer = -1f;
    private int kills;

    WingScenario(CombatEngineAPI engine, FighterWingSpecAPI wing, RefitShip refit, Vector2f origin, ShipVariantAPI dummy,
                 ShieldMode shieldMode) {
        super(engine, null, refit, origin);
        this.wing = wing;
        this.dummy = dummy;
        this.shieldMode = shieldMode;
    }

    @Override
    protected HostFactory.HostPlan planHost() {
        return HostFactory.planWing(wing, refit);
    }

    @Override
    protected void spawnScene() {
        targetHome = new Vector2f(host.getLocation().x + DUMMY_DISTANCE, host.getLocation().y);
        spawnTarget();
        float hostRadius = host.getCollisionRadius();
        float targetRadius = target.getCollisionRadius();
        frameMinX = host.getLocation().x - hostRadius * 1.15f;
        frameMaxX = targetHome.x + Math.max(targetRadius * 1.15f, RUN_MARGIN);
        frameCenterY = host.getLocation().y;
        frameHalfHeight = Math.max(1.2f * Math.max(hostRadius, targetRadius), targetRadius + RUN_MARGIN);
    }

    @Override
    protected String describe() {
        return String.format(Locale.ROOT, "wing %s (%d fighters, %s), dummy %s at x=%.0f with PD, shield %s",
                wing.getId(), wing.getNumFighters(), wing.getRole(), dummy.getHullVariantId(), targetHome.x, shieldMode);
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
    public int kills() {
        return kills;
    }

    @Override
    public void beforeStep(float dt) {
        super.beforeStep(dt);
        // The carrier has no AI giving orders: keep its wings engaged on the dummy.
        host.setPullBackFighters(false);
        if (target != null && target.isAlive()) {
            host.setShipTarget(target);
            host.setForceCarrierTarget(target);
            host.setForceCarrierTargetTime(1f);
        }

        if (target != null && !target.isAlive()) {
            if (respawnTimer < 0f) {
                respawnTimer = RESPAWN_DELAY;
                kills++;
                Log.debug("%s: dummy destroyed (kill #%d)", wing.getId(), kills);
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
        StringBuilder bays = new StringBuilder();
        for (FighterLaunchBayAPI bay : host.getLaunchBaysCopy()) {
            FighterWingAPI w = bay.getWing();
            if (w == null) continue;
            bays.append(String.format(Locale.ROOT, " %s %d/%d rate=%.0f%% next=%.1fs lost=%d;", w.getWingId(),
                    w.getWingMembers().size(), w.getSpec().getNumFighters(), 100f * bay.getCurrRate(),
                    bay.getTimeUntilNextReplacement(), bay.getNumLost()));
        }
        return String.format(Locale.ROOT, "target hull=%.0f flux=%.2f alive=%s kills=%d | bays:%s",
                target.getHitpoints(), target.getFluxLevel(), target.isAlive(), kills, bays);
    }

    private void spawnTarget() {
        target = spawnEnemyShip(dummy, targetHome, 180f, true);
        target.setShipAI(new PreviewAIs.TargetAI(target, host, targetHome, false, shieldMode, SHIELD_PHASE, 0f, 0f));
        host.setShipTarget(target);
    }
}
