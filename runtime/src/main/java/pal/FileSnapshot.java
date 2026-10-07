package pal;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;

/** Stable exact invocation input, including inode, parent and host permissions. */
public final class FileSnapshot {
    private final Path path;
    private final byte[] bytes;
    private final PosixFileAttributes attributes;
    private final Object parentKey;

    private FileSnapshot(Path path, byte[] bytes, PosixFileAttributes attributes, Object parentKey) {
        this.path = path; this.bytes = bytes; this.attributes = attributes; this.parentKey = parentKey;
    }
    public static FileSnapshot capture(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        var before = attributes(path);
        Object parent = Files.readAttributes(path.getParent(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        var snapshot = new FileSnapshot(path, Files.readAllBytes(path), before, parent);
        snapshot.requireCurrent();
        return snapshot;
    }
    private static PosixFileAttributes attributes(Path path) throws IOException {
        var attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("Financial path must be a regular file, not a symlink: " + path);
        return attrs;
    }
    public void requireCurrent() throws IOException {
        var current = attributes(path);
        Object parent = Files.readAttributes(path.getParent(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        if (!Objects.equals(parentKey, parent) || !Objects.equals(attributes.fileKey(), current.fileKey())
                || attributes.size() != current.size() || !attributes.lastModifiedTime().equals(current.lastModifiedTime())
                || !attributes.owner().equals(current.owner()) || !attributes.group().equals(current.group())
                || !attributes.permissions().equals(current.permissions()) || !Arrays.equals(bytes, Files.readAllBytes(path)))
            throw new IOException("Portfolio changed since this invocation snapshot; external state preserved: " + path);
    }
    public byte[] bytes() { return bytes.clone(); }
    public String sha256() { return hash(bytes); }
    public Path path() { return path; }
    public PosixFileAttributes attributes() { return attributes; }
    public name.abuchen.portfolio.model.Client load() throws Exception {
        Path temporary = Files.createTempFile("pp-input-snapshot-", ".xml");
        try { Files.write(temporary, bytes); return Core.load(temporary); }
        finally { Files.deleteIfExists(temporary); }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
