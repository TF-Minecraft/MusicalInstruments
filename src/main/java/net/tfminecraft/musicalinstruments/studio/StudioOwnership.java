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
    private record Locks(String digest, java.util.Set<UUID> characters, java.util.Set<String> projects) {}
    private final java.util.Map<Path, Locks> lockCache = new java.util.HashMap<>();
    // A reconciliation snapshot only identifies operations with no effects. Own journal writes are reread,
    // and an operation checks all current journals before its first backup, write or publication.
    private boolean pass;
    private java.util.Map<Path, Object> passLocks;
    private final java.util.Set<Path> passWrites = new java.util.HashSet<>();
    private record Readiness(String digest, boolean complete, String toCharacter, boolean otherHeld, String heldSelected, String selected) {}
    private final java.util.Map<Path, Readiness> readiness = new java.util.HashMap<>();
    StudioOwnership(StudioStore store) { this.store = store; this.root = store.root(); }

    void migrate(StudioIdentity identity) throws IOException {
        if (!identity.heirsReady()) return;
        Path legacy = root.resolve("projects");
        if (!Files.isDirectory(legacy)) return;
        IOException failure = null;
        for (Path folder : files(legacy)) {
            try { migrate(identity, folder); }
            catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        }
        if (failure != null) throw failure;
    }

    private void migrate(StudioIdentity identity, Path folder) throws IOException {
        if (!Files.isDirectory(folder)) { holdUnrecognized(folder); return; }
        UUID account;
        try { account = UUID.fromString(folder.getFileName().toString()); }
        catch (IllegalArgumentException ex) { holdUnrecognized(folder); return; }
        Path journalFile = root.resolve("ownership/migration").resolve(account + ".xml");
        Properties journal = read(journalFile);
        StudioIdentity.Owner to;
        if (journal.isEmpty()) {
            to = identity.heir(account).orElse(null);
            if (to == null) return;
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
            journal(journalFile, journal); // Entire inventory/backup precedes the first publication.
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
            journal(journalFile, journal);
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
        journal(file, journal);
    }

    List<String> transfer(UUID death, String exactDecision, StudioIdentity.Owner from, StudioIdentity.Owner to,
                          BooleanSupplier guard) throws IOException {
        return transfer(death, exactDecision, from, to, guard, false);
    }

    List<String> transferDuringPass(UUID death, String exactDecision, StudioIdentity.Owner from, StudioIdentity.Owner to,
                                    BooleanSupplier guard) throws IOException {
        return transfer(death, exactDecision, from, to, guard, true);
    }

    private List<String> transfer(UUID death, String exactDecision, StudioIdentity.Owner from, StudioIdentity.Owner to,
                                  BooleanSupplier guard, boolean usePass) throws IOException {
        if (from.equals(to)) throw new Conflict("Estate recipient is the testator");
        long revision = validateDecision(exactDecision, death, from, to);
        Path file = root.resolve("ownership/estates").resolve(death + ".xml");
        Properties journal = read(file);
        boolean changed = journal.isEmpty();
        if (journal.isEmpty()) {
            journal.setProperty("format", "1");
            journal.setProperty("kind", "estate");
            journal.setProperty("death", death.toString());
            journal.setProperty("from-account", from.account().toString());
            journal.setProperty("from-character", from.character().toString());
        }
        if (!from.equals(owner(journal, "from")))
            throw new Conflict("Estate journal identity differs from this death");
        Reservation reservation = requireUnreserved(file, journal, store.directory(from.character()), usePass);
        for (String id : ids(journal)) {
            if ("moved".equals(journal.getProperty(id + ".state"))) continue;
            if (!exactDecision.equals(journal.getProperty(id + ".decision"))) {
                // Only a plan with no published output can be superseded by a new decision.
                if (!"planned".equals(journal.getProperty(id + ".state"))
                        || Files.exists(path(journal.getProperty(id + ".target"))))
                    throw new Conflict("Stale partial transfer needs staff recovery: " + id);
                removeEntry(journal, id); changed = true;
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
            reservation.beforeEffect();
            plan(journal, id, source, store.projectFile(to.character(), projectId), before, to);
            journal.setProperty(id + ".from-account", from.account().toString());
            journal.setProperty(id + ".from-character", from.character().toString());
            journal.setProperty(id + ".decision", exactDecision);
            journal.setProperty(id + ".revision", Long.toString(revision));
            changed = true;
        }
        if (changed) { reservation.beforeEffect(); journal(file, journal); }
        for (String id : ids(journal)) {
            if (!"moved".equals(journal.getProperty(id + ".state"))) { reservation.beforeEffect(); apply(file, journal, id, guard, true); }
        }
        return ids(journal).stream().filter(id -> "moved".equals(journal.getProperty(id + ".state"))).toList();
    }

    List<String> retainedReceipts(UUID death, String exactDecision, StudioIdentity.Owner from) throws IOException {
        return retainedReceipts(death, exactDecision, from, false);
    }

    List<String> retainedReceiptsDuringPass(UUID death, String exactDecision, StudioIdentity.Owner from) throws IOException {
        return retainedReceipts(death, exactDecision, from, true);
    }

    private List<String> retainedReceipts(UUID death, String exactDecision, StudioIdentity.Owner from, boolean usePass) throws IOException {
        Path file = root.resolve("ownership/estates").resolve(death + ".xml");
        Properties journal = read(file);
        boolean changed = false;
        if (!journal.isEmpty() && !from.equals(owner(journal, "from")))
            throw new Conflict("Estate journal identity differs from this death");
        Reservation reservation = requireUnreserved(file, journal, store.directory(from.character()), usePass);
        for (Path source : projects(store.directory(from.character()))) {
            String id = source.getFileName().toString().replace(".yml", "");
            if ("moved".equals(journal.getProperty(id + ".state")))
                throw new Conflict("Transferred project reappeared at its previous owner: " + id);
        }
        for (String id : ids(journal)) {
            if ("moved".equals(journal.getProperty(id + ".state"))) continue;
            if (!exactDecision.equals(journal.getProperty(id + ".decision"))) {
                if (!"planned".equals(journal.getProperty(id + ".state")) || Files.exists(path(journal.getProperty(id + ".target"))))
                    throw new Conflict("Stale partial transfer needs staff recovery: " + id);
                removeEntry(journal, id); changed = true;
            }
        }
        if (changed) { reservation.beforeEffect(); journal(file, journal); }
        return ids(journal).stream().filter(id -> "moved".equals(journal.getProperty(id + ".state"))).toList();
    }

    private static long validateDecision(String decision, UUID death, StudioIdentity.Owner from, StudioIdentity.Owner to) throws IOException {
        var suffix = java.util.regex.Pattern.compile(", revision=(0|[1-9][0-9]*)]$").matcher(decision);
        if (!decision.startsWith("EstateDecision[deathId=" + death + ", testator=" + reference(from) + ", diedAt=")
                || !decision.contains(", property=Character[ref=" + reference(to) + "], guildLocks=") || !suffix.find())
            throw new Conflict("Project plan differs from its captured full decision");
        try { return Long.parseLong(suffix.group(1)); }
        catch (NumberFormatException ex) { throw new Conflict("Unsupported project estate revision"); }
    }
    private static String reference(StudioIdentity.Owner owner) {
        return "CharacterRef[ownerId=" + owner.account() + ", characterId=" + owner.character() + "]";
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
        if (Files.exists(source) && !hash(Files.readAllBytes(source)).equals(journal.getProperty(id + ".before-hash")))
            throw new Conflict("Project changed after ownership planning: " + source);
        if (!Files.exists(source) && "planned".equals(journal.getProperty(id + ".state")))
            throw new Conflict("Planned source project disappeared: " + source);
        if (!Files.exists(source) && !Files.exists(target)) throw new Conflict("Both published project copies disappeared");
        if ("published".equals(journal.getProperty(id + ".state")) && !Files.exists(target))
            throw new Conflict("Published project target disappeared; staff recovery required: " + target);
        if (Files.exists(target)) {
            if (!hash(Files.readAllBytes(target)).equals(journal.getProperty(id + ".after-hash")))
                throw new Conflict("Recipient project conflicts: " + target);
        } else {
            if (!guard.getAsBoolean()) throw new IOException("Estate decision changed before publication");
            DurableFiles.write(target, Base64.getDecoder().decode(journal.getProperty(id + ".after")), false);
        }
        journal.setProperty(id + ".state", "published");
        journal(file, journal);
        if (removeSource) {
            if (!guard.getAsBoolean()) throw new IOException("Estate decision changed before source removal");
            DurableFiles.delete(source);
        }
        journal.setProperty(id + ".state", "moved");
        journal(file, journal);
    }

    void requireWritable(UUID character) throws IOException {
        for (String kind : List.of("migration", "estates")) for (Path file : files(root.resolve("ownership").resolve(kind)))
            if (locks(file).characters().contains(character)) throw new Conflict("Studio ownership publication needs recovery: " + file);
    }

    // Copies at either end of an unfinished publication still belong to that operation.
    private Reservation requireUnreserved(Path current, Properties journal, Path folder, boolean usePass) throws IOException {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (String id : ids(journal)) if (!"moved".equals(journal.getProperty(id + ".state"))) wanted.add(id);
        for (Path source : projects(folder)) wanted.add(source.getFileName().toString().replace(".yml", ""));
        boolean snapshot = usePass && pass;
        Reservation reservation = new Reservation(current, wanted, !snapshot);
        reservation.check(snapshot);
        return reservation;
    }

    // A pass may decide that an operation has nothing to do from its snapshot. Before the operation's
    // first backup, journal write or publication, every journal is read again without the snapshot.
    private final class Reservation {
        private final Path current; private final java.util.Set<String> wanted; private boolean exact;
        Reservation(Path current, java.util.Set<String> wanted, boolean exact) { this.current = current; this.wanted = wanted; this.exact = exact; }

        void beforeEffect() throws IOException {
            if (exact) return;
            exact = true;
            check(false);
        }

        private void check(boolean snapshot) throws IOException {
            for (Path file : snapshot ? reservations() : listed()) {
                Locks pending = snapshot ? reserved(file) : locks(file);
                if (file.equals(current)) continue;
                for (String id : pending.projects())
                    if (wanted.contains(id)) throw new Conflict("Project is still pending in another ownership journal: " + id);
            }
        }
    }

    private List<Path> listed() throws IOException {
        List<Path> listed = new ArrayList<>();
        for (String kind : List.of("migration", "estates")) listed.addAll(files(root.resolve("ownership").resolve(kind)));
        return listed;
    }

    void beginPass() { pass = true; passLocks = null; passWrites.clear(); }

    void endPass() { pass = false; passLocks = null; passWrites.clear(); }

    private java.util.Collection<Path> reservations() throws IOException {
        List<Path> listed = passLocks != null ? new ArrayList<>(passLocks.keySet()) : listed();
        if (passLocks == null) {
            var taken = new java.util.LinkedHashMap<Path, Object>();
            for (Path file : listed) {
                try { taken.put(file, locks(file)); }
                catch (IOException ex) { taken.put(file, ex); }
            }
            passLocks = taken;
        }
        var all = new java.util.LinkedHashSet<>(listed); all.addAll(passWrites);
        return all;
    }

    private Locks reserved(Path file) throws IOException {
        if (passWrites.contains(file)) return locks(file);
        Object known = passLocks.get(file);
        if (known instanceof Locks found) return found;
        IOException failure = (IOException) known;
        throw failure instanceof Conflict ? new Conflict(failure.getMessage()) : new IOException(failure.getMessage(), failure);
    }

    private void journal(Path file, Properties journal) throws IOException {
        if (pass) passWrites.add(file);
        write(file, journal);
    }

    private Locks locks(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String digest = hash(bytes);
        Locks cached = lockCache.get(file);
        if (cached == null || !digest.equals(cached.digest())) {
            Properties journal = read(file, bytes);
            java.util.Set<UUID> pending = new java.util.HashSet<>();
            java.util.Set<String> projects = new java.util.HashSet<>();
            for (String id : ids(journal)) if (!"moved".equals(journal.getProperty(id + ".state"))) {
                projects.add(id);
                pending.add(owner(journal, id + ".to").character());
                if ("estate".equals(journal.getProperty("kind"))) pending.add(owner(journal, "from").character());
            }
            cached = new Locks(digest, java.util.Set.copyOf(pending), java.util.Set.copyOf(projects));
            lockCache.put(file, cached);
        }
        return cached;
    }

    List<String> holdings(UUID character) throws IOException {
        return projects(store.directory(character)).stream().map(file -> file.getFileName().toString().replace(".yml", "")).toList();
    }

    void requireEstateReady(StudioIdentity.Owner from) throws IOException {
        Path legacy = root.resolve("projects").resolve(from.account().toString());
        if (projects(legacy).isEmpty()) return;
        Readiness journal = readiness(root.resolve("ownership/migration").resolve(from.account() + ".xml"));
        if (journal == null || !journal.complete())
            throw new IOException("The account's reviewed legacy project migration is not complete");
        if (from.character().toString().equals(journal.toCharacter())
                && (journal.otherHeld() || journal.heldSelected() != null && !invalidSelectionOnly(journal, legacy)))
            throw new Conflict("Legacy projects for this character remain held for staff");
    }

    // Memoize validated journal fields by exact bytes; the live legacy selection is checked separately.
    private Readiness readiness(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        byte[] bytes = Files.readAllBytes(file);
        String digest = hash(bytes);
        Readiness cached = readiness.get(file);
        if (cached != null && cached.digest().equals(digest)) return cached;
        Properties journal = read(file, bytes);
        boolean otherHeld = journal.stringPropertyNames().stream().anyMatch(key -> key.startsWith("held.") && !key.equals("held.selected"));
        cached = new Readiness(digest, "true".equals(journal.getProperty("complete")), journal.getProperty("to-character"), otherHeld,
                journal.getProperty("held.selected"), journal.getProperty("selected"));
        readiness.put(file, cached);
        return cached;
    }

    private boolean invalidSelectionOnly(Readiness journal, Path legacy) throws IOException {
        if (!"Invalid project selection".equals(journal.heldSelected()) || journal.selected() == null) return false;
        byte[] pointer;
        try { pointer = Base64.getDecoder().decode(journal.selected()); }
        catch (IllegalArgumentException malformed) { return false; }
        Path source = legacy.resolve(StudioStore.SELECTED);
        if (!Files.isRegularFile(source) || !java.util.Arrays.equals(pointer, Files.readAllBytes(source))) return false;
        try { UUID.fromString(new String(pointer, StandardCharsets.UTF_8).strip()); return false; }
        catch (IllegalArgumentException invalid) { return true; }
    }

    private Path path(String relative) { return root.resolve(relative).normalize(); }

    private static StudioIdentity.Owner owner(Properties values, String prefix) throws Conflict {
        try { return new StudioIdentity.Owner(UUID.fromString(values.getProperty(prefix + "-account")),
                UUID.fromString(values.getProperty(prefix + "-character"))); }
        catch (IllegalArgumentException | NullPointerException ex) { throw new Conflict("Invalid journal owner"); }
    }

    static Properties read(Path file) throws IOException { return read(file, Files.exists(file) ? Files.readAllBytes(file) : null); }

    private static Properties read(Path file, byte[] journalBytes) throws IOException {
        class Journal extends Properties {
            boolean loading;
            @Override public synchronized Object put(Object key, Object value) {
                if (loading && containsKey(key)) throw new IllegalArgumentException("Duplicate ownership XML key");
                return super.put(key, value);
            }
        }
        Journal result = new Journal();
        if (journalBytes != null) try (var input = new ByteArrayInputStream(journalBytes)) {
            result.loading = true;
            try { result.loadFromXML(input); }
            catch (java.util.InvalidPropertiesFormatException | IllegalArgumentException ex) { throw new Conflict("Unreadable ownership journal"); }
            finally { result.loading = false; }
            if (!"1".equals(result.getProperty("format"))) throw new Conflict("Unsupported ownership journal format");
            String kind = result.getProperty("kind");
            if (!"migration".equals(kind) && !"estate".equals(kind)) throw new Conflict("Unknown ownership journal kind");
            try {
                if (!file.getParent().getFileName().toString().equals("estate".equals(kind) ? "estates" : "migration"))
                    throw new Conflict("Ownership journal kind differs from its path");
                if ("estate".equals(kind)) {
                    owner(result, "from");
                    if (!file.getFileName().toString().equals(UUID.fromString(result.getProperty("death")) + ".xml"))
                        throw new Conflict("Estate death differs from its journal path");
                } else if (result.getProperty("from-account") != null) {
                    UUID account = UUID.fromString(result.getProperty("from-account"));
                    if (!file.getFileName().toString().equals(account + ".xml") || !account.equals(owner(result, "to").account()))
                        throw new Conflict("Legacy account differs from its journal path or reviewed heir");
                } else if (!file.getFileName().toString().matches("unrecognized-[0-9a-f]{64}\\.xml") || !ids(result).isEmpty()) {
                    throw new Conflict("Unattributed ownership journal needs staff review");
                }
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
                    if (!Integer.valueOf(StudioStore.FORMAT).equals(before.get("format"))
                            || !Integer.valueOf(StudioStore.FORMAT).equals(after.get("format")))
                        throw new Conflict("Unsupported project payload schema");
                    if (!id.equals(before.get("id"))) throw new Conflict("Planned source ID differs from its payload");
                    Path expectedSource;
                    if ("estate".equals(kind)) {
                        StudioIdentity.Owner from = owner(result, "from");
                        if (!from.equals(owner(result, id + ".from"))
                                || !from.account().toString().equals(before.get("owner-account"))
                                || !from.character().toString().equals(before.get("owner-character")))
                            throw new Conflict("Planned source authority differs from its payload");
                        if (!Long.toString(validateDecision(result.getProperty(id + ".decision"), UUID.fromString(result.getProperty("death")), from, to))
                                .equals(result.getProperty(id + ".revision"))) throw new Conflict("Project revision differs from its captured decision");
                        expectedSource = Path.of("character-projects", from.character().toString(), id + ".yml");
                    } else {
                        UUID account = UUID.fromString(result.getProperty("from-account"));
                        if (!account.toString().equals(before.get("owner")) || !to.equals(owner(result, "to")))
                            throw new Conflict("Planned legacy authority differs from its payload");
                        expectedSource = Path.of("projects", account.toString(), id + ".yml");
                    }
                    var expected = new java.util.LinkedHashMap<>(before);
                    expected.put("owner-account", to.account().toString()); expected.put("owner-character", to.character().toString());
                    if (!java.util.Arrays.equals(RawYaml.bytes(expected), RawYaml.bytes(after)))
                        throw new Conflict("Ownership plan changes project content or unrelated metadata");
                    if (expectedSource.equals(Path.of("character-projects", to.character().toString(), id + ".yml")))
                        throw new Conflict("Source and destination refer to the same project");
                    if (!expectedSource.equals(Path.of(result.getProperty(id + ".source")))
                            || !Path.of("character-projects", to.character().toString(), id + ".yml").equals(Path.of(result.getProperty(id + ".target"))))
                        throw new Conflict("Project path differs from its planned owner");
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
