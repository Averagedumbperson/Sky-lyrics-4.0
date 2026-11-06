package com.skylyrics.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Downloads an album cover and reduces it to a small grid of pixels for {@link CoverColors}.
 * Image decoding needs the JDK's java.desktop module; if Minecraft's Java doesn't include it, this simply
 * returns null and the default colour is used.
 */
public final class CoverArt {
    private static final Logger LOG = LoggerFactory.getLogger("skylyrics");
    public static final int GRID = 16;
    private static final int MAX_BYTES = 6_000_000;

    private static volatile boolean warned;

    private CoverArt() {
    }

    /** @return GRID*GRID pixels as 0xRRGGBB, or null if the cover couldn't be fetched or decoded */
    public static int[] fetchPixels(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return Decoder.decode(load(url.trim()));
        } catch (Exception | LinkageError e) { // LinkageError: java.desktop missing from this Java runtime
            if (!warned) {
                warned = true;
                LOG.warn("Could not read the album cover (using the default colour): {}", e.toString());
            }
            return null;
        }
    }

    private static byte[] load(String url) throws Exception {
        if (url.startsWith("file:")) {
            return Files.readAllBytes(Path.of(URI.create(url)));
        }
        return Http.getBytes(url, Map.of("User-Agent", "SkyLyrics/0.4.0 (Minecraft Fabric mod)"), MAX_BYTES);
    }

    /** Isolated in its own class so a missing java.desktop only breaks this, not the rest of CoverArt. */
    private static final class Decoder {
        static int[] decode(byte[] bytes) throws Exception {
            if (System.getProperty("java.awt.headless") == null) {
                System.setProperty("java.awt.headless", "true");
            }
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                return null;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            int[] out = new int[GRID * GRID];
            final int sub = 3; // sample 3x3 points inside each grid cell and average them
            for (int gy = 0; gy < GRID; gy++) {
                for (int gx = 0; gx < GRID; gx++) {
                    long r = 0;
                    long g = 0;
                    long b = 0;
                    for (int sy = 0; sy < sub; sy++) {
                        for (int sx = 0; sx < sub; sx++) {
                            int x = Math.min(w - 1, (int) (((gx + (sx + 0.5) / sub) / GRID) * w));
                            int y = Math.min(h - 1, (int) (((gy + (sy + 0.5) / sub) / GRID) * h));
                            int p = img.getRGB(x, y);
                            r += (p >> 16) & 0xFF;
                            g += (p >> 8) & 0xFF;
                            b += p & 0xFF;
                        }
                    }
                    int n = sub * sub;
                    out[gy * GRID + gx] = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
                }
            }
            return out;
        }
    }
}
