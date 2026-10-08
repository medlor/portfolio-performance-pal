package pal;

import java.nio.file.*;
import java.util.Arrays;
import org.eclipse.core.runtime.NullProgressMonitor;
import name.abuchen.portfolio.model.ClientFactory;

/** Literal pre/post fixtures, loaded independently through pinned upstream APIs. */
class MigrationOracle {
    public static void main(String[] args) throws Exception {
        var input = ClientFactory.load(Path.of(args[0]).toFile(), null, new NullProgressMonitor());
        var expected = ClientFactory.load(Path.of(args[1]).toFile(), null, new NullProgressMonitor());
        var output = ClientFactory.load(Path.of(args[2]).toFile(), null, new NullProgressMonitor());
        if (input.getFileVersionAfterRead() != 69 || expected.getFileVersionAfterRead() != 70
                || output.getFileVersionAfterRead() != 70)
            throw new AssertionError("Independent input/output model expectations");
        if (!Arrays.equals(Core.semanticState(expected), Core.semanticState(input))
                || !Arrays.equals(Core.semanticState(expected), Core.semanticState(output)))
            throw new AssertionError("Migration must preserve independently specified complete model70 state");
        System.out.println("PASS model69 -> model70 literal financial facts and independent complete post-state");
    }
}
