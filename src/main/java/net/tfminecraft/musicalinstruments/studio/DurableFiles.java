package net.tfminecraft.musicalinstruments.studio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Ownership publications acknowledge both the file and containing directory. */
final class DurableFiles {
    private DurableFiles() {}

    static void write(Path target, byte[] bytes, boolean replace) throws IOException {
        write(target, bytes, replace, true);
    }

    static void write(Path target, byte[] bytes, boolean replace, boolean strict) throws IOException {
        Path folder = target.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, "studio-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (strict && !replace) {
                // Atomic rename may overwrite even without REPLACE_EXISTING. A hard link cannot.
                try { Files.createLink(target, temp); }
                catch (UnsupportedOperationException ex) { throw new IOException("Durable no-clobber publication unavailable", ex); }
            } else {
                try {
                    if (replace) Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    else Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    if (strict) throw ex;
                    if (replace) Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    else Files.move(temp, target);
                }
            }
            // Earlier failures may have left an existing but unsynced parent link.
            for (Path path = folder; path != null; path = path.getParent()) force(path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void delete(Path target) throws IOException {
        if (Files.deleteIfExists(target)) force(target.toAbsolutePath().getParent());
    }

    static void force(Path folder) throws IOException {
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) { channel.force(true); }
    }
}
