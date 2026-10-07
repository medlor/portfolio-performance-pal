package pal;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.core.runtime.NullProgressMonitor;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.datatransfer.Extractor;
import name.abuchen.portfolio.datatransfer.pdf.*;

/** Pinned upstream boundary. Inspection output is a developer validation format. */
public final class Core {
    public static final int MODEL = 70;
    public static Client load(Path path) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var root = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
        var versions = root.getElementsByTagName("version");
        if (!root.getTagName().equals("client") || versions.getLength() != 1 || !versions.item(0).getTextContent().equals("70")
                || !root.hasAttribute("id"))
            throw new IOException("Unsupported input: only tested ID-reference XML model 70 is supported");
        var refs = root.getElementsByTagName("*");
        for (int i = 0; i < refs.getLength(); i++) {
            var ref = ((org.w3c.dom.Element)refs.item(i)).getAttribute("reference");
            if (!ref.isEmpty() && !ref.matches("[0-9]+")) throw new IOException("Only numeric ID references are supported");
        }
        Client client = ClientFactory.load(path.toFile(), null, new NullProgressMonitor());
        return client;
    }
    public static void save(Client client, Path path) throws Exception {
        ClientFactory.saveAs(client, path.toFile(), null, EnumSet.of(SaveFlag.XML, SaveFlag.ID_REFERENCES));
    }
    public static String inspect(Client client) {
        var out = new StringBuilder("MODEL " + client.getFileVersionAfterRead() + " " + client.getBaseCurrency() + "\n");
        for (var s : client.getSecurities()) out.append("SECURITY ").append(s.getUUID()).append(' ').append(s.getName()).append(' ').append(s.getCurrencyCode()).append(' ').append(s.getIsin()).append(' ').append(s.getWkn()).append(' ').append(s.getNote()).append('\n');
        for (var a : client.getAccounts()) {
            out.append("ACCOUNT ").append(a.getUUID()).append(' ').append(a.getName()).append(' ').append(a.getCurrencyCode()).append('\n');
            for (var t : a.getTransactions()) out.append(transaction(t, t.getType().name()));
        }
        for (var p : client.getPortfolios()) {
            out.append("PORTFOLIO ").append(p.getUUID()).append(' ').append(p.getName()).append(' ').append(p.getReferenceAccount().getUUID()).append('\n');
            for (var t : p.getTransactions()) out.append(transaction(t, t.getType().name()));
        }
        for (var taxonomy : client.getTaxonomies()) {
            out.append("TAXONOMY ").append(taxonomy.getId()).append(' ').append(taxonomy.getName()).append('\n');
            for (var c : taxonomy.getAllClassifications()) {
                out.append("CLASS ").append(c.getId()).append(' ').append(c.getName()).append(' ').append(c.getWeight()).append(' ').append(c.getColor()).append('\n');
                for (var a : c.getAssignments()) out.append("ASSIGN ").append(a.getInvestmentVehicle().getUUID()).append(' ').append(a.getWeight()).append('\n');
            }
        }
        return out.toString();
    }
    private static String transaction(Transaction t, String type) {
        var out = new StringBuilder("TRANSACTION " + t.getUUID() + " " + type + " " + t.getDateTime() + " " + t.getShares() + " " + t.getCurrencyCode() + " " + t.getAmount() + " " + (t.getSecurity() == null ? "null" : t.getSecurity().getUUID()) + " " + t.getNote() + " " + t.getSource());
        if (t.getCrossEntry() != null) out.append(" CROSS ").append(t.getCrossEntry().getCrossOwner(t).getUUID()).append(' ').append(t.getCrossEntry().getCrossTransaction(t).getUUID());
        t.getUnits().forEach(u -> out.append(" UNIT ").append(u.getType()).append(' ').append(u.getAmount().getCurrencyCode()).append(' ').append(u.getAmount().getAmount()).append(' ').append(u.getForex() == null ? "null" : u.getForex().getCurrencyCode() + " " + u.getForex().getAmount()).append(' ').append(u.getExchangeRate()));
        return out.append('\n').toString();
    }
    /** Canonical upstream serialization includes every supported persisted field. */
    public static byte[] semanticState(Client client) throws Exception {
        Path file = Files.createTempFile("pp-semantic-", ".xml");
        try { save(client, file); return Files.readAllBytes(file); }
        finally { Files.deleteIfExists(file); }
    }
    private static void assertSame(Client expected, Client actual) throws Exception {
        if (!Arrays.equals(semanticState(expected), semanticState(actual)))
            throw new IOException("Semantic roundtrip changed upstream persisted model state");
    }
    private static void prove(Client client, Path output) throws Exception {
        byte[] originalState = semanticState(client);
        if (client.getAccounts().size() != 2 || client.getPortfolios().size() != 2 || client.getSecurities().size() != 2)
            throw new IOException("Synthetic fixture shape differs");
        var p = client.getPortfolios().get(0);
        long quantity = p.getTransactions().stream().mapToLong(t -> t.getType() == PortfolioTransaction.Type.BUY ? t.getShares() : -t.getShares()).sum();
        if (quantity != 600_000_000L) throw new IOException("Expected independently specified six remaining shares");
        if (!"preserve-me".equals(client.getProperty("fixture.setting"))) throw new IOException("Fixture setting missing");
        var entry = new BuySellEntry(p, p.getReferenceAccount());
        entry.setType(PortfolioTransaction.Type.SELL);
        entry.setDate(LocalDateTime.of(2025, 3, 1, 11, 0, 1));
        entry.setSecurity(client.getSecurities().get(0)); entry.setShares(600_000_000L);
        entry.setCurrencyCode("EUR"); entry.setAmount(72_00); entry.insert();
        save(client, output);
        var loaded = load(output); assertSame(client, loaded);
        var lp = loaded.getPortfolios().get(0);
        var sell = lp.getTransactions().getLast();
        if (sell.getShares() != 600_000_000L || sell.getAmount() != 72_00 || sell.getCrossEntry() == null
                || sell.getCrossEntry().getCrossTransaction(sell).getAmount() != 72_00
                || sell.getSecurity() != loaded.getSecurities().get(0)) throw new IOException("Linked full sale did not survive reload");
        long remaining = lp.getTransactions().stream().mapToLong(t -> t.getType() == PortfolioTransaction.Type.BUY ? t.getShares() : -t.getShares()).sum();
        if (remaining != 0 || !"preserve-me".equals(loaded.getProperty("fixture.setting"))) throw new IOException("Full sale/settings differ");
        var cashSale = (AccountTransaction) sell.getCrossEntry().getCrossTransaction(sell);
        lp.shallowDeleteTransaction(sell, loaded);
        lp.getReferenceAccount().shallowDeleteTransaction(cashSale, loaded);
        if (!Arrays.equals(originalState, semanticState(loaded))) throw new IOException("Unrelated persisted fields changed during intended sale");
        System.out.println("PASS linked full sale, zero holding, preserved complete upstream model");
    }
    public static void main(String[] args) {
        try {
            if (args.length < 2) throw new IllegalArgumentException("Usage: inspect FILE | roundtrip INPUT OUTPUT | prove INPUT OUTPUT | pdf PDF_OR_TEXT");
            if (args[0].equals("pdf")) {
                var file = new File(args[1]);
                var input = PDFInputFile.isTextFile(file) ? PDFInputFile.fromTextFile(file) : new PDFInputFile(file);
                if (!input.isTextFile()) input.convertPDFtoText();
                var errors = new ArrayList<Exception>();
                var items = new BaaderBankPDFExtractor(new Client()).extract(List.of(input), errors);
                if (!errors.isEmpty()) throw new IOException("PDF extraction failed", errors.getFirst());
                for (var item : items) if (item.getSubject() instanceof BuySellEntry e) {
                    var t = e.getPortfolioTransaction();
                    System.out.println(t.getType().name() + " " + t.getDateTime() + " " + t.getShares() + " " + t.getCurrencyCode() + " " + t.getAmount() + " " + t.getSecurity().getIsin());
                }
                if (items.isEmpty()) throw new IOException("No recognized Baader transactions");
            } else {
                var client = load(Path.of(args[1]));
                switch (args[0]) {
                    case "inspect" -> System.out.print(inspect(client));
                    case "roundtrip" -> { save(client, Path.of(args[2])); assertSame(client, load(Path.of(args[2]))); }
                    case "prove" -> prove(client, Path.of(args[2]));
                    default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
                }
            }
        } catch (Exception e) { System.err.println("ERROR: " + e.getMessage()); e.printStackTrace(System.err); System.exit(1); }
    }
}
