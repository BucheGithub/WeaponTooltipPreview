package weapontooltippreview.ui;

import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import weapontooltippreview.Log;
import weapontooltippreview.bridge.Refl;
import weapontooltippreview.bridge.StarfarerBridge;
import weapontooltippreview.scenario.RefitShip;

/**
 * Finds the ship and slot behind a refit-screen weapon tooltip. The tooltip is attached to the slot button on the
 * ship display; climbing its parents reaches the refit panel, whose methods are not obfuscated.
 */
final class RefitLookup {

    private static final int MAX_CHAIN = 30;

    private RefitLookup() {
    }

    /**
     * Null outside the refit screen, or when the panel cannot be found (the preview then uses a generic weapon host).
     */
    static RefitShip find(Object tooltip) {
        if (StarfarerBridge.refitPanelClass == null) return null;
        Object widget = StarfarerBridge.tooltipWidget.invoke(tooltip);
        Object panel = null;
        Object node = widget;
        for (int i = 0; node != null && i < MAX_CHAIN; i++) {
            if (StarfarerBridge.refitPanelClass.isInstance(node)) {
                panel = node;
                break;
            }
            node = UiTree.parent(node);
        }
        if (panel == null) return null;

        Object member = StarfarerBridge.refitMember.invoke(panel);
        Object display = StarfarerBridge.refitShipDisplay.invoke(panel);
        Object variant = display != null ? Refl.call(display, "getCurrentVariant") : null;
        if (!(member instanceof FleetMemberAPI) || !(variant instanceof ShipVariantAPI)) return null;

        WeaponSlotAPI slot = slotOf(widget);
        Log.info("refit context: %s/%s slot=%s", ((FleetMemberAPI) member).getShipName(),
                ((ShipVariantAPI) variant).getHullVariantId(), slot != null ? slot.getId() : "none");
        return new RefitShip((FleetMemberAPI) member, (ShipVariantAPI) variant, slot);
    }

    /** A weapon slot's button is drawn by a renderer that holds the slot; other widgets have no such renderer. */
    private static WeaponSlotAPI slotOf(Object widget) {
        Refl.ReflectedMethod getRenderer = Refl.method(widget.getClass(), "getRenderer");
        Object renderer = getRenderer != null ? getRenderer.invoke(widget) : null;
        if (renderer == null) return null;
        Refl.ReflectedField slot = Refl.fieldOfType(renderer.getClass(), WeaponSlotAPI.class, false);
        return slot != null ? (WeaponSlotAPI) slot.get(renderer) : null;
    }
}
