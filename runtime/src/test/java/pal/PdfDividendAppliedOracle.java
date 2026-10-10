package pal;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Independently authored literal fixture facts, read through pinned persistence. */
class PdfDividendAppliedOracle {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var before = Core.load(Path.of(args[0])); var after = Core.load(Path.of(args[1]));
        var facts = Files.readAllLines(Path.of(args[2]));
        var ids = new HashSet<String>();
        before.getPortfolios().forEach(p -> p.getTransactions().forEach(t -> ids.add(t.getUUID())));
        before.getAccounts().forEach(a -> a.getTransactions().forEach(t -> ids.add(t.getUUID())));
        var p = after.getPortfolios().getFirst();
        var a = args.length > 3 ? after.getAccounts().stream().filter(owner -> owner.getUUID().equals(args[3])).findFirst().orElseThrow() : after.getAccounts().getFirst();
        var trades = p.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        var payments = a.getTransactions().stream().filter(t -> !ids.contains(t.getUUID())).toList();
        check(trades.size() == facts.stream().filter(f -> !f.split("\\t")[1].equals("DIVIDENDS")).count() && payments.size() == facts.size(), "exact linked batch size");
        for (String fact : facts) {
            var f = fact.split("\\t");
            var cashMatches = payments.stream().filter(t -> ("Vorgangs-Nr.: " + f[0]).equals(t.getNote())).toList();
            check(cashMatches.size() == 1,"unique cash reference");
            if (f[1].equals("DIVIDENDS")) {
                var d = cashMatches.getFirst();
                check(d.getType() == AccountTransaction.Type.DIVIDENDS && d.getCrossEntry() == null,"security-associated cash credit only");
                check(d.getShares() == new BigDecimal(f[2]).movePointRight(8).longValueExact(),"statement distribution quantity, no securities transaction");
                check(d.getAmount() == Long.parseLong(f[3]) && d.getGrossValue().getAmount() == Long.parseLong(f[4]),"literal dividend booked/gross");
                check(d.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == Long.parseLong(f[5])
                        && d.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == Long.parseLong(f[6]),"literal dividend fee/tax units");
                check(d.getCurrencyCode().equals("EUR") && d.getSecurity() == after.getSecurities().getFirst(),"retained dividend security/currency");
                check(d.getDateTime().equals(LocalDateTime.parse(f[7])),"exact dividend explicit/fallback time");
                continue;
            }
            var matches = trades.stream().filter(t -> ("Vorgangs-Nr.: " + f[0]).equals(t.getNote())).toList();
            check(matches.size() == 1, "unique statement reference");
            var t = matches.getFirst(); var c = (AccountTransaction)t.getCrossEntry().getCrossTransaction(t);
            check(t.getType().name().equals(f[1]) && c.getType().name().equals(f[1]), "trade and cash types");
            check(t.getShares() == new BigDecimal(f[2]).movePointRight(8).longValueExact() && c.getShares() == 0, "literal shares");
            check(t.getAmount() == Long.parseLong(f[3]) && c.getAmount() == t.getAmount()
                    && t.getGrossValueAmount() == Long.parseLong(f[4]), "authoritative booked and gross cents");
            check(t.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == Long.parseLong(f[5])
                    && t.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == Long.parseLong(f[6]) && c.getUnits().count() == 0, "fees and taxes");
            check(t.getDateTime().equals(LocalDateTime.parse(f[7])) && c.getDateTime().equals(t.getDateTime()), "statement/fallback time");
            check(t.getCurrencyCode().equals("EUR") && c.getCurrencyCode().equals("EUR")
                    && t.getSecurity() == after.getSecurities().getFirst() && c.getSecurity() == t.getSecurity(), "currency and retained security");
            check(payments.contains(c) && t.getCrossEntry().getCrossOwner(t) == a && c.getCrossEntry() == t.getCrossEntry()
                    && c.getCrossEntry().getCrossOwner(c) == p, "reciprocal owners and links");
        }
        p.getTransactions().removeAll(trades); a.getTransactions().removeAll(payments);
        check(Arrays.equals(Core.semanticState(before), Core.semanticState(after)), "complete unrelated state and history preserved");
        System.out.println("PASS saved PDF dividend/mixed batch literal facts and complete model preservation");
    }
}
