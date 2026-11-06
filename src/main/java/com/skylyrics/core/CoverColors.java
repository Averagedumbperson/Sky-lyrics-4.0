package com.skylyrics.core;

/**
 * Picks a text colour from album-cover pixels: the cover's dominant vivid colour, adjusted so it stays readable
 * over the sky. Pure logic (no Minecraft or image classes).
 */
public final class CoverColors {
    /** Used when there is no cover (or it can't be read): soft lavender. */
    public static final int DEFAULT = 0xC9B8FF;
    /** Used for grey/black/white covers that have no dominant colour. */
    private static final int NEUTRAL = 0xF0F0F0;
    private static final int BUCKETS = 12;

    private CoverColors() {
    }

    /** @param rgb pixels as 0xRRGGBB (alpha is ignored) */
    public static int pick(int[] rgb) {
        if (rgb == null || rgb.length == 0) {
            return DEFAULT;
        }
        double[] w = new double[BUCKETS];
        double[] rs = new double[BUCKETS];
        double[] gs = new double[BUCKETS];
        double[] bs = new double[BUCKETS];

        for (int p : rgb) {
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            float[] hsv = toHsv(r, g, b);
            float s = hsv[1];
            float v = hsv[2];
            if (v < 0.15f || s < 0.12f) {
                continue; // too dark or too grey to say what colour the cover is
            }
            double weight = s * (0.35 + 0.65 * v);
            int bucket = Math.min(BUCKETS - 1, (int) (hsv[0] / (360f / BUCKETS)));
            w[bucket] += weight;
            rs[bucket] += weight * r;
            gs[bucket] += weight * g;
            bs[bucket] += weight * b;
        }

        // Score each hue bucket together with its neighbours (hues wrap around, e.g. reds straddle the seam).
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < BUCKETS; i++) {
            double score = w[i] + 0.5 * w[(i + BUCKETS - 1) % BUCKETS] + 0.5 * w[(i + 1) % BUCKETS];
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0 || bestScore < rgb.length * 0.03) {
            return NEUTRAL; // grey, black or white cover
        }

        double tw = 0;
        double tr = 0;
        double tg = 0;
        double tb = 0;
        for (int d = -1; d <= 1; d++) {
            int i = (best + d + BUCKETS) % BUCKETS;
            tw += w[i];
            tr += rs[i];
            tg += gs[i];
            tb += bs[i];
        }
        return readable((int) Math.round(tr / tw), (int) Math.round(tg / tw), (int) Math.round(tb / tw));
    }

    /** Brightens a colour so it stays legible, while keeping its hue. */
    static int readable(int r, int g, int b) {
        float[] hsv = toHsv(r, g, b);
        float s = Math.max(0.30f, Math.min(0.90f, hsv[1]));
        float v = Math.max(0.88f, hsv[2]);
        int rgb = fromHsv(hsv[0], s, v);
        return lerp(rgb, 0xFFFFFF, 0.12f);
    }

    public static int lerp(int a, int b, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * k);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * k);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /** Parses "RRGGBBRRGGBB..." into pixel values; returns null if the text isn't valid. */
    public static int[] parseHex(String hex) {
        if (hex == null || hex.isEmpty() || hex.length() % 6 != 0) {
            return null;
        }
        int[] out = new int[hex.length() / 6];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = Integer.parseInt(hex.substring(i * 6, i * 6 + 6), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    // ---- HSV helpers (h in degrees 0..360, s and v in 0..1) -------------------------------------

    static float[] toHsv(int r, int g, int b) {
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;
        float h;
        if (d == 0f) {
            h = 0f;
        } else if (max == rf) {
            h = 60f * (((gf - bf) / d) % 6f);
        } else if (max == gf) {
            h = 60f * (((bf - rf) / d) + 2f);
        } else {
            h = 60f * (((rf - gf) / d) + 4f);
        }
        if (h < 0f) {
            h += 360f;
        }
        float s = max == 0f ? 0f : d / max;
        return new float[]{h, s, max};
    }

    static int fromHsv(float h, float s, float v) {
        float c = v * s;
        float hp = (h % 360f) / 60f;
        float x = c * (1f - Math.abs(hp % 2f - 1f));
        float r = 0;
        float g = 0;
        float b = 0;
        if (hp < 1) {
            r = c; g = x;
        } else if (hp < 2) {
            r = x; g = c;
        } else if (hp < 3) {
            g = c; b = x;
        } else if (hp < 4) {
            g = x; b = c;
        } else if (hp < 5) {
            r = x; b = c;
        } else {
            r = c; b = x;
        }
        float m = v - c;
        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return (ri << 16) | (gi << 8) | bi;
    }
}
