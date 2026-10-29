package wtp.scenario;

import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

/** Ship AIs for the preview's actors. Movement goes through real ship commands wherever it is visible. */
final class PreviewAIs {

    private PreviewAIs() {
    }

    private abstract static class BaseAI implements ShipAIPlugin {
        private final ShipwideAIFlags flags = new ShipwideAIFlags();
        private final ShipAIConfig config = new ShipAIConfig();

        @Override public void setDoNotFireDelay(float amount) { }
        @Override public void forceCircumstanceEvaluation() { }
        @Override public boolean needsRefit() { return false; }
        @Override public ShipwideAIFlags getAIFlags() { return flags; }
        @Override public void cancelCurrentManeuver() { }
        @Override public ShipAIConfig getConfig() { return config; }
    }

    /** Holds position; the host's weapon is aimed by the scenario (manual fire) or by its own autofire AI. */
    static final class HoldAI extends BaseAI {
        private final ShipAPI ship;

        HoldAI(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public void advance(float amount) {
            ship.getVelocity().set(0f, 0f);
            ship.setAngularVelocity(0f);
        }
    }

    /**
     * Holds its shield on, off, or alternating, facing the host. In drift mode it follows a bounded sine wave across
     * the line of fire using real strafe commands, so guidance is visible but the ship never wanders out of frame.
     */
    static final class TargetAI extends BaseAI {
        private static final float GAIN_POSITION = 1.5f;
        private static final float GAIN_VELOCITY = 0.9f;
        private final ShipAPI ship;
        private final ShipAPI threat;
        private final Vector2f home;
        private final boolean drift;
        private final ShieldMode shieldMode;
        private final float shieldPhase;
        private final float driftPeriod;
        private final float driftAmplitude;
        private float time;

        TargetAI(ShipAPI ship, ShipAPI threat, Vector2f home, boolean drift, ShieldMode shieldMode, float shieldPhase,
                 float driftPeriod, float driftAmplitude) {
            this.ship = ship;
            this.threat = threat;
            this.home = home;
            this.drift = drift;
            this.shieldMode = shieldMode;
            this.shieldPhase = shieldPhase;
            this.driftPeriod = driftPeriod;
            this.driftAmplitude = driftAmplitude;
        }

        @Override
        public void advance(float amount) {
            time += amount;
            ShieldAPI shield = ship.getShield();
            if (shield != null) {
                boolean up = shieldMode == ShieldMode.ALWAYS_ON
                        || shieldMode == ShieldMode.FLUCTUATING && ((int) (time / shieldPhase)) % 2 == 0;
                if (up && !shield.isOn() && !ship.getFluxTracker().isOverloadedOrVenting()) shield.toggleOn();
                if (!up && shield.isOn()) shield.toggleOff();
                shield.forceFacing(VectorUtils.getAngle(ship.getLocation(), threat.getLocation()));
            }
            ship.setAngularVelocity(0f);
            if (!drift) {
                ship.getVelocity().set(0f, 0f);
                return;
            }
            // Facing 180°: STRAFE_LEFT moves toward -y, STRAFE_RIGHT toward +y.
            float wantY = home.y + driftAmplitude * (float) Math.sin(2 * Math.PI * time / driftPeriod);
            float push = GAIN_POSITION * (wantY - ship.getLocation().y) - GAIN_VELOCITY * ship.getVelocity().y;
            if (push > 5f) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            else if (push < -5f) ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
            ship.getVelocity().x = 0f;
        }
    }

    /**
     * Fighter attack run: flies its real speed and turn rate through a list of waypoints (past the host, then out of
     * frame). The scenario removes it once it leaves the frame.
     */
    static final class FlyByAI extends BaseAI {
        private static final float WAYPOINT_RADIUS = 120f;
        private static final float TURN_DEADBAND = 3f;
        private final ShipAPI ship;
        private final Vector2f[] waypoints;
        private int index;

        FlyByAI(ShipAPI ship, Vector2f... waypoints) {
            this.ship = ship;
            this.waypoints = waypoints;
        }

        @Override
        public void advance(float amount) {
            Vector2f wp = waypoints[index];
            if (index < waypoints.length - 1 && MathUtils.isWithinRange(ship.getLocation(), wp, WAYPOINT_RADIUS)) {
                wp = waypoints[++index];
            }
            float diff = MathUtils.getShortestRotation(ship.getFacing(), VectorUtils.getAngle(ship.getLocation(), wp));
            // Turn toward the waypoint, letting angular momentum carry the last stretch so it does not wobble.
            float w = ship.getAngularVelocity();
            float turnAccel = Math.max(1f, ship.getMutableStats().getTurnAcceleration().getModifiedValue());
            float error = diff - w * Math.abs(w) / (2f * turnAccel);
            if (error > TURN_DEADBAND) ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
            else if (error < -TURN_DEADBAND) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
            ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        }
    }
}
