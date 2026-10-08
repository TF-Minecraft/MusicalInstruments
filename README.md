# Musical Instruments

> Live music, played by the players of TF-Minecraft.

Musical Instruments turns held items into instruments for performances, tavern gatherings, and spontaneous music. An instrument in the off-hand makes the hotbar a set of playable notes, with sneaking providing an alternate note or chord layout.

Sounds play at the performer's location for nearby listeners, while floating note particles give each performance a visual presence.

## Features

- **Live hotbar performance** — changing hotbar slots triggers the instrument's notes immediately.
- **Alternate notes and chords** — sneaking opens a second set of sounds for the same instrument.
- **Repeatable notes** — the selected slot resets after a note so players can play it again.
- **Distinct instrument voices** — each instrument has its own sound mappings, pitch, and volume.
- **Custom instrument items** — supports vanilla items and instrument items from MMOItems, ItemsAdder, and Nexo.
- **In-game note reference** — players can view the keybind layout for the instrument they are holding.

## Credits

Created by [Justinas Launikonis](https://github.com/JustinasLa).

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/MusicalInstruments/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests and coverage

With Java 21 installed, run `mvn clean verify`. Tests use JUnit 5, Mockito, and
MockBukkit for playback, commands, item resolution, configuration, and lifecycle
behaviour. Surefire writes test results to `target/surefire-reports/`; JaCoCo
writes HTML and XML reports to `target/site/jacoco/`. Verification requires 100%
instruction and branch coverage, with no production-code exclusions. Tests use
stand-ins for item plugins; live integrations and resource-pack audio still
need Minecraft server and client testing.

## License

MusicalInstruments is distributed under the [Artistic License 2.0](LICENSE).

Copyright (c) 2026 Justinas Launikonis.
Copyright (c) 2026 TF-Minecraft contributors.

Third-party dependencies retain their own licenses.
