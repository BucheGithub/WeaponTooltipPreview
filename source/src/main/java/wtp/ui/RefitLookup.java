package wtp.ui;

import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import wtp.Log;
import wtp.bridge.ObfBridge;
import wtp.bridge.Refl;
import wtp.scenario.RefitShip;

import java.util.Map;

/**
 * Finds the ship and slot behind a refit-screen weapon tooltip. The tooltip is attached to the slot button on the
 * ship display; climbing its parents reaches the refit panel, whose methods are not obfuscated.
 */
final class RefitLookup {

    private static final int MAX_CHAIN = 30;

    private RefitLookup() {
    }

    /** Null outside the refit screen, or when the panel cannot be found (the preview then uses a generic host). */
    static RefitShip find(Object tooltip) {
        if (ObfBridge.refitPanelClass == null || !ObfBridge.tooltipWidget.owner.isInstance(tooltip)) return null;
        Object widget = ObfBridge.tooltipWidget.invoke(tooltip);
        Object panel = null;
        Object node = widget;
        for (int i = 0; node != null && i < MAX_CHAIN; i++) {
            if (ObfBridge.refitPanelClass.isInstance(node)) {
                panel = node;
                break;
            }
            node = UiTree.parent(node);
        }
        if (panel == null) return null;

        Object member = ObfBridge.refitMember.invoke(panel);
        Object display = ObfBridge.refitShipDisplay.invoke(panel);
        Object variant = display != null ? Refl.call(display, "getCurrentVariant") : null;
        if (!(member instanceof FleetMemberAPI) || !(variant instanceof ShipVariantAPI)) return null;

        String[] how = {"not found"};
        WeaponSlotAPI slot = findSlot(display, widget, how);
        Log.debug("refit context: %s/%s slot=%s (%s)", ((FleetMemberAPI) member).getShipName(),
                ((ShipVariantAPI) variant).getHullVariantId(), slot != null ? slot.getId() : "null", how[0]);
        return new RefitShip((FleetMemberAPI) member, (ShipVariantAPI) variant, slot);
    }

    /** The slot paired with the tooltip's widget: in one of the display's maps, or held by the widget itself. */
    private static WeaponSlotAPI findSlot(Object display, Object widget, String[] how) {
        for (Refl.RField f : Refl.fields(display.getClass())) {
            if (f.isStatic || !Map.class.isAssignableFrom(f.type)) continue;
            Object value = f.get(display);
            if (!(value instanceof Map)) continue;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (e.getKey() == widget && e.getValue() instanceof WeaponSlotAPI) {
                    how[0] = "display map " + f.name + " widget→slot";
                    return (WeaponSlotAPI) e.getValue();
                }
                if (e.getValue() == widget && e.getKey() instanceof WeaponSlotAPI) {
                    how[0] = "display map " + f.name + " slot→widget";
                    return (WeaponSlotAPI) e.getKey();
                }
            }
        }
        if (widget != null) {
            for (Refl.RField f : Refl.fields(widget.getClass())) {
                if (f.isStatic || f.type.isPrimitive()) continue;
                Object value = f.get(widget);
                if (value instanceof WeaponSlotAPI) {
                    how[0] = "widget field " + f.name;
                    return (WeaponSlotAPI) value;
                }
                // Buttons often carry their payload as a custom-data object; only game classes are inspected
                // (JDK internals refuse reflective access on Java 9+).
                if (value == null || !value.getClass().getName().startsWith("com.fs.")) continue;
                Refl.RField nestedField = Refl.fieldOfType(value.getClass(), WeaponSlotAPI.class, false);
                Object nested = nestedField != null ? nestedField.get(value) : null;
                if (nested instanceof WeaponSlotAPI) {
                    how[0] = "widget field " + f.name + "." + nestedField.name;
                    return (WeaponSlotAPI) nested;
                }
            }
        }
        return null;
    }
}
