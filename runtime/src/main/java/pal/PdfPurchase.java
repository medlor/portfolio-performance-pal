package pal;

import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import name.abuchen.portfolio.datatransfer.*;
import name.abuchen.portfolio.datatransfer.pdf.*;
import name.abuchen.portfolio.model.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

/** Tested statement evidence, actual upstream extraction and one detached document plan. */
public final class PdfPurchase {
    private static final String NUMBER = "(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+)(?:,[0-9]+)?";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
    public record Snapshot(Path path, byte[] bytes, String sha256) {
        public Snapshot { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        public void unchanged() throws Exception {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Arrays.equals(bytes, Files.readAllBytes(path)))
                throw new IllegalArgumentException("PDF changed during preview; retry against the current document");
        }
    }
    public record Member(int index, String brokerReference, Purchase.Plan purchase, Map<String,String> origins) {
        public Member { origins = Map.copyOf(origins); }
    }
    public record Document(Snapshot snapshot, String pdfBoxVersion, List<Member> members) {
        public Document { members = List.copyOf(members); }
    }

    public static Document plan(Client client, Portfolio securities, Account cash, CommandLine command) throws Exception {
        if (command.value("pdf") == null) throw new IllegalArgumentException("Select one --pdf PATH");
        var path = Path.of(command.value("pdf")).toAbsolutePath().normalize();
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path))
            throw new IllegalArgumentException("PDF must be one readable regular .pdf file");
        byte[] bytes = Files.readAllBytes(path);
        var snapshot = new Snapshot(path, bytes, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var temporary = Files.createTempFile("pp-pal-pdf-snapshot-", ".pdf");
        final PDFInputFile input;
        final List<String> pages;
        try {
            Files.write(temporary, bytes);
            input = new PDFInputFile(temporary.toFile());
            try { input.convertPDFtoText(); }
            catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot convert the selected PDF", e); }
            var pageTexts = new ArrayList<String>();
            // The same pinned PDFBox stripper/settings expose physical boundaries.
            // Upstream still performs the document conversion and financial extraction.
            try (var pdf = Loader.loadPDF(temporary.toFile())) {
                for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                    var stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true); stripper.setStartPage(page); stripper.setEndPage(page);
                    pageTexts.add(((PDFInputFile)PDFInputFile.createTestCase("page.txt",stripper.getText(pdf)).getFirst()).getText());
                }
            } catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot classify the selected PDF pages",e); }
            pages = List.copyOf(pageTexts);
            if (!String.join("",pages).equals(input.getText())) throw rejected(1,"Page conversion disagrees with upstream document text");
        } finally { Files.deleteIfExists(temporary); }
        var members = new ArrayList<Member>();
        var parts = classified(pages);
        if (parts.size() > 1 && Set.of("quantity","price","gross","total","fees","taxes","date","time").stream().anyMatch(command::flag))
            throw rejected(1,"Financial supplements require a single-member document");
        var references = new HashSet<String>();
        for (int i = 0; i < parts.size(); i++) {
            var member = purchase(client,securities,cash,command,parts.get(i),path.getFileName().toString(),i+1);
            if (!references.add(member.brokerReference())) throw rejected(i+1,"Ambiguous repeated reference across document members");
            members.add(member);
        }
        snapshot.unchanged();
        return new Document(snapshot, input.getPDFBoxVersion(), members);
    }

    private static List<String> classified(List<String> pages) {
        if (pages.isEmpty()) throw rejected(1,"Empty PDF");
        boolean paged = pages.size() > 1 || pages.getFirst().contains("Seite") || pages.getFirst().contains("Page ");
        for (int page = 0; page < pages.size(); page++) {
            String text = pages.get(page);
            if (paged && !text.startsWith("Baader Bank AG\nSeite 1/1\n"))
                throw rejected(page+1,"Physical page has no proved Baader page-1 member start");
            int markers = 0;
            for (String line : text.split("\\n")) {
                if (line.strip().equals("Seite 1/1")) markers++;
                else if (line.contains("Seite") || line.startsWith("Page ")) throw rejected(page+1,"Unsupported or malformed page/continuation layout");
            }
            if (paged && markers != 1) throw rejected(page+1,"Physical page and member coverage disagree");
        }
        return pages;
    }

    private static Member purchase(Client client, Portfolio securities, Account cash, CommandLine command, String text, String filename, int index) {
        var facts = new LinkedHashMap<String,String>();
        String reference = null;
        int bank = 0, heading = 0, nominal = 0, quantity = 0;
        boolean nameContinuation = false, expectTime = false;
        LocalDate bookingDate = null, tradeDate = null;
        LocalTime time = null;
        for (var raw : text.split("\\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (expectTime) {
                var match = Pattern.compile("([0-9]{2}\\.[0-9]{2}\\.[0-9]{4}) ([0-9]{2}:[0-9]{2}:[0-9]{2}):00").matcher(line);
                if (!match.matches()) throw rejected(index, "Malformed or unsupported explicit trade time");
                try { tradeDate = LocalDate.parse(match.group(1), DATE); time = LocalTime.parse(match.group(2)); }
                catch (DateTimeException e) { throw rejected(index, "Invalid explicit trade date/time"); }
                expectTime = false; continue;
            }
            if (nameContinuation) {
                if (line.matches(".*(?:ISIN:|Konto |Kurswert|Provision|steuer|Handelsdatum|Vorgangs|Seite|abrechnung|[A-Z]{3} [0-9]).*"))
                    throw rejected(index, "Missing or ambiguous security name continuation");
                nameContinuation = false; continue;
            }
            if (line.equals("Baader Bank AG")) { bank++; continue; }
            if (line.equals("Seite 1/1")) continue;
            var match = Pattern.compile("Vorgangs-Nr\\.: (.+)").matcher(line);
            if (match.matches()) {
                if (reference != null) throw rejected(index, "Repeated broker reference");
                reference = match.group(1); continue;
            }
            if (line.equals("Wertpapierabrechnung: Kauf")) { heading++; continue; }
            match = Pattern.compile("Nominale ISIN: ([A-Z]{2}[A-Z0-9]{9}[0-9]) WKN: ([A-Z0-9]{6}) Kurs").matcher(line);
            if (match.matches()) { nominal++; put(facts,"isin",match.group(1),index); put(facts,"wkn",match.group(2),index); continue; }
            match = Pattern.compile("STK (" + NUMBER + ") (.+) ([A-Z]{3}) (" + NUMBER + ")").matcher(line);
            if (match.matches()) {
                quantity++; currency(match.group(3),index); put(facts,"quantity",decimal(match.group(1)),index); put(facts,"price",decimal(match.group(4)),index);
                nameContinuation = true; continue;
            }
            match = Pattern.compile("Kurswert ([A-Z]{3}) (" + NUMBER + ")").matcher(line);
            if (match.matches()) { currency(match.group(1),index); put(facts,"gross",decimal(match.group(2)),index); continue; }
            match = Pattern.compile("(Provision|Kapitalertragsteuer) ([A-Z]{3}) (" + NUMBER + ")( -)?").matcher(line);
            if (match.matches()) {
                currency(match.group(2),index); put(facts,match.group(1).equals("Provision") ? "fees" : "taxes",decimal(match.group(3)),index); continue;
            }
            match = Pattern.compile("Zu Lasten Konto [A-Za-z0-9]+ Valuta: ([0-9]{2}\\.[0-9]{2}\\.[0-9]{4}) ([A-Z]{3}) (" + NUMBER + ")").matcher(line);
            if (match.matches()) {
                currency(match.group(2),index);
                try { bookingDate = LocalDate.parse(match.group(1),DATE); }
                catch (DateTimeException e) { throw rejected(index,"Invalid value date"); }
                put(facts,"total",decimal(match.group(3)),index); continue;
            }
            if (line.equals("Handelsdatum Handelsuhrzeit")) {
                if (tradeDate != null) throw rejected(index,"Repeated trade time");
                expectTime = true; continue;
            }
            throw rejected(index,"Unsupported or uncovered statement line: " + line);
        }
        if (bank != 1 || heading != 1 || nominal != 1 || quantity > 1 || reference == null || expectTime || nameContinuation)
            throw rejected(index,"Incomplete or ambiguous Baader purchase member");
        var date = tradeDate == null ? bookingDate : tradeDate;
        if (date != null) put(facts,"date",date.toString(),index);
        if (time != null) put(facts,"time",time.toString(),index);
        var options = new LinkedHashMap<>(command.options());
        for (var fact : facts.entrySet()) {
            String supplied = options.get(fact.getKey());
            if (supplied != null && !same(fact.getKey(),supplied,fact.getValue())) throw rejected(index,"CLI conflicts with extracted " + fact.getKey());
            options.put(fact.getKey(),fact.getValue());
        }
        if (!facts.containsKey("total") && !command.flag("total")) throw rejected(index,"Missing booked debit requires --total");
        if (quantity == 0 && (!command.flag("quantity") || !command.flag("price")))
            throw rejected(index,"Absent quantity/price section requires both --quantity and --price");
        var determined = Purchase.plan(client, securities, cash, new CommandLine("purchase",options));
        // Complete only truly absent source fields, then use upstream monetary/model extraction.
        // Present malformed lines were rejected above, so no ignored value can be replaced here.
        String completed = text;
        if (quantity == 0) {
            String section = "STK " + german(options.get("quantity")) + " " + determined.entry().getPortfolioTransaction().getSecurity().getName()
                    + " EUR " + german(options.get("price")) + "\nExisting selected security\n";
            completed = completed.replaceFirst("(?m)^(Nominale ISIN: .*\\n)","$1" + Matcher.quoteReplacement(section));
        }
        if (!facts.containsKey("total")) completed += "Zu Lasten Konto SUPPLEMENT EUR " + german(options.get("total")) + "\n";
        if (!facts.containsKey("fees") && command.flag("fees")) completed += "Provision EUR " + german(options.get("fees")) + "\n";
        if (!facts.containsKey("taxes") && command.flag("taxes")) completed += "Kapitalertragsteuer EUR " + german(options.get("taxes")) + " -\n";
        if (tradeDate == null && (command.flag("time") || date == null)) {
            var selectedDateTime = determined.entry().getPortfolioTransaction().getDateTime();
            if (selectedDateTime.getNano() != 0) throw rejected(index,"Supplemental PDF time must have whole-second precision");
            completed += "Handelsdatum Handelsuhrzeit\n" + selectedDateTime.toLocalDate().format(DATE) + " "
                    + selectedDateTime.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + ":00\n";
        }
        var extractionClient = new Client();
        var input = PDFInputFile.createTestCase(filename,completed).getFirst();
        var errors = new ArrayList<Exception>();
        var extracted = new BaaderBankPDFExtractor(extractionClient).extract(new SecurityCache(extractionClient), input, errors);
        if (!errors.isEmpty() || extracted.size() != 1 || extracted.getFirst().isFailure() || extracted.getFirst().isSkipped()
                || !(extracted.getFirst().getSubject() instanceof BuySellEntry))
            throw rejected(index,"Upstream extraction did not produce one valid purchase");
        var entry = (BuySellEntry)extracted.getFirst().getSubject();
        var tx = entry.getPortfolioTransaction(); var expected = determined.entry().getPortfolioTransaction();
        if (tx.getType() != PortfolioTransaction.Type.BUY || tx.getShares() != expected.getShares() || tx.getAmount() != expected.getAmount()
                || !tx.getCurrencyCode().equals(expected.getCurrencyCode()) || !tx.getSecurity().getIsin().equals(facts.get("isin"))
                || !tx.getSecurity().getWkn().equals(facts.get("wkn")) || !tx.getSecurity().getCurrencyCode().equals("EUR")
                || tx.getGrossValueAmount() != expected.getGrossValueAmount()
                || tx.getUnitSum(Transaction.Unit.Type.FEE).getAmount() != determined.amounts().fees()
                || tx.getUnitSum(Transaction.Unit.Type.TAX).getAmount() != determined.amounts().taxes()
                || !tx.getDateTime().equals(tradeDate == null && (command.flag("time") || date == null)
                        ? expected.getDateTime() : date.atTime(time == null ? LocalTime.MIDNIGHT : time)))
            throw rejected(index,"Extracted financial values disagree with exact statement evidence");
        entry.setPortfolio(securities); entry.setAccount(cash); entry.setSecurity(expected.getSecurity()); entry.setDate(expected.getDateTime());
        var plan = new Purchase.Plan(entry,determined.amounts(),determined.fallbackTime());
        var origins = new LinkedHashMap<String,String>();
        for (var key : facts.keySet()) origins.put(key,"PDF");
        for (var key : Set.of("quantity","price","gross","total","fees","taxes","date","time"))
            if (!origins.containsKey(key) && command.flag(key)) origins.put(key,"SUPPLEMENT");
        return new Member(index,reference,plan,origins);
    }
    private static void currency(String value, int member) { if (!value.equals("EUR")) throw rejected(member,"Foreign-currency layout is not supported by this purchase preview"); }
    private static String decimal(String text) { return text.replace(".","").replace(',','.'); }
    private static String german(String text) { return new BigDecimal(text).stripTrailingZeros().toPlainString().replace('.',','); }
    private static void put(Map<String,String> values,String key,String value,int member) {
        if (values.putIfAbsent(key,value) != null) throw rejected(member,"Repeated " + key + " field");
    }
    private static boolean same(String key,String supplied,String extracted) {
        if (Set.of("quantity","price","gross","total","fees","taxes").contains(key)) {
            try { return new BigDecimal(supplied).compareTo(new BigDecimal(extracted)) == 0; }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid supplemental decimal",e); }
        }
        if (key.equals("time")) {
            try { return LocalTime.parse(supplied).equals(LocalTime.parse(extracted)); }
            catch (DateTimeException e) { throw new IllegalArgumentException("Invalid supplemental time",e); }
        }
        return supplied.equalsIgnoreCase(extracted);
    }
    private static IllegalArgumentException rejected(int member,String reason) { return new IllegalArgumentException("PDF member " + member + ": " + reason); }

    public static String preview(Document document,int inputModel) throws Exception {
        document.snapshot().unchanged();
        var out = new StringBuilder("PREVIEW PDF PURCHASE DOCUMENT (" + document.members().size() + " members)\n");
        out.append("INPUT PDF ").append(document.snapshot().path()).append('\n');
        for (var member : document.members()) {
            out.append("MEMBER ").append(member.index()).append(" | Baader Bank AG | Vorgangs-Nr.: ").append(member.brokerReference()).append('\n');
            for (String line : Purchase.preview(member.purchase(),inputModel).split("\\n")) {
                if (line.startsWith("DATE ")) {
                    String date = member.origins().getOrDefault("date","SUPPLEMENT");
                    String time = member.origins().getOrDefault("time","fallback time 11:00:00");
                    String origin = date.equals(time) ? date + " date and time" : date + " date; " + time + (time.equals("SUPPLEMENT") ? " time" : "");
                    line = line.substring(0,line.indexOf(" (")) + " (" + origin + ")";
                } else {
                    var fields = Map.of("QUANTITY ","quantity","PRICE ","price","GROSS ","gross","FEES ","fees","TAXES ","taxes","BOOKED DEBIT ","total");
                    for (var field : fields.entrySet()) if (line.startsWith(field.getKey()) && member.origins().containsKey(field.getValue()))
                        line = line.substring(0,line.indexOf(" (")) + " (" + member.origins().get(field.getValue()) + ")";
                }
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}
