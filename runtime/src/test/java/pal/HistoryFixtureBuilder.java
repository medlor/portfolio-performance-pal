package pal;

import java.nio.file.*;
import java.time.LocalDateTime;
import name.abuchen.portfolio.model.*;

/** Add independently specified synthetic relevance edges to the query fixture. */
class HistoryFixtureBuilder {
    private static AccountTransaction dividend(Account cash, Security security, String date, long amount) {
        var t = new AccountTransaction(AccountTransaction.Type.DIVIDENDS); t.setSecurity(security);
        t.setDateTime(LocalDateTime.parse(date)); t.setCurrencyCode("EUR"); t.setAmount(amount); cash.addTransaction(t); return t;
    }
    public static void main(String[] args) throws Exception {
        var client = Core.load(Path.of(args[0]));
        var duplicate = dividend(client.getAccounts().get(0),client.getSecurities().get(0),"2025-01-20T11:00:00",100);
        var irrelevantSecurity = dividend(client.getAccounts().get(1),client.getSecurities().get(1),"2025-01-22T11:00:00",7777);
        var cash = new Account("Unreferenced Cash"); client.addAccount(cash);
        var irrelevantCash = dividend(cash,client.getSecurities().get(0),"2025-01-23T11:00:00",9999);
        Core.save(client,Path.of(args[1]));
        String xml=Files.readString(Path.of(args[1]))
            .replace(duplicate.getUUID(),"60000000-0000-4000-8000-000000000001")
            .replace(irrelevantSecurity.getUUID(),"60000000-0000-4000-8000-000000000002")
            .replace(irrelevantCash.getUUID(),"60000000-0000-4000-8000-000000000003")
            .replace(cash.getUUID(),"70000000-0000-4000-8000-000000000001")
            .replaceAll("<updatedAt>.*?</updatedAt>","<updatedAt>2026-01-01T12:00:00Z</updatedAt>");
        Files.writeString(Path.of(args[1]),xml+"\n");
    }
}
