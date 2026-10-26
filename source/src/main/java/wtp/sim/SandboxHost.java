package wtp.sim;

import wtp.Log;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Owns the one preview engine. Building an engine costs a visible hitch and a large burst of garbage, so it is kept
 * warm between hovers: each preview takes a cleared scene in it, and the engine is only rebuilt after
 * {@link #MAX_SCENES} scenes (bounding whatever a long-lived engine accumulates), after a failure, or after sitting
 * unused for {@link #IDLE_NANOS}.
 */
public final class SandboxHost {

    private static final int MAX_SCENES = 40;
    private static final long IDLE_NANOS = 60_000_000_000L;
    /** Consecutive engine creation failures before previews are switched off for the rest of the game session. */
    private static final int MAX_CREATE_FAILURES = 3;

    private static EngineSandbox sandbox;
    private static Object owner;
    private static long idleSince;
    private static int createFailures;
    private static boolean disabled;
    private static int created;
    /** Engines handed to destroyInstance; they should all be collected. Logged to catch leaks. */
    private static final Map<Object, Boolean> DISCARDED = new WeakHashMap<>();

    private SandboxHost() {
    }

    public static boolean isDisabled() {
        return disabled;
    }

    /** The sandbox for a new preview, built or rebuilt as needed. Throws if no engine can be built. */
    public static EngineSandbox acquire(Object newOwner) {
        if (disabled) throw new IllegalStateException("preview engine disabled");
        if (owner != null && owner != newOwner) Log.debug("sandbox taken over from a preview that did not release it");
        if (sandbox != null && sandbox.scenes() >= MAX_SCENES) discard("recycled after " + sandbox.scenes() + " scenes");
        if (sandbox == null) {
            long t0 = System.nanoTime();
            try {
                sandbox = EngineSandbox.create();
                createFailures = 0;
                created++;
            } catch (RuntimeException e) {
                if (++createFailures >= MAX_CREATE_FAILURES) {
                    disabled = true;
                    Log.info("preview engine failed to start %d times in a row; previews disabled until the next game load",
                            createFailures);
                }
                throw e;
            }
            Log.debug("sandbox #%d created in %.1f ms; %d combat plugins, stripped %s; %s", created,
                    (System.nanoTime() - t0) / 1e6, sandbox.plugins().size(), sandbox.strippedPlugins(), heap());
        }
        owner = newOwner;
        return sandbox;
    }

    /** The preview is done; its scene is cleared so nothing of it lingers while the engine waits for the next one. */
    public static void release(Object who) {
        if (owner != who || sandbox == null) return;
        owner = null;
        idleSince = System.nanoTime();
        try {
            sandbox.swapIn();
            try {
                sandbox.clearScene();
            } finally {
                sandbox.swapOut();
            }
        } catch (Throwable t) {
            Log.error("clearing the preview scene failed", t);
            discard("clear failed");
        }
    }

    /** Destroys the engine (failure, idle, game load). The next preview builds a fresh one. */
    public static void discard(String why) {
        if (sandbox == null) return;
        EngineSandbox s = sandbox;
        sandbox = null;
        owner = null;
        Object api = s.api();
        try {
            s.destroy();
        } catch (Throwable t) {
            Log.error("sandbox destroy failed", t);
        }
        if (api != null) DISCARDED.put(api, Boolean.TRUE);
        Log.debug("sandbox discarded (%s) after %d scenes; discarded engines still reachable: %d; %s", why, s.scenes(),
                DISCARDED.size(), heap());
    }

    /** Called every campaign frame: releases an engine nobody has used for a while. */
    public static void tick() {
        if (sandbox != null && owner == null && System.nanoTime() - idleSince > IDLE_NANOS) discard("idle");
    }

    /** New game session: drop the engine and give previews another chance if they had been switched off. */
    public static void reset() {
        discard("game load");
        disabled = false;
        createFailures = 0;
    }

    public static String heap() {
        Runtime rt = Runtime.getRuntime();
        return String.format(Locale.ROOT, "heap %dMB/%dMB", (rt.totalMemory() - rt.freeMemory()) >> 20,
                rt.maxMemory() >> 20);
    }
}
