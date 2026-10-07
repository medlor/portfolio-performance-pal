package pal;

import java.io.*;
import java.nio.file.*;
import java.util.Set;

/** Owner-facing command dispatcher. Diagnostics retain the pinned Core boundary. */
public final class Main {
    public static int run(String... args) {
        try {
            var line = CommandLine.parse(args);
            if (line.flag("help") || args.length == 0) {
                try (var help = Main.class.getResourceAsStream("/help.txt")) { System.out.write(help.readAllBytes()); }
                return 0;
            }
            if (line.command() == null) throw new IllegalArgumentException("Select a command; use --help");
            switch (line.command()) {
                case "holdings" -> line.allow(Set.of("securities-account", "cash-account", "security", "sort", "ascending", "limit", "include-zero"));
                case "history" -> line.allow(Set.of("securities-account", "cash-account", "security", "type", "from", "to", "sort", "ascending", "limit"));
                case "configure" -> line.allow(Set.of("securities-account", "cash-account"));
                case "purchase" -> line.allow(Set.of("securities-account", "cash-account", "security", "security-id", "isin", "wkn", "ticker", "quantity", "price", "gross", "total", "fees", "taxes", "date", "time"));
                case "backup-monthly", "backup-before-update" -> line.allow(Set.of());
                default -> throw new IllegalArgumentException("Unknown command: " + line.command());
            }
            var cwd = Path.of(System.getProperty("user.dir"));
            var config = Configuration.load(line, cwd);
            var file = config.portfolio(line, cwd);
            if (line.flag("resolve-file")) { System.out.println(file); return 0; }
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) throw new IllegalArgumentException("Portfolio is not a readable regular file: " + file);
            var backups = new BackupService(file, BackupService.launchClock());
            backups.monthly();
            if (line.command().equals("backup-monthly")) return 0;
            if (line.command().equals("backup-before-update")) {
                System.out.println(backups.beforeUpgrade());
                return 0;
            }
            name.abuchen.portfolio.model.Client client;
            try { client = Core.load(file); }
            catch (Exception e) { throw new IllegalArgumentException("Portfolio cannot be loaded as supported ID-reference XML model 70: " + e.getMessage(), e); }
            var securities = Accounts.securities(client, config.selection(line, "securities-account"), line.command().equals("configure"));
            var cash = Accounts.cash(client, config.selection(line, "cash-account"), false);
            switch (line.command()) {
                case "purchase" -> System.out.print(Purchase.preview(Purchase.plan(client, securities, cash, line), client.getFileVersionAfterRead()));
                case "holdings" -> System.out.print(Holdings.text(Holdings.query(client, securities, line.value("security"), line.flag("include-zero"), line.value("sort", "updated"), line.flag("ascending"), line.limit())));
                case "history" -> {
                    if (!line.value("sort", "date").equals("date")) throw new IllegalArgumentException("History --sort must be date");
                    System.out.print(History.text(History.query(client, securities, line.value("security"), line.value("type"), History.date(line.value("from"), "from"), History.date(line.value("to"), "to"), line.flag("ascending"), line.limit())));
                }
                case "configure" -> {
                    if (cash == null) cash = securities.getReferenceAccount();
                    if (cash == null) throw new IllegalArgumentException("Select a cash account by stable ID or exact unique name");
                    config.save(file, securities.getUUID(), cash.getUUID());
                    System.out.println("Configuration saved: " + config.path());
                }
            }
            return 0;
        } catch (IllegalArgumentException | ArithmeticException e) { System.err.println("ERROR: " + e.getMessage()); return 2; }
        catch (Exception e) { System.err.println("ERROR: " + e.getMessage()); return 3; }
    }
    public static void main(String[] args) {
        if (args.length > 0 && Set.of("inspect", "roundtrip", "prove", "pdf").contains(args[0])) { Core.main(args); return; }
        System.exit(run(args));
    }
}
