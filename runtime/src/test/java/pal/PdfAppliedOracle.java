package pal;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Literal saved-document expectations independent of the PDF planner. */
class PdfAppliedOracle {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var before = Core.load(Path.of(args[0])); var after = Core.load(Path.of(args[1]));
        int count = Integer.parseInt(args[2]);
        var ids = new HashSet<String>();
        before.getPortfolios().forEach(p -> p.getTransactions().forEach(t -> ids.add(t.getUUID())));
        before.getAccounts().forEach(a -> a.getTransactions().forEach(t -> ids.add(t.getUUID())));
        var p = after.getPortfolios().getFirst(); var a = after.getAccounts().getFirst();
        var trades = p.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        var payments = a.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        check(trades.size() == count && payments.size() == count, "exact complete member count");
        for (var t : trades) {
            boolean first = t.getNote().equals("Vorgangs-Nr.: SYNTHETIC-PAGED-0001");
            check(first || t.getNote().equals("Vorgangs-Nr.: SYNTHETIC-PAGED-0002"), "legitimate broker note only");
            var payment = (AccountTransaction)t.getCrossEntry().getCrossTransaction(t);
            check(t.getType() == PortfolioTransaction.Type.BUY && payment.getType() == AccountTransaction.Type.BUY, "linked types");
            check(t.getShares() == (first ? 200000000L : 300000000L) && payment.getShares() == 0, "literal shares");
            check(t.getAmount() == (first ? 5100 : 7600) && payment.getAmount() == t.getAmount()
                    && t.getGrossValueAmount() == (first ? 5000 : 7500), "authoritative booked/gross cents");
            check(t.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == 100 && t.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == 0
                    && payment.getUnits().count() == 0, "literal fee/tax units");
            check(t.getDateTime().equals(LocalDateTime.of(2025,1,first ? 3 : 4,9,30)) && payment.getDateTime().equals(t.getDateTime()), "member times");
            check(t.getCurrencyCode().equals("EUR") && payment.getCurrencyCode().equals("EUR")
                    && t.getSecurity() == after.getSecurities().getFirst() && payment.getSecurity() == t.getSecurity(), "currency and retained definition");
            check(t.getCrossEntry().getCrossOwner(t) == a && payment.getCrossEntry() == t.getCrossEntry()
                    && payment.getCrossEntry().getCrossOwner(payment) == p, "reciprocal owners");
        }
        p.getTransactions().removeAll(trades); a.getTransactions().removeAll(payments);
        check(Arrays.equals(Core.semanticState(before), Core.semanticState(after)), "complete unrelated model preserved");
        System.out.println("PASS saved PDF batch literal financial records, linked owners and complete unrelated model");
    }
}
