package pal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import name.abuchen.portfolio.model.Client;

/** Trusted baseline-only comparison; candidate containers never mount this source. */
class SelectedPortfolioOracle {
    private static byte[] roundtrip(Client client, Path output) throws Exception {
        Core.save(client, output);
        if (!Arrays.equals(Core.semanticState(client), Core.semanticState(Core.load(output))))
            throw new IOException("Semantic roundtrip changed upstream persisted model state");
        return Files.readAllBytes(output);
    }

    public static void main(String[] args) throws Exception {
        var before = Core.load(Path.of(args[0]));
        var after = Core.load(Path.of(args[1]));
        var directory = Path.of(args[2]);
        var beforeState = roundtrip(before, directory.resolve("before.xml"));
        var afterState = roundtrip(after, directory.resolve("after.xml"));
        if (!Arrays.equals(beforeState, afterState))
            throw new IOException("Candidate changes selected portfolio persisted semantics; active pair retained");
        if (!Core.inspect(before).equals(Core.inspect(after)))
            throw new IOException("Candidate changes selected portfolio financial relationships; active pair retained");
    }
}
