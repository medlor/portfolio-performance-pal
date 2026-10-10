package pal;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Adjacent evidence is trustworthy only when it agrees with the current financial model. */
public final class ImportBookkeeping {
    private static final String FORMAT = "pp-pal-imports-v1";
    static Path path(Path portfolio) { return portfolio.resolveSibling(portfolio.getFileName() + ".pp-pal-imports.properties"); }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String identity(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "missing";
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Import metadata must be a regular file: " + path);
        return hash(Files.readAllBytes(path));
    }
    private static Properties read(Path path) throws IOException {
        var result = new Properties();
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var reader = Files.newBufferedReader(path)) { result.load(reader); }
            catch (IllegalArgumentException | java.nio.charset.MalformedInputException e) { result.clear(); }
        }
        return result;
    }
    private static String signature(BuySellEntry entry) throws Exception {
        var t = entry.getPortfolioTransaction(); var c = entry.getAccountTransaction();
        String units = t.getUnits().map(u -> u.getType() + ":" + u.getAmount() + ":" + u.getForex() + ":" + u.getExchangeRate()).sorted().reduce("", (a,b) -> a + "|" + b);
        return hash((entry.getPortfolio().getUUID() + "|" + entry.getAccount().getUUID() + "|" + t.getSecurity().getUUID()
                + "|" + t.getSecurity().getCurrencyCode() + "|" + t.getType() + "|" + t.getDateTime() + "|" + t.getShares()
                + "|" + t.getCurrencyCode() + "|" + t.getAmount() + "|" + units + "|" + c.getType() + "|" + c.getDateTime()
                + "|" + c.getCurrencyCode() + "|" + c.getAmount() + "|" + c.getShares() + "|" + c.getSecurity().getUUID()
                + "|" + c.getUnits().count()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static String key(String reference) throws Exception { return "member." + hash(reference.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "."; }
    private static boolean recorded(Client client, Properties data, String key, String reference, String signature) throws Exception {
        if (signature == null || !reference.equals(data.getProperty(key + "reference")) || !signature.equals(data.getProperty(key + "signature"))
                || !data.getProperty(key + "document", "").matches("[a-f0-9]{64}")) return false;
        String tradeId = data.getProperty(key + "trade"), cashId = data.getProperty(key + "cash");
        var trades = client.getPortfolios().stream().flatMap(p -> p.getTransactions().stream()).filter(t -> t.getUUID().equals(tradeId)).toList();
        long payments = client.getAccounts().stream().flatMap(a -> a.getTransactions().stream()).filter(t -> t.getUUID().equals(cashId)).count();
        if (trades.size() != 1 || payments != 1 || !(trades.getFirst().getCrossEntry() instanceof BuySellEntry entry)) return false;
        return entry.getPortfolio() != null && entry.getAccount() != null
                && entry.getPortfolioTransaction().getSecurity() != null && entry.getAccountTransaction().getSecurity() != null
                && entry.getPortfolio().getTransactions().contains(entry.getPortfolioTransaction())
                && entry.getAccountTransaction().getUUID().equals(cashId)
                && entry.getAccount().getTransactions().contains(entry.getAccountTransaction())
                && entry.getAccountTransaction().getCrossEntry() == entry
                && ("Vorgangs-Nr.: " + reference).equals(entry.getNote()) && signature.equals(signature(entry));
    }
    private static boolean resembles(PortfolioTransaction existing, PortfolioTransaction proposed) {
        return existing.getSecurity() == proposed.getSecurity()
                && (existing.getDateTime().toLocalDate().equals(proposed.getDateTime().toLocalDate())
                    || existing.getShares() == proposed.getShares()
                    || existing.getAmount() == proposed.getAmount()
                    || existing.getGrossValueAmount() == proposed.getGrossValueAmount()
                    || (existing.getShares() > 0 && proposed.getShares() > 0
                        && java.math.BigInteger.valueOf(existing.getGrossValueAmount()).multiply(java.math.BigInteger.valueOf(proposed.getShares()))
                            .equals(java.math.BigInteger.valueOf(proposed.getGrossValueAmount()).multiply(java.math.BigInteger.valueOf(existing.getShares())))));
    }
    public record Decision(List<PdfTrades.Member> additions, String text, boolean uncertain, Update update) {
        public Decision { additions = List.copyOf(additions); }
    }
    public record Update(Path portfolio, String metadataInput, Properties evidence, PdfTrades.Snapshot document) {
        void requireCurrent() throws Exception {
            document.unchanged();
            if (!metadataInput.equals(identity(path(portfolio)))) throw new IOException("Import metadata changed during planning; financial data unchanged");
        }
        void store(Properties receipt) {
            receipt.setProperty("import.input", metadataInput);
            evidence.forEach((key,value) -> receipt.setProperty("import.evidence." + key, value.toString()));
        }
    }
    public static Decision decide(Path file, Client client, PdfTrades.Document document) throws Exception {
        Path metadata = path(file);
        String metadataInput = identity(metadata);
        var data = read(metadata);
        boolean trusted = FORMAT.equals(data.getProperty("format")) && FileSnapshot.capture(file).sha256().equals(data.getProperty("portfolioHash"));
        var evidence = new Properties();
        if (trusted) evidence.putAll(data);
        evidence.setProperty("format", FORMAT);
        var additions = new ArrayList<PdfTrades.Member>(); var text = new StringBuilder(); boolean uncertain = false;
        for (var member : document.members()) {
            var entry = member.trade().entry(); String key = key(member.brokerReference()), signature = signature(entry);
            boolean confirmed = trusted && recorded(client, data, key, member.brokerReference(), data.getProperty(key + "signature"));
            if (confirmed) {
                if (!signature.equals(data.getProperty(key + "signature"))) throw new IllegalArgumentException("PDF member " + member.index()
                        + ": proposed values conflict with a confirmed broker reference; a duplicate override cannot authorize replay");
                text.append("CONFIRMED RE-IMPORT member ").append(member.index()).append(" reference ").append(member.brokerReference()).append("; skipped\n");
                continue;
            }
            var proposed = entry.getPortfolioTransaction();
            if (data.containsKey(key + "reference") || data.stringPropertyNames().stream().anyMatch(k -> k.endsWith(".document") && document.snapshot().sha256().equals(data.getProperty(k)))) {
                uncertain = true; text.append("SUSPECTED DUPLICATE member ").append(member.index()).append(" inconsistent document/reference evidence\n");
            }
            for (var owner : client.getPortfolios()) for (var existing : owner.getTransactions()) {
                if (resembles(existing, proposed) || ("Vorgangs-Nr.: " + member.brokerReference()).equals(existing.getNote())) {
                    uncertain = true; text.append("SUSPECTED DUPLICATE member ").append(member.index()).append(" existing transaction ")
                            .append(existing.getUUID()).append(" in securities account ").append(owner.getUUID()).append('\n');
                }
            }
            for (var earlier : additions) if (resembles(earlier.trade().entry().getPortfolioTransaction(), proposed)) {
                uncertain = true; text.append("SUSPECTED DUPLICATE member ").append(member.index()).append(" resembles document member ").append(earlier.index()).append('\n');
            }
            additions.add(member);
            evidence.setProperty(key + "document", document.snapshot().sha256()); evidence.setProperty(key + "reference", member.brokerReference());
            evidence.setProperty(key + "signature", signature); evidence.setProperty(key + "trade", proposed.getUUID());
            evidence.setProperty(key + "cash", entry.getAccountTransaction().getUUID());
        }
        if (uncertain) text.append("Document-wide --allow-suspected-duplicates required for application; financial validation still applies\n");
        return new Decision(additions, text.toString(), uncertain, new Update(file, metadataInput, evidence, document.snapshot()));
    }
    /** Idempotent recovery from receipt after the independently verified financial replacement. */
    static void complete(Path file, Properties receipt) throws Exception {
        if (!receipt.containsKey("import.input")) return;
        var expected = new Properties();
        receipt.stringPropertyNames().stream().filter(k -> k.startsWith("import.evidence.")).forEach(k -> expected.setProperty(k.substring("import.evidence.".length()), receipt.getProperty(k)));
        expected.setProperty("portfolioHash", receipt.getProperty("output"));
        Path target = path(file);
        if (expected.equals(read(target))) return;
        if (!receipt.getProperty("import.input").equals(identity(target))) throw new IOException("Import metadata changed after planning; restore its pre-operation state, then recover the write");
        Path temporary = Files.createTempFile(file.getParent(), ".pp-pal-imports-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { expected.store(writer, "PP PAL import identity evidence"); }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var channel = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { channel.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
