package net.tfminecraft.musicalinstruments.studio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Raw project publications with immutable source bytes and checked replay receipts. */
final class StudioOwnership {
    static final class Conflict extends IOException {
        Conflict(String message) { super(message); }
    }

    private final StudioStore store;
    private final Path root;
    private record Stamp(long size, java.nio.file.attribute.FileTime modified, Object key) {}
    private record Locks(Stamp stamp, java.util.Set<UUID> characters) {}
    private final java.util.Map<Path, Locks> lockCache = new java.util.HashMap<>();
    StudioOwnership(StudioStore store) { this.store = store; this.root = store.root(); }

    void migrate(StudioIdentity identity) throws IOException {
        if (!identity.heirsReady()) return;
        Path legacy = root.resolve("projects");
        if (!Files.isDirectory(legacy)) return;
        for (Path folder : files(legacy)) {
            if (!Files.isDirectory(folder)) { holdUnrecognized(folder); continue; }
            UUID account;
            try { account = UUID.fromString(folder.getFileName().toString()); }
            catch (IllegalArgumentException ex) { holdUnrecognized(folder); continue; }
            Path journalFile = root.resolve("ownership/migration").resolve(account + ".xml");
            Properties journal = read(journalFile);
            StudioIdentity.Owner to;
            if (journal.isEmpty()) {
                to = identity.heir(account).orElse(null);
                if (to == null) continue;
                journal.setProperty("format", "1");
                journal.setProperty("kind", "migration");
                journal.setProperty("from-account", account.toString());
                journal.setProperty("to-account", to.account().toString());
                journal.setProperty("to-character", to.character().toString());
                for (Path source : projects(folder)) {
                    String id = source.getFileName().toString().replace(".yml", "");
                    byte[] bytes = Files.readAllBytes(source);
                    try {
                        UUID.fromString(id);
                        var yaml = yaml(bytes);
                        if (!Integer.valueOf(StudioStore.FORMAT).equals(yaml.get("format")) || !account.toString().equals(yaml.get("owner"))
                                || !id.equals(yaml.get("id"))) throw new Conflict("Legacy project identity mismatch");
                        plan(journal, id, source, store.projectFile(to.character(), UUID.fromString(id)), bytes, to);
                    } catch (IOException | IllegalArgumentException ex) {
                        journal.setProperty("held." + id, ex.getMessage());
                        journal.setProperty("held-source." + id, Base64.getEncoder().encodeToString(bytes));
                    }
                }
                Path selected = folder.resolve(StudioStore.SELECTED);
                if (Files.exists(selected)) journal.setProperty("selected", Base64.getEncoder().encodeToString(Files.readAllBytes(selected)));
                write(journalFile, journal); // Entire inventory/backup precedes the first publication.
            } else {
                to = owner(journal, "to");
            }
            for (String id : ids(journal)) apply(journalFile, journal, id, () -> true, false);
            if (!"true".equals(journal.getProperty("complete"))) {
                String selected = journal.getProperty("selected");
                Path target = store.directory(to.character()).resolve(StudioStore.SELECTED);
                if (selected != null && !Files.exists(target)) {
                    byte[] pointer = Base64.getDecoder().decode(selected);
                    try {
                        UUID id = UUID.fromString(new String(pointer, StandardCharsets.UTF_8).strip());
                        if (Files.exists(store.projectFile(to.character(), id))) DurableFiles.write(target, pointer, false);
                    } catch (IllegalArgumentException ex) { journal.setProperty("held.selected", "Invalid project selection"); }
                }
                journal.setProperty("complete", "true");
                write(journalFile, journal);
            }
        }
    }

    private void holdUnrecognized(Path source) throws IOException {
        Path file = root.resolve("ownership/migration").resolve("unrecognized-" + hash(root.relativize(source).toString().getBytes(StandardCharsets.UTF_8)) + ".xml");
        if (Files.exists(file)) { read(file); return; }
        Properties journal = new Properties();
        journal.setProperty("format", "1"); journal.setProperty("kind", "migration");
        List<Path> records = Files.isDirectory(source) ? projects(source) : List.of(source);
        for (Path record : records) {
            String key = root.relativize(record).toString();
            byte[] bytes = Files.readAllBytes(record);
            journal.setProperty("held." + key, "Unrecognized legacy account path");
            journal.setProperty("held-source." + key, Base64.getEncoder().encodeToString(bytes));
        }
        write(file, journal);
    }

    List<String> transfer(UUID death, String exactDecision, StudioIdentity.Owner from, StudioIdentity.Owner to,
                          BooleanSupplier guard) throws IOException {
        if (from.equals(to)) throw new Conflict("Estate recipient is the testator");
        Path file = root.resolve("ownership/estates").resolve(death + ".xml");
        Properties journal = read(file);
        if (journal.isEmpty()) {
            journal.setProperty("format", "1");
            journal.setProperty("kind", "estate");
            journal.setProperty("death", death.toString());
            journal.setProperty("from-account", from.account().toString());
            journal.setProperty("from-character", from.character().toString());
        }
        if (!death.toString().equals(journal.getProperty("death")) || !from.equals(owner(journal, "from")))
            throw new Conflict("Estate journal identity differs from this death");
        for (String id : ids(journal)) {
            if ("moved".equals(journal.getProperty(id + ".state"))) continue;
            if (!exactDecision.equals(journal.getProperty(id + ".decision"))) {
                // Only a plan with no published output can be superseded by a new decision.
                if (!"planned".equals(journal.getProperty(id + ".state"))
                        || Files.exists(path(journal.getProperty(id + ".target"))))
                    throw new Conflict("Stale partial transfer needs staff recovery: " + id);
                removeEntry(journal, id);
            } else if (!to.equals(owner(journal, id + ".to"))) {
                throw new Conflict("The planned recipient differs from the current exact decision");
            }
        }
        if (!guard.getAsBoolean()) throw new IOException("Estate decision no longer holds");
        for (Path source : projects(store.directory(from.character()))) {
            String id = source.getFileName().toString().replace(".yml", "");
            if ("moved".equals(journal.getProperty(id + ".state"))) throw new Conflict("Transferred project reappeared at its previous owner: " + id);
            if (journal.containsKey(id + ".state")) continue;
            UUID projectId;
            try { projectId = UUID.fromString(id); }
            catch (IllegalArgumentException ex) { throw new Conflict("Unrecognized project file: " + source); }
            byte[] before = Files.readAllBytes(source);
            var yaml = yaml(before);
            if (!id.equals(yaml.get("id")) || !from.account().toString().equals(yaml.get("owner-account"))
                    || !from.character().toString().equals(yaml.get("owner-character")))
                throw new Conflict("Unresolved project authority: " + id);
            plan(journal, id, source, store.projectFile(to.character(), projectId), before, to);
            journal.setProperty(id + ".from-account", from.account().toString());
            journal.setProperty(id + ".from-character", from.character().toString());
            journal.setProperty(id + ".decision", exactDecision);
        }
        write(file, journal);
        for (String id : ids(journal)) {
            if (!"moved".equals(journal.getProperty(id + ".state"))) apply(file, journal, id, guard, true);
        }
        return ids(journal).stream().filter(id -> "moved".equals(journal.getProperty(id + ".state"))).toList();
    }

    List<String> retainedReceipts(UUID death, String exactDecision, StudioIdentity.Owner from) throws IOException {
        Path file = root.resolve("ownership/estates").resolve(death + ".xml");
        Properties journal = read(file);
        if (!journal.isEmpty() && (!death.toString().equals(journal.getProperty("death")) || !from.equals(owner(journal, "from"))))
            throw new Conflict("Estate journal identity differs from this death");
        for (String id : ids(journal)) {
            if ("moved".equals(journal.getProperty(id + ".state"))) continue;
            if (!exactDecision.equals(journal.getProperty(id + ".decision"))) {
                if (!"planned".equals(journal.getProperty(id + ".state")) || Files.exists(path(journal.getProperty(id + ".target"))))
                    throw new Conflict("Stale partial transfer needs staff recovery: " + id);
                removeEntry(journal, id);
            }
        }
        if (!journal.isEmpty()) write(file, journal);
        return ids(journal).stream().filter(id -> "moved".equals(journal.getProperty(id + ".state"))).toList();
    }

    private void plan(Properties journal, String id, Path source, Path target, byte[] bytes, StudioIdentity.Owner to) throws IOException {
        Path backup = root.resolve("ownership/backups").resolve(hash(bytes) + ".yml");
        if (!Files.exists(backup)) DurableFiles.write(backup, bytes, false);
        else if (!hash(Files.readAllBytes(backup)).equals(hash(bytes))) throw new Conflict("Ownership backup differs from its hash");
        var yaml = yaml(bytes);
        yaml.put("owner-account", to.account().toString());
        yaml.put("owner-character", to.character().toString());
        byte[] after = RawYaml.bytes(yaml);
        journal.setProperty(id + ".source", root.relativize(source).toString());
        journal.setProperty(id + ".target", root.relativize(target).toString());
        journal.setProperty(id + ".before", Base64.getEncoder().encodeToString(bytes));
        journal.setProperty(id + ".after", Base64.getEncoder().encodeToString(after));
        journal.setProperty(id + ".before-hash", hash(bytes));
        journal.setProperty(id + ".after-hash", hash(after));
        journal.setProperty(id + ".to-account", to.account().toString());
        journal.setProperty(id + ".to-character", to.character().toString());
        journal.setProperty(id + ".state", "planned");
    }

    private void apply(Path file, Properties journal, String id, BooleanSupplier guard, boolean removeSource) throws IOException {
        if ("moved".equals(journal.getProperty(id + ".state"))) return;
        Path source = path(journal.getProperty(id + ".source"));
        Path target = path(journal.getProperty(id + ".target"));
        if (source.equals(target)) throw new Conflict("Source and destination refer to the same project");
        StudioIdentity.Owner to = owner(journal, id + ".to");
        if (!target.equals(store.projectFile(to.character(), UUID.fromString(id)).normalize()))
            throw new Conflict("Destination path differs from its planned character");
        if (Files.exists(source) && !hash(Files.readAllBytes(source)).equals(journal.getProperty(id + ".before-hash")))
            throw new Conflict("Project changed after ownership planning: " + source);
        if (!Files.exists(source) && "planned".equals(journal.getProperty(id + ".state")))
            throw new Conflict("Planned source project disappeared: " + source);
        if (!Files.exists(source) && !Files.exists(target)) throw new Conflict("Both published project copies disappeared");
        if (Files.exists(target)) {
            if (!hash(Files.readAllBytes(target)).equals(journal.getProperty(id + ".after-hash")))
                throw new Conflict("Recipient project conflicts: " + target);
        } else {
            if (!guard.getAsBoolean()) throw new IOException("Estate decision changed before publication");
            DurableFiles.write(target, Base64.getDecoder().decode(journal.getProperty(id + ".after")), false);
        }
        journal.setProperty(id + ".state", "published");
        write(file, journal);
        if (removeSource) {
            if (!guard.getAsBoolean()) throw new IOException("Estate decision changed before source removal");
            DurableFiles.delete(source);
        }
        journal.setProperty(id + ".state", "moved");
        write(file, journal);
    }

    void requireWritable(UUID character) throws IOException {
        for (String kind : List.of("migration", "estates")) for (Path file : files(root.resolve("ownership").resolve(kind))) {
            var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            Stamp stamp = new Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
            Locks cached = lockCache.get(file);
            if (cached == null || !stamp.equals(cached.stamp())) {
                Properties journal = read(file);
                java.util.Set<UUID> pending = new java.util.HashSet<>();
                for (String id : ids(journal)) if (!"moved".equals(journal.getProperty(id + ".state"))) {
                    pending.add(owner(journal, id + ".to").character());
                    if ("estate".equals(journal.getProperty("kind"))) pending.add(owner(journal, "from").character());
                }
                cached = new Locks(stamp, java.util.Set.copyOf(pending));
                lockCache.put(file, cached);
            }
            if (cached.characters().contains(character)) throw new Conflict("Studio ownership publication needs recovery: " + file);
        }
    }

    List<String> holdings(UUID character) throws IOException {
        return projects(store.directory(character)).stream().map(file -> file.getFileName().toString().replace(".yml", "")).toList();
    }

    void requireEstateReady(StudioIdentity.Owner from) throws IOException {
        Path legacy = root.resolve("projects").resolve(from.account().toString());
        if (projects(legacy).isEmpty()) return;
        Properties journal = read(root.resolve("ownership/migration").resolve(from.account() + ".xml"));
        if (journal.isEmpty() || !"true".equals(journal.getProperty("complete")))
            throw new IOException("The account's reviewed legacy project migration is not complete");
        if (from.character().toString().equals(journal.getProperty("to-character"))
                && journal.stringPropertyNames().stream().anyMatch(key -> key.startsWith("held.")))
            throw new Conflict("Legacy projects for this character remain held for staff");
    }

    private Path path(String relative) throws Conflict {
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root.normalize())) throw new Conflict("Invalid ownership journal path");
        return result;
    }

    private static StudioIdentity.Owner owner(Properties values, String prefix) throws Conflict {
        try { return new StudioIdentity.Owner(UUID.fromString(values.getProperty(prefix + "-account")),
                UUID.fromString(values.getProperty(prefix + "-character"))); }
        catch (IllegalArgumentException | NullPointerException ex) { throw new Conflict("Invalid journal owner"); }
    }

    static Properties read(Path file) throws IOException {
        Properties result = new Properties();
        if (Files.exists(file)) try (var input = Files.newInputStream(file)) {
            try { result.loadFromXML(input); }
            catch (java.util.InvalidPropertiesFormatException ex) { throw new Conflict("Unreadable ownership journal"); }
            if (!"1".equals(result.getProperty("format"))) throw new Conflict("Unsupported ownership journal format");
            String kind = result.getProperty("kind");
            if (!"migration".equals(kind) && !"estate".equals(kind)) throw new Conflict("Unknown ownership journal kind");
            try {
                for (String id : ids(result)) {
                    UUID.fromString(id);
                    if (!List.of("planned", "published", "moved").contains(result.getProperty(id + ".state")))
                        throw new Conflict("Unknown ownership publication state");
                    for (String part : List.of("before", "after")) {
                        byte[] bytes = Base64.getDecoder().decode(result.getProperty(id + "." + part));
                        if (!hash(bytes).equals(result.getProperty(id + "." + part + "-hash")))
                            throw new Conflict("Ownership journal payload hash differs");
                    }
                    StudioIdentity.Owner to = owner(result, id + ".to");
                    var after = yaml(Base64.getDecoder().decode(result.getProperty(id + ".after")));
                    if (!id.equals(after.get("id")) || !to.account().toString().equals(after.get("owner-account"))
                            || !to.character().toString().equals(after.get("owner-character")))
                        throw new Conflict("Planned authority differs from its payload");
                    if (result.getProperty(id + ".source") == null || result.getProperty(id + ".target") == null)
                        throw new Conflict("Ownership publication path missing");
                    var before = yaml(Base64.getDecoder().decode(result.getProperty(id + ".before")));
                    if (!id.equals(before.get("id"))) throw new Conflict("Planned source ID differs from its payload");
                    Path expectedSource;
                    if ("estate".equals(kind)) {
                        StudioIdentity.Owner from = owner(result, "from");
                        if (!from.equals(owner(result, id + ".from"))
                                || !from.account().toString().equals(before.get("owner-account"))
                                || !from.character().toString().equals(before.get("owner-character")))
                            throw new Conflict("Planned source authority differs from its payload");
                        expectedSource = Path.of("character-projects", from.character().toString(), id + ".yml");
                    } else {
                        UUID account = UUID.fromString(result.getProperty("from-account"));
                        if (!account.toString().equals(before.get("owner")) || !to.equals(owner(result, "to")))
                            throw new Conflict("Planned legacy authority differs from its payload");
                        expectedSource = Path.of("projects", account.toString(), id + ".yml");
                    }
                    if (!expectedSource.equals(Path.of(result.getProperty(id + ".source"))))
                        throw new Conflict("Source path differs from its planned owner");
                }
            } catch (IllegalArgumentException | NullPointerException ex) { throw new Conflict("Malformed ownership publication record"); }
        }
        return result;
    }

    static void write(Path file, Properties journal) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        journal.storeToXML(output, "Studio ownership format 1", StandardCharsets.UTF_8);
        DurableFiles.write(file, output.toByteArray(), true);
    }

    static String hash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    private static java.util.Map<String, Object> yaml(byte[] bytes) throws IOException {
        try { return RawYaml.read(bytes); }
        catch (IOException ex) { throw new Conflict("Unreadable project YAML: " + ex.getMessage()); }
    }

    private static List<Path> files(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (var paths = Files.list(folder)) { return paths.sorted().toList(); }
    }

    private static List<Path> projects(Path folder) throws IOException {
        return files(folder).stream().filter(file -> file.getFileName().toString().endsWith(".yml")).toList();
    }

    static List<String> ids(Properties journal) {
        return journal.stringPropertyNames().stream().filter(key -> key.endsWith(".state")
                        && !key.startsWith("held.") && !key.startsWith("held-source."))
                .map(key -> key.substring(0, key.length() - 6)).sorted().toList();
    }

    private static void removeEntry(Properties journal, String id) {
        new ArrayList<>(journal.stringPropertyNames()).stream().filter(key -> key.startsWith(id + ".")).forEach(journal::remove);
    }
}
