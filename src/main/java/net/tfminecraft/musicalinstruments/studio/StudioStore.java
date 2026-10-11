package net.tfminecraft.musicalinstruments.studio;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Versioned, bounded data files. A failed write never truncates the last saved project. */
public final class StudioStore {
    // Enough for a full 12,000-note song plus a replacement take, including escaped names.
    static final int MAX_FILE_BYTES = 32_000_000;
    // Not a .yml file, so project listings skip it.
    static final String INDEX = "library.index";
    private static final String SELECTED = "selected";
    private final Path root;
    // Owners whose selected file already has its per-project copy. Both the main thread
    // and the checkpoint writer save, so the set is concurrent.
    private final Set<UUID> archived = ConcurrentHashMap.newKeySet();

    public StudioStore(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root.resolve("projects"));
        Files.createDirectories(root.resolve("songs"));
    }

    public Optional<Project> project(UUID owner) throws IOException {
        Path file = root.resolve("projects").resolve(owner + ".yml");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Project selected = decodeProject(file, owner, null);
        Path current = projectFile(owner, selected.song().id());
        return Optional.of(Files.exists(current) && Files.getLastModifiedTime(current).compareTo(Files.getLastModifiedTime(file)) > 0
                ? decodeProject(current, owner, selected.song().id()) : selected);
    }

    public Project project(UUID owner, UUID id) throws IOException {
        Path file = projectFile(owner, id);
        if (Files.exists(file)) return decodeProject(file, owner, id);
        return project(owner).filter(project -> project.song().id().equals(id))
                .orElseThrow(() -> new IllegalArgumentException("That project is no longer available"));
    }

    /** The owner's projects for the library, sorted by title, without loading their notes. */
    public List<ProjectSummary> summaries(UUID owner) throws IOException {
        Map<String, ProjectSummary> index = index(owner);
        ProjectSummary selected = index.remove(SELECTED);
        Map<UUID, ProjectSummary> result = new LinkedHashMap<>();
        for (ProjectSummary summary : index.values()) result.put(summary.id(), summary);
        if (selected != null) result.putIfAbsent(selected.id(), selected); // A legacy project saved only as the selection.
        return result.values().stream().sorted(ProjectSummary.ORDER).toList();
    }

    private Path projectFile(UUID owner, UUID id) {
        return root.resolve("projects").resolve(owner.toString()).resolve(id + ".yml");
    }

    public void delete(UUID owner, UUID id) throws IOException {
        Map<String, ProjectSummary> index = index(owner); // Validates identity and ownership of every project file.
        ProjectSummary selected = index.get(SELECTED);
        boolean isSelected = selected != null && selected.id().equals(id);
        if (!index.containsKey(id.toString()) && !isSelected) {
            throw new IllegalArgumentException("That project is no longer available");
        }
        if (isSelected) Files.deleteIfExists(root.resolve("projects").resolve(owner + ".yml"));
        Files.deleteIfExists(projectFile(owner, id));
    }

    // Library summaries are cached in one small file per owner, keyed by each project file's size and
    // modification time, so listing parses only files written outside save(). The cache is rebuilt when
    // missing, stale or unreadable; concurrent updates may lose an entry, which costs one reparse later.
    private Map<String, ProjectSummary> index(UUID owner) throws IOException {
        Path directory = root.resolve("projects").resolve(owner.toString());
        Map<String, List<?>> cached = readIndex(directory);
        Map<String, List<?>> fresh = new TreeMap<>();
        Map<String, ProjectSummary> result = new LinkedHashMap<>();
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).toList()) {
                    UUID id;
                    try {
                        String name = file.getFileName().toString();
                        id = UUID.fromString(name.substring(0, name.length() - 4));
                    } catch (IllegalArgumentException ex) {
                        throw new IOException("Invalid project filename: " + file.getFileName(), ex);
                    }
                    summarize(id.toString(), file, owner, id, cached, fresh, result);
                }
            }
        }
        Path selected = root.resolve("projects").resolve(owner + ".yml");
        if (Files.exists(selected)) summarize(SELECTED, selected, owner, null, cached, fresh, result);
        if (!fresh.equals(cached)) writeIndex(directory, fresh);
        return result;
    }

    private void summarize(String key, Path file, UUID owner, UUID id, Map<String, List<?>> cached,
                           Map<String, List<?>> fresh, Map<String, ProjectSummary> result) throws IOException {
        String stamp = stamp(file);
        ProjectSummary summary = decodeSummary(cached.get(key), stamp, id);
        if (summary == null) summary = ProjectSummary.of(decodeProject(file, owner, id));
        fresh.put(key, encodeSummary(stamp, summary));
        result.put(key, summary);
    }

    // Records a saved project's summary so the next listing need not parse the files just written.
    private void remember(Project project, Path file, Path selected) throws IOException {
        Map<String, List<?>> entries = readIndex(file.getParent());
        ProjectSummary summary = ProjectSummary.of(project);
        entries.put(summary.id().toString(), encodeSummary(stamp(file), summary));
        entries.put(SELECTED, encodeSummary(stamp(selected), summary));
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
            return id == null || summary.id().equals(id) ? summary : null;
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
        yaml.set("format", 1);
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
            if (id != null && !song.id().equals(id)) throw new IllegalArgumentException("Project ID mismatch");
            Track pending = yaml.isSet("pending") ? decodeTrack(map(yaml.get("pending"))) : null;
            return new Project(song, pending, yaml.getInt("bpm"), yaml.getBoolean("metronome"));
        } catch (RuntimeException ex) {
            throw new IOException("Invalid project " + owner, ex);
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
            throw new IOException("Invalid song " + id, ex);
        }
    }

    public void save(Project project) throws IOException {
        UUID owner = project.song().owner();
        Path selectedFile = root.resolve("projects").resolve(owner + ".yml");
        Files.createDirectories(projectFile(owner, project.song().id()).getParent());
        // Archive the original single-project file before selecting a different song. Every save
        // below writes both copies, so this parse runs once per owner instead of on every save.
        if (!archived.contains(owner) && Files.exists(selectedFile)) {
            Project previous = decodeProject(selectedFile, owner, null);
            Path previousFile = projectFile(owner, previous.song().id());
            if (!Files.exists(previousFile)) write(previousFile, encodeProject(previous), true);
        }
        YamlConfiguration yaml = encodeProject(project);
        Path file = projectFile(owner, project.song().id());
        write(file, yaml, true);
        write(selectedFile, yaml, true);
        archived.add(owner);
        remember(project, file, selectedFile);
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

    private YamlConfiguration read(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("Studio file exceeds the size limit: " + file.getFileName());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException ex) {
            throw new IOException("Invalid studio YAML: " + file.getFileName(), ex);
        }
        if (yaml.getInt("format") != 1) {
            throw new IOException("Unsupported studio format: " + file.getFileName());
        }
        return yaml;
    }

    private void write(Path file, YamlConfiguration yaml, boolean replace) throws IOException {
        if (!replace && Files.exists(file)) {
            throw new IOException("Published editions cannot be overwritten");
        }
        byte[] contents = yaml.saveToString().getBytes(StandardCharsets.UTF_8);
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
        yaml.set("format", 1);
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
        result.put("notes", track.notes().stream().map(note -> List.of(
                note.tick(), note.instrument(), note.sound(), note.volume(), note.pitch())).toList());
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
        List<?> entries = list(raw.get("notes"));
        if (entries.size() > Song.MAX_NOTES) {
            throw new IllegalArgumentException("Too many notes");
        }
        List<Note> notes = new ArrayList<>();
        for (Object value : entries) {
            List<?> note = list(value);
            if (note.size() != 5) {
                throw new IllegalArgumentException("Invalid note fields");
            }
            notes.add(new Note(integer(note.get(0)), text(note.get(1)), text(note.get(2)),
                    number(note.get(3)).floatValue(), number(note.get(4)).floatValue()));
        }
        return new Track(integer(raw.get("slot")), integer(raw.get("length")),
                number(raw.get("gain")).floatValue(), (Boolean) raw.get("muted"), notes);
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
