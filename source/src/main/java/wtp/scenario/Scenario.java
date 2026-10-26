package wtp.scenario;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.FogOfWarAPI;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.mission.FleetSide;
import org.lwjgl.util.vector.Vector2f;
import wtp.Log;
import wtp.WtpSettings;

import java.util.ArrayList;
import java.util.Locale;

/**
 * One preview scene in a sandbox engine: the host ship carrying the hovered weapon at {@link #origin}, plus whatever
 * the weapon is shown against. Everything here runs while the sandbox is swapped in.
 * <p>
 * Combat sides matter: deployment takes a ship's owner from its FleetMember, not from the fleet manager it is spawned
 * through, and factory-made members all share the default owner. Every member gets its owner before spawning, or
 * missiles pass through enemies, their AI rejects them as targets, and autofire ignores them.
 */
public abstract class Scenario {

    static final String MOD_ID = "wtp_preview";
    static final int HOST_OWNER = 0;
    static final int ENEMY_OWNER = 1;
    /**
     * Combat readiness ships deploy with. CR penalties are stat modifiers applied at deployment, and a factory-made
     * fleet member has no crew and 0% CR: it deploys with malfunctions, critical malfunctions (missiles losing
     * guidance and flaming out) and weapons disabled. Raising CR after spawn undoes none of it.
     */
    static final float DEPLOY_CR = 0.7f;
    /** Fallback dummy if the chosen variant is missing, and the swarm's hidden missile launcher. */
    static final String DEFAULT_ENEMY_VARIANT = "hammerhead_Balanced";

    protected final CombatEngineAPI engine;
    protected final WeaponSpecAPI spec;
    protected final RefitShip refit;
    protected final Vector2f origin;
    protected float time;
    private String hostSource = "generic";

    public ShipAPI host;
    public WeaponAPI weapon;
    /** World framing fixed at spawn (the camera never chases moving ships): x extent, centre line, half height. */
    public float frameMinX, frameMaxX, frameCenterY, frameHalfHeight;
    /** Bumped on every fresh setup (new magazine, new target) so the session can re-arm per-setup visuals. */
    public int setupCycle;

    Scenario(CombatEngineAPI engine, WeaponSpecAPI spec, RefitShip refit, Vector2f origin) {
        this.engine = engine;
        this.spec = spec;
        this.refit = refit;
        this.origin = origin;
    }

    public static Scenario create(CombatEngineAPI engine, String weaponId, RefitShip refit, Vector2f origin) {
        WeaponSpecAPI spec = Global.getSettings().getWeaponSpec(weaponId);
        if (spec == null) throw new NoPreviewException("unknown weapon " + weaponId);
        ShipVariantAPI dummy = enemyVariant(WtpSettings.dummyVariant());
        switch (PreviewKind.of(spec)) {
            case SWARM:
                return new SwarmScenario(engine, spec, refit, origin);
            case SHIP_WITH_SALVOS:
                return new ShipScenario(engine, spec, refit, origin, dummy, WtpSettings.shieldMode(), true);
            default:
                return new ShipScenario(engine, spec, refit, origin, dummy, WtpSettings.shieldMode(), false);
        }
    }

    public final void spawn() {
        spawnHost();
        spawnScene();
        Log.debug("scene %s: host=%s (%s) slot=%s range=%.0f origin=(%.0f,%.0f) frame x %.0f..%.0f ±%.0f; %s",
                getClass().getSimpleName(), host.getHullSpec().getHullId(), hostSource, weapon.getSlot().getId(),
                weapon.getRange(), origin.x, origin.y, frameMinX, frameMaxX, frameHalfHeight, describe());
    }

    /** Places what the weapon is shown against and sets the framing. */
    protected abstract void spawnScene();

    /** Per-frame upkeep before the engine step. */
    public void beforeStep(float dt) {
        time += dt;
        host.getFluxTracker().setHardFlux(0f);
        host.getFluxTracker().setCurrFlux(0f);
        revealScene();
        silenceOtherWeapons();
    }

    /** The ship the weapon is shown against, if any: strip marker and split-view focus. */
    public ShipAPI target() {
        return null;
    }

    /** Where {@link #target()} is held. */
    public Vector2f targetHome() {
        return null;
    }

    /** Long-range ship scenes may show as a split view. */
    public boolean canSplit() {
        return false;
    }

    public int kills() {
        return 0;
    }

    /** Scene-specific summary for the spawn log line. */
    protected abstract String describe();

    /** Periodic diagnostics line. */
    public String diagnostics() {
        WeaponGroupAPI group = host.getWeaponGroupFor(weapon);
        AutofireAIPlugin ai = group != null && group.isAutofiring() ? group.getAutofirePlugin(weapon) : null;
        String autofire = ai == null ? "off" : String.format(Locale.ROOT, "on target=%s missile=%s fire=%s",
                ai.getTargetShip() != null ? ai.getTargetShip().getHullSpec().getHullId() : "-",
                ai.getTargetMissile() != null ? ai.getTargetMissile().getProjectileSpecId() : "-", ai.shouldFire());
        return String.format(Locale.ROOT, "weapon disabled=%s firing=%s ammo=%d/%d autofire %s proj=%d missiles=%d beams=%d",
                weapon.isDisabled(), weapon.isFiring(), weapon.usesAmmo() ? weapon.getAmmo() : -1,
                weapon.usesAmmo() ? weapon.getMaxAmmo() : -1, autofire, engine.getProjectiles().size(),
                engine.getMissiles().size(), engine.getBeams().size());
    }

    // ------------------------------------------------------------------ host

    private void spawnHost() {
        HostFactory.HostPlan plan = HostFactory.plan(spec, refit);
        hostSource = plan.source;
        FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, plan.variant);
        float hostCr = DEPLOY_CR;
        if (plan.fromRefit) {
            // Officer and fleet-commander skills shape the real ship's stats (range, rate of fire...).
            member.setCaptain(refit.member.getCaptain());
            if (refit.member.getFleetData() != null) {
                member.setFleetCommanderForStats(refit.member.getFleetData().getCommander(), refit.member.getFleetData());
            }
            hostCr = Math.max(hostCr, refit.member.getRepairTracker().getCR());
        }
        prepareForDeployment(member, hostCr, HOST_OWNER);
        engine.getFleetManager(FleetSide.PLAYER).setSuppressDeploymentMessages(true);
        host = engine.getFleetManager(FleetSide.PLAYER).spawnFleetMember(member, new Vector2f(origin), -plan.slotAngle, 0f);
        enforceOwner(host, HOST_OWNER);
        makeInvulnerable(host);
        // Preview conditions, not weapon stats: a freshly deployed ship that never degrades.
        host.getMutableStats().getPeakCRDuration().modifyFlat(MOD_ID, 1e6f);
        host.getMutableStats().getCRLossPerSecondPercent().modifyMult(MOD_ID, 0f);
        for (WeaponAPI w : host.getAllWeapons()) {
            if (w.getId().equals(spec.getWeaponId())) weapon = w;
        }
        if (weapon == null) throw new IllegalStateException("weapon missing on spawned host");
        host.setShipAI(new PreviewAIs.HoldAI(host));
        if (host.getShield() != null && host.getShield().isOn()) host.getShield().toggleOff();
    }

    /** Hands the weapon to its own autofire AI (true) or to the scenario's manual aim (false). */
    protected void setAutofire(boolean on) {
        WeaponGroupAPI group = host.getWeaponGroupFor(weapon);
        if (group != null) {
            if (on && !group.isAutofiring()) group.toggleOn();
            if (!on && group.isAutofiring()) group.toggleOff();
        }
        weapon.setSuspendAutomaticTurning(!on);
    }

    /** A refit host keeps built-in weapons; only the previewed weapon may fire. */
    private void silenceOtherWeapons() {
        for (WeaponAPI w : host.getAllWeapons()) {
            if (w != weapon) w.setForceNoFireOneFrame(true);
        }
    }

    /**
     * Weapon and missile AIs only engage what their side can see. Nothing in the sandbox lifts combat fog of war,
     * so both sides get the whole scene revealed every frame.
     */
    private void revealScene() {
        float cx = (frameMinX + frameMaxX) / 2f;
        float radius = (frameMaxX - frameMinX) + frameHalfHeight * 2f;
        for (int owner = 0; owner <= 1; owner++) {
            FogOfWarAPI fog = engine.getFogOfWar(owner);
            if (fog != null) fog.revealAroundPoint(this, cx, frameCenterY, radius);
        }
    }

    // ------------------------------------------------------------------ enemies

    /** The stock variant, or the default dummy if it does not exist (a mod providing it was removed...). */
    static ShipVariantAPI enemyVariant(String variantId) {
        ShipVariantAPI variant = null;
        try {
            variant = Global.getSettings().getVariant(variantId);
        } catch (RuntimeException ignored) {
        }
        if (variant == null) {
            Log.info("variant %s not found; using %s", variantId, DEFAULT_ENEMY_VARIANT);
            variant = Global.getSettings().getVariant(DEFAULT_ENEMY_VARIANT);
        }
        return variant;
    }

    /** An enemy ship stripped of its weapons and fighters, deployed at full readiness with the enemy owner. */
    protected ShipAPI spawnEnemyShip(ShipVariantAPI stock, Vector2f at, float facing) {
        ShipVariantAPI variant = stock.clone();
        for (String slotId : new ArrayList<>(variant.getFittedWeaponSlots())) variant.clearSlot(slotId);
        for (int i = 0; i < variant.getWings().size(); i++) {
            try {
                variant.setWingId(i, null);
            } catch (RuntimeException ignored) {
            }
        }
        FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variant);
        prepareForDeployment(member, DEPLOY_CR, ENEMY_OWNER);
        engine.getFleetManager(FleetSide.ENEMY).setSuppressDeploymentMessages(true);
        ShipAPI ship = engine.getFleetManager(FleetSide.ENEMY).spawnFleetMember(member, new Vector2f(at), facing, 0f);
        enforceOwner(ship, ENEMY_OWNER);
        return ship;
    }

    /**
     * An enemy missile from {@code source} (which sets its side) homing on {@code target}. MissileAI reads its target
     * from the source ship when constructed, so the source's ship target is set first; the AI is told directly too.
     */
    protected MissileAPI launchMissile(ShipAPI source, String weaponId, Vector2f at, float facing, ShipAPI target) {
        source.setShipTarget(target);
        CombatEntityAPI entity = engine.spawnProjectile(source, null, weaponId, new Vector2f(at), facing, null);
        if (!(entity instanceof MissileAPI)) return null;
        MissileAPI missile = (MissileAPI) entity;
        if (missile.getMissileAI() instanceof GuidedMissileAI) ((GuidedMissileAI) missile.getMissileAI()).setTarget(target);
        return missile;
    }

    /** Side, full crew and a real CR before spawning, so deployment applies no low-CR penalties. */
    static void prepareForDeployment(FleetMemberAPI member, float cr, int owner) {
        member.setOwner(owner);
        member.getCrewComposition().setCrew(member.getNeededCrew());
        member.updateStats();
        member.getRepairTracker().setCR(cr);
    }

    static void enforceOwner(ShipAPI ship, int owner) {
        if (ship.getOwner() != owner) {
            Log.debug("%s spawned with owner %d; forcing %d", ship.getHullSpec().getHullId(), ship.getOwner(), owner);
            ship.setOwner(owner);
        }
        ship.setOriginalOwner(owner);
    }

    static void makeInvulnerable(ShipAPI ship) {
        ship.getMutableStats().getHullDamageTakenMult().modifyMult(MOD_ID, 0f);
        ship.getMutableStats().getArmorDamageTakenMult().modifyMult(MOD_ID, 0f);
        ship.getMutableStats().getEmpDamageTakenMult().modifyMult(MOD_ID, 0f);
    }

    // ------------------------------------------------------------------ math

    static float angleTo(Vector2f from, Vector2f to) {
        return (float) Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x));
    }

    static float shortestAngle(float from, float to) {
        float d = (to - from) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    static Vector2f polar(Vector2f centre, float radius, float degrees) {
        double a = Math.toRadians(degrees);
        return new Vector2f(centre.x + radius * (float) Math.cos(a), centre.y + radius * (float) Math.sin(a));
    }
}
