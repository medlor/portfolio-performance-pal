package pal;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import name.abuchen.portfolio.datatransfer.pdf.*;
import name.abuchen.portfolio.model.*;

/** Direct, unadapted PP extraction is the financial oracle; dates are independent literals. */
class UpstreamPdfAuthorityOracle {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static List<String> units(Transaction tx) {
        return tx.getUnits().map(u -> u.getType()+"|"+u.getAmount()+"|"+u.getForex()+"|"+u.getExchangeRate()).sorted().toList();
    }
    private static void financial(Transaction expected, Transaction actual) {
        check(expected.getShares()==actual.getShares(),"PP shares unchanged");
        check(expected.getMonetaryAmount().equals(actual.getMonetaryAmount()),"PP authoritative booked amount/currency unchanged");
        check(expected.getGrossValue().equals(actual.getGrossValue()),"PP model gross unchanged");
        check(units(expected).equals(units(actual)),"all PP cost/FX units and rates unchanged");
        check(Objects.equals(expected.getNote(),actual.getNote()),"PP note preserved without bookkeeping text");
        check(Objects.equals(expected.getSource(),actual.getSource()),"original source filename preserved");
        check(Objects.equals(expected.getSecurity().getIsin(),actual.getSecurity().getIsin()),"PP security bound by its identifier");
    }
    public static void main(String[] args) throws Exception {
        var before = Core.load(Path.of(args[0]));
        byte[] baseline = Core.semanticState(before);
        var input = new PDFInputFile(Path.of(args[1]).toFile()); input.convertPDFtoText();
        var errors = new ArrayList<Exception>();
        var upstream = new BaaderBankPDFExtractor(new Client()).extract(List.of(input),errors);
        check(errors.isEmpty(),"unadapted PP extracts fixture without errors");
        var extracted = upstream.stream().map(i->i.getSubject()).filter(s->s instanceof BuySellEntry || s instanceof AccountTransaction).toList();
        check(extracted.size()==1,"one independent upstream financial record");
        var expected = extracted.getFirst() instanceof BuySellEntry e ? e.getPortfolioTransaction() : (AccountTransaction)extracted.getFirst();
        var plan = PdfTrades.plan(before,before.getPortfolios().getFirst(),before.getAccounts().getFirst(),CommandLine.parse("import","--pdf",args[1]));
        check(plan.members().size()==1,"wrapper admits entire statement");
        var member = plan.members().getFirst();
        financial(expected,member.transaction());
        check(member.transaction().getDateTime().equals(LocalDateTime.parse(args[2])),"independent explicit/fallback date literal");
        check(Arrays.equals(baseline,Core.semanticState(before)),"planning preserves complete input model");
        if (args.length>3) {
            var after = Core.load(Path.of(args[3]));
            var originalIds = new HashSet<String>();
            before.getPortfolios().forEach(p->p.getTransactions().forEach(t->originalIds.add(t.getUUID())));
            before.getAccounts().forEach(a->a.getTransactions().forEach(t->originalIds.add(t.getUUID())));
            var p = after.getPortfolios().getFirst(); var a = after.getAccounts().getFirst();
            var trades = p.getTransactions().stream().filter(t->!originalIds.contains(t.getUUID())).toList();
            var cash = a.getTransactions().stream().filter(t->!originalIds.contains(t.getUUID())).toList();
            check(cash.size()==1 && trades.size()==(extracted.getFirst() instanceof BuySellEntry ? 1 : 0),"exact saved linked/cash record counts");
            Transaction saved = trades.isEmpty() ? cash.getFirst() : trades.getFirst();
            financial(expected,saved);
            check(saved.getDateTime().equals(LocalDateTime.parse(args[2])),"saved literal timestamp");
            check(saved.getSecurity()==after.getSecurities().stream().filter(s->s.getUUID().equals(member.transaction().getSecurity().getUUID())).findFirst().orElseThrow(),"saved existing security identity");
            if (!trades.isEmpty()) {
                var t=trades.getFirst(); var c=cash.getFirst();
                check(t.getType()==((BuySellEntry)extracted.getFirst()).getPortfolioTransaction().getType(),"upstream trade type");
                check(t.getCrossEntry()==c.getCrossEntry() && t.getCrossEntry().getCrossOwner(t)==a
                        && c.getCrossEntry().getCrossOwner(c)==p && c.getAmount()==t.getAmount() && c.getUnits().count()==0,"PP reciprocal saved linked entry");
            } else check(cash.getFirst().getType()==AccountTransaction.Type.DIVIDENDS && cash.getFirst().getCrossEntry()==null,"saved dividend only");
            p.getTransactions().removeAll(trades); a.getTransactions().removeAll(cash);
            check(Arrays.equals(baseline,Core.semanticState(after)),"all unrelated financial/model state preserved");
        }
        System.out.println("PASS PP-authoritative extraction and preserved model: "+Path.of(args[1]).getFileName());
    }
}
