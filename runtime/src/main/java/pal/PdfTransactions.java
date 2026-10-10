package pal;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import name.abuchen.portfolio.datatransfer.*;
import name.abuchen.portfolio.datatransfer.pdf.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;

/** Bind and validate PP-produced records; never reconstruct a transaction from PDF text. */
final class PdfTransactions {
    private PdfTransactions() {}

    static PdfTrades.Member member(Client client, Portfolio securities, Account cash, CommandLine command,
            PDFInputFile input, String filename, String documentHash, int index) {
        var extractor = new ObservedBaaderExtractor();
        var errors = new ArrayList<Exception>();
        final List<Extractor.Item> items;
        try { items = extractor.extract(new SecurityCache(extractor.getClient()), input, errors); }
        catch (RuntimeException e) { throw PdfTrades.rejected(index,"Portfolio Performance extraction failed: " + e.getMessage()); }
        if (!errors.isEmpty()) throw PdfTrades.rejected(index,"Portfolio Performance extraction failed: " + errors.getFirst().getMessage());
        // One complete statement per page group is the qualified envelope. Reject
        // unsupported/failure/skip items and additional cash adjustments as a batch.
        if (items.size() != 1 || items.getFirst().isFailure() || items.getFirst().isSkipped())
            throw PdfTrades.rejected(index,"PP must produce exactly one valid purchase, sale or dividend per statement");
        var item = items.getFirst();
        BuySellEntry entry = item.getSubject() instanceof BuySellEntry trade ? trade : null;
        AccountTransaction dividend = item.getSubject() instanceof AccountTransaction tx && tx.getType() == AccountTransaction.Type.DIVIDENDS ? tx : null;
        if (entry == null && dividend == null || entry != null && entry.getPortfolioTransaction().getType() != PortfolioTransaction.Type.BUY
                && entry.getPortfolioTransaction().getType() != PortfolioTransaction.Type.SELL)
            throw PdfTrades.rejected(index,"Unsupported PP transaction type");
        var tx = entry != null ? entry.getPortfolioTransaction() : dividend;
        if (tx.getSecurity() == null || tx.getShares() < 0 || entry != null && tx.getShares() == 0 || tx.getAmount() < 0 || tx.getDateTime() == null)
            throw PdfTrades.rejected(index,"Incomplete or invalid PP transaction");
        var options = new LinkedHashMap<>(command.options());
        var identity = extractor.identity(tx.getSecurity());
        if (identity == null) throw PdfTrades.rejected(index,"Missing PP security evidence");
        for (String key : List.of("isin", "wkn")) {
            String extracted = identity.get(key), supplied = options.get(key);
            if (extracted != null) {
                if (supplied != null && !supplied.equalsIgnoreCase(extracted)) throw conflict(index,key);
                boolean known = client.getSecurities().stream().anyMatch(s -> extracted.equalsIgnoreCase(key.equals("isin") ? s.getIsin() : s.getWkn()));
                if (known || supplied != null) options.put(key,extracted);
            }
        }
        var selected = Securities.existing(client, new CommandLine("import",options));
        for (String key : List.of("isin", "wkn")) {
            String original = key.equals("isin") ? selected.getIsin() : selected.getWkn();
            if (original != null && identity.get(key) != null && !original.equalsIgnoreCase(identity.get(key))) throw conflict(index,key);
        }
        if (!selected.getCurrencyCode().equals(tx.getSecurity().getCurrencyCode()))
            throw PdfTrades.rejected(index,"Extracted security currency conflicts with existing definition");
        item.setSecurity(selected);
        item.setAccountPrimary(cash);
        item.setPortfolioPrimary(securities);
        boolean sale = entry != null && entry.getPortfolioTransaction().getType() == PortfolioTransaction.Type.SELL;
        var origins = new LinkedHashMap<String,String>();
        for (String key : List.of("quantity", "total", "date")) origins.put(key,"PDF");
        cost(tx, Transaction.Unit.Type.FEE, "fees", extractor.feesPresent(), command, origins, index);
        cost(tx, Transaction.Unit.Type.TAX, "taxes", extractor.taxesPresent(), command, origins, index);
        var timestamp = tx.getDateTime();
        if (command.flag("date") && !History.date(command.value("date"),"date").equals(timestamp.toLocalDate())) throw conflict(index,"date");
        boolean fallback = !extractor.explicitTime() && !command.flag("time");
        if (extractor.explicitTime()) {
            if (command.flag("time") && !suppliedTime(command).equals(timestamp.toLocalTime())) throw conflict(index,"time");
            origins.put("time","PDF");
        } else {
            timestamp = timestamp.toLocalDate().atTime(command.flag("time") ? suppliedTime(command) : LocalTime.of(11,0,sale ? 1 : 0));
            if (command.flag("time")) origins.put("time","SUPPLEMENT");
        }
        if (entry != null) {
            if (securities == null) throw PdfTrades.rejected(index,"Select a securities account for a purchase or sale");
            entry.setPortfolio(securities); entry.setAccount(cash); entry.setDate(timestamp); entry.setSource(filename);
        } else { dividend.setDateTime(timestamp); dividend.setSource(filename); }
        // The model's booked amount stays authoritative. PP calculates domestic
        // gross and unit price from it; ignored printed fields are never reparsed.
        assertMoney(command,"total",tx.getAmount(),index);
        assertMoney(command,"gross",tx.getGrossValue().getAmount(),index);
        if (command.flag("quantity") && Amounts.scaled(command.value("quantity"),Values.Share.precision(),"quantity",entry != null) != tx.getShares()) throw conflict(index,"quantity");
        var forex = tx.getUnit(Transaction.Unit.Type.GROSS_VALUE).orElse(null);
        if (command.flag("forex-gross") && (forex == null || forex.getForex() == null
                || Amounts.money(command.value("forex-gross"),"forex-gross") != forex.getForex().getAmount())) throw conflict(index,"forex-gross");
        if (command.flag("exchange-rate") && (forex == null || new BigDecimal(command.value("exchange-rate")).compareTo(forex.getExchangeRate()) != 0)) throw conflict(index,"exchange-rate");
        UpstreamImports.validate(item);
        String note = item.getSubject().getNote();
        String reference = reference(note);
        origins.put("reference",reference == null ? "DOCUMENT" : "PDF");
        if (reference == null) reference = "sha256:" + documentHash + ":member:" + index;
        String grossOrigin = forex != null ? "PP foreign gross" : "PP derived from booked and costs";
        origins.put("gross",grossOrigin);
        long fees = tx.getUnitSum(Transaction.Unit.Type.FEE).getAmount(), taxes = tx.getUnitSum(Transaction.Unit.Type.TAX).getAmount();
        if (entry != null) {
            var converter = new CurrencyConverterImpl(new ExchangeRateProviderFactory(client),selected.getCurrencyCode());
            var quote = entry.getPortfolioTransaction().getGrossPricePerShare(converter);
            var price = BigDecimal.valueOf(quote.getAmount(),Values.Quote.precision());
            if (command.flag("price") && new BigDecimal(command.value("price")).compareTo(price) != 0) throw conflict(index,"price (PP derived)");
            var amounts = new Amounts.Purchase(tx.getShares(),price,tx.getGrossValue().getAmount(),fees,taxes,tx.getAmount(),
                    "PP derived from gross / quantity",grossOrigin,"PDF",origins.get("fees"),origins.get("taxes"),forex,"PDF");
            return new PdfTrades.Member(index,reference,new Purchase.Plan(entry,amounts,fallback),null,origins);
        }
        if (command.flag("price")) throw PdfTrades.rejected(index,"PP does not extract a dividend unit price; --price cannot supplement it");
        var amounts = new Amounts.Dividend(tx.getGrossValue().getAmount(),fees,taxes,tx.getAmount(),grossOrigin,"PDF",
                origins.get("fees"),origins.get("taxes"),forex,"PDF");
        return new PdfTrades.Member(index,reference,null,new Dividend.Plan(cash,dividend,amounts,fallback),origins);
    }

    private static void cost(Transaction tx, Transaction.Unit.Type type, String key, boolean observed,
            CommandLine command, Map<String,String> origins, int index) {
        boolean present = observed || tx.getUnit(type).isPresent();
        if (command.flag(key)) {
            long supplied = Amounts.money(command.value(key),key);
            if (present && supplied != tx.getUnitSum(type).getAmount()) throw conflict(index,key);
            if (!present && supplied != 0) tx.addUnit(new Transaction.Unit(type,Money.of(tx.getCurrencyCode(),supplied)));
        }
        origins.put(key,present ? "PDF" : command.flag(key) ? "SUPPLEMENT" : "default zero");
    }

    private static void assertMoney(CommandLine command, String key, long amount, int index) {
        if (command.flag(key) && Amounts.money(command.value(key),key) != amount) throw conflict(index,key);
    }

    private static IllegalArgumentException conflict(int index, String key) { return PdfTrades.rejected(index,"CLI conflicts with extracted " + key); }

    private static LocalTime suppliedTime(CommandLine command) {
        try { return LocalTime.parse(command.value("time")); }
        catch (DateTimeException e) { throw new IllegalArgumentException("Use a local --time HH:MM[:SS[.fraction]]",e); }
    }

    private static String reference(String note) {
        if (note == null) return null;
        for (String prefix : List.of("Vorgangs-Nr.: ","Transaction No.: ")) {
            if (note.startsWith(prefix)) return note.substring(prefix.length()).split(" \\| ",2)[0];
        }
        return null;
    }
}
