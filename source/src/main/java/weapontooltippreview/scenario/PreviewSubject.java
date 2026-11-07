package weapontooltippreview.scenario;

import java.util.Objects;

/**
 * What a preview shows
 * weaponId = This is a Weapon
 * wingId = This is a Wing
 * weaponId + wingId = This is a Weapon on a wing for cycle info
 */
public final class PreviewSubject {

    public final String weaponId;
    public final String wingId;

    public PreviewSubject(String weaponId, String wingId) {
        this.weaponId = weaponId;
        this.wingId = wingId;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PreviewSubject)) return false;
        PreviewSubject other = (PreviewSubject) o;
        return Objects.equals(other.weaponId, weaponId) && Objects.equals(other.wingId, wingId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(weaponId, wingId);
    }

    @Override
    public String toString() {
        if (weaponId == null) return "wing " + wingId;
        return wingId != null ? weaponId + " on wing " + wingId : weaponId;
    }
}
