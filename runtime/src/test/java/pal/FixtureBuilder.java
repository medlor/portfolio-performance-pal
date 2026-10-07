package pal;

import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Money;

/** Generates only invented, public test data. Expectations are specified separately. */
public final class FixtureBuilder {
    private static void trade(Portfolio p, Security s, PortfolioTransaction.Type type, long shares, long amount, String date, long fee, long tax) {
        var e = new BuySellEntry(p, p.getReferenceAccount()); e.setType(type);
        e.setSecurity(s); e.setShares(shares); e.setAmount(amount); e.setCurrencyCode("EUR");
        e.setDate(LocalDateTime.parse(date));
        if (fee != 0) e.getPortfolioTransaction().addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of("EUR", fee)));
        if (tax != 0) e.getPortfolioTransaction().addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of("EUR", tax)));
        e.insert();
    }
    public static void main(String[] args) throws Exception {
        var c = new Client(); c.setBaseCurrency("EUR"); c.setProperty("fixture.setting", "preserve-me");
        var s = new Security("Synthetic Euro Instrument", "EUR"); s.setIsin("DE0000000001"); s.setWkn("TEST01"); s.setNote("Invented public fixture"); c.addSecurity(s);
        var usd = new Security("Synthetic Dollar Instrument", "USD"); usd.setIsin("US0000000002"); c.addSecurity(usd);
        var a = new Account("Synthetic Cash A"); var b = new Account("Synthetic Cash B"); c.addAccount(a); c.addAccount(b);
        var p = new Portfolio("Synthetic Securities A"); p.setReferenceAccount(a); c.addPortfolio(p);
        var q = new Portfolio("Synthetic Securities B"); q.setReferenceAccount(b); c.addPortfolio(q);
        trade(p, s, PortfolioTransaction.Type.BUY, 1_000_000_000, 10_200, "2025-01-02T11:00:00", 100, 100);
        trade(p, s, PortfolioTransaction.Type.SELL, 400_000_000, 4_600, "2025-02-02T11:00:01", 100, 100);
        trade(q, usd, PortfolioTransaction.Type.BUY, 200_000_000, 5_100, "2025-01-03T09:30:00", 100, 0);
        q.getTransactions().getFirst().addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE, Money.of("EUR", 5_000), Money.of("USD", 6_000), new BigDecimal("0.8333333333")));
        trade(q, usd, PortfolioTransaction.Type.SELL, 200_000_000, 5_700, "2025-02-03T09:30:01", 100, 200);
        q.getTransactions().getLast().addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE, Money.of("EUR", 6_000), Money.of("USD", 7_200), new BigDecimal("0.8333333333")));
        var dividend = new AccountTransaction(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(LocalDateTime.parse("2025-02-10T11:00:00")); dividend.setSecurity(s); dividend.setShares(600_000_000);
        dividend.setCurrencyCode("EUR"); dividend.setAmount(850); dividend.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of("EUR", 50))); dividend.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of("EUR", 100))); a.addTransaction(dividend);
        var taxonomy = new Taxonomy("Synthetic Classification");
        var root = new Classification("fixture-root", "All synthetic assets");
        root.addAssignment(new Classification.Assignment(s)); root.addAssignment(new Classification.Assignment(usd)); taxonomy.setRootNode(root); c.addTaxonomy(taxonomy);
        Core.save(c, Path.of(args[0]));
    }
}
