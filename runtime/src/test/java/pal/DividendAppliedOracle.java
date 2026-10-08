package pal;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;

/** Literal cash dividend facts, independently checked in the saved upstream model. */
class DividendAppliedOracle {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var before = Core.load(Path.of(args[0])); var after = Core.load(Path.of(args[1]));
        var oldIds = new HashSet<String>();
        before.getAccounts().forEach(a -> a.getTransactions().forEach(t -> oldIds.add(t.getUUID())));
        var added = after.getAccounts().stream().flatMap(a -> a.getTransactions().stream()).filter(t -> !oldIds.contains(t.getUUID())).toList();
        check(added.size() == 1, "exactly one cash record added");
        var tx = added.getFirst();
        var cash = after.getAccounts().stream().filter(a -> a.getUUID().equals(args[2])).findFirst().orElseThrow();
        var security = after.getSecurities().stream().filter(s -> s.getUUID().equals(args[3])).findFirst().orElseThrow();
        check(cash.getTransactions().contains(tx) && tx.getSecurity() == security, "cash owner and existing security identity");
        check(tx.getType() == AccountTransaction.Type.DIVIDENDS && tx.getType().isCredit(), "upstream dividend cash credit");
        check(tx.getAmount() == 2350 && tx.getGrossValue().getAmount() == 2500, "literal booked and upstream gross values");
        check(tx.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == 100 && tx.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == 50, "literal fee/tax units");
        check(tx.getUnits().count() == 2 && tx.getCurrencyCode().equals("EUR"), "only domestic cost units");
        check(tx.getShares() == 0 && tx.getCrossEntry() == null, "no quantity or invented securities-account link");
        check(tx.getDateTime().equals(LocalDateTime.parse(args[4])), "fallback or explicit timestamp persisted exactly");
        cash.getTransactions().remove(tx);
        check(Arrays.equals(Core.semanticState(before), Core.semanticState(after)), "complete securities history, quantity/timestamps and unrelated state preserved");
        cash.getTransactions().add(tx);
        var expected = new HashSet<>(Arrays.asList(args[5].split(",")));
        for (var portfolio : after.getPortfolios()) {
            var filtered = new PortfolioClientFilter(portfolio).filter(after);
            boolean relevant = filtered.getAccounts().stream().flatMap(a -> a.getTransactions().stream()).anyMatch(t -> tx.getUUID().equals(t.getUUID()));
            check(relevant == expected.contains(portfolio.getUUID()), "independent upstream relevance for " + portfolio.getUUID());
        }
        System.out.println("PASS saved upstream dividend amounts, references, relevance and complete unrelated state preservation");
    }
}
