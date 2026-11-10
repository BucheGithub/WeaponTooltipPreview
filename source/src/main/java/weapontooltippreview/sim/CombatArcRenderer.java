package weapontooltippreview.sim;

import com.fs.starfarer.api.combat.WeaponGroupAPI;
import weapontooltippreview.bridge.Refl;
import weapontooltippreview.bridge.StarfarerBridge;

/**
 * The game's OG weapon-arc renderer (the white dashed "ladder" lines shown in combat for the selected weapon group,
 * textured with graphics/hud/line4x4.png). Driven the way CombatState drives it: select a group, advance its fade,
 * render in world coordinates. Every call must happen while the sandbox engine is swapped in.
 */
public final class CombatArcRenderer {

    private final Object renderer = Refl.newInstance(StarfarerBridge.arcRendererClass);

    public static boolean isAvailable() {
        return StarfarerBridge.arcRendererClass != null;
    }

    public void select(WeaponGroupAPI group) {
        StarfarerBridge.arcSelectGroup.invoke(renderer, group);
    }

    public void advance(float dt) {
        StarfarerBridge.arcAdvance.invoke(renderer, dt);
    }

    public void render() {
        StarfarerBridge.arcRender.invoke(renderer);
    }
}
