package de.imagemapclaude;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** Kleiner eingebauter HTTP-Server: nimmt das Bild von der Webseite entgegen. */
final class UploadServer {
    private final ImageMapPlugin plugin;
    private HttpServer http;

    UploadServer(ImageMapPlugin plugin) {
        this.plugin = plugin;
    }

    void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(plugin.getConfig().getInt("http.port", 8765)), 0);
        http.createContext("/", this::handle);
        http.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ImageMap-HTTP");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    void stop() {
        if (http != null) http.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            ex.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
            String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            if (method.equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());

            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                try (InputStream in = plugin.getResource("web/index.html")) {
                    byte[] page = in == null ? "Webseite fehlt im Plugin.".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
                    send(ex, 200, "text/html; charset=utf-8", page);
                }
            } else if (method.equals("GET") && path.equals("/api/session")) {
                ImageMapPlugin.Session s = plugin.session(q.get("token"));
                if (s == null) {
                    json(ex, 401, "{\"error\":\"Link ungültig oder abgelaufen. Gib /imagemap upload erneut im Spiel ein.\"}");
                } else {
                    json(ex, 200, "{\"player\":\"" + s.name().replace("\"", "") + "\",\"max\":"
                            + plugin.getConfig().getInt("limits.max-maps-per-axis", 16) + "}");
                }
            } else if (method.equals("POST") && path.equals("/api/upload")) {
                upload(ex, q);
            } else {
                json(ex, 404, "{\"error\":\"Nicht gefunden\"}");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("HTTP-Fehler: " + e);
            json(ex, 500, "{\"error\":\"Interner Fehler\"}");
        } finally {
            ex.close();
        }
    }

    private void upload(HttpExchange ex, Map<String, String> q) throws IOException {
        ImageMapPlugin.Session s = plugin.session(q.get("token"));
        if (s == null) {
            json(ex, 401, "{\"error\":\"Link ungültig oder abgelaufen. Gib /imagemap upload erneut im Spiel ein.\"}");
            return;
        }
        int max = plugin.getConfig().getInt("limits.max-maps-per-axis", 16);
        int w, h;
        try {
            w = Integer.parseInt(q.getOrDefault("w", "0"));
            h = Integer.parseInt(q.getOrDefault("h", "0"));
        } catch (NumberFormatException e) {
            json(ex, 400, "{\"error\":\"Ungültige Größe\"}");
            return;
        }
        if (w < 1 || h < 1 || w > max || h > max) {
            json(ex, 400, "{\"error\":\"Größe muss zwischen 1 und " + max + " liegen\"}");
            return;
        }
        long maxBytes = plugin.getConfig().getLong("limits.max-upload-mb", 16) * 1024 * 1024;
        byte[] body = ex.getRequestBody().readNBytes((int) maxBytes + 1);
        if (body.length > maxBytes) {
            json(ex, 413, "{\"error\":\"Datei zu groß\"}");
            return;
        }
        try {
            ImageData d = plugin.store.process(body, w, h, "1".equals(q.get("auto")), "1".equals(q.get("hd")), s.player());
            plugin.store.save(d);
            plugin.consume(q.get("token"));
            String cmd = "/imagemap get " + d.id;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(s.player());
                if (p != null) p.sendMessage(Component.text("Bild hochgeladen! Klicke hier oder tippe: " + cmd, NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.suggestCommand(cmd)));
            });
            json(ex, 200, "{\"id\":\"" + d.id + "\",\"command\":\"" + cmd + "\"}");
        } catch (IllegalArgumentException e) {
            json(ex, 400, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
    }
}
