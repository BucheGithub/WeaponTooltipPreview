package wtp.scenario;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import org.lazywizard.lazylib.MathUtils;
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
    /** Wings with a shorter engagement range get the dummy this far into it. */
    private static final float ENGAGEMENT_SHARE = 0.9f;
    /**
     * Wings that stay with the carrier (engagement range 0) get the dummy this far into their weapons' range, counted
     * from the edge of the carrier, where they hold.
     */
    private static final float WEAPON_RANGE_SHARE = 0.8f;
    /** Least space between carrier and dummy. */
    private static final float MIN_GAP = 100f;
    private static final float SHIELD_PHASE = 6f;
    private static final float RESPAWN_DELAY = 3f;
    /** Room past the dummy and around the line for fighters swinging round on their attack runs. */
    private static final float RUN_MARGIN = 250f;

    private final FighterWingSpecAPI wing;
    private final ShipVariantAPI dummy;
    private final ShieldMode shieldMode;
    /** The wing's engagement range is 0: it escorts the carrier instead of flying out to the dummy. */
    private boolean escorts;
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
        targetHome = new Vector2f(host.getLocation().x + dummyDistance(), host.getLocation().y);
        spawnTarget();
        float hostRadius = host.getCollisionRadius();
        float targetRadius = target.getCollisionRadius();
        frameMinX = host.getLocation().x - hostRadius * 1.15f;
        frameMaxX = targetHome.x + Math.max(targetRadius * 1.15f, RUN_MARGIN);
        frameCenterY = host.getLocation().y;
        frameHalfHeight = Math.max(1.2f * Math.max(hostRadius, targetRadius), targetRadius + RUN_MARGIN);
    }

    /**
     * How far out the dummy sits: {@link #DUMMY_DISTANCE}, or closer where the fighters would not reach it. Wings fly out
     * to their engagement range, the carrier's own (Defensive Targeting Array cuts it to 0). At 0 they hold by the
     * carrier, as support wings do, and only fire on what comes within their weapons' range.
     */
    private float dummyDistance() {
        float hostRadius = host.getCollisionRadius();
        float dummyRadius = dummy.getHullSpec().getCollisionRadius();
        float engagement = host.getMutableStats().getFighterWingRange().computeEffective(wing.getRange());
        escorts = engagement < 1f;
        float closeIn = hostRadius + dummyRadius + WEAPON_RANGE_SHARE * longestWeaponRange(wing);
        float distance = Math.max(ENGAGEMENT_SHARE * engagement, closeIn);
        return MathUtils.clamp(distance, hostRadius + dummyRadius + MIN_GAP, DUMMY_DISTANCE);
    }

    /**
     * Escorting fighters take up stations around the carrier, which puts some wings (Xyphos) behind it: out of range
     * of the dummy, with the carrier in their line of fire. The game's own tag, which Sarissa and Mining Drone wings
     * carry and Defensive Targeting Array gives other fighters, keeps them in front, between carrier and dummy. The
     * escort maneuver reads it every frame, so fighters tagged as they launch take it up at once.
     */
    private void holdInFront() {
        for (FighterLaunchBayAPI bay : host.getLaunchBaysCopy()) {
            FighterWingAPI w = bay.getWing();
            if (w == null || !wing.getId().equals(w.getWingId())) continue;
            for (ShipAPI fighter : w.getWingMembers()) {
                if (!fighter.hasTag(Tags.WING_STAY_IN_FRONT_OF_SHIP)) fighter.addTag(Tags.WING_STAY_IN_FRONT_OF_SHIP);
            }
        }
    }

    /** The fighter's longest weapon range, as fitted in the wing's variant (before any carrier or skill bonus). */
    private static float longestWeaponRange(FighterWingSpecAPI wing) {
        ShipVariantAPI variant = wing.getVariant();
        float longest = 0f;
        for (String slotId : variant.getFittedWeaponSlots()) {
            WeaponSpecAPI weapon = variant.getWeaponSpec(slotId);
            if (weapon != null) longest = Math.max(longest, weapon.getMaxRange());
        }
        return longest;
    }

    @Override
    protected String describe() {
        return String.format(Locale.ROOT,
                "wing %s (%d fighters, %s, engagement %.0f, weapons %.0f), dummy %s at x=%.0f with PD, shield %s",
                wing.getId(), wing.getNumFighters(), wing.getRole(),
                host.getMutableStats().getFighterWingRange().computeEffective(wing.getRange()), longestWeaponRange(wing),
                dummy.getHullVariantId(), targetHome.x, shieldMode);
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
        if (escorts) holdInFront();

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
