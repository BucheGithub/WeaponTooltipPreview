package wtp.sim;

import com.fs.starfarer.api.combat.ShipAPI;
import wtp.bridge.ObfBridge;
import wtp.bridge.Refl;

/**
 * The game's own target reticle, as drawn around the selected target in combat: corner brackets, hull and flux bars,
 * the armor holo and range readouts. Driven the way CombatState drives it: mark the target every frame, advance its
 * fades, render in screen coordinates (see {@link EngineSandbox#render}). Construct and call while swapped in.
 */
public final class TargetReticle {

    private final Object renderer = Refl.newInstance(ObfBridge.reticleClass);

    public static boolean isAvailable() {
        return ObfBridge.reticleClass != null;
    }

    /** Marks {@code ship} as the selected target for this frame; unmarked ships fade out. */
    public void track(ShipAPI ship) {
        ObfBridge.reticleTrack.invoke(renderer, ship, 1f);
    }

    public void advance(float dt) {
        ObfBridge.reticleAdvance.invoke(renderer, dt);
    }

    /** Arguments as CombatState passes them at normal zoom: zoom factor 1, full brightness, no ribbon icon. */
    public void render() {
        ObfBridge.reticleRender.invoke(renderer, 1f, 1f, null);
    }
}
