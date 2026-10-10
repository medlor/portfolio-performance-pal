package pal;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import name.abuchen.portfolio.datatransfer.*;
import name.abuchen.portfolio.datatransfer.pdf.*;
import name.abuchen.portfolio.model.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Immutable PDF input, page completeness, upstream extraction and atomic document admission. */
public final class PdfTrades {
    public record Snapshot(Path path, byte[] bytes, String sha256) {
        public Snapshot { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        public void unchanged() throws Exception {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Arrays.equals(bytes, Files.readAllBytes(path)))
                throw new IllegalArgumentException("PDF changed during planning; retry against the current document");
        }
    }
    public record Member(int index, String brokerReference, Purchase.Plan trade, Dividend.Plan dividend, Map<String,String> origins) {
        public Member {
            if ((trade == null) == (dividend == null)) throw new IllegalArgumentException("Exactly one financial plan required");
            origins = Map.copyOf(origins);
        }
        public name.abuchen.portfolio.model.Transaction transaction() { return trade != null ? trade.entry().getPortfolioTransaction() : dividend.transaction(); }
        public Account cash() { return trade != null ? trade.entry().getAccount() : dividend.account(); }
        public AccountTransaction cashTransaction() { return trade != null ? trade.entry().getAccountTransaction() : dividend.transaction(); }
        public String type() { return trade != null ? trade.entry().getPortfolioTransaction().getType() == PortfolioTransaction.Type.SELL ? "SALE" : "PURCHASE" : "DIVIDEND"; }
    }
    public record Document(Snapshot snapshot, String pdfBoxVersion, List<Member> members) {
        public Document { members = List.copyOf(members); }
        public String description() {
            var types = members.stream().map(Member::type).distinct().toList();
            return "PDF " + (types.size() > 1 ? "TRADE" : types.getFirst()) + " DOCUMENT";
        }
    }

    /** Apply to the invocation's disposable model, then admit the complete batch. */
    public static List<AtomicWriter.Added> insertAndValidate(Client client, List<Member> members) {
        var records = new ArrayList<AtomicWriter.Added>();
        for (var member : members) {
            if (member.trade() != null) {
                UpstreamImports.insert(client, UpstreamImports.item(member.trade().entry()));
                records.addAll(AtomicWriter.Change.purchase(member.trade().entry()).records());
            } else {
                UpstreamImports.insert(client, UpstreamImports.item(member.cash(), member.cashTransaction()));
                records.add(new AtomicWriter.Added(AtomicWriter.Owner.CASH, member.cash().getUUID(), member.cashTransaction().getUUID()));
            }
        }
        for (var member : members) {
            if (member.trade() != null) {
                var entry = member.trade().entry();
                Sale.validateHistory(entry.getPortfolio(), entry.getPortfolioTransaction().getSecurity());
            }
        }
        return List.copyOf(records);
    }

    public static Document plan(Client client, Portfolio securities, Account cash, CommandLine command) throws Exception {
        if (command.value("pdf") == null) throw new IllegalArgumentException("Select one --pdf PATH");
        var path = Path.of(command.value("pdf")).toAbsolutePath().normalize();
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path))
            throw new IllegalArgumentException("PDF must be one readable regular .pdf file");
        byte[] bytes = Files.readAllBytes(path);
        var snapshot = new Snapshot(path, bytes, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var temporary = Files.createTempFile("pp-pal-pdf-snapshot-", ".pdf");
        var members = new ArrayList<Member>();
        String version;
        try {
            Files.write(temporary, bytes);
            var input = new PDFInputFile(temporary.toFile());
            input.convertPDFtoText();
            version = input.getPDFBoxVersion();
            // PDFBox splits physical pages only. Every part is converted by PP itself;
            // neither bank fields nor supplemental financial text are manufactured.
            try (var pdf = Loader.loadPDF(temporary.toFile())) {
                var pageText = new ArrayList<String>();
                for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                    try (var single = new PDDocument()) {
                        single.importPage(pdf.getPage(page));
                        pageText.add(convert(single, part -> part.getText()));
                    }
                }
                var groups = pages(pageText);
                if (groups.size() > 1 && Set.of("quantity","price","gross","total","fees","taxes","date","time","exchange-rate","forex-gross").stream().anyMatch(command::flag))
                    throw rejected(1,"Financial supplements require a single-member document");
                var references = new HashSet<String>();
                for (var group : groups) {
                    int index = members.size() + 1;
                    try (var statement = new PDDocument()) {
                        for (int page : group) statement.importPage(pdf.getPage(page));
                        var member = convert(statement, part -> PdfTransactions.member(client, securities, cash, command,
                                part, path.getFileName().toString(), snapshot.sha256(), index));
                        if (!references.add(member.brokerReference())) throw rejected(index,"Ambiguous repeated reference across document members");
                        members.add(member);
                    }
                }
            }
        } finally { Files.deleteIfExists(temporary); }
        snapshot.unchanged();
        return new Document(snapshot, version, members);
    }

    @FunctionalInterface private interface Converted<T> { T use(PDFInputFile input) throws Exception; }
    private static <T> T convert(PDDocument pdf, Converted<T> action) throws Exception {
        var temporary = Files.createTempFile("pp-pal-pdf-part-", ".pdf");
        try {
            pdf.save(temporary.toFile());
            var input = new PDFInputFile(temporary.toFile());
            input.convertPDFtoText();
            return action.use(input);
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Envelope completeness only, independent of any bank's financial grammar. */
    private static List<List<Integer>> pages(List<String> text) {
        if (text.isEmpty()) throw rejected(1,"Empty PDF");
        var result = new ArrayList<List<Integer>>();
        int expected = 1, total = 0;
        List<Integer> group = null;
        var marker = Pattern.compile(".*(?:Seite|Page) ([0-9]+)/([0-9]+)\\s*");
        for (int page = 0; page < text.size(); page++) {
            if (text.get(page).isBlank()) throw rejected(page+1,"Blank physical page has no extracted statement");
            int number = 0, count = 0, markers = 0;
            for (String line : text.get(page).lines().toList()) {
                var match = marker.matcher(line);
                if (match.matches()) { markers++; number = Integer.parseInt(match.group(1)); count = Integer.parseInt(match.group(2)); }
            }
            if (markers > 1) throw rejected(page+1,"Ambiguous physical page markers");
            if (number == 0) {
                if (expected != 1) throw rejected(page+1,"Missing continuation page marker");
                result.add(List.of(page));
                continue;
            }
            if (number != expected || count < number || count == 0 || expected != 1 && count != total)
                throw rejected(page+1,"Incomplete or contradictory physical page sequence");
            if (expected == 1) { group = new ArrayList<>(); result.add(group); total = count; }
            group.add(page);
            expected = number == total ? 1 : number + 1;
        }
        if (expected != 1) throw rejected(text.size(),"Incomplete PDF: continuation pages are missing");
        return result;
    }

    static IllegalArgumentException rejected(int member,String reason) { return new IllegalArgumentException("PDF member " + member + ": " + reason); }

    public static String preview(Document document,int inputModel) throws Exception {
        document.snapshot().unchanged();
        var out = new StringBuilder("PREVIEW " + document.description() + " (" + document.members().size() + " members)\n");
        out.append("INPUT PDF ").append(document.snapshot().path()).append('\n');
        for (var member : document.members()) {
            out.append("MEMBER ").append(member.index()).append(" | Baader Bank AG | ")
                    .append(member.origins().get("reference").equals("PDF") ? "Vorgangs-Nr.: " : "DOCUMENT ID: ")
                    .append(member.brokerReference()).append('\n');
            for (String line : (member.trade() != null ? Purchase.preview(member.trade(),inputModel) : Dividend.preview(member.dividend(),inputModel)).split("\\n")) {
                if (line.startsWith("DATE ")) {
                    String date = member.origins().getOrDefault("date","SUPPLEMENT");
                    String time = member.origins().getOrDefault("time","fallback time " + (member.type().equals("SALE") ? "11:00:01" : "11:00:00"));
                    String origin = date.equals(time) ? date + " date and time" : date + " date; " + time + (time.equals("SUPPLEMENT") ? " time" : "");
                    line = line.substring(0,line.indexOf(" (")) + " (" + origin + ")";
                } else if (line.startsWith("EXCHANGE RATE ")) {
                    line = line.substring(0,line.indexOf(" (")) + " (PDF; Portfolio Performance conversion)";
                } else {
                    var fields = Map.of("QUANTITY ","quantity","PRICE ","price","GROSS ","gross","FEES ","fees","TAXES ","taxes","BOOKED DEBIT ","total","BOOKED CREDIT ","total");
                    for (var field : fields.entrySet()) if (line.startsWith(field.getKey()) && member.origins().containsKey(field.getValue()))
                        line = line.substring(0,line.indexOf(" (")) + " (" + member.origins().get(field.getValue()) + ")";
                }
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}
