package pal;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Independent invented financial literals for anonymized current-layout statements. */
public class CurrentLayoutOracle {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        var before = Core.load(Path.of(args[1]));
        if (args[0].equals("prepare")) {
            var franc = new Security("Synthetic Franc Instrument", "CHF");
            franc.setIsin("CH0000000003"); franc.setWkn("TEST03"); before.addSecurity(franc);
            Core.save(before, Path.of(args[2]));
            System.out.println("PASS prepared invented CHF security without owner data");
            return;
        }
        var after = Core.load(Path.of(args[2]));
        String profile = args[3];
        var ids = new HashSet<String>();
        before.getPortfolios().forEach(p -> p.getTransactions().forEach(t -> ids.add(t.getUUID())));
        before.getAccounts().forEach(a -> a.getTransactions().forEach(t -> ids.add(t.getUUID())));
        var portfolio = after.getPortfolios().getFirst();
        var account = after.getAccounts().getFirst();
        var trades = portfolio.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        var cash = account.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        boolean mixed = profile.equals("mixed"), dividend = profile.equals("dividend-chf");
        check(trades.size() == (mixed ? 2 : dividend ? 0 : 1), "exact new securities entries");
        check(cash.size() == (mixed ? 3 : 1), "exact new cash entries");
        if (mixed) {
            check(trades.stream().filter(t -> t.getType() == PortfolioTransaction.Type.BUY).count() == 1
                    && trades.stream().filter(t -> t.getType() == PortfolioTransaction.Type.SELL).count() == 1, "mixed contains exactly one purchase and one sale");
        }
        for (var tx : trades) {
            boolean sale = tx.getType() == PortfolioTransaction.Type.SELL;
            check(mixed || sale == profile.equals("sale"), "literal trade type");
            check(sale || tx.getType() == PortfolioTransaction.Type.BUY, "purchase type");
            check(tx.getDateTime().equals(LocalDateTime.parse(sale ? "2025-03-02T09:30:01" : "2025-03-01T09:30:00")), "execution date/time rather than order or value date");
            check(tx.getShares() == 200_000_000L && tx.getAmount() == (sale ? 6_000 : 5_000), "literal shares and authoritative cash total");
            check(tx.getGrossValue().getAmount() == tx.getAmount() && tx.getUnits().count() == 0, "domestic gross and absent costs");
            check(tx.getCurrencyCode().equals("EUR") && tx.getSecurity().getIsin().equals("DE0000000001"), "existing EUR security");
            var linked = tx.getCrossEntry().getCrossTransaction(tx);
            check(cash.contains(linked) && linked.getCrossEntry() == tx.getCrossEntry()
                    && tx.getCrossEntry().getCrossOwner(tx) == account && linked.getAmount() == tx.getAmount(), "reciprocal cash link");
            check(linked instanceof AccountTransaction cashTx
                    && cashTx.getType() == (sale ? AccountTransaction.Type.SELL : AccountTransaction.Type.BUY)
                    && linked.getDateTime().equals(tx.getDateTime()) && linked.getSecurity() == tx.getSecurity()
                    && linked.getCurrencyCode().equals("EUR") && linked.getUnits().count() == 0
                    && linked.getCrossEntry().getCrossOwner(linked) == portfolio, "cash type, time, security, currency and reciprocal securities owner");
        }
        var dividends = cash.stream().filter(t -> t.getType() == AccountTransaction.Type.DIVIDENDS).toList();
        check(dividends.size() == (mixed || dividend ? 1 : 0), "exact dividend count");
        for (var tx : dividends) {
            check(tx.getDateTime().equals(LocalDateTime.parse("2025-03-05T11:00:00")), "disclosed dividend fallback");
            check(tx.getShares() == 1_000_000_000L && tx.getAmount() == 1_195 && tx.getCurrencyCode().equals("EUR"), "literal dividend shares and booked credit");
            check(tx.getGrossValue().getAmount() == 1_600 && tx.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == 405
                    && tx.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == 0, "gross and three summed first-page taxes; continuation is not another dividend");
            check(tx.getSecurity().getIsin().equals("CH0000000003") && tx.getSecurity().getCurrencyCode().equals("CHF") && tx.getCrossEntry() == null, "existing CHF security and cash-only dividend");
            var fx = tx.getUnit(Transaction.Unit.Type.GROSS_VALUE).orElseThrow();
            check(fx.getAmount().getAmount() == 1_600 && fx.getForex().getAmount() == 2_000
                    && fx.getForex().getCurrencyCode().equals("CHF") && fx.getExchangeRate().compareTo(new BigDecimal("0.8")) == 0, "printed EUR/CHF inversion and preserved foreign gross");
        }
        portfolio.getTransactions().removeAll(trades); account.getTransactions().removeAll(cash);
        check(Arrays.equals(Core.semanticState(before), Core.semanticState(after)), "all unrelated records, securities, accounts, settings and classifications preserved");
        System.out.println("PASS saved current-layout " + profile + " literals, links, FX and complete unrelated state");
    }
}
