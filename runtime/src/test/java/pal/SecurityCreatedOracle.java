package pal;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Literal owner-visible new definition and linked transaction facts, loaded by pinned core. */
class SecurityCreatedOracle {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var before = Core.load(Path.of(args[0])); var after = Core.load(Path.of(args[1]));
        var original = new HashSet<>(before.getSecurities().stream().map(Security::getUUID).toList());
        var added = after.getSecurities().stream().filter(s -> !original.contains(s.getUUID())).toList();
        check(added.size() == 1, "exactly one new security");
        var security = added.getFirst();
        check(security.getName().equals("New Synthetic Instrument") && security.getCurrencyCode().equals("EUR"), "literal new definition");
        if (args.length == 2) check(security.getIsin() == null && security.getWkn() == null && security.getTickerSymbol() == null, "external identifiers optional");
        else check("DE0000000099".equals(security.getIsin()) && "NEW099".equals(security.getWkn()) && "NEW.SYN".equals(security.getTickerSymbol()), "supplied external identifiers survive core reload");
        var p = after.getPortfolios().getFirst(); var a = after.getAccounts().getFirst();
        var trades = p.getTransactions().stream().filter(t -> t.getSecurity() == security).toList();
        var payments = a.getTransactions().stream().filter(t -> t.getSecurity() == security).toList();
        check(trades.size() == 1 && payments.size() == 1, "one linked purchase pair");
        var t = trades.getFirst(); var c = payments.getFirst();
        check(t.getType() == PortfolioTransaction.Type.BUY && c.getType() == AccountTransaction.Type.BUY, "upstream buy types");
        check(t.getShares() == 250000000L && c.getShares() == 0 && t.getAmount() == 2650 && c.getAmount() == 2650 && t.getGrossValueAmount() == 2500, "literal quantity and cents");
        check(t.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == 100 && t.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == 50 && c.getUnits().count() == 0, "literal cost units");
        check(t.getDateTime().equals(LocalDateTime.of(2025,3,1,11,0)) && t.getDateTime().equals(c.getDateTime()), "shared fallback time");
        check(t.getCrossEntry() == c.getCrossEntry() && t.getCrossEntry().getCrossTransaction(t) == c && t.getCrossEntry().getCrossOwner(t) == a && c.getCrossEntry().getCrossOwner(c) == p, "reciprocal owners");
        p.getTransactions().remove(t); a.getTransactions().remove(c); after.removeSecurity(security);
        check(Arrays.equals(Core.semanticState(before),Core.semanticState(after)), "all unrelated definitions and complete supported state preserved");
        System.out.println("PASS reloaded new security, literal linked amounts and complete unrelated definition/state preservation");
    }
}
