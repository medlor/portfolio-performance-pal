package pal;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Adjacent, exclusively created snapshots. Launch protection never prunes. */
public final class BackupService {
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final String FORMAT = "pp-pal-backups-v1";
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rw-------");
    private final Path portfolio;
    private final Clock clock;
    private final Path index;
    private final Path lock;

    public BackupService(Path portfolio, Clock clock) {
        this.portfolio = portfolio.toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock);
        this.index = this.portfolio.resolveSibling(this.portfolio.getFileName() + ".pp-pal-backups.properties");
        this.lock = this.portfolio.resolveSibling(this.portfolio.getFileName() + ".pp-pal-backups.lock");
    }

    /** Explicit clock boundary for reproducible Linux compatibility gates. */
    public static Clock launchClock() {
        String now = System.getenv("PP_PAL_NOW");
        if (now == null) return Clock.systemUTC();
        try { return Clock.fixed(Instant.parse(now), ZoneOffset.UTC); }
        catch (DateTimeException e) { throw new IllegalArgumentException("PP_PAL_NOW must be an ISO UTC instant", e); }
    }

    /** Catch up once using the present state, without inventing missed months. */
    public void monthly() throws IOException {
        withLock(() -> {
            var records = records();
            String month = YearMonth.now(clock.withZone(ZONE)).toString();
            for (String key : records.stringPropertyNames()) {
                if (key.endsWith(".kind") && records.getProperty(key).equals("monthly")) {
                    String name = key.substring(0, key.length() - 5);
                    if (month.equals(records.getProperty(name + ".month"))) {
                        verifySnapshot(records, name);
                        return;
                    }
                }
            }
            create(records, "monthly", month);
        });
    }

    /** Every actual attempt gets its own exact snapshot; unchanged checks do not call this. */
    public Path beforeUpgrade() throws IOException {
        Path[] result = new Path[1];
        withLock(() -> result[0] = create(records(), "update", clock.instant().toString().replace(":", "")));
        return result[0];
    }

    /** Exact validated invocation input, preserved once per Berlin calendar day. */
    public Path daily(FileSnapshot snapshot) throws IOException {
        Path[] result = new Path[1];
        withLock(() -> {
            var records = records();
            String date = LocalDate.now(clock.withZone(ZONE)).toString();
            String name = portfolio.getFileName() + "." + date + ".bak";
            Path path = portfolio.resolveSibling(name);
            if (records.containsKey(name + ".kind")) {
                if (!"daily".equals(records.getProperty(name + ".kind"))) throw new IOException("Wrong ownership kind for required daily protection");
                verifySnapshot(records, name); result[0] = path; return;
            }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                requireRegular(path);
                if (!Files.isReadable(path)) throw new IOException("Existing daily protection is unreadable: " + path);
                result[0] = path; return; // foreign regular file satisfies existence; never adopt it
            }
            if (Files.exists(index, LinkOption.NOFOLLOW_LINKS) && !Files.isWritable(index)) throw new IOException("Backup ownership index is not writable: " + index);
            if (!portfolio.equals(snapshot.path())) throw new IOException("Daily protection snapshot belongs to a different portfolio");
            snapshot.requireCurrent();
            try (var channel = FileChannel.open(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PRIVATE))) {
                var bytes = ByteBuffer.wrap(snapshot.bytes()); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true);
            }
            forceDirectory();
            records.setProperty(name + ".kind", "daily"); records.setProperty(name + ".date", date);
            records.setProperty(name + ".created", clock.instant().toString()); records.setProperty(name + ".sha256", snapshot.sha256());
            save(records); result[0] = path;
        });
        return result[0];
    }

    /** Call only for a committed financial write (including its verified recovery). */
    public void pruneDailyAfterSuccessfulWrite() throws IOException {
        prune(true);
    }
    public void pruneAdditionalAfterSuccessfulWrite() throws IOException {
        prune(false);
    }
    private void prune(boolean daily) throws IOException {
        withLock(() -> {
            var records = records();
            var selected = new TreeSet<String>();
            var months = new TreeMap<YearMonth, TreeMap<LocalDate,String>>();
            var now = clock.instant();
            var today = LocalDate.now(clock.withZone(ZONE));
            var oldest = YearMonth.from(today).minusMonths(23);
            var cutoff = now.atZone(ZONE).minusYears(2).toInstant();
            for (String key : records.stringPropertyNames()) {
                if (!key.endsWith(".kind")) continue;
                String name = key.substring(0, key.length() - 5);
                String kind = records.getProperty(key);
                ownedPath(records, name); // reject unsafe or unknown ownership, even if retained
                if (daily && kind.equals("daily")) {
                    final LocalDate date;
                    try { date = LocalDate.parse(records.getProperty(name + ".date", "")); }
                    catch (DateTimeException e) { throw new IOException("Invalid daily date: " + name, e); }
                    if (date.isAfter(today)) continue; // never infer a future financial state
                    var month = YearMonth.from(date);
                    if (month.isBefore(oldest)) selected.add(name);
                    else months.computeIfAbsent(month, m -> new TreeMap<>()).put(date, name);
                } else if (!daily && Set.of("monthly", "update").contains(kind)) {
                    final Instant created;
                    try { created = Instant.parse(records.getProperty(name + ".created", "")); }
                    catch (DateTimeException e) { throw new IOException("Invalid backup creation date: " + name, e); }
                    if (created.isBefore(cutoff)) selected.add(name);
                }
            }
            for (var dates : months.values()) {
                int remove = Math.max(0, dates.size() - 3);
                for (String name : dates.values()) { if (remove-- <= 0) break; selected.add(name); }
            }
            // A persisted intent makes partial unlink/index failures resumable.
            for (String name : selected) {
                Path path = ownedPath(records, name);
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) verifySnapshot(records, name);
                else if (!"pending".equals(records.getProperty(name + ".prune"))) throw new IOException("Owned backup unexpectedly missing: " + path);
            }
            if (selected.isEmpty()) return;
            for (String name : selected) records.setProperty(name + ".prune", "pending");
            save(records);
            for (String name : selected) {
                Path path = ownedPath(records, name);
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { verifySnapshot(records, name); Files.delete(path); }
            }
            forceDirectory();
            for (String name : selected) records.keySet().removeIf(k -> k.toString().startsWith(name + "."));
            save(records);
        });
    }

    private Path create(Properties records, String kind, String label) throws IOException {
        if (Files.exists(index, LinkOption.NOFOLLOW_LINKS) && !Files.isWritable(index))
            throw new IOException("Backup ownership index is not writable: " + index);
        byte[] snapshot = snapshot();
        Instant created = clock.instant();
        String name = portfolio.getFileName() + ".pp-pal." + kind + "." + label + "." + UUID.randomUUID() + ".bak";
        Path backup = portfolio.resolveSibling(name);
        try (var channel = FileChannel.open(backup, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PRIVATE))) {
            ByteBuffer bytes = ByteBuffer.wrap(snapshot);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        forceDirectory();
        records.setProperty(name + ".kind", kind);
        if (kind.equals("monthly")) records.setProperty(name + ".month", label);
        records.setProperty(name + ".created", created.toString());
        records.setProperty(name + ".sha256", hash(snapshot));
        save(records);
        return backup;
    }

    private byte[] snapshot() throws IOException {
        var before = Files.readAttributes(portfolio, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("Cannot back up a symlink or nonregular portfolio: " + portfolio);
        byte[] bytes = Files.readAllBytes(portfolio);
        var after = Files.readAttributes(portfolio, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Arrays.equals(bytes, Files.readAllBytes(portfolio)))
            throw new IOException("Portfolio changed while creating its required backup: " + portfolio);
        return bytes;
    }

    private Properties records() throws IOException {
        var records = new Properties();
        if (Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
            requireRegular(index);
            try (var reader = Files.newBufferedReader(index)) { records.load(reader); }
            if (!FORMAT.equals(records.getProperty("format"))
                    || !portfolio.getFileName().toString().equals(records.getProperty("portfolio")))
                throw new IOException("Unrecognized backup ownership index: " + index);
        } else {
            records.setProperty("format", FORMAT);
            records.setProperty("portfolio", portfolio.getFileName().toString());
        }
        return records;
    }

    private void verifySnapshot(Properties records, String name) throws IOException {
        Path backup = ownedPath(records, name);
        requireRegular(backup);
        if (!hash(Files.readAllBytes(backup)).equals(records.getProperty(name + ".sha256")))
            throw new IOException("Required backup differs from its ownership record: " + backup);
    }

    private Path ownedPath(Properties records, String name) throws IOException {
        String kind = records.getProperty(name + ".kind");
        if ("daily".equals(kind)) {
            String date = records.getProperty(name + ".date", "");
            try { LocalDate.parse(date); } catch (DateTimeException e) { throw new IOException("Invalid owned daily date: " + name, e); }
            if (!name.equals(portfolio.getFileName() + "." + date + ".bak")) throw new IOException("Unsafe daily ownership record: " + name);
            return portfolio.resolveSibling(name);
        }
        if (!Set.of("monthly", "update").contains(kind == null ? "" : kind)
                || Path.of(name).getNameCount() != 1
                || !name.startsWith(portfolio.getFileName() + ".pp-pal." + kind + ".") || !name.endsWith(".bak"))
            throw new IOException("Unsafe backup ownership record: " + name);
        return portfolio.resolveSibling(name);
    }

    private void save(Properties records) throws IOException {
        if (Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
            requireRegular(index);
            if (!Files.isWritable(index)) throw new IOException("Backup ownership index is not writable: " + index);
        }
        Path temporary = Files.createTempFile(portfolio.getParent(), ".pp-pal-backup-index-", ".tmp",
                PosixFilePermissions.asFileAttribute(PRIVATE));
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { records.store(writer, "PP PAL backup ownership; financial data stays in exact snapshots"); }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            if (Files.exists(index, LinkOption.NOFOLLOW_LINKS)) requireRegular(index);
            Files.move(temporary, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory();
        } finally { Files.deleteIfExists(temporary); }
    }

    private void forceDirectory() throws IOException {
        try (var directory = FileChannel.open(portfolio.getParent(), StandardOpenOption.READ)) { directory.force(true); }
    }

    private void withLock(Operation operation) throws IOException {
        try { Files.createFile(lock, PosixFilePermissions.asFileAttribute(PRIVATE)); }
        catch (FileAlreadyExistsException ignored) { requireRegular(lock); }
        try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (var held = channel.tryLock()) {
                if (held == null) throw new IOException("Another backup operation is active: " + portfolio);
                operation.run();
            } catch (OverlappingFileLockException e) { throw new IOException("Another backup operation is active: " + portfolio, e); }
        }
    }

    private static void requireRegular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Backup paths must be regular files, not symlinks: " + path);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @FunctionalInterface private interface Operation { void run() throws IOException; }
}
