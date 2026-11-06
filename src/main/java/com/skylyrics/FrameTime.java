package com.skylyrics;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * The fraction of the way through the current game tick, used to smooth movement between ticks.
 * Looked up by reflection so a renamed method in some Minecraft version degrades to slightly less smooth motion
 * (value 1.0) instead of breaking the build.
 */
final class FrameTime {
    private static final Logger LOG = LoggerFactory.getLogger("skylyrics");

    private static Method getTracker;
    private static Method getPartial;
    private static boolean failed;

    private FrameTime() {
    }

    static float partialTick(Minecraft mc) {
        if (failed) {
            return 1.0f;
        }
        try {
            if (getTracker == null) {
                getTracker = Minecraft.class.getMethod("getDeltaTracker");
            }
            if (getPartial == null) {
                getPartial = DeltaTracker.class.getMethod("getGameTimeDeltaPartialTick", boolean.class);
            }
            Object tracker = getTracker.invoke(mc);
            Object value = getPartial.invoke(tracker, false);
            if (value instanceof Float f) {
                return f;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            LOG.warn("Could not read the frame partial tick, motion may look less smooth: {}", e.toString());
        }
        return 1.0f;
    }
}
