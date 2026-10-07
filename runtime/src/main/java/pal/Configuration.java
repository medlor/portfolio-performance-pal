package pal;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Java owns Properties interpretation and path semantics for all commands. */
public final class Configuration {
    public static final String FILENAME = "pp-pal.properties";
    private final Path path;
    private final Properties properties;
    private Configuration(Path path, Properties properties) { this.path = path; this.properties = properties; }
    public static Configuration load(CommandLine command, Path workingDirectory) throws IOException {
        var config = workingDirectory.resolve(command.value("resolved-config", command.value("config", FILENAME))).normalize().toAbsolutePath();
        var properties = new Properties();
        if (Files.exists(config)) {
            try (var reader = Files.newBufferedReader(config)) { properties.load(reader); }
        } else if (command.flag("config") && !"configure".equals(command.command())) {
            throw new IllegalArgumentException("Configuration does not exist: " + config);
        }
        return new Configuration(config, properties);
    }
    public Path path() { return path; }
    public Path portfolio(CommandLine command, Path workingDirectory) {
        if (command.flag("resolved-file")) return Path.of(command.value("resolved-file"));
        if (command.flag("file")) return workingDirectory.resolve(command.value("file")).normalize().toAbsolutePath();
        String file = properties.getProperty("file");
        return (file == null ? workingDirectory.resolve("portfolio_performance.xml") : path.getParent().resolve(file)).normalize().toAbsolutePath();
    }
    public String selection(CommandLine command, String key) { return command.value(key, properties.getProperty(key)); }
    /** Explicit configuration action only; a query never invokes this method. */
    public void save(Path portfolio, String securitiesAccountId, String cashAccountId) throws IOException {
        properties.setProperty("file", portfolio.toString());
        properties.setProperty("securities-account", securitiesAccountId);
        properties.setProperty("cash-account", cashAccountId);
        if (Files.exists(path) && !Files.isWritable(path)) throw new IOException("Configuration is not writable: " + path);
        var temporary = Files.createTempFile(path.getParent(), ".pp-pal-config-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { properties.store(writer, "Portfolio Performance PAL; account values are stable IDs"); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
