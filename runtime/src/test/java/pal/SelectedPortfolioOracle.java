package pal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import org.eclipse.core.runtime.NullProgressMonitor;

/** Trusted baseline-only comparison; candidate containers never mount this source. */
class SelectedPortfolioOracle {
    private static byte[] roundtrip(Client client, Path output) throws Exception {
        Core.save(client, output);
        if (!Arrays.equals(Core.semanticState(client), Core.semanticState(Core.load(output))))
            throw new IOException("Semantic roundtrip changed upstream persisted model state");
        return Files.readAllBytes(output);
    }

    public static void main(String[] args) throws Exception {
        // The pinned baseline reader supports the declared 69 -> 70 path even
        // when the old wrapper's public input guard supported only model 70.
        var before = ClientFactory.load(Path.of(args[0]).toFile(), null, new NullProgressMonitor());
        if (!java.util.Set.of(69, 70).contains(before.getFileVersionAfterRead()))
            throw new IOException("Unsupported selected input model");
        var after = Core.load(Path.of(args[1]));
        var directory = Path.of(args[2]);
        var beforeState = roundtrip(before, directory.resolve("before.xml"));
        var afterState = roundtrip(after, directory.resolve("after.xml"));
        if (!Arrays.equals(beforeState, afterState))
            throw new IOException("Candidate changes selected portfolio persisted semantics; active pair retained");
        // Canonical comparison above includes every persisted field. Inspect's
        // first line records original file version, which this path changes.
        var beforeFacts = Core.inspect(before); var afterFacts = Core.inspect(after);
        if (!beforeFacts.substring(beforeFacts.indexOf('\n')).equals(afterFacts.substring(afterFacts.indexOf('\n'))))
            throw new IOException("Candidate changes selected portfolio financial relationships; active pair retained");
    }
}
