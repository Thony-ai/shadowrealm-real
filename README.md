# Shadow Realm (Fabric, Minecraft 26.2)

## Was die Mod macht
- **Schattenklinge** (schwarzes Netherite-Schwert mit lila Details): Schmiedetisch aus Netherite-Schwert + Netherite-Upgrade-Vorlage + Echo-Splitter, oder `/give @s shadowrealm:shadow_blade`.
- **Gefangennahme:** Wer mit der Klinge in der Hand getötet wird, droppt alle Items und landet in der **Schattenwelt** (dunkel, leeres Inventar, Abenteuermodus, nur herumlungern).
  **Benannte Tiere/Mobs** (Namensschild), die mit der Klinge getötet werden, kommen ebenfalls hinein und stehen dort herum.
- **Preis = Seele:** Beim ersten Gefangenen wird die Seele des Besitzers genommen. Sobald keiner mehr in der Schattenwelt ist, stirbt der Besitzer sofort.
  Der zuletzt zurückgerufene Spieler bekommt die **Seele** (Item). Sind alle drinnen getötet worden (oder war der Letzte ein Mob), liegt die Seele in einer **Kiste am Welt-Spawn**.
- **Menü (Taste R mit Klinge in der Hand):** Spieler und Mobs getrennt, einzeln oder alle zurückrufen, **"Betritt den Schatten" / "Verlasse den Schatten"**, Armee laden/zurückschicken.
  Zurückgerufene erscheinen an ihrem Todesort.
- **Im Schatten kämpfen:** Wer die Klinge in der Hand hält, kann in der Schattenwelt andere schlagen und töten. Getötete Gefangene sind frei und spawnen normal.
- **Armee:** Im Menü "Armee holen" lädt die Waffe, dann **Shift + R**: alle online gefangenen Spieler erscheinen im Schatten-Kit (Netherite, nur geliehen), sind verwundbar (gefallene Soldaten kehren in den Schatten zurück), können nichts bauen/abbauen und nur das Ziel angreifen, das der Besitzer zuletzt angegriffen hat. Nach 30 Sekunden verliert der Besitzer alle 10 Sekunden ein Herz. Shift + R schickt sie zurück.
- **Stirbt der Besitzer**, bleiben seine Gefangenen im Schatten ("herrenlos"). Jeder Spieler mit einer Klinge sieht sie im Menü und kann sie kostenlos herausholen. Die Seele liegt dann in einer Kiste am Welt-Spawn.
- Zustand wird in `shadowrealm_state.json` im Weltordner gespeichert.

## Seelen-System (neu)
- **Jeder Spieler hat eine Seele mit Rang**: Verderbt / Böse / Neutral / Gut / Rein. Töten von Monstern, Retten von Gefangenen und Töten böser Spieler heben den Rang; Tiere, Spieler (nicht-böse) und Gefangennahmen senken ihn.
- **Anzeige:** links neben dem Offhand-Slot der Hotbar und im Inventar neben dem Offhand-Slot (nur Anzeige).
- **Geist:** Wer die Seele verliert (Preis fällig oder Elixier-Schwert), ist unsichtbar, im Abenteuermodus, unverwundbar, kann niemandem schaden und trägt nichts. Eine **Leiche** (liegender Spieler mit Namen) bleibt mit seiner Beute zurück.
- **Ritual:** Seele in der Hand, 2 Sekunden an der Leiche schleichen: der Besitzer der Seele lebt dort mit der Beute wieder (bei fremder Leiche bleibt deren Opfer ein Geist).
- **Seelen-Kiste (Welt-Spawn):** Fremde Seele nur mit komplett leerem Inventar, sonst kommt sie zurück. Wer sie trotzdem nimmt, landet im Schatten (herrenlos), der Besitzer erscheint an dieser Stelle. Kiste abbauen = Besitzer lebt sofort mit seiner Beute. Die eigene Seele nehmen = sofort lebendig.
- **Elixier-Schwert** (Schmiedetisch: Netherite-Schwert + Netherite-Vorlage + Drachenatem): Wer einen bösen Spieler (Rang Böse oder schlimmer) damit besiegt, zieht dessen Seele. Ab 3 Seelen oder einer verderbten Seele: **R = Darkness** (1 Min, alle Feinde im Umkreis), **Shift+R = Bund** (2 Min, der zuletzt geschlagene Feind wird zu dir gezogen). Abklingzeit 3 Min.
- **Testbefehle (OP):** `/shadowrealm karma <spieler> <-100..100>`, `/shadowrealm losesoul <spieler>`, `/shadowrealm givesoul <spieler>`.

## Bauen
Am einfachsten über GitHub Actions (siehe .github/workflows/build.yml): Code hochladen, unter *Actions* die fertige `.jar` herunterladen.
Lokal: JDK 25, dann `gradle wrapper --gradle-version 9.5.1` und `./gradlew build`.
