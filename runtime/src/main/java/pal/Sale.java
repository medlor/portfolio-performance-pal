package pal;

import java.math.BigInteger;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Sale admission depends on the complete affected holding history, never FIFO. */
public final class Sale {
    public static Purchase.Plan plan(Client client, Portfolio securities, Account cash, CommandLine line) {
        var plan = Purchase.trade(client, securities, cash, line, true);
        var proposed = plan.entry().getPortfolioTransaction();
        validateHistory(securities, proposed.getSecurity(), proposed);
        return plan;
    }
    public static void validateHistory(Portfolio securities, Security security) { validateHistory(securities, security, null); }
    private static void validateHistory(Portfolio securities, Security security, PortfolioTransaction proposed) {
        var history = new ArrayList<PortfolioTransaction>();
        for (var tx : securities.getTransactions()) {
            if (tx.getSecurity() == security && tx.getShares() != 0) history.add(tx);
        }
        if (proposed != null) history.add(proposed);
        // List.sort is stable: original persisted account-list order breaks ties
        // within each direction; the proposed sale follows existing decreases.
        history.sort(Comparator.comparing(PortfolioTransaction::getDateTime)
                .thenComparingInt(tx -> tx.getType().isPurchase() ? 0 : 1));
        BigInteger balance = BigInteger.ZERO;
        for (var tx : history) {
            var shares = BigInteger.valueOf(tx.getShares());
            balance = tx.getType().isPurchase() ? balance.add(shares) : balance.subtract(shares);
            if (balance.signum() < 0) throw new IllegalArgumentException("Transaction rejected: negative holding at " + tx.getDateTime()
                    + " for security " + security.getUUID() + " in securities account " + securities.getUUID()
                    + "; financial data unchanged");
        }
    }
}
