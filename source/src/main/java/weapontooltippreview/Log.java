package weapontooltippreview;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

import java.util.Locale;

/**
 * All mod output goes to starsector.log with a "[WeaponTooltipPreview]" prefix
 * Errors are always written; the rest only with the "Enable logs" setting on.
 */
public final class Log {

    private static final Logger LOGGER = Global.getLogger(Log.class);
    private static boolean enabled;

    private Log() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void info(String format, Object... args) {
        if (enabled) LOGGER.info("[WeaponTooltipPreview] " + (args.length == 0 ? format : String.format(Locale.ROOT, format, args)));
    }

    /** Used and maximum heap, for log lines. */
    public static String heap() {
        Runtime rt = Runtime.getRuntime();
        return String.format(Locale.ROOT, "heap %dMB/%dMB", (rt.totalMemory() - rt.freeMemory()) >> 20,
                rt.maxMemory() >> 20);
    }

    public static void error(String message) {
        LOGGER.error("[WeaponTooltipPreview] " + message);
    }

    public static void error(String message, Throwable t) {
        LOGGER.error("[WeaponTooltipPreview] " + message, t);
    }
}
