package pal;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Durable identity of one replacement; it is never permission to replay a plan. */
final class WriteReceipt {
    final Path path;
    final Properties data;
    private WriteReceipt(Path path, Properties data) { this.path = path; this.data = data; }
    static WriteReceipt load(Path file) throws IOException {
        Path path = file.resolveSibling(file.getFileName() + ".pp-pal-write.properties");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe financial receipt path: " + path);
        var data = new Properties(); try (var reader = Files.newBufferedReader(path)) { data.load(reader); }
        if (!"pp-pal-write-v1".equals(data.getProperty("format")) || !file.getFileName().toString().equals(data.getProperty("portfolio"))
                || !Set.of("prepared", "committed", "complete").contains(data.getProperty("state", ""))
                || !data.getProperty("input", "").matches("[a-f0-9]{64}") || !data.getProperty("output", "").matches("[a-f0-9]{64}"))
            throw new IOException("Unrecognized financial receipt: " + path);
        return new WriteReceipt(path, data);
    }
    static WriteReceipt prepare(Path file, FileSnapshot input, String output, AtomicWriter.Change change, Path daily, Path staging) throws IOException {
        var data = new Properties();
        data.setProperty("format", "pp-pal-write-v1"); data.setProperty("portfolio", file.getFileName().toString());
        data.setProperty("state", "prepared"); data.setProperty("input", input.sha256()); data.setProperty("output", output);
        data.setProperty("description", change.description()); data.setProperty("daily", daily.getFileName().toString());
        data.setProperty("staging", staging.getParent().getFileName().toString());
        data.setProperty("transactions", String.join(" ", change.records().stream().map(AtomicWriter.Added::transactionId).toList()));
        data.setProperty("securities", String.join(" ", change.securities()));
        data.setProperty("engine", System.getenv().getOrDefault("PP_PAL_ENGINE_IMAGE", "unknown"));
        var receipt = new WriteReceipt(file.resolveSibling(file.getFileName() + ".pp-pal-write.properties"), data);
        receipt.save(); return receipt;
    }
    boolean complete() { return "complete".equals(data.getProperty("state")); }
    boolean isOutput(FileSnapshot snapshot) { return snapshot.sha256().equals(data.getProperty("output")); }
    boolean isInput(FileSnapshot snapshot) { return snapshot.sha256().equals(data.getProperty("input")); }
    void state(String value) throws IOException { data.setProperty("state", value); save(); }
    String report() { return "APPLIED " + data.getProperty("description") + "\nTRANSACTIONS " + data.getProperty("transactions")
        + "\nDAILY BACKUP " + path.resolveSibling(data.getProperty("daily")) + "\nOUTPUT SHA256 " + data.getProperty("output")
        + "\nINPUT SHA256 " + data.getProperty("input") + "\nENGINE IMAGE " + data.getProperty("engine")
        + "\nINPUT MODEL 70\nOUTPUT MODEL 70\nRECEIPT " + path + "\n"; }
    void verifyOutput(FileSnapshot snapshot) throws Exception {
        if (!isOutput(snapshot)) throw new IOException("Pending financial receipt does not match current file; do not repeat apply: " + path);
        var client = snapshot.load(); var ids = new HashMap<String,Integer>();
        client.getAccounts().forEach(a -> a.getTransactions().forEach(t -> ids.merge(t.getUUID(), 1, Integer::sum)));
        client.getPortfolios().forEach(p -> p.getTransactions().forEach(t -> ids.merge(t.getUUID(), 1, Integer::sum)));
        String transactions = data.getProperty("transactions", "");
        if (transactions.isBlank()) throw new IOException("Pending receipt has no financial identities");
        for (String id : transactions.split(" ")) if (ids.getOrDefault(id, 0) != 1) throw new IOException("Pending receipt transaction is not present exactly once");
        for (String id : data.getProperty("securities", "").split(" ")) if (!id.isEmpty() && client.getSecurities().stream().filter(s -> id.equals(s.getUUID())).count() != 1) throw new IOException("Pending receipt security is not present exactly once");
        snapshot.requireCurrent();
    }
    private void save() throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(path))) throw new IOException("Financial receipt is not a writable regular file: " + path);
        Path temporary = Files.createTempFile(path.getParent(), ".pp-pal-receipt-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { data.store(writer, "PP PAL financial replacement identity; never replay this operation"); }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var channel = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { channel.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
