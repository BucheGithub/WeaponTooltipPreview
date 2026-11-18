package weapontooltippreview.scenario;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;
import weapontooltippreview.Log;

import java.util.List;
import java.util.Locale;

/** Directs the preview: deploys the weapon host, places the target dummy against it, frames the scene, and starts a new setup when the dummy is destroyed, a non-reloading magazine is spent, or the player cycles point-defense threats. */
public final class Scenario {

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

    /** How far the range arc cuts into the target's shield bubble, as a fraction of the shield radius. */
    private static final float RANGE_OVERLAP = 0.65f;
    /** Weapons reaching this far may show as a split view; shorter ones are framed at true scale in one pane. */
    private static final float SPLIT_VIEW_MIN_RANGE = 2000f;

    /** Close enough so all fighters can attack, but beyond the dummy's PD range. */
    private static final float WING_DUMMY_DISTANCE = 1200f;
    /** Wings with a shorter engagement range get the dummy this far into it. */
    private static final float ENGAGEMENT_SHARE = 0.9f;
    /** For wings with range = 0, this is the ratio for their weapon range. */
    private static final float WEAPON_RANGE_SHARE = 0.8f;
    /** Least space between carrier and dummy. */
    private static final float MIN_GAP = 100f;
    /** Room past the dummy and around the line for fighters swinging round on their attack runs. */
    private static final float RUN_MARGIN = 250f;

    private final CombatEngineAPI engine;
    private final WeaponHost weaponHost;
    private final TargetDummy targetDummy;
    public final Vector2f dummyPosition;
    public float frameMinX, frameMaxX, frameCenterX, frameCenterY, frameHalfHeight;
    /** The scene may show as a split view based on weapon range, each ship in a split pane. */
    public final boolean canSplit;

    /** Counts down to the next setup; negative while none is due. */
    private float newSetupTimer = -1f;

    private Scenario(CombatEngineAPI engine, WeaponHost weaponHost) {
        this.engine = engine;
        this.weaponHost = weaponHost;
        ShipVariantAPI dummyVariant = TargetDummy.stockVariantWithoutWeapons();
        WeaponAPI weapon = weaponHost.weapon();
        String details;
        if (weapon != null) { // Weapons case, wether the host is a ship or fighter
            boolean drifts = tracks(weapon.getSpec());
            Vector2f mount = weapon.getSlot().computePosition(weaponHost.ship());
            float shieldRadius = TargetDummy.shieldRadius(dummyVariant.getHullSpec());
            dummyPosition = new Vector2f(mount.x + weapon.getRange() + shieldRadius * (1f - RANGE_OVERLAP), mount.y);
            targetDummy = new TargetDummy(engine, weaponHost.ship(), dummyVariant, dummyPosition, drifts,
                    weaponHost.pointDefense() ? TargetDummy.Loadout.THREATS : TargetDummy.Loadout.NONE);
            frameScene(0f, drifts ? TargetDummy.DRIFT_AMPLITUDE : 0f);
            canSplit = !weaponHost.pointDefense() && weapon.getRange() >= SPLIT_VIEW_MIN_RANGE;
            details = String.format(Locale.ROOT, "tracking '%s'%s", weapon.getSpec().getTrackingStr(),
                    weaponHost.pointDefense() ? ", point defense" : "");
        } else { // Wings case
            ShipAPI carrier = weaponHost.ship();
            float distance = wingDummyDistance(dummyVariant.getHullSpec().getCollisionRadius());
            dummyPosition = new Vector2f(carrier.getLocation().x + distance, carrier.getLocation().y);
            targetDummy = new TargetDummy(engine, carrier, dummyVariant, dummyPosition, false,
                    TargetDummy.Loadout.POINT_DEFENSE);
            frameScene(RUN_MARGIN, 0f);
            canSplit = false;
            FighterWingSpecAPI wing = weaponHost.wing();
            details = String.format(Locale.ROOT, "wing %s (%d fighters, %s, engagement %.0f, weapons %.0f)",
                    wing.getId(), wing.getNumFighters(), wing.getRole(), weaponHost.engagementRange(),
                    longestWeaponRange(wing));
        }
        Log.info("scene: %s, frame x %.0f..%.0f ±%.0f; %s; %s", weaponHost.spawnLogDetails(),
                frameMinX, frameMaxX, frameHalfHeight, targetDummy.spawnLogDetails(), details);
    }

    /** The weapon's or the wing's scene, spawned around the weapon host the factory picks. */
    public static Scenario spawn(CombatEngineAPI engine, PreviewSubject subject, RefitShip refit, Vector2f origin) {
        return new Scenario(engine, WeaponHostFactory.create(engine, subject, refit, origin));
    }

    /** Per-frame before the engine step. */
    public void beforeStep(float dt) {
        advanceSetup(dt);
        weaponHost.step(dt, targetDummy.ship());
        targetDummy.step(dt);
    }

    /** New setup when the dummy dies, magazine is empty, or player changes setup. */
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

    public WeaponHost weaponHost() {
        return weaponHost;
    }

    public TargetDummy targetDummy() {
        return targetDummy;
    }

    public List<DamagingProjectileAPI> projectiles() {
        return engine.getProjectiles();
    }

    /** Listener at the firing weapon, or the middle of the frame for a wing. */
    public Vector2f listenerPosition() {
        return weaponHost.weapon() != null
                ? new Vector2f(weaponHost.weapon().getLocation())
                : new Vector2f(frameCenterX, frameCenterY);
    }

    /** Frames weapon host and dummy with a margin & extra room for the sway on moving targets. */
    private void frameScene(float runMargin, float sway) {
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

    /** The weapon's shots home in (its tooltip lists a tracking rating other than "None"). */
    private static boolean tracks(WeaponSpecAPI spec) {
        String rating = spec.getTrackingStr();
        return rating != null && !rating.trim().isEmpty() && !rating.trim().equalsIgnoreCase("None");
    }

    /** How far out the dummy sits for a fighter wing based on engagement and weapon ranges. */
    private float wingDummyDistance(float dummyRadius) {
        float weaponHostRadius = weaponHost.ship().getCollisionRadius();
        float closeIn = weaponHostRadius + dummyRadius + WEAPON_RANGE_SHARE * longestWeaponRange(weaponHost.wing());
        float distance = Math.max(ENGAGEMENT_SHARE * weaponHost.engagementRange(), closeIn);
        return MathUtils.clamp(distance, weaponHostRadius + dummyRadius + MIN_GAP, WING_DUMMY_DISTANCE);
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

    /** Deploy with full crew, high CR, and no AI (behaviour handled by TargetDummy and WeaponHost). */
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

    /** The game's own mounting rule (type compatibility, same size or one size up), minus non-weapon slots. */
    static boolean fits(WeaponSlotAPI slot, WeaponSpecAPI spec) {
        if (!slot.isWeaponSlot() || slot.isBuiltIn() || slot.isDecorative() || slot.isSystemSlot() || slot.isHidden()) {
            return false;
        }
        return slot.weaponFits(spec);
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

    /** Periodic diagnostics line. */
    public String frameLogDetails() {
        return String.format(Locale.ROOT, "%s | %s | proj=%d missiles=%d beams=%d", targetDummy.frameLogDetails(),
                weaponHost.frameLogDetails(), engine.getProjectiles().size(), engine.getMissiles().size(),
                engine.getBeams().size());
    }
}
