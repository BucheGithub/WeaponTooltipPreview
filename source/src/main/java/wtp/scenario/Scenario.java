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
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.mission.FleetSide;
import org.lwjgl.util.vector.Vector2f;
import wtp.Log;
import wtp.WtpSettings;

import java.util.ArrayList;
import java.util.Locale;

/**
 * One preview scene in a sandbox engine: the host ship carrying the hovered weapon (or launching the hovered wing) at
 * {@link #origin}, plus whatever it is shown against. Everything here runs while the sandbox is swapped in.
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
    /** Fallback dummy if the chosen variant is missing. */
    static final String DEFAULT_ENEMY_VARIANT = "hammerhead_Balanced";
    /** A dummy's point defense, one per slot that takes one: PD lasers where energy fits, Vulcans where ballistic does. */
    private static final String[] DUMMY_POINT_DEFENSE = {"pdlaser", "vulcan"};

    protected final CombatEngineAPI engine;
    /** The previewed weapon; null in wing scenes. */
    protected final WeaponSpecAPI spec;
    protected final RefitShip refit;
    protected final Vector2f origin;
    protected float time;
    private String hostSource = "generic";

    public ShipAPI host;
    /** The previewed weapon on the host; null in wing scenes. */
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

    public static Scenario create(CombatEngineAPI engine, PreviewSubject subject, RefitShip refit, Vector2f origin) {
        ShipVariantAPI dummy = enemyVariant(WtpSettings.dummyVariant());
        if (subject.isWing()) {
            FighterWingSpecAPI wing = null;
            try {
                wing = Global.getSettings().getFighterWingSpec(subject.id);
            } catch (RuntimeException ignored) {
            }
            if (wing == null) throw new NoPreviewException("unknown wing " + subject.id);
            return new WingScenario(engine, wing, refit, origin, dummy, WtpSettings.shieldMode());
        }
        WeaponSpecAPI spec = Global.getSettings().getWeaponSpec(subject.id);
        if (spec == null) throw new NoPreviewException("unknown weapon " + subject.id);
        boolean pointDefense = PreviewKind.of(spec) == PreviewKind.POINT_DEFENSE;
        return new ShipScenario(engine, spec, refit, origin, dummy, WtpSettings.shieldMode(), pointDefense);
    }

    public final void spawn() {
        spawnHost();
        spawnScene();
        Log.debug("scene %s: host=%s (%s)%s origin=(%.0f,%.0f) frame x %.0f..%.0f ±%.0f; %s",
                getClass().getSimpleName(), host.getHullSpec().getHullId(), hostSource,
                weapon != null ? String.format(Locale.ROOT, " slot=%s range=%.0f", weapon.getSlot().getId(),
                        weapon.getRange()) : "", origin.x, origin.y, frameMinX, frameMaxX, frameHalfHeight, describe());
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
        if (weapon == null) return "";
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

    /** The host and where the previewed weapon sits on it; wing scenes plan a carrier instead. */
    protected HostFactory.HostPlan planHost() {
        return HostFactory.plan(spec, refit);
    }

    private void spawnHost() {
        HostFactory.HostPlan plan = planHost();
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
        if (plan.slotId != null) {
            for (WeaponAPI w : host.getAllWeapons()) {
                if (w.getSlot().getId().equals(plan.slotId) && w.getId().equals(spec.getWeaponId())) weapon = w;
            }
            if (weapon == null) throw new IllegalStateException("weapon missing on spawned host");
        }
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

    /** A refit host keeps built-in weapons; only the previewed weapon may fire (none at all in wing scenes). */
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
        return spawnEnemyShip(stock, at, facing, false);
    }

    /** As above; with {@code pointDefense}, re-armed with light PD on autofire (see {@link #DUMMY_POINT_DEFENSE}). */
    protected ShipAPI spawnEnemyShip(ShipVariantAPI stock, Vector2f at, float facing, boolean pointDefense) {
        ShipVariantAPI variant = stock.clone();
        for (String slotId : new ArrayList<>(variant.getFittedWeaponSlots())) variant.clearSlot(slotId);
        for (int i = 0; i < variant.getWings().size(); i++) {
            try {
                variant.setWingId(i, null);
            } catch (RuntimeException ignored) {
            }
        }
        if (pointDefense) armWithPointDefense(variant);
        FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variant);
        prepareForDeployment(member, DEPLOY_CR, ENEMY_OWNER);
        engine.getFleetManager(FleetSide.ENEMY).setSuppressDeploymentMessages(true);
        ShipAPI ship = engine.getFleetManager(FleetSide.ENEMY).spawnFleetMember(member, new Vector2f(at), facing, 0f);
        enforceOwner(ship, ENEMY_OWNER);
        if (pointDefense) {
            for (WeaponGroupAPI group : ship.getWeaponGroupsCopy()) {
                if (!group.isAutofiring()) group.toggleOn();
            }
        }
        return ship;
    }

    /** One light PD weapon in every slot that takes one (small and medium energy or ballistic), all in one group. */
    private static void armWithPointDefense(ShipVariantAPI variant) {
        variant.getWeaponGroups().clear();
        WeaponGroupSpec group = new WeaponGroupSpec(WeaponGroupType.LINKED);
        group.setAutofireOnByDefault(true);
        for (WeaponSlotAPI slot : variant.getHullSpec().getAllWeaponSlotsCopy()) {
            if (!slot.isWeaponSlot() || slot.isBuiltIn() || slot.isDecorative() || slot.isSystemSlot() || slot.isHidden()) {
                continue;
            }
            for (String weaponId : DUMMY_POINT_DEFENSE) {
                WeaponSpecAPI pd = Global.getSettings().getWeaponSpec(weaponId);
                if (pd != null && slot.weaponFits(pd)) {
                    variant.addWeapon(slot.getId(), weaponId);
                    group.addSlot(slot.getId());
                    break;
                }
            }
        }
        if (!group.getSlots().isEmpty()) variant.addWeaponGroup(group);
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
