package pal;

import java.util.*;

/** Shared command arguments. Each command explicitly validates its own options. */
public record CommandLine(String command, Map<String, String> options) {
    private static final Set<String> FLAGS = Set.of("help", "ascending", "include-zero", "resolve-file");
    public static CommandLine parse(String... args) {
        String command = null;
        var values = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                if (command != null) throw new IllegalArgumentException("Unexpected argument: " + arg);
                command = arg; continue;
            }
            String key = arg.substring(2);
            if (command == null && !key.equals("help")) throw new IllegalArgumentException("Put the command first; use pp-pal COMMAND [OPTIONS]");
            String value = "true";
            if (!FLAGS.contains(key)) {
                if (++i == args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Missing value for --" + key);
                value = args[i];
            }
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Repeated option: --" + key);
        }
        return new CommandLine(command, Collections.unmodifiableMap(values));
    }
    public String value(String option) { return options.get(option); }
    public String value(String option, String fallback) { return options.getOrDefault(option, fallback); }
    public boolean flag(String option) { return options.containsKey(option); }
    public void allow(Set<String> commandOptions) {
        var allowed = new HashSet<>(commandOptions);
        allowed.addAll(Set.of("help", "file", "config", "resolved-config", "resolved-file", "resolve-file"));
        for (String option : options.keySet()) if (!allowed.contains(option)) throw new IllegalArgumentException("Unknown option: --" + option);
    }
    public int limit() {
        try {
            int limit = Integer.parseInt(value("limit", "15"));
            if (limit < 0) throw new NumberFormatException();
            return limit;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("--limit must be a nonnegative integer; 0 means unlimited"); }
    }
}
