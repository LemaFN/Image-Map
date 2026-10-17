package de.imagemapclaude;

import org.bukkit.Bukkit;
import org.bukkit.map.MapView;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class ImageStore {
    record Ref(ImageData data, int tile) {}

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();
    private final ImageMapPlugin plugin;
    private final File dir;
    private final Map<String, ImageData> images = new ConcurrentHashMap<>();
    private final Map<Integer, Ref> byMapId = new ConcurrentHashMap<>();

    ImageStore(ImageMapPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "images");
        dir.mkdirs();
    }

    ImageData get(String id) {
        return id == null ? null : images.get(id.toLowerCase());
    }

    Ref byMapId(int mapId) {
        return byMapId.get(mapId);
    }

    void register(ImageData d, int tile) {
        if (d.mapIds[tile] >= 0) byMapId.put(d.mapIds[tile], new Ref(d, tile));
    }

    /** Verarbeitet ein hochgeladenes PNG: skalieren, Farben anpassen, in Kacheln teilen. */
    ImageData process(byte[] png, int w, int h, boolean auto, boolean hd, UUID owner) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
        if (src == null) throw new IllegalArgumentException("Die Datei ist kein lesbares Bild.");
        int W = w * 128, H = h * 128;
        BufferedImage img = src;
        if (src.getWidth() != W || src.getHeight() != H) {
            img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, W, H, null);
            g.dispose();
        }
        byte[] full = hd ? quantizeDithered(img, W, H) : quantizeFast(img, W, H);

        byte[][] tiles = new byte[w * h][128 * 128];
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++) {
                byte[] t = tiles[r * w + c];
                for (int y = 0; y < 128; y++)
                    System.arraycopy(full, (r * 128 + y) * W + c * 128, t, y * 128, 128);
            }
        int[] mapIds = new int[w * h];
        java.util.Arrays.fill(mapIds, -1);

        String id;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            id = sb.toString();
        } while (images.containsKey(id));

        ImageData d = new ImageData(id, owner, w, h, auto, hd, tiles, mapIds);
        images.put(id, d);
        return d;
    }

    private static byte[] quantizeFast(BufferedImage img, int W, int H) {
        byte[] out = new byte[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) < 128) continue; // transparent = 0
                out[y * W + x] = Palette.nearestFast((argb >> 16) & 255, (argb >> 8) & 255, argb & 255);
            }
        return out;
    }

    /** HD-Modus: Floyd-Steinberg-Dithering mit exakter Farbzuordnung. */
    private static byte[] quantizeDithered(BufferedImage img, int W, int H) {
        float[] fr = new float[W * H], fg = new float[W * H], fb = new float[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int argb = img.getRGB(x, y), i = y * W + x;
                fr[i] = (argb >> 16) & 255;
                fg[i] = (argb >> 8) & 255;
                fb[i] = argb & 255;
            }
        byte[] out = new byte[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                if ((img.getRGB(x, y) >>> 24) < 128) continue;
                int r = clamp(fr[i]), g = clamp(fg[i]), b = clamp(fb[i]);
                byte idx = Palette.nearestExact(r, g, b);
                out[i] = idx;
                java.awt.Color p = Palette.COLORS[idx & 0xFF];
                float er = r - p.getRed(), eg = g - p.getGreen(), eb = b - p.getBlue();
                spread(fr, fg, fb, W, H, x + 1, y, er, eg, eb, 7f / 16);
                spread(fr, fg, fb, W, H, x - 1, y + 1, er, eg, eb, 3f / 16);
                spread(fr, fg, fb, W, H, x, y + 1, er, eg, eb, 5f / 16);
                spread(fr, fg, fb, W, H, x + 1, y + 1, er, eg, eb, 1f / 16);
            }
        return out;
    }

    private static void spread(float[] fr, float[] fg, float[] fb, int W, int H, int x, int y,
                               float er, float eg, float eb, float f) {
        if (x < 0 || y < 0 || x >= W || y >= H) return;
        int i = y * W + x;
        fr[i] += er * f;
        fg[i] += eg * f;
        fb[i] += eb * f;
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }

    // ---------- Speichern / Laden ----------

    synchronized void save(ImageData d) throws IOException {
        File f = new File(dir, d.id + ".dat");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(f))))) {
            out.writeUTF(d.id);
            out.writeLong(d.owner.getMostSignificantBits());
            out.writeLong(d.owner.getLeastSignificantBits());
            out.writeInt(d.width);
            out.writeInt(d.height);
            out.writeBoolean(d.auto);
            out.writeBoolean(d.hd);
            for (int id : d.mapIds) out.writeInt(id);
            for (byte[] t : d.tiles) out.write(t);
        }
    }

    void saveAsync(ImageData d) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                save(d);
            } catch (IOException e) {
                plugin.getLogger().warning("Konnte Bild " + d.id + " nicht speichern: " + e.getMessage());
            }
        });
    }

    void loadAll() {
        File[] files = dir.listFiles((x, n) -> n.endsWith(".dat"));
        if (files == null) return;
        for (File f : files) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))))) {
                String id = in.readUTF();
                UUID owner = new UUID(in.readLong(), in.readLong());
                int w = in.readInt(), h = in.readInt();
                boolean auto = in.readBoolean(), hd = in.readBoolean();
                int[] mapIds = new int[w * h];
                for (int i = 0; i < mapIds.length; i++) mapIds[i] = in.readInt();
                byte[][] tiles = new byte[w * h][128 * 128];
                for (byte[] t : tiles) in.readFully(t);
                ImageData d = new ImageData(id, owner, w, h, auto, hd, tiles, mapIds);
                images.put(id, d);
                for (int i = 0; i < mapIds.length; i++) register(d, i);
            } catch (IOException e) {
                plugin.getLogger().warning("Konnte " + f.getName() + " nicht laden: " + e.getMessage());
            }
        }
        plugin.getLogger().info(images.size() + " Bild(er) geladen.");
    }

    /** Hängt Renderer an bereits existierende Karten (nach Serverstart). */
    void attachAll() {
        for (Ref ref : byMapId.values()) {
            MapView v = Bukkit.getMap(ref.data().mapIds[ref.tile()]);
            if (v != null) plugin.attach(v, ref.data().tiles[ref.tile()]);
        }
    }
}
