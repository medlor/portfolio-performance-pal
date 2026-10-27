package pal;

import java.nio.file.Path;
import java.util.List;
import name.abuchen.portfolio.model.*;

/** Independent literal financial oracle, read through upstream public model. */
class FixtureOracle {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void transaction(Transaction t, long shares, long amount, String date, long fee, long tax) {
        check(t.getShares() == shares && t.getAmount() == amount && t.getCurrencyCode().equals("EUR")
            && t.getDateTime().toString().equals(date), "quantity/booked amount/date/currency");
        check(t.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == fee, "fee");
        check(t.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == tax, "tax");
    }
    public static void main(String[] args) throws Exception {
        if (args[0].endsWith(".pdf") || args[0].endsWith(".txt")) {
            var file = Path.of(args[0]).toFile();
            var input = args[0].endsWith(".txt") ? name.abuchen.portfolio.datatransfer.pdf.PDFInputFile.fromTextFile(file) : new name.abuchen.portfolio.datatransfer.pdf.PDFInputFile(file);
            if (!input.isTextFile()) input.convertPDFtoText();
            var errors = new java.util.ArrayList<Exception>();
            var items = new name.abuchen.portfolio.datatransfer.pdf.BaaderBankPDFExtractor(new Client()).extract(List.of(input), errors);
            check(errors.isEmpty(), "complete PDF extraction");
            var entries = items.stream().filter(i -> i.getSubject() instanceof BuySellEntry).toList();
            check(entries.size() == 1, "exactly one purchase");
            var t = ((BuySellEntry)entries.getFirst().getSubject()).getPortfolioTransaction();
            transaction(t, 200_000_000L, 5100, "2025-01-03T09:30", 100, 0);
            check(t.getGrossValue().getAmount() == 5000 && t.getSecurity().getIsin().equals("DE0000000001"), "PDF gross/security");
            System.out.println("PASS independent PDF facts"); return;
        }
        var c = Core.load(Path.of(args[0]));
        check(c.getAccounts().size() == 2 && c.getPortfolios().size() == 2 && c.getSecurities().size() == 2, "two account pairs and instruments");
        var a = c.getPortfolios().get(0); var b = c.getPortfolios().get(1);
        check(a.getReferenceAccount() == c.getAccounts().get(0) && b.getReferenceAccount() == c.getAccounts().get(1), "reference cash accounts");
        check(a.getTransactions().size() == 2 && b.getTransactions().size() == 2, "preserved closed history");
        transaction(a.getTransactions().get(0), 1_000_000_000L, 10200, "2025-01-02T11:00", 100, 100);
        transaction(a.getTransactions().get(1), 400_000_000L, 4600, "2025-02-02T11:00:01", 100, 100);
        transaction(b.getTransactions().get(0), 200_000_000L, 5100, "2025-01-03T09:30", 100, 0);
        transaction(b.getTransactions().get(1), 200_000_000L, 5700, "2025-02-03T09:30:01", 100, 200);
        for (var p : List.of(a,b)) for (var t : p.getTransactions()) {
            check(t.getType() == (t == p.getTransactions().get(0) ? PortfolioTransaction.Type.BUY : PortfolioTransaction.Type.SELL), "purchase/sale types");
            check(t.getSecurity() == c.getSecurities().get(p == a ? 0 : 1), "security identity reference");
            var cross = t.getCrossEntry();
            check(cross != null && cross.getCrossOwner(t) == p.getReferenceAccount(), "linked cash owner");
            var cash = (AccountTransaction) cross.getCrossTransaction(t);
            check(cash.getAmount() == t.getAmount() && cash.getDateTime().equals(t.getDateTime()) && cash.getSecurity() == t.getSecurity() && cash.getCrossEntry() == cross, "reciprocal linked financial entries");
        }
        var fxBuy = b.getTransactions().get(0).getUnit(Transaction.Unit.Type.GROSS_VALUE).orElseThrow();
        var fxSell = b.getTransactions().get(1).getUnit(Transaction.Unit.Type.GROSS_VALUE).orElseThrow();
        check(fxBuy.getAmount().getAmount() == 5000 && fxBuy.getForex().getAmount() == 6000 && fxBuy.getForex().getCurrencyCode().equals("USD") && fxBuy.getExchangeRate().toPlainString().equals("0.8333333333"), "purchase FX gross/rate");
        check(fxSell.getAmount().getAmount() == 6000 && fxSell.getForex().getAmount() == 7200 && fxSell.getExchangeRate().toPlainString().equals("0.8333333333"), "sale FX gross/rate");
        var dividend = c.getAccounts().get(0).getTransactions().get(2);
        check(dividend.getType() == AccountTransaction.Type.DIVIDENDS && dividend.getSecurity() == c.getSecurities().get(0) && dividend.getCrossEntry() == null, "dividend association without quantity-changing link");
        transaction(dividend, 600_000_000L, 850, "2025-02-10T11:00", 50, 100);
        check(c.getSecurities().get(0).getNote().equals("Invented public fixture") && c.getProperty("fixture.setting").equals("preserve-me"), "unrelated note/settings");
        check(c.getSettings().getBookmarks().size() == 18, "unrelated bookmark settings");
        var root = c.getTaxonomies().get(0).getRoot();
        check(root.getName().equals("All synthetic assets") && root.getAssignments().size() == 2 && root.getAssignments().get(0).getInvestmentVehicle() == c.getSecurities().get(0) && root.getAssignments().get(0).getWeight() == 10000, "classification assignments");
        System.out.println("PASS independent fixture facts");
    }
}
