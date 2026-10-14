package net.tfminecraft.musicalinstruments.studio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.tfminecraft.musicalinstruments.InstrumentPlugin;
import net.tfminecraft.musicalinstruments.managers.InstrumentManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Reviewed recording identity and independent ownership publication regressions. */
class StudioOwnershipReviewTest {
    @TempDir Path root;

    record Rig(StudioService studio, Player player, Runnable tick, ExecutorService writer, StudioStore store, StudioIdentity.Token token, java.util.logging.Logger logger, InstrumentPlugin plugin, Server server) {}

    Rig rig(UUID account, UUID character, TestIdentity identity) throws IOException {
        var plugin = mock(InstrumentPlugin.class); var server = mock(Server.class); var scheduler = mock(BukkitScheduler.class);
        var logger=mock(java.util.logging.Logger.class);
        when(plugin.getServer()).thenReturn(server); when(plugin.getLogger()).thenReturn(logger);
        when(server.getScheduler()).thenReturn(scheduler);
        var tick = new AtomicReference<Runnable>();
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L))).thenAnswer(call -> { tick.set(call.getArgument(1)); return mock(BukkitTask.class); });
        var player = mock(Player.class); var inventory = mock(PlayerInventory.class); var world = mock(World.class);
        when(player.getUniqueId()).thenReturn(account); when(player.getName()).thenReturn("Account name");
        when(player.getInventory()).thenReturn(inventory); when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(server.getPlayer(account)).thenReturn(player);
        var instruments = mock(InstrumentManager.class); when(instruments.getInstrument(any())).thenReturn("lute");
        var token = new StudioIdentity.Token(new StudioIdentity.Owner(account, character), 1);
        identity.sessions.put(account, token);
        var store = new StudioStore(root); var writer = Executors.newSingleThreadExecutor();
        var studio = new StudioService(plugin, instruments, store, new StudioSettings(4, 40, 20, 60, 8, 32, 2, 0, 2, 0, 0),
                StudioStation.none(), mock(DiscItems.class), writer, identity);
        return new Rig(studio, player, () -> tick.get().run(), writer, store, token, logger, plugin, server);
    }

    // F1: recordings are keyed by account, requireIdle looks them up by character.
    @Test void desiredSecondRecordIsRefusedWhileRecordingWithADistinctCharacter() throws Exception {
        UUID account = UUID.randomUUID();
        Rig rig = rig(account, UUID.randomUUID(), new TestIdentity());
        try {
            rig.studio().create(rig.player(), "First", false);
            Project first=rig.studio().requireProject(rig.player());
            rig.studio().record(rig.player(), 1);
            rig.tick().run();rig.tick().run();rig.tick().run();rig.studio().capture(rig.player(),"lute","kept_first_take",1,1);
            var error = assertThrows(IllegalArgumentException.class, () -> rig.studio().record(rig.player(), 2));
            assertEquals("Stop the recording with /music stop first", error.getMessage());
            assertThrows(IllegalArgumentException.class,()->rig.studio().deleteProject(rig.player(),first));
            assertEquals(1,rig.studio().activity(rig.player()).track());assertEquals(1,rig.studio().activity(rig.player()).notes());
            rig.studio().stop(rig.player());rig.writer().submit(()->{}).get(3,TimeUnit.SECONDS);rig.tick().run();
            assertEquals("kept_first_take",rig.store().project(rig.token().owner().character(),first.song().id()).pending().notes().getFirst().sound());
        } finally { rig.studio().close(); }
    }

    @Test void controlSecondRecordIsRefusedWhenCharacterEqualsAccount() throws Exception {
        UUID account = UUID.randomUUID();
        Rig rig = rig(account, account, new TestIdentity());
        try {
            rig.studio().create(rig.player(), "First", false);
            rig.studio().record(rig.player(), 1);
            assertThrows(IllegalArgumentException.class, () -> rig.studio().record(rig.player(), 2));
        } finally { rig.studio().close(); }
    }


    // F2: an incomplete earlier estate is not a reservation against a later estate of its recipient.
    StudioIdentity.Owner owner() { return new StudioIdentity.Owner(UUID.randomUUID(), UUID.randomUUID()); }

    UUID interruptedFirstEstate(StudioStore store, StudioOwnership ownership, UUID d1, StudioIdentity.Owner a, StudioIdentity.Owner b) throws Exception {
        Project project = new Project(new Song(UUID.randomUUID(), a.account(), "Author", "Chain", List.of()), null, 100, false);
        store.saveOwned(a, project);
        UUID id = project.song().id();
        Path sourceA = store.projectFile(a.character(), id);
        byte[] sourceBytes = Files.readAllBytes(sourceA);
        try (var disk = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            // Stubbing a CALLS_REAL_METHODS static runs the real delete once; restore the source as the suite does.
            disk.when(() -> DurableFiles.delete(sourceA)).thenThrow(new IOException("temporary disk failure"));
            Files.write(sourceA, sourceBytes);
            assertThrows(IOException.class, () -> ownership.transfer(d1, StudioOwnershipTest.decision(d1, "one", a, b), a, b, () -> true));
        }
        assertEquals("published", StudioOwnership.read(root.resolve("ownership/estates").resolve(d1 + ".xml")).getProperty(id + ".state"));
        assertTrue(Files.exists(sourceA)); assertTrue(Files.exists(store.projectFile(b.character(), id)));
        return id;
    }


    @Test void desiredLaterEstateIsBlockedWhileAnEarlierEstateStillOwnsTheProject() throws Exception {
        StudioStore store = new StudioStore(root); StudioOwnership ownership = new StudioOwnership(store);
        var a = owner(); var b = owner(); var c = owner(); UUID d1 = UUID.randomUUID(); UUID d2 = UUID.randomUUID();
        UUID id = interruptedFirstEstate(store, ownership, d1, a, b);
        assertThrows(StudioOwnership.Conflict.class, () -> ownership.transfer(d2, StudioOwnershipTest.decision(d2, "one", b, c), b, c, () -> true));
        assertFalse(Files.exists(store.projectFile(c.character(), id)));
        // d1 resumes, then the genuine chain completes.
        assertEquals(List.of(id.toString()), ownership.transfer(d1, StudioOwnershipTest.decision(d1, "one", a, b), a, b, () -> true));
        assertEquals(List.of(id.toString()), ownership.transfer(d2, StudioOwnershipTest.decision(d2, "one", b, c), b, c, () -> true));
        assertFalse(Files.exists(store.projectFile(b.character(), id)));
        assertTrue(Files.exists(store.projectFile(c.character(), id)));
    }

    // F3: one account's held migration conflict blocks every player's studio.

    @Test void desiredDeleteIsRefusedWhileRecordingWithADistinctCharacter() throws Exception {
        UUID account = UUID.randomUUID();
        Rig rig = rig(account, UUID.randomUUID(), new TestIdentity());
        try {
            rig.studio().create(rig.player(), "First", false);
            Project first = rig.studio().requireProject(rig.player());
            rig.studio().record(rig.player(), 1);
            assertThrows(IllegalArgumentException.class, () -> rig.studio().deleteProject(rig.player(), first));
            assertThrows(IllegalArgumentException.class, () -> rig.studio().selectProject(rig.player(), UUID.randomUUID()));
        } finally { rig.studio().close(); }
    }

    @Test void desiredRetainedReceiptsAlsoRespectAnotherEstatesReservation() throws Exception {
        StudioStore store = new StudioStore(root); StudioOwnership ownership = new StudioOwnership(store);
        var a = owner(); var b = owner(); var c = owner(); UUID d1 = UUID.randomUUID(); UUID d2 = UUID.randomUUID();
        UUID id = interruptedFirstEstate(store, ownership, d1, a, b);
        assertThrows(StudioOwnership.Conflict.class, () -> ownership.retainedReceipts(d2, StudioOwnershipTest.decision(d2, "one waiting", b, c), b));
        assertTrue(Files.exists(store.projectFile(b.character(), id)));
    }

    @Test void desiredMigrationConflictStaysWithItsAccount() throws Exception {
        List<UUID> sorted = new java.util.ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        sorted.sort(java.util.Comparator.comparing(UUID::toString));
        final UUID x = sorted.get(0), z = sorted.get(1), y = sorted.get(2);
        StudioStore store = new StudioStore(root);
        Project legacyX = new Project(new Song(UUID.randomUUID(), x, "X", "Legacy X", List.of()), null, 100, false);
        Project legacyZ = new Project(new Song(UUID.randomUUID(), z, "Z", "Legacy Z", List.of()), null, 100, false);
        Project legacyY = new Project(new Song(UUID.randomUUID(), y, "Y", "Legacy Y", List.of()), null, 100, false);
        for (Project project : List.of(legacyX, legacyZ, legacyY)) {
            UUID account = project.song().owner();
            store.save(project);
            Path legacy = root.resolve("projects").resolve(account.toString());
            Files.createDirectories(legacy.getParent()); Files.move(store.directory(account), legacy);
        }
        store.saveOwned(new StudioIdentity.Owner(x, x), new Project(new Song(legacyX.song().id(), x, "X", "Edited after migration", List.of()), null, 100, false));
        store.saveOwned(new StudioIdentity.Owner(z, z), new Project(new Song(legacyZ.song().id(), z, "Z", "Edited after migration", List.of()), null, 100, false));
        var identity = new TestIdentity(); identity.defaultHeirs = false;
        identity.heirs.put(x, new StudioIdentity.Owner(x, x)); identity.heirs.put(z, new StudioIdentity.Owner(z, z));
        UUID yCharacter = UUID.randomUUID(); identity.heirs.put(y, new StudioIdentity.Owner(y, yCharacter));
        var ownership = new StudioOwnership(store);
        var error = assertThrows(StudioOwnership.Conflict.class, () -> ownership.migrate(identity));
        assertEquals(1, error.getSuppressed().length);
        assertTrue(Files.exists(store.projectFile(yCharacter, legacyY.song().id())), "later account still migrates");
        assertThrows(StudioOwnership.Conflict.class, () -> ownership.requireWritable(x));
        ownership.requireWritable(yCharacter);
        Rig rig = rig(y, yCharacter, identity);
        try {
            assertEquals(List.of(legacyY.song().id()), rig.studio().listProjects(rig.player()).stream().map(ProjectSummary::id).toList());
            rig.studio().create(rig.player(), "Unrelated", false);
            rig.studio().token(rig.player());rig.studio().listProjects(rig.player());
            verify(rig.logger(),times(1)).warning(argThat((String text)->text.contains(x.toString())&&text.contains(z.toString())));
        } finally { rig.studio().close(); }
    }

    @Test void desiredUnrelatedIncompleteEstateDoesNotBlockAnotherTransfer() throws Exception {
        StudioStore store = new StudioStore(root); StudioOwnership ownership = new StudioOwnership(store);
        var a = owner(); var b = owner(); var d = owner(); var e = owner(); UUID d1 = UUID.randomUUID(); UUID d3 = UUID.randomUUID();
        interruptedFirstEstate(store, ownership, d1, a, b);
        Project other = new Project(new Song(UUID.randomUUID(), d.account(), "Other", "Unrelated", List.of()), null, 100, false);
        store.saveOwned(d, other);
        assertEquals(List.of(other.song().id().toString()), ownership.transfer(d3, StudioOwnershipTest.decision(d3, "one", d, e), d, e, () -> true));
    }

    @Test void desiredUnrecognizedSourceFileStillBlocksWithItsOwnReason() throws Exception {
        StudioStore store = new StudioStore(root); StudioOwnership ownership = new StudioOwnership(store);
        var a = owner(); var b = owner(); UUID death = UUID.randomUUID();
        Files.createDirectories(store.directory(a.character()));
        Files.writeString(store.directory(a.character()).resolve("0-not-a-uuid.yml"), "unreadable identity");
        var error = assertThrows(StudioOwnership.Conflict.class, () -> ownership.transfer(death, StudioOwnershipTest.decision(death, "one", a, b), a, b, () -> true));
        assertTrue(error.getMessage().startsWith("Unrecognized project file"), error.getMessage());
    }

    java.util.Map<Path,String> snapshot() throws IOException {
        var result=new java.util.TreeMap<Path,String>();try(var files=Files.walk(root)) {
            for(Path file:files.filter(Files::isRegularFile).toList())result.put(root.relativize(file),java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
        }return result;
    }
    @Test void publishedMissingTargetNeverRepublishesButUntouchedPlannedMissingTargetIsSafe() throws Exception {
        var store=new StudioStore(root);var ownership=new StudioOwnership(store);var a=owner();var b=owner();UUID death=UUID.randomUUID();
        UUID id=interruptedFirstEstate(store,ownership,death,a,b);Files.delete(store.projectFile(b.character(),id));var before=snapshot();
        assertThrows(StudioOwnership.Conflict.class,()->ownership.transfer(death,StudioOwnershipTest.decision(death,"one",a,b),a,b,()->true));assertEquals(before,snapshot());
        var c=owner();var d=owner();UUID untouched=UUID.randomUUID();var project=new Project(new Song(UUID.randomUUID(),c.account(),"Author","Untouched",List.of()),null,100,false);store.saveOwned(c,project);
        int[] checks={0};assertThrows(IOException.class,()->ownership.transfer(untouched,StudioOwnershipTest.decision(untouched,"one",c,d),c,d,()->++checks[0]==1));
        assertFalse(Files.exists(store.projectFile(d.character(),project.song().id())));
        assertEquals(List.of(project.song().id().toString()),ownership.transfer(untouched,StudioOwnershipTest.decision(untouched,"one",c,d),c,d,()->true));
    }
    @Test void pendingMigrationAlsoReservesAProjectsEstateAndMissingPublishedTargetStaysHeld() throws Exception {
        var store=new StudioStore(root);var ownership=new StudioOwnership(store);var a=owner();var b=owner();UUID id=UUID.randomUUID();
        var project=new Project(new Song(id,a.account(),"Author","Legacy",List.of()),null,100,false);store.save(project);
        Path folder=root.resolve("projects/"+a.account());Files.createDirectories(folder.getParent());Files.move(store.directory(a.account()),folder);
        var identity=new TestIdentity();identity.defaultHeirs=false;identity.heirs.put(a.account(),a);
        Path journal=root.resolve("ownership/migration/"+a.account()+".xml");
        try(var disk=mockStatic(DurableFiles.class,CALLS_REAL_METHODS)) {
            disk.when(()->DurableFiles.write(eq(journal),any(byte[].class),eq(true))).thenAnswer(call->{call.callRealMethod();if(new String((byte[])call.getArgument(1),java.nio.charset.StandardCharsets.UTF_8).contains(">published<"))throw new IOException("after durable migration publication");return null;});
            assertThrows(IOException.class,()->ownership.migrate(identity));
        }
        var before=snapshot();UUID death=UUID.randomUUID();
        assertThrows(StudioOwnership.Conflict.class,()->ownership.transfer(death,StudioOwnershipTest.decision(death,"one",a,b),a,b,()->true));
        assertThrows(StudioOwnership.Conflict.class,()->ownership.retainedReceipts(death,StudioOwnershipTest.decision(death,"one waiting",a,b),a));assertEquals(before,snapshot());
        Files.delete(store.projectFile(a.character(),id));before=snapshot();
        assertThrows(StudioOwnership.Conflict.class,()->ownership.migrate(identity));assertEquals(before,snapshot());
    }
    @Test void journalLocksValidateCurrentBytesEvenWhenSizeMtimeAndFileKeyAreUnchanged() throws Exception {
        var store=new StudioStore(root);var ownership=new StudioOwnership(store);var a=owner();var b=owner();var c=owner();UUID death=UUID.randomUUID();
        var project=new Project(new Song(UUID.randomUUID(),a.account(),"Author","History",List.of()),null,100,false);store.saveOwned(a,project);
        ownership.transfer(death,StudioOwnershipTest.decision(death,"one",a,b),a,b,()->true);
        Path file=root.resolve("ownership/estates/"+death+".xml");byte[] completed=Files.readAllBytes(file);var attributes=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class);
        ownership.requireWritable(a.character());ownership.requireWritable(b.character());
        byte[] reopened=new String(completed,java.nio.charset.StandardCharsets.UTF_8).replace(">moved<",">published<").replace("Studio ownership format 1","Studio ownership fmt1").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(completed.length,reopened.length);Files.write(file,reopened);Files.setLastModifiedTime(file,attributes.lastModifiedTime());
        assertEquals(attributes.fileKey(),Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class).fileKey());var before=snapshot();
        assertThrows(StudioOwnership.Conflict.class,()->ownership.requireWritable(a.character()));assertThrows(StudioOwnership.Conflict.class,()->ownership.requireWritable(b.character()));
        UUID next=UUID.randomUUID();assertThrows(StudioOwnership.Conflict.class,()->ownership.transfer(next,StudioOwnershipTest.decision(next,"one",b,c),b,c,()->true));assertEquals(before,snapshot());
        byte[] invalid=new String(reopened,java.nio.charset.StandardCharsets.UTF_8).replace("<entry key=\"format\">1</entry>","<entry key=\"format\">9</entry>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(reopened.length,invalid.length);Files.write(file,invalid);Files.setLastModifiedTime(file,attributes.lastModifiedTime());before=snapshot();
        for(int attempt=0;attempt<2;attempt++)assertThrows(StudioOwnership.Conflict.class,()->ownership.requireWritable(c.character()));assertEquals(before,snapshot());
        Files.write(file,completed);Files.setLastModifiedTime(file,attributes.lastModifiedTime());ownership.requireWritable(a.character());ownership.requireWritable(b.character());
        assertEquals(List.of(project.song().id().toString()),ownership.transfer(next,StudioOwnershipTest.decision(next,"one",b,c),b,c,()->true));
    }
    @Test void otherMalformedOrMisnamedJournalsHoldBeforeAnyNewBackupOrPublication() throws Exception {
        var store=new StudioStore(root);var ownership=new StudioOwnership(store);var a=owner();var b=owner();UUID death=UUID.randomUUID();
        var project=new Project(new Song(UUID.randomUUID(),a.account(),"Author","Source",List.of()),null,100,false);store.saveOwned(a,project);
        Path file=root.resolve("ownership/estates/"+UUID.randomUUID()+".xml");Files.createDirectories(file.getParent());
        var base=new java.util.Properties();base.setProperty("format","1");base.setProperty("kind","estate");base.setProperty("death",file.getFileName().toString().replace(".xml",""));base.setProperty("from-account",a.account().toString());base.setProperty("from-character",a.character().toString());
        for(String fault:List.of("death","from-character","kind","unreadable","wrong-folder")) {
            var changed=new java.util.Properties();changed.putAll(base);
            if(fault.equals("death"))changed.setProperty("death",UUID.randomUUID().toString());
            if(fault.equals("from-character"))changed.remove("from-character");
            if(fault.equals("kind"))changed.setProperty("kind","migration");
            if(fault.equals("wrong-folder")){changed.setProperty("kind","migration");changed.remove("from-account");}
            StudioOwnership.write(file,changed);if(fault.equals("unreadable"))Files.writeString(file,"not XML");var before=snapshot();
            assertThrows(IOException.class,()->ownership.transfer(death,StudioOwnershipTest.decision(death,"one",a,b),a,b,()->true),fault);
            assertThrows(IOException.class,()->ownership.retainedReceipts(death,StudioOwnershipTest.decision(death,"one waiting",a,b),a),fault);assertEquals(before,snapshot(),fault);
        }
    }
    @Test void invalidLegacySelectedPointerDoesNotHoldAnAssetButUnprovenPointerAndOtherHoldsDo() throws Exception {
        var store=new StudioStore(root);var ownership=new StudioOwnership(store);var a=owner();var b=owner();
        var project=new Project(new Song(UUID.randomUUID(),a.account(),"Author","Legacy",List.of()),null,100,false);store.save(project);
        Path folder=root.resolve("projects/"+a.account());Files.createDirectories(folder.getParent());Files.move(store.directory(a.account()),folder);Path pointer=folder.resolve("selected");Files.writeString(pointer,"bad pointer");
        var identity=new TestIdentity();identity.defaultHeirs=false;identity.heirs.put(a.account(),a);ownership.migrate(identity);ownership.requireEstateReady(a);
        Path file=root.resolve("ownership/migration/"+a.account()+".xml");var original=StudioOwnership.read(file);String bytes=java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(pointer));assertEquals(bytes,original.getProperty("selected"));
        for(String fault:List.of("reason","missing","base64","valid-id","source-missing","source-edited","other-hold")) {
            var changed=new java.util.Properties();changed.putAll(original);Files.writeString(pointer,"bad pointer");
            switch(fault) {
                case "reason"->changed.setProperty("held.selected","Unknown asset hold");case "missing"->changed.remove("selected");case "base64"->changed.setProperty("selected","invalid base64!");
                case "valid-id"->{Files.writeString(pointer,project.song().id().toString());changed.setProperty("selected",java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(pointer)));}
                case "source-missing"->Files.delete(pointer);case "source-edited"->Files.writeString(pointer,"different pointer");case "other-hold"->changed.setProperty("held.future","Retained opaque asset");
            }
            StudioOwnership.write(file,changed);var before=snapshot();assertThrows(StudioOwnership.Conflict.class,()->ownership.requireEstateReady(a),fault);assertEquals(before,snapshot());
        }
        Files.writeString(pointer,"bad pointer");StudioOwnership.write(file,original);UUID death=UUID.randomUUID();
        assertEquals(List.of(project.song().id().toString()),ownership.transfer(death,StudioOwnershipTest.decision(death,"one",a,b),a,b,()->true));
        assertEquals(bytes,StudioOwnership.read(file).getProperty("selected"));assertEquals("bad pointer",Files.readString(pointer));
    }
    @Test void unattributedHeaderAndHashConsistentLegacyOwnerChangesAreNotCachedAsUnrelated() throws Exception {
        var store=new StudioStore(root);var ownership=new StudioOwnership(store);var a=owner();var b=owner();
        var project=new Project(new Song(UUID.randomUUID(),a.account(),"Author","Legacy",List.of()),null,100,false);store.save(project);
        Path folder=root.resolve("projects/"+a.account());Files.createDirectories(folder.getParent());Files.move(store.directory(a.account()),folder);
        var identity=new TestIdentity();identity.defaultHeirs=false;identity.heirs.put(a.account(),a);ownership.migrate(identity);
        Path file=root.resolve("ownership/migration/"+a.account()+".xml");var valid=StudioOwnership.read(file);String id=project.song().id().toString();
        for(boolean hasEntry:List.of(false,true)) {
            var changed=new java.util.Properties();changed.putAll(valid);changed.remove("from-account");
            Path unknown=file.resolveSibling(hasEntry?"unrecognized-"+"a".repeat(64)+".xml":"unknown.xml");
            if(!hasEntry)for(String key:List.copyOf(changed.stringPropertyNames()))if(key.startsWith(id+"."))changed.remove(key);
            StudioOwnership.write(unknown,changed);var before=snapshot();
            assertThrows(StudioOwnership.Conflict.class,()->ownership.requireWritable(b.character()));assertEquals(before,snapshot());Files.delete(unknown);
        }
        var changed=new java.util.Properties();changed.putAll(valid);String author=UUID.randomUUID().toString();
        for(String part:List.of("before","after")) {
            var raw=RawYaml.read(java.util.Base64.getDecoder().decode(changed.getProperty(id+"."+part)));raw.put("owner",author);byte[] bytes=RawYaml.bytes(raw);
            changed.setProperty(id+"."+part,java.util.Base64.getEncoder().encodeToString(bytes));changed.setProperty(id+"."+part+"-hash",StudioOwnership.hash(bytes));
        }
        StudioOwnership.write(file,changed);var before=snapshot();assertThrows(StudioOwnership.Conflict.class,()->ownership.requireWritable(b.character()));assertEquals(before,snapshot());
    }
    @Test void hourlyRetryUsesFixedHeirAfterIsolatedFailureWithoutRepeatedActionAttempts() throws Exception {
        var a=owner();var b=owner();var identity=new TestIdentity();identity.defaultHeirs=false;identity.heirs.put(a.account(),a);
        var store=new StudioStore(root);var project=new Project(new Song(UUID.randomUUID(),a.account(),"Author","Legacy",List.of()),null,100,false);store.save(project);
        Path folder=root.resolve("projects/"+a.account());Files.createDirectories(folder.getParent());Files.move(store.directory(a.account()),folder);
        var conflict=new Project(new Song(project.song().id(),a.account(),"Author","Conflicting current copy",List.of()),null,100,false);store.saveOwned(a,conflict);
        var rig=rig(b.account(),b.character(),identity);
        try {
            rig.studio().create(rig.player(),"Independent",false);rig.studio().token(rig.player());rig.studio().listProjects(rig.player());
            verify(rig.logger(),times(1)).warning(anyString());
            try(var estate=mockStatic(StudioEstate.class)){rig.studio().startOwnership(mock(StudioMenu.class));}
            var callbacks=org.mockito.ArgumentCaptor.forClass(Runnable.class);verify(rig.server().getScheduler()).runTaskTimer(eq(rig.plugin()),callbacks.capture(),eq(1L),eq(72_000L));
            callbacks.getValue().run();verify(rig.logger(),times(2)).warning(anyString());
            Files.delete(store.projectFile(a.character(),project.song().id()));identity.heirs.put(a.account(),b);callbacks.getValue().run();
            assertEquals(project,store.project(a.character(),project.song().id()));assertFalse(Files.exists(store.projectFile(b.character(),project.song().id())));
            new StudioOwnership(store).requireWritable(a.character());verify(rig.logger(),times(2)).warning(anyString());
        } finally {rig.studio().close();}
    }
}
