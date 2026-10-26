package wtp.sim;

import wtp.Log;
import wtp.bridge.ObfBridge;

/**
 * Preview volume. The game applies its sound effects volume to every sound when it starts and on every per-frame
 * loop update, so scaling it only during the sandbox step would let beam and engine loops jump back to full volume.
 * Instead the volume is lowered for as long as a preview is showing, and restored a moment after it ends, once its
 * last sounds have faded. Only the game's options and title screens ever set this volume, so nothing competes.
 */
public final class PreviewSound {

    /** Lets the preview's last explosions finish at the lowered volume. */
    private static final long TAIL_NANOS = 1_500_000_000L;

    private static boolean held;
    private static float savedVolume;
    private static float savedVolume2;
    private static long restoreAt;

    private PreviewSound() {
    }

    /** False if the game's volume control could not be found; the preview is then muted below 100%. */
    public static boolean isAvailable() {
        return ObfBridge.soundInstance != null;
    }

    /** Keeps the game's sound effects at {@code level} (0..1) of the player's setting while a preview runs. */
    public static void hold(float level) {
        if (!isAvailable()) return;
        try {
            Object manager = ObfBridge.soundInstance.invoke(null);
            if (!held) {
                savedVolume = (Float) ObfBridge.soundVolumeGet.invoke(manager);
                savedVolume2 = (Float) ObfBridge.soundVolume2Get.invoke(manager);
                held = true;
            }
            restoreAt = 0L;
            ObfBridge.soundVolumeSet.invoke(manager, savedVolume * level);
            ObfBridge.soundVolume2Set.invoke(manager, savedVolume2 * level);
        } catch (Throwable t) {
            Log.error("setting the preview volume failed; preview sound control disabled", t);
            restoreNow();
            ObfBridge.soundInstance = null;
        }
    }

    /** The preview ended (or was muted): restore the player's volume after a short tail. */
    public static void release() {
        if (held && restoreAt == 0L) restoreAt = System.nanoTime() + TAIL_NANOS;
    }

    /** Called every campaign frame. */
    public static void tick() {
        if (held && restoreAt != 0L && System.nanoTime() >= restoreAt) restoreNow();
    }

    public static void restoreNow() {
        if (!held) return;
        held = false;
        restoreAt = 0L;
        try {
            Object manager = ObfBridge.soundInstance.invoke(null);
            ObfBridge.soundVolumeSet.invoke(manager, savedVolume);
            ObfBridge.soundVolume2Set.invoke(manager, savedVolume2);
        } catch (Throwable t) {
            Log.error("restoring the sound effects volume failed", t);
        }
    }
}
