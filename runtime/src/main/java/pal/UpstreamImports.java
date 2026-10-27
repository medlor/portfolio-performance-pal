package pal;

import java.util.List;
import name.abuchen.portfolio.datatransfer.Extractor;
import name.abuchen.portfolio.datatransfer.ImportAction;
import name.abuchen.portfolio.datatransfer.actions.*;
import name.abuchen.portfolio.model.*;

/** Use the desktop import checks and insertion without investment-plan replacement. */
public final class UpstreamImports {
    private UpstreamImports() {}

    public static Extractor.Item item(BuySellEntry entry) {
        var item = new Extractor.BuySellEntryItem(entry);
        item.setAccountPrimary(entry.getAccount());
        item.setPortfolioPrimary(entry.getPortfolio());
        return item;
    }

    public static Extractor.Item item(Account account, AccountTransaction transaction) {
        var item = new Extractor.TransactionItem(transaction);
        item.setAccountPrimary(account);
        return item;
    }

    private static final ImportAction.Context CONTEXT = new ImportAction.Context() {
        public Account getAccount(String currency) { return null; }
        public Portfolio getPortfolio() { return null; }
        public Account getSecondaryAccount(String currency) { return null; }
        public Portfolio getSecondaryPortfolio() { return null; }
    };

    public static void validate(Extractor.Item item) {
        var tx = item.getSubject() instanceof BuySellEntry entry ? entry.getPortfolioTransaction()
                : (Transaction)item.getSubject();
        long costs = 0;
        for (var unit : tx.getUnits().filter(u -> u.getType() == Transaction.Unit.Type.FEE || u.getType() == Transaction.Unit.Type.TAX).toList())
            costs = Math.addExact(costs, unit.getAmount().getAmount());
        // PP's model sums long amounts; the CLI additionally refuses overflow.
        if (tx instanceof PortfolioTransaction trade && trade.getType().isPurchase()) Math.subtractExact(tx.getAmount(), costs);
        else Math.addExact(tx.getAmount(), costs);
        if (tx.getAmount() < 0 || tx.getGrossValue().getAmount() < 0)
            throw new IllegalArgumentException("Negative PP booked or gross value");
        require(new CheckCurrenciesAction().process(item.getSecurity()));
        for (var action : List.of(new CheckValidTypesAction(), new CheckSecurityRelatedValuesAction(),
                new CheckTransactionDateAction(), new CheckCurrenciesAction(), new CheckForexGrossValueAction()))
            require(item.apply(action, CONTEXT));
    }

    public static void insert(Client client, Extractor.Item item) {
        validate(item);
        require(item.apply(new InsertAction(client), CONTEXT));
    }

    public static ImportAction.Status duplicates(Client client, Extractor.Item item) {
        return item.apply(new DetectDuplicatesAction(client), CONTEXT);
    }

    private static void require(ImportAction.Status status) {
        if (status.getCode() != ImportAction.Status.Code.OK)
            throw new IllegalArgumentException("Portfolio Performance import validation: " + status.getMessage());
    }
}
