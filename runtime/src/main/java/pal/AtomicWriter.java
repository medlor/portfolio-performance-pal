package pal;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import name.abuchen.portfolio.model.*;

/** One same-filesystem commit boundary for fully planned appended financial records. */
public final class AtomicWriter {
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rw-------");
    private final Path file;
    private final Clock clock;
    public AtomicWriter(Path file, Clock clock) { this.file = file.toAbsolutePath().normalize(); this.clock = clock; }
    public enum Owner { SECURITIES, CASH }
    public record Added(Owner kind, String ownerId, String transactionId) {}
    public record Change(String description, List<Added> records, List<String> securities) {
        public Change { records = List.copyOf(records); securities = List.copyOf(securities); }
        public static Change purchase(BuySellEntry entry) {
            return new Change("PURCHASE", List.of(new Added(Owner.SECURITIES, entry.getPortfolio().getUUID(), entry.getPortfolioTransaction().getUUID()),
                    new Added(Owner.CASH, entry.getAccount().getUUID(), entry.getAccountTransaction().getUUID())), List.of());
        }
    }
    @FunctionalInterface public interface Planner { Change plan(Client invocationClient) throws Exception; }
    public record Outcome(int status, boolean committed, List<String> transactions, String outputHash, Path receipt, String text) {}
    public Outcome recover() throws Exception { return execute(null); }
    public Outcome apply(Planner planner) throws Exception { return execute(Objects.requireNonNull(planner)); }
    private Outcome execute(Planner planner) throws Exception {

        boolean committed = false;
        Exception failure = null;
        Change change = null;
        Path daily = null, staging = null, stagingDirectory = null;
        String outputHash = null;
        Object stagingKey = null, stagingDirectoryKey = null;
        WriteReceipt receipt = null;
        String recoveredText = null;
        Outcome aborted = null;
        var resources = new ArrayList<AutoCloseable>();
        try {
            if (!Files.isWritable(file) || !Files.isWritable(file.getParent())) throw new IOException("Portfolio and containing directory must be writable: " + file);
            Path lockPath = file.resolveSibling(file.getFileName() + ".pp-pal-write.lock");
            try { Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(PRIVATE)); }
            catch (FileAlreadyExistsException ignored) { requireRegular(lockPath); }
            Object lockKey = Files.readAttributes(lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); resources.add(channel);
            if (!Objects.equals(lockKey, Files.readAttributes(lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) throw new IOException("Writer lock path changed: " + lockPath);
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { throw new IOException("Another financial writer is active: " + file, e); }
            if (lock == null) throw new IOException("Another financial writer is active: " + file);
            resources.add(lock);
            var opened = Files.newDirectoryStream(file.getParent()); resources.add(opened);
            if (!(opened instanceof SecureDirectoryStream<Path> directory)) throw new IOException("A secure atomic local Linux directory provider is required");
            var snapshot = FileSnapshot.capture(file);
            receipt = WriteReceipt.load(file);
            if (receipt != null && !receipt.complete()) {
                if (receipt.isOutput(snapshot)) {
                    receipt.verifyOutput(snapshot); committed = true; outputHash = snapshot.sha256();
                    if (planner != null) throw new IOException("Previous financial replacement already succeeded; use --recover-write --desktop-closed, never repeat apply");
                    receipt.state("committed");
                    var protection = new BackupService(file, clock);
                    protection.pruneDailyAfterSuccessfulWrite(); protection.pruneAdditionalAfterSuccessfulWrite();
                    cleanupAbortedStaging(receipt, snapshot);
                    snapshot.requireCurrent(); receipt.state("complete");
                    recoveredText = receipt.report() + "RECOVERED pending cleanup; no financial records inserted\n";
                } else if (planner == null && receipt.isInput(snapshot) && "prepared".equals(receipt.data.getProperty("state"))) {
                    cleanupAbortedStaging(receipt, snapshot);
                    snapshot.requireCurrent(); receipt.state("complete");
                    aborted = new Outcome(0, false, List.of(), null, receipt.path, "NO FINANCIAL CHANGE: interrupted replacement was not committed; pending staging cleanup completed\n");
                } else throw new IOException("Pending financial replacement requires verified recovery before another apply: " + receipt.path);
            }
            if (aborted != null || committed) { /* verified recovery is complete; the writer lock remains held through finally */ }
            else if (planner == null) throw new IllegalArgumentException("No pending financial replacement to recover");
            else {
            var client = snapshot.load();
            byte[] baseline = Core.semanticState(client);
            Set<String> originalIds = transactionIds(client);
            Set<String> originalSecurities = new HashSet<>(client.getSecurities().stream().map(Security::getUUID).toList());
            change = planner.plan(client);
            if (change.records().isEmpty()) throw new IllegalArgumentException("No planned financial records");
            Set<String> proposedIds = new HashSet<>();
            for (var added : change.records()) if (originalIds.contains(added.transactionId()) || !proposedIds.add(added.transactionId())) throw new IOException("Proposed transaction identity is not new and unique");
            for (String id : change.securities()) if (originalSecurities.contains(id)) throw new IOException("Proposed security identity already exists");
            stagingDirectory = Files.createTempDirectory(file.getParent(), ".pp-pal-write-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            stagingDirectoryKey = Files.readAttributes(stagingDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            staging = Files.createFile(stagingDirectory.resolve("portfolio.xml"), PosixFilePermissions.asFileAttribute(PRIVATE));
            var stageOpened = Files.newDirectoryStream(stagingDirectory); resources.add(stageOpened);
            if (!(stageOpened instanceof SecureDirectoryStream<Path> stageDirectory)) throw new IOException("Secure staging directory unavailable");
            stagingKey = Files.readAttributes(staging, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            Core.save(client, staging);
            if (!Objects.equals(stagingKey, Files.readAttributes(staging, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) throw new IOException("Staging identity changed during serialization");
            requireRegular(staging);
            var saved = Core.load(staging);
            if (!Arrays.equals(Core.semanticState(client), Core.semanticState(saved))) throw new IOException("Staged financial model failed complete pinned roundtrip validation");
            stripDeclared(saved, change);
            if (!Arrays.equals(baseline, Core.semanticState(saved))) throw new IOException("Financial plan changes unrelated supported persisted state");
            var view = Files.getFileAttributeView(staging, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            view.setOwner(snapshot.attributes().owner()); view.setGroup(snapshot.attributes().group()); view.setPermissions(snapshot.attributes().permissions());
            try (var staged = FileChannel.open(staging, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { staged.force(true); }
            var stagedSnapshot = FileSnapshot.capture(staging);
            outputHash = stagedSnapshot.sha256();
            snapshot.requireCurrent();
            daily = new BackupService(file, clock).daily(snapshot);
            receipt = WriteReceipt.prepare(file, snapshot, outputHash, change, daily, staging);
            WriteObserver.phase(file, "before-final-check", staging);
            snapshot.requireCurrent(); stagedSnapshot.requireCurrent();
            stageDirectory.move(staging.getFileName(), directory, file.getFileName());
            committed = true;
            try (var parent = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { parent.force(true); }
            receipt.state("committed");
            WriteObserver.phase(file, "committed-before-follow-up", null);
            var protection = new BackupService(file, clock);
            protection.pruneDailyAfterSuccessfulWrite(); protection.pruneAdditionalAfterSuccessfulWrite();
            receipt.state("complete");
            }
        } catch (Exception e) { failure = e; }
        finally {
            if (staging != null && !committed) try {
                if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Objects.equals(stagingKey, Files.readAttributes(staging, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) throw new IOException("Uncommitted staging identity changed; refusing foreign cleanup: " + staging);
                    Files.delete(staging);
                }
                if (receipt != null && "prepared".equals(receipt.data.getProperty("state"))) receipt.state("complete");
            } catch (Exception e) { failure = append(failure, e); }
            if (stagingDirectory != null) try {
                if (!Objects.equals(stagingDirectoryKey, Files.readAttributes(stagingDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) throw new IOException("Staging directory identity changed; refusing foreign cleanup");
                Files.delete(stagingDirectory);
            } catch (Exception e) { failure = append(failure, e); }
            for (int i = resources.size() - 1; i >= 0; i--) try { resources.get(i).close(); } catch (Exception e) { failure = append(failure, e); }
        }
        if (aborted != null && failure == null) return aborted;
        if (!committed) { if (failure != null) throw failure; throw new IOException("Financial replacement did not complete"); }
        String text = recoveredText != null ? recoveredText : receipt.report();
        var ids = List.of(receipt.data.getProperty("transactions").split(" "));
        if (failure != null) return new Outcome(4, true, ids, outputHash, receipt.path, text + "RECOVERY REQUIRED: financial change succeeded; do not repeat apply. " + failure.getMessage() + "\n");
        return new Outcome(0, true, ids, outputHash, receipt.path, text);
    }
    private static void cleanupAbortedStaging(WriteReceipt receipt, FileSnapshot snapshot) throws Exception {
        String name = receipt.data.getProperty("staging", "");
        if (!name.startsWith(".pp-pal-write-") || Path.of(name).getNameCount() != 1) throw new IOException("Unsafe pending staging directory");
        Path directory = snapshot.path().resolveSibling(name), stage = directory.resolve("portfolio.xml");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).equals(snapshot.attributes().owner())
                || !Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))) throw new IOException("Unsafe interrupted staging directory");
        try (var entries = Files.list(directory)) { if (entries.anyMatch(p -> !p.equals(stage))) throw new IOException("Unexpected pending staging contents"); }
        if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
            requireRegular(stage);
            if (!FileSnapshot.capture(stage).sha256().equals(receipt.data.getProperty("output"))) throw new IOException("Interrupted staging differs from validated output");
            Files.delete(stage);
        }
        Files.delete(directory);
    }
    private static Exception append(Exception first, Exception next) { if (first == null) return next; first.addSuppressed(next); return first; }
    private static void requireRegular(Path path) throws IOException { if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe nonregular writer path: " + path); }
    private static Set<String> transactionIds(Client client) {
        var result = new HashSet<String>();
        client.getAccounts().forEach(a -> a.getTransactions().forEach(t -> result.add(t.getUUID())));
        client.getPortfolios().forEach(p -> p.getTransactions().forEach(t -> result.add(t.getUUID())));
        return result;
    }
    private static void stripDeclared(Client client, Change change) throws Exception {
        for (var added : change.records()) {
            List<? extends Transaction> entries;
            if (added.kind() == Owner.CASH) {
                var owners = client.getAccounts().stream().filter(a -> a.getUUID().equals(added.ownerId())).toList();
                if (owners.size() != 1) throw new IOException("Proposed cash owner is not unique"); entries = owners.getFirst().getTransactions();
            } else {
                var owners = client.getPortfolios().stream().filter(p -> p.getUUID().equals(added.ownerId())).toList();
                if (owners.size() != 1) throw new IOException("Proposed securities owner is not unique"); entries = owners.getFirst().getTransactions();
            }
            if (entries.stream().filter(t -> t.getUUID().equals(added.transactionId())).count() != 1) throw new IOException("Proposed transaction did not survive exactly once under its owner");
            entries.removeIf(t -> t.getUUID().equals(added.transactionId()));
        }
        // getSecurities is unmodifiable; prove upstream cascading removal has no
        // remaining references to remove before invoking it on validation only.
        if (!change.securities().isEmpty()) {
            var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(Core.semanticState(client)));
            var definitions = document.getDocumentElement().getElementsByTagName("security");
            for (String uuid : change.securities()) {
                var found = client.getSecurities().stream().filter(s -> s.getUUID().equals(uuid)).toList();
                if (found.size() != 1) throw new IOException("Proposed security did not survive uniquely");
                String numericId = null;
                for (int i = 0; i < definitions.getLength(); i++) {
                    var element = (org.w3c.dom.Element)definitions.item(i);
                    var ids = element.getElementsByTagName("uuid");
                    if (element.hasAttribute("id") && ids.getLength() == 1 && uuid.equals(ids.item(0).getTextContent())) numericId = element.getAttribute("id");
                }
                if (numericId == null) throw new IOException("Proposed definition lacks canonical ID");
                var nodes = document.getElementsByTagName("*");
                for (int i = 0; i < nodes.getLength(); i++) if (numericId.equals(((org.w3c.dom.Element)nodes.item(i)).getAttribute("reference"))) throw new IOException("New security has an undeclared persisted reference");
                client.removeSecurity(found.getFirst());
            }
        }
    }
}
