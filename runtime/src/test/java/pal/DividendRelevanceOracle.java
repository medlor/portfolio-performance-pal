package pal;

import java.nio.file.Path;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;

/** Check independently fixed dividend counts against the pinned upstream filter. */
class DividendRelevanceOracle {
    public static void main(String[] args) throws Exception {
        var client=Core.load(Path.of(args[0]));
        long[] expected={3,2,1};
        for (int i=0;i<3;i++) {
            var filtered=new PortfolioClientFilter(client.getPortfolios().get(i)).filter(client);
            long count=filtered.getAccounts().stream().flatMap(a -> a.getTransactions().stream())
                .filter(t -> t.getType()==AccountTransaction.Type.DIVIDENDS).count();
            if (count!=expected[i]) throw new AssertionError("Upstream dividend relevance differs for account " + (i+1) + ": " + count);
        }
        System.out.println("PASS independent upstream dividend relevance 3/2/1");
    }
}
