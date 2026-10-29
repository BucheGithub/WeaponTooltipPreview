package wtp.scenario;

/** What a preview shows, by spec id: a weapon, or a fighter wing. */
public final class PreviewSubject {

    public enum Kind {WEAPON, WING}

    public final Kind kind;
    public final String id;

    private PreviewSubject(Kind kind, String id) {
        this.kind = kind;
        this.id = id;
    }

    public static PreviewSubject weapon(String weaponId) {
        return new PreviewSubject(Kind.WEAPON, weaponId);
    }

    public static PreviewSubject wing(String wingId) {
        return new PreviewSubject(Kind.WING, wingId);
    }

    public boolean isWing() {
        return kind == Kind.WING;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PreviewSubject && ((PreviewSubject) o).kind == kind && ((PreviewSubject) o).id.equals(id);
    }

    @Override
    public int hashCode() {
        return 31 * kind.hashCode() + id.hashCode();
    }

    /** The id for weapons (as logged so far), "wing &lt;id&gt;" for wings. */
    @Override
    public String toString() {
        return isWing() ? "wing " + id : id;
    }
}
