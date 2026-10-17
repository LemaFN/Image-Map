# ImageMap-Claude

Paper-Plugin (26.x, Java 25): Bilder per Webseite hochladen und als große Karten-Wand in Item Frames anzeigen.

## So funktioniert's

1. Im Spiel `/imagemap upload` eingeben → du bekommst einen persönlichen Link (15 Min. gültig, einmalig nutzbar).
2. Auf der Webseite Bild **hochladen** (kein URL-Eintippen), Breite/Höhe in Karten wählen, **Automatisch platzieren** und **HD-Modus** nach Wunsch.
3. Nach dem Upload zeigt die Seite einen Befehl, z. B. `/imagemap get k7x2mq`. Den im Chat eingeben.
4. Du bekommst die Karte für **oben links**. Rahmen an die Wand hängen, mit der Karte in den Rahmen klicken – der Rest wird automatisch nach **rechts und unten** platziert. (Ohne „Automatisch platzieren“ bekommst du alle Karten ins Inventar.)

**HD-Modus:** Floyd-Steinberg-Dithering mit exakter Farbzuordnung auf die Minecraft-Kartenpalette (mehr Farbtiefe wirkt es nicht – die Palette ist fest).

## Einrichtung

1. `config.yml`: `http.public-host` (Domain/IP des Servers) und `web-ui-url` (deine GitHub-Pages-Adresse) setzen. TCP-Port `http.port` (Standard 8765) in der Firewall freigeben.
2. **HTTPS-Hinweis:** GitHub Pages läuft über HTTPS, Browser verbieten dort Anfragen an einen reinen `http://`-Server. Deshalb gilt:
   - **Ohne HTTPS-Proxy** (Standard): der Link zeigt auf die im Plugin eingebaute Kopie derselben Webseite (`http://host:port/`).
   - **Mit HTTPS-Reverse-Proxy** (z. B. Caddy/nginx vor Port 8765): `http.https-url: "https://maps.example.com"` setzen – dann öffnet der Link die GitHub-Pages-Seite.
3. Rechte: `imagemap.use` (Standard: OP), `imagemap.admin`.

## Bauen

```
gradle build          # → build/libs/ImageMap-Claude-1.0.0.jar
```
Optional einmalig `gradle wrapper`, dann `./gradlew build`. Die GitHub Action baut bei jedem Push, hängt die JAR bei Tags `v*` an ein Release und veröffentlicht `docs/` auf GitHub Pages
(Repo → Settings → Pages → Source: **GitHub Actions**).

Falls die Paper-Version nicht aufgelöst wird: `paperVersion` in `gradle.properties` anpassen.
