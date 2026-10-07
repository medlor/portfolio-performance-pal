package pal;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Independently specified committed financial facts, inspected with pinned core. */
class PurchaseAppliedOracle {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var before = Core.load(Path.of(args[0])); var after = Core.load(Path.of(args[1]));
        int count = Integer.parseInt(args[2]);
        var p = after.getPortfolios().getFirst(); var a = after.getAccounts().getFirst();
        var originalIds = new HashSet<String>();
        before.getPortfolios().forEach(owner -> owner.getTransactions().forEach(t -> originalIds.add(t.getUUID())));
        before.getAccounts().forEach(owner -> owner.getTransactions().forEach(t -> originalIds.add(t.getUUID())));
        var trades = p.getTransactions().stream().filter(t -> !originalIds.contains(t.getUUID())).toList();
        var payments = a.getTransactions().stream().filter(t -> !originalIds.contains(t.getUUID())).toList();
        check(trades.size() == count && payments.size() == count, "exact independently specified new linked record count");
        for (var t : trades) {
            var cash = (AccountTransaction)t.getCrossEntry().getCrossTransaction(t);
            check(t.getType() == PortfolioTransaction.Type.BUY && cash.getType() == AccountTransaction.Type.BUY, "purchase types");
            check(t.getShares() == 250000000L && cash.getShares() == 0, "quantity securities only");
            check(t.getAmount() == 2650 && cash.getAmount() == 2650 && t.getGrossValueAmount() == 2500, "literal cents survive actual reload");
            check(t.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == 100 && t.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == 50 && cash.getUnits().count() == 0, "costs only on securities");
            check(t.getDateTime().equals(LocalDateTime.of(2025,3,1,11,0)) && t.getDateTime().equals(cash.getDateTime()), "recorded fallback time");
            check(t.getSecurity() == after.getSecurities().getFirst() && cash.getSecurity() == t.getSecurity() && cash.getCrossEntry() == t.getCrossEntry(), "same retained security and reciprocal links");
            check(t.getCurrencyCode().equals("EUR") && cash.getCurrencyCode().equals("EUR") && t.getCrossEntry().getCrossOwner(t) == a && cash.getCrossEntry().getCrossOwner(cash) == p, "currencies and actual owners");
        }
        p.getTransactions().removeAll(trades); a.getTransactions().removeAll(payments);
        check(Arrays.equals(Core.semanticState(before), Core.semanticState(after)), "complete unrelated supported state preserved after narrow planned-record removal");
        System.out.println("PASS committed upstream purchase literals and complete unrelated model preservation");
    }
}
