package weapontooltippreview.scenario;

import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;

/** The ship being refitted and the slot the hovered weapon is for. */
public final class RefitShip {

    public final FleetMemberAPI member;
    public final ShipVariantAPI variant;
    public final WeaponSlotAPI slot;

    public RefitShip(FleetMemberAPI member, ShipVariantAPI variant, WeaponSlotAPI slot) {
        this.member = member;
        this.variant = variant;
        this.slot = slot;
    }
}
