package com.skylyrics.core;

/** Eases the text colour towards a new value (about 63% of the way after 0.45 s) so a new song's colour fades in. */
public final class TintTracker {
    private static final float TIME_CONSTANT_SECONDS = 0.45f;

    private int current = -1;
    private long lastNanos;

    public synchronized int update(int target, long nowNanos) {
        if (current < 0) {
            current = target;
        } else {
            float dt = Math.max(0f, (nowNanos - lastNanos) / 1_000_000_000f);
            float t = 1f - (float) Math.exp(-dt / TIME_CONSTANT_SECONDS);
            current = CoverColors.lerp(current, target, t);
        }
        lastNanos = nowNanos;
        return current;
    }
}
