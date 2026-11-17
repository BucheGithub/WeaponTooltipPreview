package weapontooltippreview.sim;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.entities.Ship;
import weapontooltippreview.bridge.Refl;
import weapontooltippreview.bridge.StarfarerBridge;

/**
 * The game's OG weapon-arc renderer
 */
public final class CombatArcRenderer {

    private final Object renderer = Refl.newInstance(StarfarerBridge.arcRendererClass);
    private ShipAPI ship;

    public static boolean isAvailable() {
        return StarfarerBridge.arcRendererClass != null;
    }

    public void select(WeaponGroupAPI group) {
        ship = group.getShip();
        StarfarerBridge.arcSelectGroup.invoke(renderer, group);
    }

    public void advance(float dt) {
        StarfarerBridge.arcAdvance.invoke(renderer, dt);
    }

    /**
     * Since 0.98.5a the renderer only draws arcs for the engine's player ship, and the sandbox has no player ship.
     * The weapon's ship is made the player ship for this draw call only, then the previous one is put back.
     */
    public void render() {
        CombatEngine engine = (CombatEngine) Global.getCombatEngine();
        ShipAPI previous = engine.getPlayerShip();
        if (previous == ship || !(ship instanceof Ship)) {
            StarfarerBridge.arcRender.invoke(renderer);
            return;
        }
        boolean controlled = engine.hasPlayerActuallyControlledARealShip();
        engine.setPlayerShip((Ship) ship);
        try {
            StarfarerBridge.arcRender.invoke(renderer);
        } finally {
            engine.setPlayerShip(previous != null && engine.isEntityInPlay(previous) ? (Ship) previous : null);
            engine.setPlayerHasControlledShip(controlled);
        }
    }
}
