package net.tfminecraft.musicalinstruments.studio;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Versioned, bounded data files. A failed write never truncates the last saved project. */
public final class StudioStore {
    // Enough for a full 12,000-note song plus a replacement take, even with a distinct long name per note.
    static final int MAX_FILE_BYTES = 32_000_000;
    // Format 1, written by 3.3.0 with a YAML list per note, is not read.
    static final int FORMAT = 2;
    // Neither is a .yml file, so project listings skip them.
    static final String INDEX = "library.index";
    // Holds the selected project's ID, so the selection is not a second copy of the project.
    static final String SELECTED = "selected";
    // Stands in for the edition ID while hashing, so the ID depends only on the music and its labels.
    private static final UUID CONTENT_ID = new UUID(0, 0);
    private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);
    private final Path root;
    private final Logger logger;
    private final Clock clock;
    // Unreadable files already logged, with the size and time they had then, so each is reported once.
    private final Map<Path, String> reported = new ConcurrentHashMap<>();

    /** A file whose contents cannot be decoded, unlike a failure to reach the disk. */
    static final class DamagedFileException extends IOException {
        DamagedFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public StudioStore(Path root) throws IOException {
        this(root, Logger.getLogger(StudioStore.class.getName()));
    }

    public StudioStore(Path root, Logger logger) throws IOException {
        this(root, logger, Clock.systemUTC());
    }

    StudioStore(Path root, Logger logger, Clock clock) throws IOException {
        this.root = root;
        this.logger = logger;
        this.clock = clock;
        Files.createDirectories(root.resolve("projects"));
        Files.createDirectories(root.resolve("songs"));
        // A crash between writing and moving leaves a temporary file; it never holds the only copy.
        try (var files = Files.walk(root, 3)) {
            for (Path file : files.filter(StudioStore::temporary).toList()) Files.deleteIfExists(file);
        }
    }

    private static boolean temporary(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("studio-") && name.endsWith(".tmp");
    }

    public Optional<Project> project(UUID owner) throws IOException {
        UUID id = selected(owner);
        Path file = id == null ? null : projectFile(owner, id);
        if (file == null || !Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(decodeProject(file, owner, id));
        } catch (DamagedFileException ex) {
            // Without an active project the player can still create or open another one.
            report(file, ex.getMessage());
            return Optional.empty();
        }
    }

    public Project project(UUID owner, UUID id) throws IOException {
        Path file = projectFile(owner, id);
        if (!Files.exists(file)) throw new IllegalArgumentException("That project is no longer available");
        return decodeProject(file, owner, id);
    }

    /** The owner's projects for the library, sorted by title, without loading their notes. */
    public List<ProjectSummary> summaries(UUID owner) throws IOException {
        return index(owner).values().stream().sorted(ProjectSummary.ORDER).toList();
    }

    private Path directory(UUID owner) {
        return root.resolve("projects").resolve(owner.toString());
    }

    private Path projectFile(UUID owner, UUID id) {
        return directory(owner).resolve(id + ".yml");
    }

    private UUID selected(UUID owner) throws IOException {
        Path file = directory(owner).resolve(SELECTED);
        if (!Files.exists(file)) return null;
        try {
            // Invalid UTF-8 becomes replacement characters, which no project ID contains.
            return UUID.fromString(Files.size(file) > 64 ? "" : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).strip());
        } catch (IllegalArgumentException ex) {
            report(file, "it does not hold a project ID; the next save replaces it");
            return null;
        }
    }

    private void report(Path file, String problem) throws IOException {
        String stamp = stamp(file);
        if (!stamp.equals(reported.put(file, stamp))) {
            logger.warning("Skipping unreadable studio file " + file + ": " + problem
                    + ". The player's other projects remain available; repair or remove the file.");
        }
    }

    /** Makes an existing project the owner's active one, without rewriting it. */
    public void select(UUID owner, UUID id) throws IOException {
        if (!Files.exists(projectFile(owner, id))) throw new IllegalArgumentException("That project is no longer available");
        point(owner, id);
    }

    private void point(UUID owner, UUID id) throws IOException {
        if (!id.equals(selected(owner))) write(directory(owner).resolve(SELECTED), id.toString().getBytes(StandardCharsets.UTF_8), true);
    }

    public void delete(UUID owner, UUID id) throws IOException {
        // Validates identity and ownership of every project file.
        if (!index(owner).containsKey(id.toString())) {
            throw new IllegalArgumentException("That project is no longer available");
        }
        if (id.equals(selected(owner))) Files.delete(directory(owner).resolve(SELECTED));
        Files.deleteIfExists(projectFile(owner, id));
    }

    // Library summaries are cached in one small file per owner, keyed by each project file's size and
    // modification time, so listing parses only files written outside save(). The cache is rebuilt when
    // missing, stale or unreadable; concurrent updates may lose an entry, which costs one reparse later.
    private Map<String, ProjectSummary> index(UUID owner) throws IOException {
        Path directory = directory(owner);
        Map<String, List<?>> cached = readIndex(directory);
        Map<String, List<?>> fresh = new TreeMap<>();
        Map<String, ProjectSummary> result = new LinkedHashMap<>();
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).toList()) {
                    String name = file.getFileName().toString();
                    UUID id;
                    try {
                        id = UUID.fromString(name.substring(0, name.length() - 4));
                    } catch (IllegalArgumentException ex) {
                        report(file, "its name is not a project ID");
                        continue;
                    }
                    summarize(id.toString(), file, owner, id, cached, fresh, result);
                }
            }
        }
        if (!fresh.equals(cached)) writeIndex(directory, fresh);
        return result;
    }

    // One unreadable project must not hide the others, nor stop the player from creating or deleting
    // projects. It is left on disk, logged and skipped; the index remembers it until the file changes.
    private void summarize(String key, Path file, UUID owner, UUID id, Map<String, List<?>> cached,
                           Map<String, List<?>> fresh, Map<String, ProjectSummary> result) throws IOException {
        String stamp = stamp(file);
        List<?> entry = cached.get(key);
        List<?> damaged = List.of(stamp, "unreadable"); // Not a project ID, so never a valid summary.
        if (damaged.equals(entry)) {
            report(file, "it could not be read when the library was last listed");
            fresh.put(key, damaged);
            return;
        }
        ProjectSummary summary = decodeSummary(entry, stamp, id);
        if (summary == null) {
            try {
                summary = ProjectSummary.of(decodeProject(file, owner, id));
            } catch (DamagedFileException ex) {
                report(file, ex.getMessage());
                fresh.put(key, damaged);
                return;
            }
        }
        fresh.put(key, encodeSummary(stamp, summary));
        result.put(key, summary);
    }

    // Records a saved project's summary so the next listing need not parse the file just written.
    private void remember(Project project, Path file) throws IOException {
        Map<String, List<?>> entries = readIndex(file.getParent());
        ProjectSummary summary = ProjectSummary.of(project);
        entries.put(summary.id().toString(), encodeSummary(stamp(file), summary));
        writeIndex(file.getParent(), entries);
    }

    private static String stamp(Path file) throws IOException {
        return Files.size(file) + ":" + Files.getLastModifiedTime(file).to(TimeUnit.MICROSECONDS);
    }

    private static List<?> encodeSummary(String stamp, ProjectSummary summary) {
        return List.of(stamp, summary.id().toString(), summary.title(), summary.tracks(), summary.lengthTicks(),
                summary.pendingSlot());
    }

    private ProjectSummary decodeSummary(List<?> entry, String stamp, UUID id) {
        if (entry == null) return null;
        try {
            if (!stamp.equals(entry.getFirst())) return null;
            ProjectSummary summary = new ProjectSummary(UUID.fromString((String) entry.get(1)), (String) entry.get(2),
                    integer(entry.get(3)), integer(entry.get(4)), integer(entry.get(5)));
            return summary.id().equals(id) ? summary : null;
        } catch (RuntimeException ex) {
            return null; // A malformed entry is rebuilt from its project file.
        }
    }

    private Map<String, List<?>> readIndex(Path directory) {
        Map<String, List<?>> entries = new TreeMap<>();
        try {
            YamlConfiguration yaml = read(directory.resolve(INDEX));
            for (String key : yaml.getKeys(false)) {
                if (yaml.get(key) instanceof List<?> entry) entries.put(key, entry);
            }
        } catch (IOException ex) {
            // A missing or unreadable cache is rebuilt from the project files.
        }
        return entries;
    }

    private void writeIndex(Path directory, Map<String, List<?>> entries) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT);
        entries.forEach(yaml::set);
        try {
            Files.createDirectories(directory);
            write(directory.resolve(INDEX), yaml, true);
        } catch (IOException ex) {
            // The cache is optional: listing still works and retries the write next time.
        }
    }

    private Project decodeProject(Path file, UUID owner, UUID id) throws IOException {
        YamlConfiguration yaml = read(file);
        try {
            Song song = decodeSong(yaml);
            if (!song.owner().equals(owner)) {
                throw new IllegalArgumentException("Project owner mismatch");
            }
            if (!song.id().equals(id)) throw new IllegalArgumentException("Project ID mismatch");
            Track pending = yaml.isSet("pending") ? decodeTrack(map(yaml.get("pending"))) : null;
            return new Project(song, pending, yaml.getInt("bpm"), yaml.getBoolean("metronome"));
        } catch (RuntimeException ex) {
            throw new DamagedFileException("Invalid project " + owner, ex);
        }
    }

    public Song song(UUID id) throws IOException {
        YamlConfiguration yaml = read(root.resolve("songs").resolve(id + ".yml"));
        try {
            Song song = decodeSong(yaml);
            if (!song.id().equals(id)) {
                throw new IllegalArgumentException("Song ID mismatch");
            }
            return song;
        } catch (RuntimeException ex) {
            throw new DamagedFileException("Invalid song " + id, ex);
        }
    }

    public void save(Project project) throws IOException {
        UUID owner = project.song().owner();
        Path file = projectFile(owner, project.song().id());
        Files.createDirectories(file.getParent());
        write(file, encodeProject(project), true);
        point(owner, project.song().id());
        remember(project, file);
    }

    private YamlConfiguration encodeProject(Project project) {
        YamlConfiguration yaml = encodeSong(project.song());
        yaml.set("bpm", project.bpm());
        yaml.set("metronome", project.metronome());
        if (project.pending() != null) {
            yaml.set("pending", encodeTrack(project.pending()));
        }
        return yaml;
    }

    public void publish(Song song) throws IOException {
        write(root.resolve("songs").resolve(song.id() + ".yml"), encodeSong(song), false);
    }

    /**
     * Publishes an immutable edition of the song. Editions are named after their content, so
     * republishing an unchanged mix returns the earlier edition instead of adding another file.
     */
    public Song publishEdition(Song song, int perDay) throws IOException {
        // Muted and silent parts are never played, so they are not kept forever either.
        List<Track> tracks = song.audibleMix().tracks();
        String content = encodeSong(new Song(CONTENT_ID, song.owner(), song.author(), song.title(), tracks)).saveToString();
        UUID id = UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8));
        Song edition = new Song(id, song.owner(), song.author(), song.title(), tracks);
        Path file = root.resolve("songs").resolve(id + ".yml");
        if (Files.exists(file)) {
            // The same bytes are the same edition; comparing them is cheaper than parsing it again.
            byte[] contents = encodeSong(edition).saveToString().getBytes(StandardCharsets.UTF_8);
            if (Files.size(file) != contents.length || !Arrays.equals(Files.readAllBytes(file), contents)) {
                throw new IOException("Published edition does not match its content: " + id);
            }
            return edition;
        }
        long now = clock.millis();
        List<Long> recent = recentEditions(song.owner(), now);
        if (perDay > 0 && recent.size() >= perDay) {
            long minutes = (recent.getFirst() + DAY_MILLIS - now + 59_999) / 60_000;
            throw new IllegalArgumentException("You can publish " + perDay + " new song editions a day. Try again in "
                    + (minutes > 90 ? (minutes + 59) / 60 + " hours" : minutes + " minutes"));
        }
        publish(edition);
        recent.add(now);
        Path log = editionLog(song.owner());
        Files.createDirectories(log.getParent());
        write(log, String.join("\n", recent.stream().map(String::valueOf).toList()).getBytes(StandardCharsets.UTF_8), true);
        return edition;
    }

    private Path editionLog(UUID owner) {
        return root.resolve("editions").resolve(owner + ".log");
    }

    // When each of the owner's editions of the last 24 hours was published, oldest first.
    private List<Long> recentEditions(UUID owner, long now) throws IOException {
        Path file = editionLog(owner);
        List<Long> recent = new ArrayList<>();
        if (!Files.exists(file)) return recent;
        // Invalid UTF-8 becomes replacement characters, so only those lines are skipped below.
        for (String line : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n")) {
            try {
                long at = Long.parseLong(line.strip());
                if (at > now - DAY_MILLIS) recent.add(at);
            } catch (NumberFormatException ex) {
                // A damaged line no longer counts; the next publication rewrites the log.
            }
        }
        recent.sort(null);
        return recent;
    }

    private YamlConfiguration read(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new DamagedFileException("Studio file exceeds the size limit: " + file.getFileName(), null);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (CharacterCodingException ex) {
            throw new DamagedFileException("Invalid UTF-8 in studio file: " + file.getFileName(), ex);
        } catch (InvalidConfigurationException ex) {
            throw new DamagedFileException("Invalid studio YAML: " + file.getFileName(), ex);
        }
        if (yaml.getInt("format") != FORMAT) {
            throw new DamagedFileException("Unsupported studio format: " + file.getFileName(), null);
        }
        return yaml;
    }

    private void write(Path file, YamlConfiguration yaml, boolean replace) throws IOException {
        write(file, yaml.saveToString().getBytes(StandardCharsets.UTF_8), replace);
    }

    private void write(Path file, byte[] contents, boolean replace) throws IOException {
        if (!replace && Files.exists(file)) {
            throw new IOException("Published editions cannot be overwritten");
        }
        if (contents.length > MAX_FILE_BYTES) {
            throw new IOException("Studio file exceeds the size limit: " + file.getFileName());
        }
        Path temp = Files.createTempFile(file.getParent(), "studio-", ".tmp");
        try {
            Files.write(temp, contents);
            try {
                if (replace) {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException ex) {
                if (replace) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temp, file);
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private YamlConfiguration encodeSong(Song song) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT);
        yaml.set("id", song.id().toString());
        yaml.set("owner", song.owner().toString());
        yaml.set("author", song.author());
        yaml.set("title", song.title());
        yaml.set("tracks", song.tracks().stream().map(this::encodeTrack).toList());
        return yaml;
    }

    private Map<String, Object> encodeTrack(Track track) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("slot", track.slot());
        result.put("length", track.lengthTicks());
        result.put("gain", track.gain());
        result.put("muted", track.muted());
        encodeNotes(track, result);
        return result;
    }

    private Song decodeSong(YamlConfiguration yaml) {
        List<?> raw = list(yaml.get("tracks"));
        if (raw.size() > Song.MAX_TRACKS) {
            throw new IllegalArgumentException("Too many tracks");
        }
        List<Track> tracks = raw.stream().map(value -> decodeTrack(map(value))).toList();
        return new Song(UUID.fromString(yaml.getString("id")), UUID.fromString(yaml.getString("owner")),
                yaml.getString("author"), yaml.getString("title"), tracks);
    }

    private Track decodeTrack(Map<?, ?> raw) {
        return new Track(integer(raw.get("slot")), integer(raw.get("length")),
                number(raw.get("gain")).floatValue(), (Boolean) raw.get("muted"), compactNotes(raw));
    }

    // Each track lists its instrument and sample names once. Each note is one line of
    // "<ticks since the previous note> <instrument> <sample> <volume> <pitch>", the names given
    // as positions in that list. One text value per track also parses much faster than a YAML list per note.
    private void encodeNotes(Track track, Map<String, Object> result) {
        Map<String, Integer> names = new LinkedHashMap<>();
        StringBuilder lines = new StringBuilder(track.notes().size() * 16);
        int previous = 0;
        for (Note note : track.notes()) {
            if (!lines.isEmpty()) lines.append('\n');
            lines.append(note.tick() - previous).append(' ')
                    .append(names.computeIfAbsent(note.instrument(), name -> names.size())).append(' ')
                    .append(names.computeIfAbsent(note.sound(), name -> names.size())).append(' ')
                    .append(note.volume()).append(' ').append(note.pitch());
            previous = note.tick();
        }
        result.put("names", List.copyOf(names.keySet()));
        result.put("notes", lines.toString());
    }

    private List<Note> compactNotes(Map<?, ?> raw) {
        List<?> entries = list(raw.get("names"));
        if (entries.size() > 2 * Song.MAX_NOTES) {
            throw new IllegalArgumentException("Too many names");
        }
        String[] names = entries.stream().map(this::text).toArray(String[]::new);
        if (!(raw.get("notes") instanceof String lines)) {
            throw new IllegalArgumentException("Expected note lines");
        }
        List<Note> notes = new ArrayList<>();
        int tick = 0;
        for (int start = 0; start < lines.length(); ) {
            if (notes.size() == Song.MAX_NOTES) {
                throw new IllegalArgumentException("Too many notes");
            }
            int end = lines.indexOf('\n', start);
            if (end < 0) end = lines.length();
            String[] fields = lines.substring(start, end).split(" ");
            if (fields.length != 5) {
                throw new IllegalArgumentException("Invalid note fields");
            }
            tick = Math.addExact(tick, Integer.parseInt(fields[0]));
            notes.add(new Note(tick, names[Integer.parseInt(fields[1])], names[Integer.parseInt(fields[2])],
                    Float.parseFloat(fields[3]), Float.parseFloat(fields[4])));
            start = end + 1;
        }
        return notes;
    }

    private Map<?, ?> map(Object value) {
        // Bukkit turns root-level maps into configuration sections.
        if (value instanceof org.bukkit.configuration.ConfigurationSection section) {
            return section.getValues(false);
        }
        return (Map<?, ?>) value;
    }

    private List<?> list(Object value) {
        return (List<?>) value;
    }

    private String text(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Expected text");
        }
        // Songs repeat a few instrument and sample names; share one copy instead of one per note.
        return text.intern();
    }

    private Number number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Expected a number");
        }
        return number;
    }

    private int integer(Object value) {
        Number number = number(value);
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || decimal != number.intValue()) {
            throw new IllegalArgumentException("Expected a bounded integer");
        }
        return number.intValue();
    }
}
