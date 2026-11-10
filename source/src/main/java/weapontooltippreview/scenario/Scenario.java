package weapontooltippreview.scenario;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import org.lwjgl.util.vector.Vector2f;
import weapontooltippreview.Log;

import java.util.Locale;

/**
 * Directs the preview.
 * Gets the weapon host from the factory.
 * Places the dummy against it.
 * Frames the scene.
 * Starts a new setup when the dummy is destroyed, the magazine is empty or the threats picked change.
 */
public abstract class Scenario {

    /** Identifier for additions to the engine. */
    static final String PREVIEW_SOURCE_ID = "WeaponTooltipPreview_preview";
    static final int WEAPON_HOST_OWNER = 0;
    static final int ENEMY_OWNER = 1;
    static final float DEPLOY_CR = 0.7f;
    /** The frame margin around ships. */
    private static final float FRAME_MARGIN = 1.15f;
    private static final float DESTROYED_DELAY = 3f;
    /** After a non-reloading weapon runs dry and its last shot is gone, wait this long, then start a new setup. */
    private static final float MAGAZINE_SPENT_DELAY = 2f;

    protected final CombatEngineAPI engine;
    protected final WeaponHost weaponHost;

    protected TargetDummy targetDummy;
    public Vector2f dummyPosition;

    public float frameMinX, frameMaxX, frameCenterX, frameCenterY, frameHalfHeight;
    /** The scene may show as a split view based on weapon range, each ship in a split pane */
    public boolean canSplit;

    /** Counts down to the next setup; negative while none is due. */
    private float newSetupTimer = -1f;

    Scenario(CombatEngineAPI engine, WeaponHost weaponHost) {
        this.engine = engine;
        this.weaponHost = weaponHost;
    }

    /** The weapon's or the wing's scene, spawned around the weapon host the factory picks. */
    public static Scenario spawn(CombatEngineAPI engine, PreviewSubject subject, RefitShip refit, Vector2f origin) {
        WeaponHost weaponHost = WeaponHostFactory.create(engine, subject, refit, origin);
        Scenario scenario = weaponHost.weapon() != null
                ? new ScenarioWeapon(engine, weaponHost) : new ScenarioWing(engine, weaponHost);
        scenario.spawnScene();
        Log.info("scene %s: %s, frame x %.0f..%.0f ±%.0f; %s; %s", scenario.getClass().getSimpleName(),
                weaponHost.spawnLogDetails(), scenario.frameMinX, scenario.frameMaxX, scenario.frameHalfHeight,
                scenario.targetDummy.spawnLogDetails(), scenario.spawnLogDetails());
        return scenario;
    }

    /** Places the dummy and sets the framing. */
    protected abstract void spawnScene();

    /** Per-frame before the engine step. */
    public final void beforeStep(float dt) {
        advanceSetup(dt);
        weaponHost.step(dt, targetDummy.ship());
        targetDummy.step(dt);
    }

    /** New setup when the dummy dies, magazine is empty, or player changes setup */
    private void advanceSetup(float dt) {
        if (newSetupTimer >= 0f) {
            newSetupTimer -= dt;
            if (newSetupTimer < 0f) newSetup();
            return;
        }
        if (targetDummy.threatsChanged()) {
            newSetup();
        } else if (!targetDummy.ship().isAlive()) {
            newSetupTimer = DESTROYED_DELAY;
            Log.info("dummy destroyed: new setup in %.0f s", DESTROYED_DELAY);
        } else if (weaponHost.magazineSpent()) {
            newSetupTimer = MAGAZINE_SPENT_DELAY;
            Log.info("%s magazine spent: new setup in %.0f s", weaponHost.weapon().getId(), MAGAZINE_SPENT_DELAY);
        }
    }

    /** A full magazine and a fresh dummy. */
    private void newSetup() {
        newSetupTimer = -1f;
        weaponHost.refillMagazine();
        targetDummy.replace();
    }

    /** Accessor for the previewSession */
    public final WeaponHost weaponHost() {
        return weaponHost;
    }

    /** Accessor for the previewSession */
    public final TargetDummy targetDummy() {
        return targetDummy;
    }

    /** Frames weapon host and dummy with a margin & extra room for the sway on moving targets */
    protected final void frameScene(float runMargin, float sway) {
        ShipAPI host = weaponHost.ship();
        float weaponHostRadius = host.getCollisionRadius();
        float dummyRadius = targetDummy.radius();
        frameMinX = host.getLocation().x - FRAME_MARGIN * weaponHostRadius;
        frameMaxX = dummyPosition.x + Math.max(FRAME_MARGIN * dummyRadius, runMargin);
        frameCenterX = (frameMinX + frameMaxX) / 2f;
        frameCenterY = (host.getLocation().y + dummyPosition.y) / 2f;
        frameHalfHeight = Math.max(FRAME_MARGIN * Math.max(weaponHostRadius, dummyRadius), dummyRadius + runMargin)
                + sway;
    }

    /** Deploy with full crew, high CR, and no AI (Behaviour handled by TargetDummy and WeaponHost). */
    static ShipAPI deploy(CombatEngineAPI engine, FleetMemberAPI member, float cr, int owner, Vector2f at, float facing) {
        member.setOwner(owner);
        member.getCrewComposition().setCrew(member.getNeededCrew());
        member.updateStats();
        member.getRepairTracker().setCR(cr);
        CombatFleetManagerAPI fleet = engine.getFleetManager(owner);
        fleet.setSuppressDeploymentMessages(true);
        ShipAPI ship = fleet.spawnFleetMember(member, new Vector2f(at), facing, 0f);
        ship.setShipAI(null);
        return ship;
    }

    /** Takes a weapon away from its group's autofire AI and automatic turning: the scene aims and fires it. */
    static void takeOffAutofire(ShipAPI ship, WeaponAPI weapon) {
        WeaponGroupAPI group = ship.getWeaponGroupFor(weapon);
        if (group != null && group.isAutofiring()) group.toggleOff();
        weapon.setSuspendAutomaticTurning(true);
    }

    /** Without an AI the carrier gives no orders: keeps its wings out and engaged on the target. */
    static void sendWingsAt(ShipAPI carrier, ShipAPI target) {
        carrier.setPullBackFighters(false);
        carrier.setForceCarrierTarget(target);
        carrier.setForceCarrierTargetTime(1f);
    }

    /** Scene-specific summary for the spawn log line. */
    protected abstract String spawnLogDetails();

    /** Periodic diagnostics line. */
    public final String frameLogDetails() {
        return String.format(Locale.ROOT, "%s | %s | proj=%d missiles=%d beams=%d", targetDummy.frameLogDetails(),
                weaponHost.frameLogDetails(), engine.getProjectiles().size(), engine.getMissiles().size(),
                engine.getBeams().size());
    }
}
