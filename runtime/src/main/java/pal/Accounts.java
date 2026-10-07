package pal;

import java.util.*;
import java.util.function.Function;
import name.abuchen.portfolio.model.*;

/** Exact account selection shared by queries, previews and configuration. */
public final class Accounts {
    private Accounts() {}
    public static Portfolio securities(Client client, String selector, boolean required) {
        return resolve(client.getPortfolios(), selector, required, Portfolio::getUUID, Portfolio::getName, "securities account");
    }
    public static Account cash(Client client, String selector, boolean required) {
        return resolve(client.getAccounts(), selector, required, Account::getUUID, Account::getName, "cash account");
    }
    private static <T> T resolve(List<T> accounts, String selector, boolean required, Function<T,String> id, Function<T,String> name, String kind) {
        if (selector == null) {
            if (required) throw new IllegalArgumentException("Select a " + kind + " by stable ID or exact unique name");
            return null;
        }
        if (selector.isBlank()) throw new IllegalArgumentException("Empty " + kind + " selector");
        var matches = accounts.stream().filter(a -> id.apply(a).equals(selector)).toList();
        if (matches.isEmpty()) matches = accounts.stream().filter(a -> Objects.equals(name.apply(a), selector)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException((matches.isEmpty() ? "Unknown " : "Ambiguous ") + kind + ": " + selector);
        return matches.getFirst();
    }
}
