package pal;

import java.util.*;
import name.abuchen.portfolio.model.*;

/** Exact mutation selection, separate from the fuzzy query filter. */
public final class Securities {
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
