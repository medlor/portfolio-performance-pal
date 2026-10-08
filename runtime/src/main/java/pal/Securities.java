package pal;

import java.util.*;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.CurrencyUnit;

/** Exact mutation selection, separate from the fuzzy query filter. */
public final class Securities {
    public record Choice(Security security, boolean created) {}
    public static Choice purchase(Client client, CommandLine line) {
        if (!line.flag("create-security")) {
            if (line.flag("name") || line.flag("currency")) throw new IllegalArgumentException("--name and --currency require explicit --create-security");
            return new Choice(existing(client, line), false);
        }
        String name = line.value("name"), currency = line.value("currency");
        if (name == null || name.isBlank() || currency == null || currency.isBlank())
            throw new IllegalArgumentException("--create-security requires --name and --currency");
        currency = currency.toUpperCase(Locale.ROOT);
        if (!CurrencyUnit.containsCurrencyCode(currency))
            throw new IllegalArgumentException("Unsupported security --currency: " + currency);
        if (line.flag("security") || line.flag("security-id")) {
            var selected = existing(client, line);
            if (!currency.equals(selected.getCurrencyCode())) throw new IllegalArgumentException("Supplied security currency conflicts with existing definition");
            return new Choice(selected, false);
        }
        var identifiers = new LinkedHashMap<String,String>();
        for (String kind : List.of("isin", "wkn", "ticker")) {
            String value = line.value(kind);
            if (value == null) continue;
            if (value.isBlank() || !value.equals(value.strip())) throw new IllegalArgumentException("Invalid --" + kind + " identifier");
            identifiers.put(kind, value.toUpperCase(Locale.ROOT));
        }
        Set<Security> selected = null;
        boolean unknown = false;
        for (var identifier : identifiers.entrySet()) {
            var matches = client.getSecurities().stream().filter(s -> identifier.getValue().equalsIgnoreCase(switch(identifier.getKey()) {
                case "isin" -> s.getIsin(); case "wkn" -> s.getWkn(); default -> s.getTickerSymbol();
            })).toList();
            if (matches.isEmpty()) { unknown = true; continue; }
            if (selected == null) selected = new HashSet<>(matches); else selected.retainAll(matches);
            if (selected.isEmpty()) throw new IllegalArgumentException("Supplied security selectors identify different securities");
        }
        if (selected != null) {
            if (unknown) throw new IllegalArgumentException("Supplied identifiers conflict with existing security definition");
            if (selected.size() != 1) throw new IllegalArgumentException("Ambiguous security selectors; provide an exact stable --security-id");
            var security = selected.iterator().next();
            if (!currency.equals(security.getCurrencyCode())) throw new IllegalArgumentException("Supplied security currency conflicts with existing definition");
            return new Choice(security, false);
        }
        var security = new Security();
        security.setName(name.strip()); security.setCurrencyCode(currency);
        security.setIsin(identifiers.get("isin")); security.setWkn(identifiers.get("wkn")); security.setTickerSymbol(identifiers.get("ticker"));
        return new Choice(security, true);
    }
    public static Security existing(Client client, CommandLine line) {
        Set<Security> selected = null;
        for (String kind : List.of("security", "security-id", "isin", "wkn", "ticker")) {
            String selector = line.value(kind);
            if (selector == null) continue;
            if (selector.isBlank()) throw new IllegalArgumentException("Empty --" + kind + " selector");
            var matches = client.getSecurities().stream().filter(s -> switch (kind) {
                case "security-id" -> selector.equals(s.getUUID());
                case "isin" -> selector.equalsIgnoreCase(s.getIsin());
                case "wkn" -> selector.equalsIgnoreCase(s.getWkn());
                case "ticker" -> selector.equalsIgnoreCase(s.getTickerSymbol());
                default -> selector.equals(s.getUUID());
            }).toList();
            if (kind.equals("security") && matches.isEmpty()) matches = client.getSecurities().stream().filter(s -> selector.equals(s.getName())
                    || selector.equalsIgnoreCase(s.getIsin()) || selector.equalsIgnoreCase(s.getWkn()) || selector.equalsIgnoreCase(s.getTickerSymbol())).toList();
            if (matches.isEmpty()) throw new IllegalArgumentException("Unknown security --" + kind + ": " + selector);
            if (selected == null) selected = new HashSet<>(matches);
            else selected.retainAll(matches);
            if (selected.isEmpty()) throw new IllegalArgumentException("Supplied security selectors identify different securities");
        }
        if (selected == null) throw new IllegalArgumentException("Select an existing security by exact ID, identifier or unique name");
        if (selected.size() != 1) throw new IllegalArgumentException("Ambiguous security selectors; provide an exact stable --security-id");
        return selected.iterator().next();
    }
}
