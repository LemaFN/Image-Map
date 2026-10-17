package de.imagemapclaude;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/** Zeichnet eine fertig quantisierte 128x128-Kachel einmalig auf die Karte. */
@SuppressWarnings("deprecation")
final class TileRenderer extends MapRenderer {
    private final byte[] pixels;
    private volatile boolean drawn;

    TileRenderer(byte[] pixels) {
        super(false);
        this.pixels = pixels;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (drawn) return;
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                byte b = pixels[y * 128 + x];
                if ((b & 0xFF) >= 4) canvas.setPixel(x, y, b);
            }
        }
        drawn = true;
    }
}
