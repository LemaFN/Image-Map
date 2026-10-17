package de.imagemapclaude;

import org.bukkit.Color;
import org.bukkit.map.MapPalette;

import java.util.ArrayList;
import java.util.List;

/** Minecraft-Kartenpalette (direkt aus der Server-API gelesen, bleibt also versionsrichtig). */
@SuppressWarnings("deprecation")
final class Palette {
    static final Color[] COLORS = new Color[256];
    static final int[] VALID;
    private static final byte[] LUT = new byte[32768];

    static {
        List<Integer> valid = new ArrayList<>();
        for (int i = 4; i < 256; i++) {
            try {
                Color c = MapPalette.getColor((byte) i);
                if (c != null && c.getAlpha() != 0) {
                    COLORS[i] = c;
                    valid.add(i);
                }
            } catch (Throwable ignored) {
                // Index existiert in dieser Version nicht
            }
        }
        VALID = valid.stream().mapToInt(Integer::intValue).toArray();
    }

    private Palette() {}

    /** Exakte Zuordnung zur ähnlichsten Kartenfarbe. */
    static byte nearestExact(int r, int g, int b) {
        long best = Long.MAX_VALUE;
        int bestIdx = VALID[0];
        for (int idx : VALID) {
            Color c = COLORS[idx];
            long dr = r - c.getRed(), dg = g - c.getGreen(), db = b - c.getBlue();
            long d = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (d < best) {
                best = d;
                bestIdx = idx;
            }
        }
        return (byte) bestIdx;
    }

    /** Schnelle Zuordnung über eine 15-Bit-Tabelle (für den Normalmodus). */
    static byte nearestFast(int r, int g, int b) {
        int key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
        byte v = LUT[key];
        if (v == 0) {
            v = nearestExact((r & 0xF8) | 4, (g & 0xF8) | 4, (b & 0xF8) | 4);
            LUT[key] = v;
        }
        return v;
    }
}
