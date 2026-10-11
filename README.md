# Musical Instruments

> Live music, played by the players of TF-Minecraft.

Musical Instruments turns held items into instruments for performances, tavern gatherings, and spontaneous music. An instrument in the off-hand makes the hotbar a set of playable notes, with sneaking providing an alternate note or chord layout.

Sounds play at the performer's location for nearby listeners, while floating note particles give each performance a visual presence.

## Features

- **Multitrack recording** — record successive takes while hearing the other tracks, preview the mix, and keep or discard each take.
- **Player-made records** — publish immutable song editions onto physical discs, copy them, and play them from ordinary jukeboxes.
- **Visual recording studio** — players open the studio by right-clicking a configured vanilla block or ItemsAdder station; staff can also use `/music studio` anywhere. Track rows provide volume and mute controls, take review, and disc publishing.
- **Song projects** — open your project library, create up to 36 songs, and keep each project's tracks and pending take independently. Right-click a song to delete it after confirmation.
- **Project settings**: rename your song, adjust tempo and toggle the recording metronome from a dedicated inventory page.
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

Staff can use `/music studio` anywhere with `instruments.studio` (default: op).
Players with `instruments.record` open the studio by right-clicking the station
configured in `studio.yml` as `v.<material>` or `ia.<namespace:id>` and must stay
near it to use its controls. Jukebox sneak-right-click remains available to
MMOItems and ordinary right-click plays/ejects discs.
Choose a song or **Create a new song**, enter its title, and hold an instrument
in your off-hand. The library fills all 36 middle slots in order. When full,
right-click an old song and confirm its deletion before creating another one.
Each horizontal row controls one track: record, volume, mute, listen, keep and
discard. The bottom bar controls the full mix and stops recording or preview.
**Project settings** opens separate title, tempo and metronome controls. The
metronome is heard only by the performer and is never recorded; count-in clicks
always play. Pick up exactly one music disc and place it on **Publish full song**.
The encoded disc returns to the cursor with the same material; no blank-disc
preparation is needed. A previously recorded disc needs a second click within
10 seconds to confirm overwriting. Other copies keep their earlier edition.
The recording guide is centered; projects with more than four tracks use pages.

Commands are also available: start with `/music new <title>` and use
`/music record 1`. After `/music stop`, listen with `/music preview take` and
accept with `/music keep`. Repeat for the other tracks. Convert an ordinary
music disc with `/music blank`, then hold it in your main hand and use
`/music publish`. `/music help` lists the recording commands.

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
