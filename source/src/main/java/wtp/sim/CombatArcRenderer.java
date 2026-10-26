package wtp.sim;

import com.fs.starfarer.api.combat.WeaponGroupAPI;
import wtp.bridge.ObfBridge;
import wtp.bridge.Refl;

/**
 * The game's own weapon-arc renderer (the white dashed "ladder" lines shown in combat for the selected weapon group,
 * textured with graphics/hud/line4x4.png). Driven the way CombatState drives it: select a group, advance its fade,
 * render in world coordinates. Every call must happen while the sandbox engine is swapped in.
 */
public final class CombatArcRenderer {

    private final Object renderer = Refl.newInstance(ObfBridge.arcRendererClass);

    public static boolean isAvailable() {
        return ObfBridge.arcRendererClass != null;
    }

    public void select(WeaponGroupAPI group) {
        ObfBridge.arcSelectGroup.invoke(renderer, group);
    }

    public void advance(float dt) {
        ObfBridge.arcAdvance.invoke(renderer, dt);
    }

    public void render() {
        ObfBridge.arcRender.invoke(renderer);
    }
}
