package studio.bookhtml.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Private atomic files on POSIX and NTFS. Unsupported protection or atomic replace fails closed. */
final class PrivateSettingsFiles {
    private static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    private PrivateSettingsFiles() { }

    static void ensureDirectory(Path dir) throws IOException {
        Path parent = dir.getParent();
        if (parent == null || Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("UNSAFE_SETTINGS_PARENT");
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (posix(parent)) Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(DIRECTORY));
            else { Files.createDirectory(dir); restrictAcl(dir); }
        }
        requirePrivate(dir, true);
    }
    static void requirePrivate(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path) || (directory
                ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw new IOException("UNSAFE_SETTINGS_PATH");
        if (posix(path)) {
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(directory ? DIRECTORY : FILE))
                throw new IOException("SETTINGS_PERMISSIONS_NOT_PRIVATE");
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) throw new IOException("PRIVATE_ACL_UNSUPPORTED");
            UserPrincipal owner = view.getOwner();
            boolean ownerAllowed = false;
            for (AclEntry entry : view.getAcl()) {
                if (entry.type() == AclEntryType.ALLOW) {
                    if (!entry.principal().equals(owner)) throw new IOException("SETTINGS_ACL_NOT_PRIVATE");
                    ownerAllowed = true;
                }
            }
            if (!ownerAllowed) throw new IOException("SETTINGS_OWNER_NOT_ALLOWED");
        }
    }
    private static boolean posix(Path path) {
        return Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null;
    }
    private static void restrictAcl(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("PRIVATE_ACL_UNSUPPORTED");
        view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
    static byte[] read(Path file, int maxBytes) throws IOException {
        requirePrivate(file.getParent(), true);
        requirePrivate(file, false);
        long size = Files.size(file);
        if (size < 1 || size > maxBytes) throw new IOException("SETTINGS_FILE_SIZE_INVALID");
        // The limit also holds if the file changes after the size check.
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("SETTINGS_FILE_TOO_LARGE");
            return bytes;
        }
    }
    static void atomicWrite(Path file, byte[] bytes) throws IOException {
        requirePrivate(file.getParent(), true);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) requirePrivate(file, false);
        Path temp = null;
        try {
            temp = posix(file.getParent())
                    ? Files.createTempFile(file.getParent(), "private-", ".tmp", PosixFilePermissions.asFileAttribute(FILE))
                    : Files.createTempFile(file.getParent(), "private-", ".tmp");
            if (!posix(temp)) restrictAcl(temp);
            requirePrivate(temp, false);
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // The move is the publication point. Never report a failed save after this point.
            forceDirectoryBestEffort(file.getParent());
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException | RuntimeException ignored) { }
        }
    }
    private static void forceDirectoryBestEffort(Path directory) {
        // NTFS and some file systems do not support opening/fsyncing a directory.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        catch (IOException | RuntimeException ignored) { }
    }
}
