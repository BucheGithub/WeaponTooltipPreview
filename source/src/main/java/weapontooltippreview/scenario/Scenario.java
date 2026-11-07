package weapontooltippreview.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.campaign.fleet.FleetData;
import com.fs.starfarer.campaign.fleet.FleetMember;
import org.lwjgl.util.vector.Vector2f;
import weapontooltippreview.Log;

/**
 * Handles the scenario for the preview
 * Host, weapon, wing, target
 * Sets specific modifiers to match the preview needs (targeting, durability)
 */
public abstract class Scenario {

    /** Identifier for additions to the engine. */
    static final String PREVIEW_SOURCE_ID = "WeaponTooltipPreview_preview";
    private static final int WEAPON_HOST_OWNER = 0;
    static final int ENEMY_OWNER = 1;
    static final float DEPLOY_CR = 0.7f;
    /** The frame margin around ships. */
    private static final float FRAME_MARGIN = 1.15f;
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

    protected final CombatEngineAPI engine;
    protected final RefitShip refit;
    public ShipAPI weaponHost;
    public WeaponAPI weapon;
    protected final Vector2f origin;
    
    /** The weapon host and where the previewed weapon sits on it, or a carrier for a wing. */
    protected final WeaponHostFactory.WeaponHostPlan weaponHostPlan;

    protected TargetDummy dummy;
    public Vector2f dummyPosition;

    public float frameMinX, frameMaxX, frameCenterX, frameCenterY, frameHalfHeight;
    /** The scene may show as a split view based on weapon range, each ship in a split pane */
    public boolean canSplit;

    Scenario(CombatEngineAPI engine, WeaponHostFactory.WeaponHostPlan weaponHostPlan, RefitShip refit, Vector2f origin) {
        this.engine = engine;
        this.weaponHostPlan = weaponHostPlan;
        this.refit = refit;
        this.origin = origin;
    }

    /** Defines scenario based on preview parameters with the right weaponHost through the factory */
    public static Scenario create(CombatEngineAPI engine, PreviewSubject subject, RefitShip refit, Vector2f origin) {
        if (subject.weaponId == null) {
            FighterWingSpecAPI wing = Global.getSettings().getFighterWingSpec(subject.wingId);
            return new WingScenario(engine, wing, WeaponHostFactory.planWing(wing, refit), refit, origin);
        }
        WeaponSpecAPI spec = Global.getSettings().getWeaponSpec(subject.weaponId);
        WeaponHostFactory.WeaponHostPlan plan = subject.wingId != null
                ? WeaponHostFactory.planFighter(Global.getSettings().getFighterWingSpec(subject.wingId), spec)
                : WeaponHostFactory.plan(spec, refit);
        return new ShipScenario(engine, spec, plan, refit, origin);
    }

    /*** Spawn the scene elements */
    public final void spawn() {
        spawnWeaponHost();
        spawnScene();
        silenceOtherWeapons();
        Log.info("scene %s: weaponHost=%s (%s) origin=(%.0f,%.0f) frame x %.0f..%.0f ±%.0f; %s",
                getClass().getSimpleName(), weaponHost.getHullSpec().getHullId(), weaponHostPlan.source, origin.x,
                origin.y, frameMinX, frameMaxX, frameHalfHeight, spawnLogDetails());
    }

    /** Places the dummy and what else the weapon is shown against, and sets the framing. */
    protected abstract void spawnScene();

    /** The scene's own per-frame upkeep. */
    protected abstract void stepScene(float dt);

    /** Per-frame upkeep before the engine step. */
    public final void beforeStep(float dt) {
        weaponHost.getFluxTracker().setHardFlux(0f);
        weaponHost.getFluxTracker().setCurrFlux(0f);
        stepScene(dt);
        dummy.replaceIfDestroyed(dt);
    }

    /** Accessor for the previewSession */
    public final ShipAPI target() {
        return dummy.ship();
    }

    /** Frames weapon host and dummy with a margin & extra room for the sway on moving targets */
    protected final void frameScene(float runMargin, float sway) {
        float weaponHostRadius = weaponHost.getCollisionRadius();
        float dummyRadius = dummy.radius();
        frameMinX = weaponHost.getLocation().x - FRAME_MARGIN * weaponHostRadius;
        frameMaxX = dummyPosition.x + Math.max(FRAME_MARGIN * dummyRadius, runMargin);
        frameCenterX = (frameMinX + frameMaxX) / 2f;
        frameCenterY = (weaponHost.getLocation().y + dummyPosition.y) / 2f;
        frameHalfHeight = Math.max(FRAME_MARGIN * Math.max(weaponHostRadius, dummyRadius), dummyRadius + runMargin)
                + sway;
    }

    /** Spawns the weapon host as refit or generic */
    private void spawnWeaponHost() {
        FleetMemberAPI member = weaponHostPlan.wingId != null
                ? Global.getFactory().createFleetMember(FleetMemberType.FIGHTER_WING, weaponHostPlan.wingId)
                : Global.getFactory().createFleetMember(FleetMemberType.SHIP, weaponHostPlan.variant);
        float weaponHostCr = DEPLOY_CR;
        if (weaponHostPlan.fromRefit) {
            member.setCaptain(refit.member.getCaptain());
            if (refit.member.getFleetData() != null) {
                ((FleetMember) member).setFleetData((FleetData) refit.member.getFleetData());
            }
            weaponHostCr = Math.max(weaponHostCr, refit.member.getRepairTracker().getCR());
        }
        weaponHost = deploy(engine, member, weaponHostCr, WEAPON_HOST_OWNER, origin, -weaponHostPlan.slotAngle);
        weaponHost.getMutableStats().getHullDamageTakenMult().modifyMult(PREVIEW_SOURCE_ID, DAMAGE_MULT);
        weaponHost.getMutableStats().getArmorDamageTakenMult().modifyMult(PREVIEW_SOURCE_ID, ARMOR_MULT);
        weaponHost.getMutableStats().getEmpDamageTakenMult().modifyMult(PREVIEW_SOURCE_ID, EMP_MULT);
        weaponHost.getMutableStats().getPeakCRDuration().modifyFlat(PREVIEW_SOURCE_ID, CR_FLAT);
        weaponHost.getMutableStats().getSightRadiusMod().modifyFlat(PREVIEW_SOURCE_ID, SIGHT_FLAT);
        weaponHost.setShipAI(new PreviewAIs.HoldAI(weaponHost));
        if (weaponHost.getShield() != null && weaponHost.getShield().isOn()) weaponHost.getShield().toggleOff();
    }

    /** Deploy with full crew and a real CR before spawning, so no low-CR penalties. */
    static ShipAPI deploy(CombatEngineAPI engine, FleetMemberAPI member, float cr, int owner, Vector2f at, float facing) {
        member.setOwner(owner);
        member.getCrewComposition().setCrew(member.getNeededCrew());
        member.updateStats();
        member.getRepairTracker().setCR(cr);
        CombatFleetManagerAPI fleet = engine.getFleetManager(owner);
        fleet.setSuppressDeploymentMessages(true);
        return fleet.spawnFleetMember(member, new Vector2f(at), facing, 0f);
    }

    /** Only the previewed weapon may fire, the others stay disabled for the whole scene. */
    private void silenceOtherWeapons() {
        for (WeaponAPI w : weaponHost.getAllWeapons()) {
            if (w != weapon && !w.isDecorative()) w.setForceDisabled(true);
        }
    }

    // ----- Logs

    /** Scene-specific summary for the spawn log line. */
    protected abstract String spawnLogDetails();
    
    /** Periodic diagnostics line. */
    public abstract String frameLogDetails();
    
}
