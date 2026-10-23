# Shadow Realm (Fabric, Minecraft 26.2)

## Was die Mod macht
- **Schattenklinge** (Netherite-Schwert-Werte): per Schmiedetisch aus Netherite-Schwert + Netherite-Upgrade-Vorlage + Echo-Splitter, oder im Kreativmodus im Kampf-Tab.
- Wer von einem Spieler mit der Klinge in der Hand getötet wird, droppt alle Items und respawnt in der **Schattenwelt** (komplett dunkel, Blöcke aus Deepslate/Blackstone, keine Mobs, kein Schaden, Abenteuermodus, leeres Inventar).
- **Rückruf (Taste R, nur mit Klinge in der Hand):**
  - `R` = nächstes Opfer auswählen (Anzeige über der Hotbar)
  - `Shift + R` = ausgewähltes Opfer zurückrufen
  - `Strg + R` = alle Opfer zurückrufen
- Zurückgerufene Spieler erscheinen an ihrem Todesort (richtige Dimension, Blickrichtung, alter Spielmodus). Offline-Opfer kommen beim nächsten Login zurück.
- Der Zustand wird in `shadowrealm_state.json` im Weltordner gespeichert und bleibt nach Neustart erhalten.
- Die Taste ist in den Steuerungs-Einstellungen änderbar.

## Bauen
Benötigt JDK 25 und Internetzugang für Gradle.

    gradle wrapper --gradle-version 9.5.1   # einmalig, erzeugt gradlew
    ./gradlew build

Die fertige Mod liegt in `build/libs/shadowrealm-1.0.0.jar` (die `-sources`-Datei brauchst du nicht). Sie kommt in den `mods`-Ordner, zusammen mit dem Fabric API Mod. Zum Testen: `./gradlew runClient`.

## Hinweis
Das Projekt wurde ohne Compiler-Lauf geschrieben (die Build-Umgebung hatte keinen Zugriff auf das Fabric-Maven). Falls beim ersten Build ein Methodenname nicht passt, liegt das meist an kleinen Umbenennungen in 26.2. Aktuelle Versionen für `gradle.properties` stehen auf https://fabricmc.net/develop.
