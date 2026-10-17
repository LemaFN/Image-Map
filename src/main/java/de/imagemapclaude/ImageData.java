package de.imagemapclaude;

import java.util.UUID;

/** Ein hochgeladenes Bild, zerlegt in Kacheln (Zeile für Zeile, links oben beginnend). */
final class ImageData {
    final String id;
    final UUID owner;
    final int width;   // Karten in X-Richtung
    final int height;  // Karten in Y-Richtung
    final boolean auto;
    final boolean hd;
    final byte[][] tiles;
    final int[] mapIds;

    ImageData(String id, UUID owner, int width, int height, boolean auto, boolean hd, byte[][] tiles, int[] mapIds) {
        this.id = id;
        this.owner = owner;
        this.width = width;
        this.height = height;
        this.auto = auto;
        this.hd = hd;
        this.tiles = tiles;
        this.mapIds = mapIds;
    }
}
