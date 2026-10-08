package pal;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Independent saved-model FX facts and narrow-removal preservation check. */
class ForeignExchangeOracle {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var before = Core.load(Path.of(args[0])); var after = Core.load(Path.of(args[1]));
        var ids = new HashSet<String>();
        before.getAccounts().forEach(a -> a.getTransactions().forEach(t -> ids.add(t.getUUID())));
        before.getPortfolios().forEach(p -> p.getTransactions().forEach(t -> ids.add(t.getUUID())));
        var cash = after.getAccounts().get(1); var portfolio = after.getPortfolios().get(1);
        var payments = cash.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        var trades = portfolio.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        boolean dividend = args[2].equals("dividend"); boolean sale = args[2].equals("sale");
        check(payments.size() == 1 && trades.size() == (dividend ? 0 : 1), "exact added financial record count");
        var payment = payments.getFirst(); Transaction tx = dividend ? payment : trades.getFirst();
        check(payment.getType() == (dividend ? AccountTransaction.Type.DIVIDENDS : sale ? AccountTransaction.Type.SELL : AccountTransaction.Type.BUY), "cash type");
        check(tx.getCurrencyCode().equals("EUR") && payment.getCurrencyCode().equals("EUR"), "cash currencies");
        check(tx.getAmount() == Long.parseLong(args[3]) && payment.getAmount() == tx.getAmount(), "literal booked value");
        check(tx.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == Long.parseLong(args[6])
                && tx.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == Long.parseLong(args[7]), "cash fee/tax units");
        var unit = tx.getUnit(Transaction.Unit.Type.GROSS_VALUE).orElseThrow();
        check(unit.getAmount().getCurrencyCode().equals("EUR") && unit.getAmount().getAmount() == Long.parseLong(args[4]), "cash gross unit");
        check(unit.getForex().getCurrencyCode().equals("USD") && unit.getForex().getAmount() == Long.parseLong(args[5]), "foreign gross unit");
        check(unit.getExchangeRate().compareTo(new BigDecimal(args[8])) == 0, "explicit upstream rate direction and precision");
        check((dividend ? ((AccountTransaction)tx).getGrossValue().getAmount() : ((PortfolioTransaction)tx).getGrossValueAmount()) == unit.getAmount().getAmount(), "upstream gross relationship");
        check(tx.getUnits().count() == 1 + (Long.parseLong(args[6]) == 0 ? 0 : 1) + (Long.parseLong(args[7]) == 0 ? 0 : 1), "no extra units");
        check(tx.getSecurity() == after.getSecurities().get(1) && payment.getSecurity() == tx.getSecurity()
                && tx.getSecurity().getCurrencyCode().equals("USD"), "retained security links and currency");
        check(tx.getDateTime().equals(LocalDateTime.parse(args[9])) && payment.getDateTime().equals(tx.getDateTime()), "exact saved timestamps");
        if (dividend) {
            check(tx.getShares() == 0 && payment.getCrossEntry() == null, "dividend has no quantity/link");
        } else {
            var trade = (PortfolioTransaction)tx;
            check(trade.getType() == (sale ? PortfolioTransaction.Type.SELL : PortfolioTransaction.Type.BUY), "securities type");
            check(trade.getShares() == Long.parseLong(args[10]) && payment.getShares() == 0, "literal quantity");
            check(payment.getUnits().count() == 0, "FX and costs on securities entry only");
            check(trade.getCrossEntry().getCrossTransaction(trade) == payment && payment.getCrossEntry() == trade.getCrossEntry()
                    && trade.getCrossEntry().getCrossOwner(trade) == cash && payment.getCrossEntry().getCrossOwner(payment) == portfolio, "reciprocal entries and account owners");
        }
        portfolio.getTransactions().removeAll(trades); cash.getTransactions().removeAll(payments);
        check(Arrays.equals(Core.semanticState(before), Core.semanticState(after)), "complete unrelated model preservation");
        System.out.println("PASS saved " + args[2] + " FX amounts/rate, links, and unrelated model preservation");
    }
}
