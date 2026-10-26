package wtp.scenario;

import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;

/** The ship being refitted and the slot the hovered weapon is for. Read-only: the host is built from a copy. */
public final class RefitShip {

    public final FleetMemberAPI member;
    public final ShipVariantAPI variant;
    /** Null when the clicked slot could not be identified; the host then uses its best fitting slot. */
    public final WeaponSlotAPI slot;

    public RefitShip(FleetMemberAPI member, ShipVariantAPI variant, WeaponSlotAPI slot) {
        this.member = member;
        this.variant = variant;
        this.slot = slot;
    }
}
