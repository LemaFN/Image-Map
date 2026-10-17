package de.imagemapclaude;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ImageMapPlugin extends JavaPlugin implements Listener, TabExecutor {
    record Session(UUID player, String name, long expires) {}

    final Map<String, Session> sessions = new ConcurrentHashMap<>();
    ImageStore store;
    private UploadServer server;
    private NamespacedKey keyImage, keyTile;
    private final SecureRandom random = new SecureRandom();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyImage = new NamespacedKey(this, "image");
        keyTile = new NamespacedKey(this, "tile");
        store = new ImageStore(this);
        store.loadAll();
        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand cmd = Objects.requireNonNull(getCommand("imagemap"));
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
        Bukkit.getScheduler().runTask(this, store::attachAll);

        server = new UploadServer(this);
        try {
            server.start();
            getLogger().info("Upload-Server läuft auf Port " + getConfig().getInt("http.port", 8765));
        } catch (IOException e) {
            getLogger().severe("Upload-Server konnte nicht starten: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }
        if ("localhost".equals(getConfig().getString("http.public-host")))
            getLogger().warning("http.public-host steht noch auf 'localhost' - bitte in der config.yml anpassen!");
    }

    @Override
    public void onDisable() {
        if (server != null) server.stop();
    }

    // ---------- Sessions ----------

    Session session(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (s.expires() < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return s;
    }

    void consume(String token) {
        sessions.remove(token);
    }

    private String buildLink(String token) {
        String https = getConfig().getString("http.https-url", "").trim();
        if (!https.isEmpty()) {
            String ui = getConfig().getString("web-ui-url", "");
            return ui + "?api=" + URLEncoder.encode(https, StandardCharsets.UTF_8) + "&token=" + token;
        }
        return "http://" + getConfig().getString("http.public-host", "localhost") + ":"
                + getConfig().getInt("http.port", 8765) + "/?token=" + token;
    }

    // ---------- Karten ----------

    void attach(MapView v, byte[] pixels) {
        for (var r : new ArrayList<>(v.getRenderers())) v.removeRenderer(r);
        v.addRenderer(new TileRenderer(pixels));
        v.setTrackingPosition(false);
        v.setUnlimitedTracking(false);
        v.setLocked(true);
    }

    @EventHandler
    public void onMapInit(MapInitializeEvent e) {
        ImageStore.Ref ref = store.byMapId(e.getMap().getId());
        if (ref != null) attach(e.getMap(), ref.data().tiles[ref.tile()]);
    }

    private void ensureMaps(ImageData d, World world) {
        boolean changed = false;
        for (int i = 0; i < d.mapIds.length; i++) {
            if (d.mapIds[i] >= 0) continue;
            MapView v = Bukkit.createMap(world);
            d.mapIds[i] = v.getId();
            store.register(d, i);
            attach(v, d.tiles[i]);
            changed = true;
        }
        if (changed) store.saveAsync(d);
    }

    private ItemStack mapItem(ImageData d, int tile) {
        MapView view = Objects.requireNonNull(Bukkit.getMap(d.mapIds[tile]));
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        int c = tile % d.width, r = tile / d.width;
        meta.displayName(Component.text("ImageMap " + d.id + " [" + (c + 1) + "/" + (r + 1) + "]", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        if (d.auto && tile == 0)
            meta.lore(List.of(Component.text("In den Rahmen ganz oben links klicken –", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("der Rest wird automatisch platziert.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(keyImage, PersistentDataType.STRING, d.id);
        meta.getPersistentDataContainer().set(keyTile, PersistentDataType.INTEGER, tile);
        item.setItemMeta(meta);
        return item;
    }

    // ---------- Befehl ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Nur für Spieler.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("upload")) {
            long now = System.currentTimeMillis();
            sessions.values().removeIf(s -> s.expires() < now);
            byte[] raw = new byte[18];
            random.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            long ttl = getConfig().getLong("limits.token-minutes", 15) * 60_000L;
            sessions.put(token, new Session(p.getUniqueId(), p.getName(), now + ttl));
            String url = buildLink(token);
            p.sendMessage(Component.text("Bild hochladen: ", NamedTextColor.GOLD)
                    .append(Component.text(url, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(url))));
            p.sendMessage(Component.text("Der Link gilt nur für dich und läuft in "
                    + getConfig().getLong("limits.token-minutes", 15) + " Minuten ab.", NamedTextColor.GRAY));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("get")) {
            ImageData d = store.get(args[1]);
            if (d == null || (!d.owner.equals(p.getUniqueId()) && !p.hasPermission("imagemap.admin"))) {
                p.sendMessage(Component.text("Dieses Bild gibt es nicht (oder es gehört dir nicht).", NamedTextColor.RED));
                return true;
            }
            ensureMaps(d, p.getWorld());
            List<ItemStack> items = new ArrayList<>();
            if (d.auto) items.add(mapItem(d, 0));
            else for (int i = 0; i < d.mapIds.length; i++) items.add(mapItem(d, i));
            for (ItemStack left : p.getInventory().addItem(items.toArray(new ItemStack[0])).values())
                p.getWorld().dropItem(p.getLocation(), left);
            p.sendMessage(Component.text(d.auto
                    ? "Karte erhalten. Platziere einen Rahmen ganz oben links und klicke mit der Karte hinein."
                    : "Alle " + d.mapIds.length + " Karten erhalten.", NamedTextColor.GREEN));
            return true;
        }
        p.sendMessage(Component.text("Benutzung: /imagemap upload", NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        return args.length == 1 ? List.of("upload", "get") : List.of();
    }

    // ---------- Automatisches Platzieren ----------

    @EventHandler
    public void onFrameClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame origin)) return;
        Player p = e.getPlayer();
        EquipmentSlot hand = e.getHand();
        ItemStack held = p.getInventory().getItem(hand);
        if (held.getType() != Material.FILLED_MAP || !held.hasItemMeta()) return;
        var pdc = held.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(keyImage, PersistentDataType.STRING);
        Integer tile = pdc.get(keyTile, PersistentDataType.INTEGER);
        if (id == null || tile == null || tile != 0) return;
        ImageData d = store.get(id);
        if (d == null || !d.auto) return;
        if (!origin.getItem().getType().isAir()) return;

        e.setCancelled(true);
        BlockFace face = origin.getFacing();
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            p.sendMessage(Component.text("Automatisches Platzieren geht nur an Wänden.", NamedTextColor.RED));
            return;
        }
        // „Rechts“ aus Sicht eines Betrachters, der vor der Wand steht
        int rx = face.getModeZ(), rz = -face.getModeX();
        Block base = origin.getLocation().getBlock();

        List<Block> targets = new ArrayList<>();
        int blocked = 0;
        for (int r = 0; r < d.height; r++)
            for (int c = 0; c < d.width; c++) {
                if (r == 0 && c == 0) continue;
                Block b = base.getRelative(rx * c, -r, rz * c);
                boolean free = b.getType().isAir()
                        && b.getRelative(face.getOppositeFace()).getType().isSolid()
                        && b.getWorld().getNearbyEntities(BoundingBox.of(b), en -> en instanceof Hanging).isEmpty();
                if (free) targets.add(b);
                else blocked++;
            }
        if (blocked > 0) {
            p.sendMessage(Component.text("Hier ist nicht genug freie Wand: " + blocked + " von " + (d.width * d.height)
                    + " Plätzen sind blockiert. Das Bild braucht " + d.width + " × " + d.height
                    + " Blöcke Wand rechts und unterhalb des Rahmens.", NamedTextColor.RED));
            return;
        }

        ensureMaps(d, origin.getWorld());
        boolean invisible = getConfig().getBoolean("invisible-frames", false);
        origin.setVisible(!invisible);
        origin.setItem(mapItem(d, 0), false);
        int idx = 1;
        // Reihenfolge der Ziele entspricht Zeile für Zeile ohne Kachel 0
        for (Block b : targets) {
            int tileIdx = idx++;
            ItemStack item = mapItem(d, tileIdx);
            b.getWorld().spawn(b.getLocation(), ItemFrame.class, f -> {
                f.setFacingDirection(face, true);
                f.setVisible(!invisible);
                f.setItem(item, false);
            });
        }
        if (p.getGameMode() != GameMode.CREATIVE) held.setAmount(held.getAmount() - 1);
        p.sendMessage(Component.text("Fertig – " + d.width + " × " + d.height + " Karten platziert.", NamedTextColor.GREEN));
    }
}
