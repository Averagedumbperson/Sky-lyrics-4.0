package com.skylyrics;

import com.skylyrics.core.Config;
import com.skylyrics.core.LyricsService;
import com.skylyrics.core.NowPlaying;
import com.skylyrics.core.TintTracker;

/** The colour lyrics are drawn in right now: the album cover's colour (or a chosen preset), eased between songs. */
final class Tint {
    private static final TintTracker TRACKER = new TintTracker();

    private Tint() {
    }

    /** @return 0xRRGGBB */
    static int rgb(Config cfg, LyricsService svc, NowPlaying np) {
        int target = cfg.coverColor ? svc.coverRgb(np.trackId()) : Config.COLOR_RGB[cfg.colorIndex];
        return TRACKER.update(target, System.nanoTime());
    }
}
