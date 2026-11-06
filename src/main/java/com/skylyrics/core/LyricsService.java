package com.skylyrics.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Background worker: polls Spotify for the current track and fetches its synced lyrics.
 * The render thread only reads the volatile snapshots below, so it never blocks on the network.
 */
public final class LyricsService {
    private static final Logger LOG = LoggerFactory.getLogger("skylyrics");
    private static final String NOW_PLAYING_URL = "https://api.spotify.com/v1/me/player/currently-playing";

    public enum Status { LOCAL, NO_CLIENT_ID, DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    public record LyricsState(String trackId, List<LyricLine> lines, String note) {
        public static final LyricsState NONE = new LyricsState("", List.of(), "");
    }

    private final Config config;
    private final SpotifyAuth auth;
    private final LrcLibClient lrclib = new LrcLibClient();
    private final ScheduledExecutorService pool = new ScheduledThreadPoolExecutor(3, r -> {
        Thread t = new Thread(r, "SkyLyrics-worker");
        t.setDaemon(true);
        return t;
    });

    private final LocalPlayerReader local = new LocalPlayerReader(new LocalPlayerReader.Listener() {
        @Override
        public void onSample(LocalSample sample) {
            onLocalSample(sample);
        }

        @Override
        public void onCover(String trackKey, int[] rgbPixels) {
            applyCover(trackKey, rgbPixels);
        }
    });
    private final LocalPositionTracker tracker = new LocalPositionTracker();

    private volatile NowPlaying nowPlaying;
    private volatile LyricsState lyrics = LyricsState.NONE;
    private volatile String message = "";
    private volatile boolean error;
    private volatile long backoffUntilMs;

    /** Text colour taken from the album cover of {@code coverTrackId}. */
    private volatile int coverRgb = CoverColors.DEFAULT;
    private volatile String coverTrackId = "";
    /** A cover that arrived before its song was published (the order of lines from the helper isn't guaranteed). */
    private volatile String pendingCoverKey = "";
    private volatile int pendingCoverRgb = CoverColors.DEFAULT;

    /** When to try the lyrics lookup again after a network failure (0 = no retry pending). */
    private volatile long lyricsRetryAtMs;

    public LyricsService(Config config, Path tokenFile) {
        this.config = config;
        this.auth = new SpotifyAuth(config, tokenFile);
    }

    public void start() {
        pool.scheduleWithFixedDelay(this::pollSafely, 0, 2, TimeUnit.SECONDS);
    }

    public void stop() {
        local.stop();
        pool.shutdownNow();
    }

    // ---- read by the UI ------------------------------------------------------------------------

    public NowPlaying nowPlaying() {
        return nowPlaying;
    }

    public LyricsState lyrics() {
        return lyrics;
    }

    public String message() {
        return message;
    }

    /** Lyric colour for this song, taken from its album cover (a soft lavender until the cover is known). */
    public int coverRgb(String trackId) {
        return trackId != null && trackId.equals(coverTrackId) ? coverRgb : CoverColors.DEFAULT;
    }

    /** Status text of the no-setup (this computer) reader. */
    public String localStatus() {
        return local.status();
    }

    public boolean isSpotifyConnected() {
        return auth.isConnected();
    }

    public Status status() {
        if (!config.useSpotifyAccount) {
            return Status.LOCAL;
        }
        if (config.spotifyClientId == null || config.spotifyClientId.isBlank()) {
            return Status.NO_CLIENT_ID;
        }
        if (auth.isLoginRunning()) {
            return Status.CONNECTING;
        }
        if (!auth.isConnected()) {
            return Status.DISCONNECTED;
        }
        return error ? Status.ERROR : Status.CONNECTED;
    }

    public void connect() {
        error = false;
        auth.beginLogin(msg -> message = msg);
    }

    public void disconnect() {
        auth.logout();
        nowPlaying = null;
        lyrics = LyricsState.NONE;
        error = false;
        message = "Disconnected from Spotify";
    }

    // ---- polling -------------------------------------------------------------------------------

    private void pollSafely() {
        try {
            if (config.useSpotifyAccount) {
                if (local.isRunning()) {
                    local.stop();
                    tracker.reset();
                }
                poll();
            } else {
                local.start(); // no-op if already running
                if (nowPlaying != null && local.lastLineAgeMs() > 8000L) {
                    nowPlaying = null; // the helper went quiet
                }
            }
        } catch (Exception e) {
            error = true;
            message = "Spotify: " + e.getMessage();
            LOG.debug("Spotify poll failed", e);
        }
        retryLyricsIfDue();
    }

    private void retryLyricsIfDue() {
        long at = lyricsRetryAtMs;
        NowPlaying np = nowPlaying;
        if (at == 0L || np == null || System.currentTimeMillis() < at) {
            return;
        }
        lyricsRetryAtMs = 0L;
        lyrics = new LyricsState(np.trackId(), List.of(), "Loading lyrics...");
        try {
            pool.execute(() -> fetchLyrics(np));
        } catch (RejectedExecutionException e) {
            // shutting down
        }
    }

    private void poll() throws IOException {
        // Pick up a Client ID that was added to the file while the game was running.
        if (config.spotifyClientId == null || config.spotifyClientId.isBlank()) {
            config.reloadCredentialsFromDisk();
        }
        if (!auth.isConnected()) {
            nowPlaying = null;
            return;
        }
        if (System.currentTimeMillis() < backoffUntilMs) {
            return;
        }

        String token = auth.accessToken();
        long t0 = System.nanoTime();
        Http.Response res = fetchCurrent(token);
        long t1 = System.nanoTime();
        if (res.status() == 401) {
            auth.invalidateAccessToken();
            token = auth.accessToken();
            t0 = System.nanoTime();
            res = fetchCurrent(token);
            t1 = System.nanoTime();
        }

        switch (res.status()) {
            case 200 -> {
                error = false;
                handleBody(res.body(), t0 + (t1 - t0) / 2); // sample time = middle of the request
            }
            case 204 -> {
                error = false;
                nowPlaying = null;
            }
            case 429 -> {
                backoffUntilMs = System.currentTimeMillis() + res.retryAfterSeconds() * 1000L;
            }
            case 403 -> {
                error = true;
                message = "Spotify refused access (HTTP 403). Add your account under User Management "
                        + "in your Spotify app, then Disconnect and Connect again.";
            }
            default -> {
                error = true;
                message = "Spotify returned HTTP " + res.status();
            }
        }
    }

    private Http.Response fetchCurrent(String token) throws IOException {
        return Http.get(NOW_PLAYING_URL, Map.of("Authorization", "Bearer " + token));
    }

    private void handleBody(String body, long sampledAtNanos) {
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        JsonElement itemEl = o.get("item");
        if (itemEl == null || !itemEl.isJsonObject()
                || !"track".equals(SpotifyAuth.optString(o, "currently_playing_type", "track"))) {
            nowPlaying = null; // ad, podcast, or nothing
            return;
        }
        JsonObject item = itemEl.getAsJsonObject();

        String title = SpotifyAuth.optString(item, "name", "");
        String id = SpotifyAuth.optString(item, "id", "");
        StringBuilder artists = new StringBuilder();
        JsonElement artistEl = item.get("artists");
        if (artistEl != null && artistEl.isJsonArray()) {
            JsonArray arr = artistEl.getAsJsonArray();
            for (JsonElement a : arr) {
                if (a.isJsonObject()) {
                    if (artists.length() > 0) {
                        artists.append(", ");
                    }
                    artists.append(SpotifyAuth.optString(a.getAsJsonObject(), "name", ""));
                }
            }
        }
        String album = "";
        String artUrl = "";
        JsonElement albumEl = item.get("album");
        if (albumEl != null && albumEl.isJsonObject()) {
            JsonObject albumObj = albumEl.getAsJsonObject();
            album = SpotifyAuth.optString(albumObj, "name", "");
            artUrl = pickSmallImage(albumObj.get("images"));
        }
        String key = id.isEmpty() ? title + "|" + artists : id; // local files have no id

        boolean playing = o.has("is_playing") && !o.get("is_playing").isJsonNull() && o.get("is_playing").getAsBoolean();
        NowPlaying np = new NowPlaying(key, title, artists.toString(), album,
                SpotifyAuth.optLong(item, "duration_ms", 0L), SpotifyAuth.optLong(o, "progress_ms", 0L),
                playing, sampledAtNanos, artUrl);

        publish(np);
    }

    /** The smallest cover that is at least 64px wide (plenty to find a colour, and quick to download). */
    static String pickSmallImage(JsonElement images) {
        if (images == null || !images.isJsonArray()) {
            return "";
        }
        String best = "";
        long bestWidth = Long.MAX_VALUE;
        String largest = "";
        long largestWidth = -1;
        for (JsonElement el : images.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String url = SpotifyAuth.optString(o, "url", "");
            long width = SpotifyAuth.optLong(o, "width", 0L);
            if (url.isEmpty()) {
                continue;
            }
            if (width >= 64 && width < bestWidth) {
                best = url;
                bestWidth = width;
            }
            if (width > largestWidth) {
                largest = url;
                largestWidth = width;
            }
        }
        return best.isEmpty() ? largest : best;
    }

    /** Called (on the reader thread) for every reading from this computer's media player. */
    private void onLocalSample(LocalSample sample) {
        if (config.useSpotifyAccount) {
            return;
        }
        if (sample.isNothing()) {
            nowPlaying = null;
            tracker.reset();
            return;
        }
        publish(tracker.apply(sample, System.nanoTime()));
    }

    private void publish(NowPlaying np) {
        NowPlaying prev = nowPlaying;
        nowPlaying = np;
        if (prev == null || !prev.trackId().equals(np.trackId())) {
            lyricsRetryAtMs = 0L;
            lyrics = new LyricsState(np.trackId(), List.of(), "Loading lyrics...");

            coverTrackId = np.trackId();
            coverRgb = np.trackId().equals(pendingCoverKey) ? pendingCoverRgb : CoverColors.DEFAULT;
            try {
                pool.execute(() -> fetchLyrics(np));
                if (!np.artUrl().isEmpty()) {
                    pool.execute(() -> fetchCover(np));
                }
            } catch (RejectedExecutionException e) {
                // shutting down
            }
        }
    }

    private void fetchCover(NowPlaying np) {
        try {
            int[] pixels = CoverArt.fetchPixels(np.artUrl());
            if (pixels != null) {
                applyCover(np.trackId(), pixels);
            }
        } catch (Throwable t) { // never let a cover problem escape the worker thread
            LOG.debug("Cover colour lookup failed", t);
        }
    }

    /** Stores the colour for a song, or keeps it for later if that song isn't the current one yet. */
    private void applyCover(String trackId, int[] pixels) {
        int rgb = CoverColors.pick(pixels);
        NowPlaying cur = nowPlaying;
        if (cur != null && cur.trackId().equals(trackId)) {
            coverRgb = rgb;
            coverTrackId = trackId;
        } else {
            pendingCoverKey = trackId;
            pendingCoverRgb = rgb;
        }
    }

    private void fetchLyrics(NowPlaying np) {
        LyricsState state;
        boolean failed = false;
        try {
            LrcLibClient.Result r = lrclib.fetch(np);
            state = new LyricsState(np.trackId(), r.lines(), r.note());
        } catch (Exception e) {
            LOG.debug("Lyrics lookup failed", e);
            state = new LyricsState(np.trackId(), List.of(), "Lyrics lookup failed (will retry)");
            failed = true;
        }
        NowPlaying current = nowPlaying;
        if (current != null && current.trackId().equals(np.trackId())) { // ignore songs we've already skipped past
            lyrics = state;
            if (failed) {
                lyricsRetryAtMs = System.currentTimeMillis() + 30_000L;
            }
        }
    }
}
