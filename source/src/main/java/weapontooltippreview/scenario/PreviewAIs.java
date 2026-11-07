package weapontooltippreview.scenario;

import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
* Ship AIs for the preview's actors. 
* Movement goes through real ship commands wherever it is visible. 
*/
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

    /** Holds position; the scene aims and fires its weapon. */
    static final class HoldAI extends BaseAI {
        private final ShipAPI ship;

        HoldAI(ShipAPI ship) {
            this.ship = ship;
        }

        /** Points at the weaponHost for firing */
        @Override
        public void advance(float amount) {
            ship.getVelocity().set(0f, 0f);
            ship.setAngularVelocity(0f);
        }
    }

    /**
     * Holds its shield on, off, or alternating, facing the weaponHost.
     * In drift mode it follows a bounded sine wave across the line of fire.
     * With launchers, fires a salvo from all of them at the weaponHost on a fixed beat; with wings, keeps them on the
     * weapon host.
     */
    static final class TargetAI extends BaseAI {
        static final float DRIFT_AMPLITUDE = 120f;
        private static final float DRIFT_PERIOD = 6f;
        private static final float SHIELD_PHASE = 6f;
        private static final float GAIN_POSITION = 1.5f;
        private static final float GAIN_VELOCITY = 0.9f;

        private final List<WeaponAPI> launchers;
        private static final float FIRST_SALVO_DELAY = 1f;
        private static final float SALVO_PERIOD = 6f;
        private static final float SALVO_LENGTH = 2.5f;
        
        private final ShipAPI ship;
        private final ShipAPI weaponHost;
        private final Vector2f dummyPosition;
        private final boolean drift;
        private final ShieldMode shieldMode;
    
        private float time;
        private float salvoTimer = FIRST_SALVO_DELAY;

        TargetAI(ShipAPI ship, ShipAPI weaponHost, Vector2f dummyPosition, boolean drift, ShieldMode shieldMode,
                 List<WeaponAPI> launchers) {
            this.ship = ship;
            this.weaponHost = weaponHost;
            this.dummyPosition = dummyPosition;
            this.drift = drift;
            this.shieldMode = shieldMode;
            this.launchers = launchers;
        }

        @Override
        public void advance(float amount) {
            time += amount;
            ShieldAPI shield = ship.getShield();
            if (shield != null) {
                boolean up = shieldMode == ShieldMode.ALWAYS_ON
                        || shieldMode == ShieldMode.FLUCTUATING && ((int) (time / SHIELD_PHASE)) % 2 == 0;
                if (up && !shield.isOn() && !ship.getFluxTracker().isOverloadedOrVenting()) shield.toggleOn();
                if (!up && shield.isOn()) shield.toggleOff();
                shield.forceFacing(VectorUtils.getAngle(ship.getLocation(), weaponHost.getLocation()));
            }
            fireOnTheBeat(amount);
            if (!ship.getAllWings().isEmpty()) {
                ship.setPullBackFighters(false);
                ship.setForceCarrierTarget(weaponHost);
                ship.setForceCarrierTargetTime(1f);
            }
            ship.setAngularVelocity(0f);
            if (!drift) {
                ship.getVelocity().set(0f, 0f);
                return;
            }
            
            float wantY = dummyPosition.y + DRIFT_AMPLITUDE * (float) Math.sin(2 * Math.PI * time / DRIFT_PERIOD);
            float push = GAIN_POSITION * (wantY - ship.getLocation().y) - GAIN_VELOCITY * ship.getVelocity().y;
            if (push > 5f) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            else if (push < -5f) ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
            ship.getVelocity().x = 0f;
        }

        /**
         * On each beat, refills every magazine and clears every cooldown (medium launchers reload slower than the
         * beat), then holds the triggers SALVO_LENGTH, pointed at the weaponHost
         */
        private void fireOnTheBeat(float amount) {
            salvoTimer -= amount;
            if (salvoTimer <= 0f) {
                salvoTimer = SALVO_PERIOD;
                for (WeaponAPI launcher : launchers) {
                    launcher.resetAmmo();
                    launcher.setRemainingCooldownTo(0f);
                }
            }
            if (salvoTimer < SALVO_PERIOD - SALVO_LENGTH) return;
            for (WeaponAPI launcher : launchers) {
                launcher.setCurrAngle(VectorUtils.getAngle(launcher.getLocation(), weaponHost.getLocation()));
                launcher.setForceFireOneFrame(true);
            }
        }
    }
}
