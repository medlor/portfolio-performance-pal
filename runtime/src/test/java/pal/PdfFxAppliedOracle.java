package pal;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Independent literal cents, quantities and rate direction for the invented FX statements. */
class PdfFxAppliedOracle {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var before = Core.load(Path.of(args[0])); var after = Core.load(Path.of(args[1]));
        var ids = new HashSet<String>();
        before.getAccounts().forEach(a -> a.getTransactions().forEach(t -> ids.add(t.getUUID())));
        before.getPortfolios().forEach(p -> p.getTransactions().forEach(t -> ids.add(t.getUUID())));
        var p = after.getPortfolios().getFirst(); var a = after.getAccounts().getFirst();
        var trades = p.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        var cash = a.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        var names = args[2].equals("mixed") ? List.of("purchase", "sale", "dividend") : List.of(args[2]);
        check(cash.size() == names.size() && trades.size() == names.stream().filter(n -> !n.equals("dividend")).count(), "exact whole-batch counts");
        for (String name : names) {
            boolean dividend = name.equals("dividend"), sale = name.equals("sale");
            String ref = dividend ? "SYNTHETIC-FX-DIVIDEND" : sale ? "SYNTHETIC-FX-SELL" : "SYNTHETIC-FX-BUY";
            var payments = cash.stream().filter(t -> ("Vorgangs-Nr.: " + ref).equals(t.getNote())).toList();
            check(payments.size() == 1, "unique saved member");
            var payment = payments.getFirst();
            Transaction tx = dividend ? payment : trades.stream().filter(t -> ("Vorgangs-Nr.: " + ref).equals(t.getNote())).findFirst().orElseThrow();
            check(payment.getType() == (dividend ? AccountTransaction.Type.DIVIDENDS : sale ? AccountTransaction.Type.SELL : AccountTransaction.Type.BUY), "cash transaction type");
            check(tx.getShares() == (dividend ? 1_000_000_000L : sale ? 100_000_000L : 200_000_000L), "literal shares");
            check(tx.getCurrencyCode().equals("EUR") && tx.getAmount() == (dividend ? 1880 : sale ? 280 : 920), "authoritative booked EUR cents");
            check(tx.getGrossValue().getAmount() == (dividend ? 2000 : sale ? 400 : 800), "literal gross EUR cents");
            check(tx.getSecurity() == after.getSecurities().get(1) && payment.getSecurity() == tx.getSecurity(), "existing USD security identity");
            var unit = tx.getUnit(Transaction.Unit.Type.GROSS_VALUE).orElseThrow();
            check(unit.getAmount().getCurrencyCode().equals("EUR") && unit.getAmount().getAmount() == tx.getGrossValue().getAmount(), "cash gross unit");
            check(unit.getForex().getCurrencyCode().equals("USD") && unit.getForex().getAmount() == (dividend ? 2500 : sale ? 500 : 1000), "foreign gross literal");
            check(unit.getExchangeRate().compareTo(new BigDecimal("0.8")) == 0, "EUR per USD (inverse of printed EUR/USD 1.25)");
            check(tx.getUnits().count() == 3, "exact gross, fee and tax units");
            for (var type : List.of(Transaction.Unit.Type.FEE, Transaction.Unit.Type.TAX)) {
                var cost = tx.getUnit(type).orElseThrow(); boolean fee = type == Transaction.Unit.Type.FEE;
                check(cost.getAmount().getCurrencyCode().equals("EUR") && cost.getAmount().getAmount() == (fee ? 80 : 40), "converted cost cents");
                if (name.equals("missing-rate")) check(cost.getForex() == null, "supplement leaves extracted cash costs intact");
                else check(cost.getForex().getCurrencyCode().equals("USD") && cost.getForex().getAmount() == (fee ? 100 : 50)
                        && cost.getExchangeRate().compareTo(new BigDecimal("0.8")) == 0, "original foreign cost and rate");
            }
            check(tx.getDateTime().equals(LocalDateTime.parse(dividend ? "2025-03-01T11:00:00" : "2025-01-03T09:30:00")), "literal explicit/fallback timestamp");
            if (dividend) check(payment.getCrossEntry() == null, "dividend does not change securities quantity");
            else {
                var trade = (PortfolioTransaction)tx;
                check(trade.getType() == (sale ? PortfolioTransaction.Type.SELL : PortfolioTransaction.Type.BUY), "securities type");
                check(trade.getCrossEntry().getCrossTransaction(trade) == payment && payment.getCrossEntry() == trade.getCrossEntry()
                        && trade.getCrossEntry().getCrossOwner(trade) == a && payment.getCrossEntry().getCrossOwner(payment) == p, "reciprocal account links");
                check(payment.getAmount() == trade.getAmount() && payment.getUnits().count() == 0 && payment.getDateTime().equals(trade.getDateTime()), "linked cash semantics");
            }
        }
        p.getTransactions().removeAll(trades); a.getTransactions().removeAll(cash);
        check(Arrays.equals(Core.semanticState(before), Core.semanticState(after)), "complete unrelated financial and model state");
        System.out.println("PASS saved FX PDF literal amounts, rates, costs, links and preservation");
    }
}
