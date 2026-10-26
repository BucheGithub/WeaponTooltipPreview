package wtp.scenario;

/** How the dummy target uses its shield. */
public enum ShieldMode {
    ALWAYS_ON,
    ALWAYS_OFF,
    /** Up for a few seconds, down for a few seconds, so both shield and hull hits show. */
    FLUCTUATING
}
