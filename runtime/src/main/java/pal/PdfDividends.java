package pal;

import java.math.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import name.abuchen.portfolio.datatransfer.*;
import name.abuchen.portfolio.datatransfer.pdf.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Values;

/** Strict evidence coverage for the fixture-tested German EUR Baader dividend profile. */
final class PdfDividends {
    private static final String NUMBER = "(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+)(?:,[0-9]+)?";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);

    static PdfTrades.Member plan(Client client, Account cash, CommandLine command, String text, String filename, int index) {
        var facts = new LinkedHashMap<String,String>();
        String reference = null;
        int bank = 0, page = 0, heading = 0, nominal = 0, quantity = 0;
        boolean continuation = false, expectTime = false;
        for (String raw : text.split("\\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (expectTime) {
                var match = Pattern.compile("([0-9]{2}\\.[0-9]{2}\\.[0-9]{4}) ([0-9]{2}:[0-9]{2}:[0-9]{2})").matcher(line);
                if (!match.matches()) throw PdfTrades.rejected(index,"Malformed dividend date/time");
                try {
                    PdfTrades.put(facts,"date",LocalDate.parse(match.group(1),DATE).toString(),index);
                    PdfTrades.put(facts,"time",LocalTime.parse(match.group(2)).toString(),index);
                } catch (DateTimeException e) { throw PdfTrades.rejected(index,"Invalid dividend date/time"); }
                expectTime = false; continue;
            }
            if (continuation) {
                if (line.matches(".*(?:ISIN:|Konto |Bruttobetrag|Provision|steuer|Zahlungsdatum|Vorgangs|Seite|abrechnung|[A-Z]{3} [0-9]).*"))
                    throw PdfTrades.rejected(index,"Missing or ambiguous security name continuation");
                continuation = false; continue;
            }
            if (line.equals("Baader Bank AG")) { bank++; continue; }
            if (line.equals("Seite 1/1")) { page++; continue; }
            if (line.equals("Dividendenabrechnung")) { heading++; continue; }
            var match = Pattern.compile("Vorgangs-Nr\\.: (.+)").matcher(line);
            if (match.matches()) {
                if (reference != null) throw PdfTrades.rejected(index,"Repeated broker reference");
                reference = match.group(1); continue;
            }
            match = Pattern.compile("Nominale ISIN: ([A-Z]{2}[A-Z0-9]{9}[0-9]) WKN: ([A-Z0-9]{6}) Ausschüttung").matcher(line);
            if (match.matches()) {
                nominal++; PdfTrades.put(facts,"isin",match.group(1),index); PdfTrades.put(facts,"wkn",match.group(2),index); continue;
            }
            match = Pattern.compile("STK (" + NUMBER + ") (.+) EUR (" + NUMBER + ") p\\.STK").matcher(line);
            if (match.matches()) {
                quantity++; PdfTrades.put(facts,"quantity",PdfTrades.decimal(match.group(1)),index);
                PdfTrades.put(facts,"price",PdfTrades.decimal(match.group(3)),index); continuation = true; continue;
            }
            match = Pattern.compile("(Bruttobetrag|Provision|Kapitalertragsteuer) EUR (" + NUMBER + ")( -)?").matcher(line);
            if (match.matches()) {
                if (match.group(1).equals("Bruttobetrag") && match.group(3) != null) throw PdfTrades.rejected(index,"Negative gross dividend");
                PdfTrades.put(facts,switch (match.group(1)) { case "Bruttobetrag" -> "gross"; case "Provision" -> "fees"; default -> "taxes"; },PdfTrades.decimal(match.group(2)),index); continue;
            }
            match = Pattern.compile("Zu Gunsten Konto [A-Za-z0-9]+ Valuta: ([0-9]{2}\\.[0-9]{2}\\.[0-9]{4}) EUR (" + NUMBER + ")").matcher(line);
            if (match.matches()) {
                try { PdfTrades.put(facts,"booking-date",LocalDate.parse(match.group(1),DATE).toString(),index); }
                catch (DateTimeException e) { throw PdfTrades.rejected(index,"Invalid dividend value date"); }
                PdfTrades.put(facts,"total",PdfTrades.decimal(match.group(2)),index); continue;
            }
            if (line.equals("Zahlungsdatum Zahlungsuhrzeit")) { expectTime = true; continue; }
            throw PdfTrades.rejected(index,"Unsupported or uncovered dividend statement line: " + line);
        }
        if (bank != 1 || page != 1 || heading != 1 || nominal != 1 || quantity != 1 || reference == null || continuation || expectTime)
            throw PdfTrades.rejected(index,"Incomplete or ambiguous Baader dividend member");
        String bookingDate = facts.remove("booking-date");
        if (!facts.containsKey("date") && bookingDate != null) facts.put("date",bookingDate);
        if (bookingDate != null && !bookingDate.equals(facts.get("date"))) throw PdfTrades.rejected(index,"Dividend payment and value dates disagree");
        var options = new LinkedHashMap<>(command.options());
        for (var fact : facts.entrySet()) {
            if (options.containsKey(fact.getKey()) && !PdfTrades.same(fact.getKey(),options.get(fact.getKey()),fact.getValue()))
                throw PdfTrades.rejected(index,"CLI conflicts with extracted " + fact.getKey());
            options.put(fact.getKey(),fact.getValue());
        }
        if (!options.containsKey("total")) throw PdfTrades.rejected(index,"Missing booked amount requires --total");
        long shares = Amounts.scaled(facts.get("quantity"),Values.Share.precision(),"quantity",true);
        long price = Amounts.scaled(facts.get("price"),Values.Quote.precision(),"price",false);
        var determined = Dividend.plan(client,cash,new CommandLine("dividend",options));
        long pricedGross = BigDecimal.valueOf(shares,Values.Share.precision()).multiply(BigDecimal.valueOf(price,Values.Quote.precision()))
                .setScale(Values.Money.precision(),RoundingMode.HALF_UP).movePointRight(Values.Money.precision()).longValueExact();
        if (pricedGross != determined.amounts().gross()) throw PdfTrades.rejected(index,"Dividend gross contradicts statement quantity x distribution");
        var expected = determined.transaction();
        if (expected.getDateTime().getNano() != 0) throw PdfTrades.rejected(index,"Supplemental PDF time must have whole-second precision");
        // The pinned extractor reads value date only. Verify that result before restoring
        // the independently covered explicit time or the disclosed date-only fallback.
        String completed = text;
        if (!facts.containsKey("total")) completed += "Zu Gunsten Konto SUPPLEMENT Valuta: " + expected.getDateTime().toLocalDate().format(DATE) + " EUR " + PdfTrades.german(options.get("total")) + "\n";
        if (!facts.containsKey("fees") && command.flag("fees")) completed += "Provision EUR " + PdfTrades.german(options.get("fees")) + "\n";
        if (!facts.containsKey("taxes") && command.flag("taxes")) completed += "Kapitalertragsteuer EUR " + PdfTrades.german(options.get("taxes")) + " -\n";
        var extractionClient = new Client();
        var input = PDFInputFile.createTestCase(filename,completed).getFirst();
        var errors = new ArrayList<Exception>();
        var extracted = new BaaderBankPDFExtractor(extractionClient).extract(new SecurityCache(extractionClient),input,errors);
        if (!errors.isEmpty() || extracted.size() != 1 || extracted.getFirst().isFailure() || extracted.getFirst().isSkipped()
                || !(extracted.getFirst().getSubject() instanceof AccountTransaction tx))
            throw PdfTrades.rejected(index,"Upstream extraction did not produce one valid dividend");
        if (tx.getType() != AccountTransaction.Type.DIVIDENDS || tx.getShares() != shares || tx.getAmount() != expected.getAmount()
                || !tx.getCurrencyCode().equals(expected.getCurrencyCode()) || !tx.getSecurity().getCurrencyCode().equals("EUR")
                || !tx.getSecurity().getIsin().equals(facts.get("isin")) || !tx.getSecurity().getWkn().equals(facts.get("wkn"))
                || tx.getGrossValue().getAmount() != determined.amounts().gross()
                || tx.getUnitSum(Transaction.Unit.Type.FEE).getAmount() != determined.amounts().fees()
                || tx.getUnitSum(Transaction.Unit.Type.TAX).getAmount() != determined.amounts().taxes()
                || !tx.getDateTime().equals(expected.getDateTime().toLocalDate().atStartOfDay()))
            throw PdfTrades.rejected(index,"Extracted dividend values disagree with exact statement evidence");
        tx.setSecurity(expected.getSecurity()); tx.setDateTime(expected.getDateTime());
        var plan = new Dividend.Plan(cash,tx,determined.amounts(),determined.fallbackTime());
        var origins = new LinkedHashMap<String,String>();
        facts.keySet().forEach(k -> origins.put(k,"PDF"));
        for (String key : Set.of("gross","total","fees","taxes","date","time"))
            if (!origins.containsKey(key) && command.flag(key)) origins.put(key,"SUPPLEMENT");
        return new PdfTrades.Member(index,reference,null,plan,origins);
    }
}
