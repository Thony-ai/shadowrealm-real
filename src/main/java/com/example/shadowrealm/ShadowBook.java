package com.example.shadowrealm;

import net.minecraft.server.level.ServerPlayer;

/** Das Buch der Schatten: Geschichte und Regeln der Schattenwelt als geschriebenes Buch. */
public final class ShadowBook {
	private static final String TITLE = "Das Buch der Schatten";
	private static final String AUTHOR = "Der Namenlose";

	private static final String[] PAGES = {
			"DAS BUCH DER SCHATTEN\n\nGeschrieben von einem,\ndessen Name vergessen wurde.\n\nWer dies liest, hat\nbereits einen Schatten.\n\nLies weiter, wenn du\ndich traust.",

			"I. DER URSPRUNG\n\nVor allen Welten gab es nur Licht. Das Licht warf einen Schatten, und der Schatten wurde hungrig.\n\nEr wuchs unter dem Boden, bis er sich selbst einen Namen gab: die Schattenwelt.",

			"Ein Schmied, den man den Namenlosen nannte, schmiedete aus Netherit und dem Echo der Tiefe eine Klinge, die den Schatten fassen konnte.\n\nEr nannte sie Schattenklinge. Sie kostete ihn alles.",

			"II. DIE SCHATTENKLINGE\n\nSchmiedetisch:\nNetherit-Schwert\n+ Netherit-Upgrade-Vorlage\n+ Echo-Fragment.\n\nWer mit ihr einen Spieler erschlägt, verbannt ihn in die Schattenwelt.",

			"Taste R: Menü der Klinge.\nTaste G: Armee rufen.\n(Alle Tasten findest du unter Steuerung > Tastenbelegung > Schattenwelt.)\n\nGefangene lassen sich zurückrufen. Wer sie als Armee ruft, zahlt mit seinen Herzen.",

			"III. DER PREIS\n\nDie Klinge nimmt, was man ihr schuldet. Ist die Schattenwelt eines Besitzers leer, wird seine Seele fällig.\n\nEr stirbt, wird zum Geist, und seine Seele liegt in einer Kiste am Welt-Spawn.",

			"IV. DER GEIST\n\nOhne Seele bist du ein Geist: unsichtbar, ohne Hand und ohne Wirkung.\n\nDein Körper bleibt als Leiche liegen, bewacht von deinem Besitz.\n\nDu kannst nur warten.",

			"V. DIE SEELEN-KISTE\n\nDie Seele ruht als Gegenstand in einer Kiste am Spawn.\n\nNimmt der Besitzer sie, lebt er wieder.\n\nBricht jemand die Kiste, lebt er ebenfalls wieder.",

			"Greift ein Fremder mit vollem Inventar nach der Seele, erscheint der Besitzer neben der Kiste mit seinem alten Besitz, und die Seele gehört wieder ihm.\n\nGreift ein Fremder mit leeren Händen zu, wird er selbst zum Gefangenen.",

			"VI. DAS RITUAL\n\nHalte eine Seele in der Hand, stelle dich an eine Leiche und schleiche zwei Sekunden lang.\n\nDer Besitzer der Seele steigt an dieser Stelle empor, mit allem, was die Leiche bewahrte.",

			"VII. DAS ELIXIER-SCHWERT\n\nSchmiedetisch:\nNetherit-Schwert\n+ Netherit-Upgrade-Vorlage\n+ Drachenatem.\n\nTötest du mit ihm einen Spieler, zieht es dessen Seele als Gegenstand heraus.",

			"Mit drei gezogenen Seelen oder einer ganz verderbten erwachen seine Kräfte:\n\nTaste X: Darkness über alle Feinde nahebei.\nTaste C: Bund. Der zuletzt Geschlagene wird zu dir gezogen.\n\nBeides ladet drei Minuten.",

			"VIII. DER RANG\n\nJede Seele trägt ein Gewicht:\nVerderbt, Böse, Neutral, Gut, Rein.\n\nBöse zu erschlagen hebt den Rang, Gute zu erschlagen senkt ihn. Der Rang steht in deinem Inventar.",

			"IX. DIE SCHATTENWELT\n\nEin Reich ohne Sonne. Ein Schloss, so hoch wie ein Berg, ein Markt, ein Kerker, ein Tempel und eine Stadt ohne Bewohner.\n\nJedes Gebäude steht offen. Jedes Licht ist eine Laterne.",

			"Wer dort landet, ist nicht verloren, nur vergessen.\n\nAuf dem Platz vor dem Thron versammeln sich die Verbannten. Von der Terrasse aus kann der Herrscher zu ihnen sprechen.\n\nDies ist das Ende des Buches. Nicht der Geschichte.",
	};

	private ShadowBook() {
	}

	private static String snbt(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	/** Befehl, der dem Spieler das Buch gibt. */
	static String giveCommand(ServerPlayer player) {
		StringBuilder pages = new StringBuilder();
		for (String page : PAGES) {
			if (pages.length() > 0) {
				pages.append(',');
			}
			pages.append(snbt(page));
		}
		return "give " + player.getUUID() + " minecraft:written_book[minecraft:written_book_content={title:"
				+ snbt(TITLE) + ",author:" + snbt(AUTHOR) + ",pages:[" + pages + "]}]";
	}

	static void give(ServerPlayer player) {
		var server = RealmManager.server();
		if (server == null) {
			return;
		}
		try {
			server.getCommands().performPrefixedCommand(
					server.createCommandSourceStack().withSuppressedOutput(), giveCommand(player));
		} catch (RuntimeException e) {
			System.err.println("[shadowrealm] Konnte Buch nicht geben: " + e);
		}
	}
}
