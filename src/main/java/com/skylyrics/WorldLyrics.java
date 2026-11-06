package com.skylyrics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.skylyrics.core.Config;
import com.skylyrics.core.LrcParser;
import com.skylyrics.core.LyricAnim;
import com.skylyrics.core.LyricLine;
import com.skylyrics.core.LyricsService;
import com.skylyrics.core.NowPlaying;
import com.skylyrics.core.TextWrap;
import com.skylyrics.core.WorldAnchor;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws each lyric line as a real object in the world: it appears at a fixed spot in the sky, facing where you
 * were standing, and stays there while you move (so it has parallax and can be hidden behind terrain).
 * It is only drawn on your own screen; nothing is sent to the server.
 */
public final class WorldLyrics {
    /** How far away (blocks) lines appear. */
    private static final double DISTANCE = 36.0;
    /** Widest a row of text may be, as an angle of the sky seen from where it appeared. */
    private static final double MAX_WIDTH_DEG = 70.0;
    private static final int FULL_BRIGHT = 15728880;
    /** A line that hasn't been drawn for this long is "new" again (song replayed, looped or skipped back). */
    private static final long RESPAWN_AFTER_NANOS = 3_000_000_000L;

    private static final Logger LOG = LoggerFactory.getLogger("skylyrics");

    /** Style that selects the bundled Poppins font, or null if it couldn't be created (then the vanilla font is used). */
    private static final Style SMOOTH = createSmoothStyle();

    /** Where a line sits and how its words are laid out. Created when the line first appears. */
    private static final class Placed {
        final WorldAnchor anchor;
        long lastDrawnNanos;
        // Layout, rebuilt only if the font or size setting changes (not every frame).
        boolean layoutSmooth;
        int layoutSize = -1;
        List<List<String>> rows;
        float[] rowStartX;
        float[][] wordX;

        Placed(WorldAnchor anchor, long now) {
            this.anchor = anchor;
            this.lastDrawnNanos = now;
        }
    }

    private static final Map<Integer, Placed> PLACED = new HashMap<>();
    private static String placedTrack = "";

    private WorldLyrics() {
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(WorldLyrics::collect);
    }

    private static void collect(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        Config cfg = SkyLyricsClient.config();
        if (!cfg.worldText || !cfg.showLyrics) {
            return;
        }
        LyricsService svc = SkyLyricsClient.service();
        NowPlaying np = svc.nowPlaying();
        if (np == null || !np.playing()) {
            return;
        }
        LyricsService.LyricsState ls = svc.lyrics();
        if (!ls.trackId().equals(np.trackId()) || ls.lines().isEmpty()) {
            return;
        }
        List<LyricLine> lines = ls.lines();
        long pos = np.positionMs(System.nanoTime()) + cfg.offsetMs;
        int idx = LrcParser.indexAt(lines, pos);
        if (idx < 0) {
            return;
        }
        if (!np.trackId().equals(placedTrack)) {
            PLACED.clear();
            placedTrack = np.trackId();
        }

        float partialTick = FrameTime.partialTick(mc);
        Vec3 cam = mc.player.getEyePosition(partialTick);
        int rgb = Tint.rgb(cfg, svc, np);

        // Previous line first so the current one is drawn on top while they cross-fade.
        if (idx > 0) {
            drawLine(context, mc, cfg, np, lines, idx - 1, pos, cam, rgb);
        }
        drawLine(context, mc, cfg, np, lines, idx, pos, cam, rgb);
    }

    /**
     * Built by reflection so that a moved/renamed FontDescription class can't break the build; on failure the mod
     * simply uses Minecraft's own font.
     */
    private static Style createSmoothStyle() {
        try {
            Class<?> description = Class.forName("net.minecraft.network.chat.FontDescription");
            Class<?> resource = Class.forName("net.minecraft.network.chat.FontDescription$Resource");
            Object font = resource.getConstructor(Identifier.class)
                    .newInstance(Identifier.fromNamespaceAndPath(SkyLyricsClient.MOD_ID, "lyrics"));
            return (Style) Style.class.getMethod("withFont", description).invoke(Style.EMPTY, font);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Could not set up the smooth font, using Minecraft's font instead: {}", e.toString());
            return null;
        }
    }

    private static Component styled(String text, boolean smooth) {
        return (smooth && SMOOTH != null) ? Component.literal(text).withStyle(SMOOTH) : Component.literal(text);
    }

    private static void drawLine(LevelRenderContext context, Minecraft mc, Config cfg, NowPlaying np,
                                 List<LyricLine> lines, int i, long pos, Vec3 cam, int rgb) {
        LyricLine line = lines.get(i);
        String text = line.text();
        if (text.isBlank()) {
            return;
        }
        long start = line.timeMs();
        long end = LyricAnim.lineEnd(lines, i, np.durationMs());
        float alpha = LyricAnim.alpha(pos, start, end);
        if (alpha <= 0.02f) {
            return;
        }

        // The spot is chosen when the line first appears (or appears again), from where you are standing and looking.
        long now = System.nanoTime();
        Placed placed = PLACED.get(i);
        if (placed == null || now - placed.lastDrawnNanos > RESPAWN_AFTER_NANOS) {
            float[] place = LyricAnim.placement(np.trackId().hashCode(), i);
            placed = new Placed(WorldAnchor.create(cam.x, cam.y, cam.z, mc.player.getYRot(),
                    place[0] * 0.6, place[1] + 6.0, DISTANCE), now);
            PLACED.put(i, placed);
        }
        placed.lastDrawnNanos = now;
        WorldAnchor anchor = placed.anchor;

        boolean smooth = cfg.smoothFont;
        Font font = mc.font;

        double heightRad = Math.toRadians(Config.SIZE_DEG[cfg.sizeIndex]);
        float blocksPerPixel = (float) (DISTANCE * heightRad / font.lineHeight);

        String[] words = text.trim().split("\\s+");
        if (placed.rows == null || placed.layoutSmooth != smooth || placed.layoutSize != cfg.sizeIndex) {
            layout(placed, font, words, smooth, blocksPerPixel);
            placed.layoutSmooth = smooth;
            placed.layoutSize = cfg.sizeIndex;
        }
        List<List<String>> rows = placed.rows;
        long[] reveal = cfg.wordByWord ? LyricAnim.wordRevealMs(words, start, end) : null;

        int rowH = font.lineHeight + 3;
        float totalH = rows.size() * rowH;
        float scale = blocksPerPixel * LyricAnim.pop(pos - start);
        double rise = Math.min(1.5, (pos - start) * 0.00018); // slow upward drift

        var collector = context.submitNodeCollector();
        PoseStack pose = context.poseStack();
        pose.pushPose();
        pose.translate(anchor.x() - cam.x, anchor.y() - cam.y + rise, anchor.z() - cam.z);
        pose.mulPose(Axis.YP.rotation(anchor.yawRad()));
        pose.mulPose(Axis.XP.rotation(anchor.pitchRad()));
        pose.scale(scale, -scale, scale);

        int wordIndex = 0;
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            float y0 = -totalH / 2f + r * rowH;
            for (int k = 0; k < row.size(); k++) {
                float wa = alpha;
                if (reveal != null && wordIndex < reveal.length) {
                    wa *= LyricAnim.clamp01((pos - reveal[wordIndex]) / 180f);
                }
                wordIndex++;
                int a255 = Math.round(wa * 255f);
                if (a255 >= 8) { // vanilla treats very low alpha as opaque, so skip those
                    collector.submitText(pose, placed.rowStartX[r] + placed.wordX[r][k], y0,
                            styled(row.get(k), smooth).getVisualOrderText(), true,
                            Font.DisplayMode.NORMAL, FULL_BRIGHT, (a255 << 24) | rgb, 0, 0);
                }
            }
        }
        pose.popPose();
    }

    /** Wraps the words into rows and measures where each word starts. Done once per line, not every frame. */
    private static void layout(Placed placed, Font font, String[] words, boolean smooth, float blocksPerPixel) {
        double maxBlocks = 2.0 * DISTANCE * Math.tan(Math.toRadians(MAX_WIDTH_DEG) / 2.0);
        int maxBase = Math.max(40, (int) (maxBlocks / blocksPerPixel));
        List<List<String>> rows = TextWrap.wrapWords(words, maxBase, s -> font.width(styled(s, smooth)));

        float[] rowStartX = new float[rows.size()];
        float[][] wordX = new float[rows.size()][];
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            rowStartX[r] = -font.width(styled(String.join(" ", row), smooth)) / 2f;
            wordX[r] = new float[row.size()];
            StringBuilder prefix = new StringBuilder();
            for (int k = 0; k < row.size(); k++) {
                wordX[r][k] = font.width(styled(prefix.toString(), smooth));
                prefix.append(row.get(k)).append(' ');
            }
        }
        placed.rows = rows;
        placed.rowStartX = rowStartX;
        placed.wordX = wordX;
    }
}
