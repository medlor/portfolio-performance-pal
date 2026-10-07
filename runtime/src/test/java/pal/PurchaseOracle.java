package pal;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Independent literals read from the public proposed upstream financial models. */
class PurchaseOracle {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var client = Core.load(Path.of(args[0]));
        byte[] before = Core.semanticState(client);
        var securities = client.getPortfolios().getFirst(); var cash = client.getAccounts().getFirst();
        var line = CommandLine.parse("purchase", "--security", "DE0000000001", "--quantity", "2.5", "--price", "10", "--fees", "1", "--taxes", "0.50", "--date", "2025-03-01");
        var plan = Purchase.plan(client, securities, cash, line);
        var trade = plan.entry().getPortfolioTransaction(); var payment = plan.entry().getAccountTransaction();
        check(trade.getType() == PortfolioTransaction.Type.BUY && payment.getType() == AccountTransaction.Type.BUY, "both entries are purchases");
        check(trade.getShares() == 250000000L && payment.getShares() == 0, "literal quantity on securities only");
        check(trade.getAmount() == 2650 && payment.getAmount() == 2650 && trade.getGrossValueAmount() == 2500, "literal gross/booked financial amounts");
        check(trade.getCurrencyCode().equals("EUR") && payment.getCurrencyCode().equals("EUR"), "same recorded currency");
        check(trade.getDateTime().equals(LocalDateTime.of(2025,3,1,11,0)) && trade.getDateTime().equals(payment.getDateTime()), "literal fallback timestamp synchronized");
        check(trade.getSecurity() == client.getSecurities().getFirst() && payment.getSecurity() == trade.getSecurity(), "same existing security definition");
        check(trade.getCrossEntry() == plan.entry() && payment.getCrossEntry() == plan.entry()
            && trade.getCrossEntry().getCrossOwner(trade) == cash && payment.getCrossEntry().getCrossOwner(payment) == securities, "reciprocal account cross entries");
        check(trade.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == 100 && trade.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == 50
            && payment.getUnits().count() == 0 && trade.getUnit(Transaction.Unit.Type.GROSS_VALUE).isEmpty(), "cost units only on securities; no invented same-currency gross unit");
        check(Arrays.equals(before, Core.semanticState(client)), "preview preserves complete loaded upstream model");
        var midnight = Purchase.plan(client, securities, cash, CommandLine.parse("purchase", "--security", "DE0000000001", "--quantity", "1", "--gross", "0", "--date", "2025-03-01", "--time", "00:00:00"));
        check(!midnight.fallbackTime() && midnight.entry().getPortfolioTransaction().getDateTime().equals(LocalDateTime.of(2025,3,1,0,0)), "explicit midnight preserved");
        check(midnight.entry().getAccountTransaction().getAmount() == 0 && midnight.entry().getPortfolioTransaction().getShares() == 100000000L, "zero booked positive quantity modeled explicitly");
        String[][] facts = {{"1", "1.005", "101"}, {"3", "0.33333333", "100"},
            {"1.23456789", "12.34567890", "1524"}, {"12345678.12345678", "12.34567890", "15241577791"},
            {"92233720368.54775807", "0.01", "92233720369"}, {"1", "0.00000001", "0"}};
        for (var fact : facts) {
            var proposed = Purchase.plan(client, securities, cash, CommandLine.parse("purchase", "--security", "DE0000000001", "--quantity", fact[0], "--price", fact[1], "--date", "2025-03-01", "--time", "12:34:56.123456789"));
            check(proposed.entry().getPortfolioTransaction().getGrossValueAmount() == Long.parseLong(fact[2])
                && proposed.entry().getAccountTransaction().getAmount() == Long.parseLong(fact[2]), "independent cents boundary " + fact[0]);
            check(proposed.entry().getAccountTransaction().getDateTime().equals(LocalDateTime.of(2025,3,1,12,34,56,123456789)), "nanoseconds preserved on cash model");
        }
        check(Arrays.equals(before, Core.semanticState(client)), "all proposals remain detached from the complete model");
        System.out.println("PASS independent upstream purchase plan: quantities, units, links, timestamps, security/currencies and immutable complete model");
    }
}
