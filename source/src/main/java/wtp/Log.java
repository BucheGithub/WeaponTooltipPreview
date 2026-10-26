package wtp;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

import java.util.Locale;

/** All mod output goes to starsector.log with a "[WTP]" prefix so it can be grepped. */
public final class Log {

    private static final Logger LOGGER = Global.getLogger(Log.class);
    /** Per-session and per-injection detail; on while the mod is being tested, off for release. */
    public static boolean verbose = true;

    private Log() {
    }

    public static void info(String format, Object... args) {
        LOGGER.info("[WTP] " + (args.length == 0 ? format : String.format(Locale.ROOT, format, args)));
    }

    public static void debug(String format, Object... args) {
        if (verbose) info(format, args);
    }

    public static void error(String message, Throwable t) {
        LOGGER.error("[WTP] " + message, t);
    }
}
