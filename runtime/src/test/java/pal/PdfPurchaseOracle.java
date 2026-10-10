package pal;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.Arrays;
import name.abuchen.portfolio.model.*;

/** Independent literal assertions at the shared detached-document public boundary. */
class PdfPurchaseOracle {
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var client = Core.load(Path.of(args[0]));
        byte[] before = Core.semanticState(client);
        var securities = client.getPortfolios().getFirst(); var cash = client.getAccounts().getFirst();
        var document = PdfTrades.plan(client,securities,cash,CommandLine.parse("import","--pdf",args[1]));
        check(document.members().size() == 2,"complete two-member batch");
        for (int index = 0; index < 2; index++) {
            var member = document.members().get(index); var entry = member.trade().entry();
            var trade = entry.getPortfolioTransaction(); var payment = entry.getAccountTransaction();
            check(trade.getType() == PortfolioTransaction.Type.BUY && payment.getType() == AccountTransaction.Type.BUY,"linked purchase types");
            check(trade.getShares() == (index == 0 ? 200000000L : 300000000L) && payment.getShares() == 0,"literal shares only on securities");
            check(trade.getAmount() == (index == 0 ? 5100 : 7600) && payment.getAmount() == trade.getAmount()
                    && trade.getGrossValueAmount() == (index == 0 ? 5000 : 7500),"literal booked/gross amounts");
            check(trade.getUnitSum(Transaction.Unit.Type.FEE).getAmount() == 100 && trade.getUnitSum(Transaction.Unit.Type.TAX).getAmount() == 0
                    && payment.getUnits().count() == 0,"upstream fee units on securities only");
            check(trade.getSecurity() == client.getSecurities().getFirst() && payment.getSecurity() == trade.getSecurity(),"existing security identity");
            check(trade.getCrossEntry() == entry && payment.getCrossEntry() == entry && entry.getCrossOwner(trade) == cash
                    && entry.getCrossOwner(payment) == securities,"reciprocal selected account links");
            check(trade.getDateTime().equals(LocalDateTime.of(2025,1,index == 0 ? 3 : 4,9,30)) && payment.getDateTime().equals(trade.getDateTime()),"member-scoped literal times");
            check(entry.getNote().equals("Vorgangs-Nr.: SYNTHETIC-PAGED-000"+(index+1)) && entry.getSource().equals("two-purchases.pdf"),"legitimate broker reference and source, no wrapper note bookkeeping");
        }
        byte[] bytes = document.snapshot().bytes(); bytes[0] ^= 1;
        check(!Arrays.equals(bytes,document.snapshot().bytes()),"defensive immutable byte snapshot");
        document.snapshot().unchanged();
        check(Arrays.equals(before,Core.semanticState(client)),"complete owner model unchanged by all extracted plans");
        System.out.println("PASS detached upstream PDF batch: literal amounts, units, reciprocal links, member times, reference/source, immutable snapshot and owner model");
    }
}
