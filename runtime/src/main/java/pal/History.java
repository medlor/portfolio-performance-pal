package pal;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;

/** Recorded upstream transaction history, with cash ownership kept explicit. */
public final class History {
    public record Record(Transaction transaction, String type, List<Portfolio> relevantAccounts, Account cashAccount) {}
    private History() {}
    public static List<Record> query(Client client, Portfolio selected, String security, String type, LocalDate from, LocalDate to, boolean ascending, int limit) {
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("--from must not follow --to");
        String selectedType = type == null ? null : switch (type) {
            case "purchase" -> "PURCHASE";
            case "sale" -> "SALE";
            case "dividend" -> "DIVIDEND";
            case "transfer-in", "transfer-out", "delivery-inbound", "delivery-outbound" -> type.toUpperCase(Locale.ROOT).replace('-', '_');
            default -> throw new IllegalArgumentException("--type must be purchase, sale, dividend, transfer-in, transfer-out, delivery-inbound or delivery-outbound");
        };
        var rows = new ArrayList<Record>();
        for (var p : client.getPortfolios()) {
            if (selected != null && p != selected) continue;
            for (var transaction : p.getTransactions()) {
                if (transaction.getSecurity() == null) continue;
                String recordedType = switch (transaction.getType()) {
                    case BUY -> "PURCHASE";
                    case SELL -> "SALE";
                    default -> transaction.getType().name();
                };
                Account owner = null;
                if (transaction.getCrossEntry() != null && transaction.getCrossEntry().getCrossOwner(transaction) instanceof Account account) owner = account;
                rows.add(new Record(transaction, recordedType, List.of(p), owner));
            }
        }
        // Ask the upstream projection for relevance over complete history.
        var relevance = new IdentityHashMap<AccountTransaction, List<Portfolio>>();
        for (var portfolio : client.getPortfolios()) {
            var projected = new PortfolioClientFilter(portfolio).filter(client);
            for (var account : projected.getAccounts()) for (var tx : account.getTransactions()) {
                if (tx.getType() == AccountTransaction.Type.DIVIDENDS)
                    relevance.computeIfAbsent(tx, ignored -> new ArrayList<>()).add(portfolio);
            }
        }
        for (var cash : client.getAccounts()) for (var transaction : cash.getTransactions()) {
            if (transaction.getType() != AccountTransaction.Type.DIVIDENDS || transaction.getSecurity() == null) continue;
            var relevant = relevance.getOrDefault(transaction, List.of()).stream()
                .sorted(Comparator.comparing(Portfolio::getUUID)).toList();
            if (relevant.isEmpty() || selected != null && !relevant.contains(selected)) continue;
            rows.add(new Record(transaction, "DIVIDEND", relevant, cash));
        }
        rows.removeIf(r -> !Holdings.matches(r.transaction().getSecurity(), security)
            || selectedType != null && !r.type().equals(selectedType)
            || from != null && r.transaction().getDateTime().toLocalDate().isBefore(from)
            || to != null && r.transaction().getDateTime().toLocalDate().isAfter(to));
        Comparator<Record> dates = Comparator.comparing(r -> r.transaction().getDateTime());
        if (!ascending) dates = dates.reversed();
        rows.sort(dates.thenComparing(r -> r.transaction().getUUID()));
        return limit == 0 || rows.size() <= limit ? List.copyOf(rows) : List.copyOf(rows.subList(0,limit));
    }
    public static LocalDate date(String input, String option) {
        if (input == null) return null;
        try { return LocalDate.parse(input); }
        catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("--" + option + " requires an ISO date YYYY-MM-DD"); }
    }
    public static String text(List<Record> rows) {
        var out = new StringBuilder("DATE/TIME | TYPE | SECURITY | QUANTITY | RECORDED AMOUNT | SECURITIES ACCOUNT | CASH ACCOUNT\n");
        for (var row : rows) {
            var t = row.transaction();
            String attribution = row.relevantAccounts().stream().map(p -> Holdings.clean(p.getName()) + " [" + p.getUUID() + "]").collect(Collectors.joining("; "));
            if (attribution.isEmpty()) attribution = "unattributed";
            else if (row.relevantAccounts().size() > 1) attribution = "AMBIGUOUS: " + attribution;
            out.append(t.getDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append(" | ").append(row.type()).append(" | ")
                .append(Holdings.clean(t.getSecurity().getName())).append(" | ").append(Holdings.quantity(t.getShares())).append(" | ")
                .append(t.getCurrencyCode()).append(' ').append(BigDecimal.valueOf(t.getAmount(),2).toPlainString()).append(" | ")
                .append(attribution).append(" | ").append(row.cashAccount() == null ? "—" : Holdings.clean(row.cashAccount().getName())).append('\n');
        }
        return out.toString();
    }
}
