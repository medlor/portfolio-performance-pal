package pal;

import java.nio.file.*;
import name.abuchen.portfolio.model.Security;

/** Add one invented identifier/name collision to the existing public v1 fixture. */
class PurchaseFixtureBuilder {
    public static void main(String[] args) throws Exception {
        var client = Core.load(Path.of(args[0]));
        var duplicate = new Security("Synthetic Euro Instrument", "EUR");
        duplicate.setIsin("DE0000000001"); duplicate.setWkn("TEST01");
        client.addSecurity(duplicate);
        Path output = Path.of(args[1]);
        Core.save(client, output);
        Files.writeString(output, Files.readString(output).replace(duplicate.getUUID(), "00000000-0000-4000-8000-000000000099"));
    }
}
