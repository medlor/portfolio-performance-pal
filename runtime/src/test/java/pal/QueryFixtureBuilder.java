package pal;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Only invented public data; query expectations are specified separately. */
class QueryFixtureBuilder {
    private static void trade(Portfolio p, Security security, PortfolioTransaction.Type type, long shares, String date) {
        var entry = new BuySellEntry(p, p.getReferenceAccount());
        entry.setType(type); entry.setDate(LocalDateTime.parse(date)); entry.setSecurity(security);
        entry.setCurrencyCode("EUR"); entry.setAmount(shares * 1000); entry.setShares(shares * 100_000_000L); entry.insert();
    }
    private static void delivery(Portfolio p, Security security, PortfolioTransaction.Type type, long shares, String date) {
        var tx = new PortfolioTransaction(); tx.setType(type); tx.setSecurity(security); tx.setShares(shares * 100_000_000L);
        tx.setCurrencyCode("EUR"); tx.setAmount(shares * 1000); tx.setDateTime(LocalDateTime.parse(date)); p.addTransaction(tx);
    }
    private static void dividend(Account cash, Security security, String date) {
        var tx = new AccountTransaction(AccountTransaction.Type.DIVIDENDS); tx.setSecurity(security);
        tx.setCurrencyCode("EUR"); tx.setAmount(100); tx.setDateTime(LocalDateTime.parse(date)); cash.addTransaction(tx);
    }
    public static void main(String[] args) throws Exception {
        var client = new Client(); client.setBaseCurrency("EUR"); client.setProperty("query.fixture", "unrelated-preserved");
        var a = new Security("Alpha Instrument", "EUR"); a.setIsin("DE0000000001"); a.setWkn("TEST01"); a.setTickerSymbol("ALPHA");
        var b = new Security("Beta Instrument", "EUR"); b.setIsin("DE0000000002");
        var m = new Security("Movement Instrument", "EUR"); m.setIsin("DE0000000003");
        client.addSecurity(a); client.addSecurity(b); client.addSecurity(m);
        var cash1 = new Account("Shared Cash"); var cash2 = new Account("Other Cash"); client.addAccount(cash1); client.addAccount(cash2);
        var p1 = new Portfolio("Twin Depot"); var p2 = new Portfolio("Twin Depot"); var p3 = new Portfolio("Other Depot");
        p1.setReferenceAccount(cash1); p2.setReferenceAccount(cash1); p3.setReferenceAccount(cash2);
        client.addPortfolio(p1); client.addPortfolio(p2); client.addPortfolio(p3);
        trade(p1,a,PortfolioTransaction.Type.BUY,10,"2025-01-10T10:00:00");
        trade(p1,a,PortfolioTransaction.Type.SELL,4,"2025-01-12T09:00:00");
        trade(p2,a,PortfolioTransaction.Type.BUY,3,"2025-01-11T08:00:00");
        trade(p1,b,PortfolioTransaction.Type.BUY,5,"2025-01-09T08:00:00");
        trade(p1,b,PortfolioTransaction.Type.SELL,5,"2025-01-13T08:00:00");
        delivery(p3,a,PortfolioTransaction.Type.DELIVERY_INBOUND,2,"2025-01-14T08:00:00");
        delivery(p1,m,PortfolioTransaction.Type.DELIVERY_INBOUND,5,"2025-01-01T08:00:00");
        var transfer = new PortfolioTransferEntry(p1,p3); transfer.setSecurity(m); transfer.setCurrencyCode("EUR");
        transfer.setAmount(3000); transfer.setShares(300_000_000L); transfer.setDate(LocalDateTime.parse("2025-01-16T08:00:00")); transfer.insert();
        delivery(p3,m,PortfolioTransaction.Type.DELIVERY_INBOUND,2,"2025-01-17T08:00:00");
        delivery(p3,m,PortfolioTransaction.Type.DELIVERY_OUTBOUND,1,"2025-01-18T08:00:00");
        dividend(cash1,a,"2025-01-20T11:00:00"); dividend(cash1,b,"2025-01-21T11:00:00"); dividend(cash2,a,"2025-01-15T11:00:00");
        for (int i = 1; i <= 16; i++) {
            var s = new Security(String.format("Tie %02d",i),"EUR"); client.addSecurity(s);
            delivery(p3,s,PortfolioTransaction.Type.DELIVERY_INBOUND,1,"2024-01-01T08:00:00");
        }
        var watchlist = new Watchlist(); watchlist.setName("Synthetic watchlist"); watchlist.addSecurity(a); client.addWatchlist(watchlist);
        a.addPrice(new SecurityPrice(LocalDate.of(2026,1,1),12_000_000_000L)); a.setNote("Metadata dated later must not change holding last updated");
        Core.save(client,Path.of(args[0]));
        var replacements = new LinkedHashMap<String,String>();
        int i=0; for (var s : client.getSecurities()) replacements.put(s.getUUID(), String.format("10000000-0000-4000-8000-%012d",++i));
        i=0; for (var cash : client.getAccounts()) replacements.put(cash.getUUID(), String.format("20000000-0000-4000-8000-%012d",++i));
        i=0; for (var p : client.getPortfolios()) replacements.put(p.getUUID(), String.format("30000000-0000-4000-8000-%012d",++i));
        i=0; for (var pair : client.getAllTransactions()) replacements.put(pair.getTransaction().getUUID(), String.format("40000000-0000-4000-8000-%012d",++i));
        String xml = Files.readString(Path.of(args[0]));
        for (var replacement : replacements.entrySet()) xml=xml.replace(replacement.getKey(),replacement.getValue());
        xml=xml.replaceAll("<updatedAt>.*?</updatedAt>","<updatedAt>2026-01-01T12:00:00Z</updatedAt>");
        Files.writeString(Path.of(args[0]),xml+"\n");
    }
}
