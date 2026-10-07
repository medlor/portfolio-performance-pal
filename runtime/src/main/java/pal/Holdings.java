package pal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Quantity-only holdings over the complete upstream securities transaction types. */
public final class Holdings {
    public record Holding(Portfolio account, Security security, long shares, LocalDateTime lastUpdated) {}
    private Holdings() {}
    public static List<Holding> query(Client client, Portfolio account, String securityFilter, boolean includeZero, String sort, boolean ascending, int limit) {
        var rows = new ArrayList<Holding>();
        for (var p : client.getPortfolios()) {
            if (account != null && p != account) continue;
            var groups = new LinkedHashMap<Security, List<PortfolioTransaction>>();
            for (var t : p.getTransactions()) {
                if (t.getSecurity() != null && t.getShares() != 0) groups.computeIfAbsent(t.getSecurity(), s -> new ArrayList<>()).add(t);
            }
            for (var group : groups.entrySet()) {
                long quantity = 0;
                LocalDateTime latest = LocalDateTime.MIN;
                for (var t : group.getValue()) {
                    quantity = Math.addExact(quantity, t.getType().isPurchase() ? t.getShares() : Math.negateExact(t.getShares()));
                    if (t.getDateTime().isAfter(latest)) latest = t.getDateTime();
                }
                if ((quantity != 0 || includeZero) && matches(group.getKey(), securityFilter)) rows.add(new Holding(p, group.getKey(), quantity, latest));
            }
        }
        Comparator<Holding> primary = switch (sort) {
            case "name" -> Comparator.comparing(h -> h.security().getName(), String.CASE_INSENSITIVE_ORDER);
            case "quantity" -> Comparator.comparingLong(Holding::shares);
            case "updated" -> Comparator.comparing(Holding::lastUpdated);
            default -> throw new IllegalArgumentException("--sort must be name, quantity or updated");
        };
        if (!ascending) primary = primary.reversed();
        rows.sort(primary.thenComparing(h -> h.security().getUUID()).thenComparing(h -> h.account().getUUID()));
        return limit == 0 || rows.size() <= limit ? List.copyOf(rows) : List.copyOf(rows.subList(0, limit));
    }
    public static boolean matches(Security security, String filter) {
        if (filter == null) return true;
        if (List.of(security.getUUID(), nullToEmpty(security.getIsin()), nullToEmpty(security.getWkn()), nullToEmpty(security.getTickerSymbol())).stream().anyMatch(filter::equalsIgnoreCase)) return true;
        return security.getName().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }
    private static String nullToEmpty(String text) { return text == null ? "" : text; }
    public static String quantity(long shares) { return BigDecimal.valueOf(shares, 8).stripTrailingZeros().toPlainString(); }
    public static String text(List<Holding> holdings) {
        var out = new StringBuilder("SECURITIES ACCOUNT | SECURITY | QUANTITY | HOLDING LAST UPDATED\n");
        for (var row : holdings) out.append(clean(row.account().getName())).append(" | ").append(clean(row.security().getName())).append(" | ").append(quantity(row.shares())).append(" | ").append(row.lastUpdated().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append('\n');
        return out.toString();
    }
    public static String clean(String value) { return value.replace('\n', ' ').replace('\r', ' ').replace('|', '/'); }
}
