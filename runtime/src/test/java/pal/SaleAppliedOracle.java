package pal;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Literal sale facts at the saved-model boundary, independent of CLI planning. */
class SaleAppliedOracle {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var before = Core.load(Path.of(args[0])); var after = Core.load(Path.of(args[1]));
        var p = after.getPortfolios().getFirst(); var a = after.getAccounts().getFirst();
        var ids = new HashSet<String>();
        before.getPortfolios().forEach(owner -> owner.getTransactions().forEach(t -> ids.add(t.getUUID())));
        before.getAccounts().forEach(owner -> owner.getTransactions().forEach(t -> ids.add(t.getUUID())));
        var trades = p.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        var payments = a.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        check(trades.size() == 1 && payments.size() == 1, "exact linked entry count");
        var t = trades.getFirst(); var cash = payments.getFirst();
        long shares = new BigDecimal(args[2]).movePointRight(8).longValueExact();
        long gross = args[2].equals("6") ? 6000 : 2500;
        check(t.getType() == PortfolioTransaction.Type.SELL && cash.getType() == AccountTransaction.Type.SELL, "sale types");
        check(t.getShares() == shares && cash.getShares() == 0, "quantity securities only");
        check(t.getAmount() == gross - 150 && cash.getAmount() == gross - 150 && t.getGrossValueAmount() == gross, "gross minus costs is cash credit");
        check(t.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == 100 && t.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == 50 && cash.getUnits().count() == 0, "securities-only fees/taxes");
        check(t.getDateTime().equals(LocalDateTime.parse(args[3])) && cash.getDateTime().equals(t.getDateTime()), "preserved timestamps");
        check(t.getCurrencyCode().equals("EUR") && cash.getCurrencyCode().equals("EUR"), "currencies");
        check(t.getSecurity() == after.getSecurities().getFirst() && cash.getSecurity() == t.getSecurity(), "retained security definition");
        check(t.getCrossEntry() == cash.getCrossEntry() && t.getCrossEntry().getCrossTransaction(t) == cash
                && t.getCrossEntry().getCrossOwner(t) == a && cash.getCrossEntry().getCrossOwner(cash) == p, "reciprocal links and owners");
        p.getTransactions().removeAll(trades); a.getTransactions().removeAll(payments);
        check(Arrays.equals(Core.semanticState(before), Core.semanticState(after)), "retained transaction history and all unrelated supported state");
        System.out.println("PASS saved upstream sale facts and complete unrelated model preservation");
    }
}
