# Run the Java Core as a Native User Process

Run every pp-pal operation as a plain, rootless user process from a self-contained
runtime directory, built by the annotated `tools/build-runtime`. The directory holds
the pinned Temurin 21.0.11+10 JDK, the classes compiled from upstream source, and the
release's dependency jars. Docker is removed entirely rather than kept as an option:
a second execution path would double the launcher, updater and test code with no
benefit to the owner. This supersedes the Docker packaging clause of ADR 0002; reusing
the Portfolio Performance Java core still stands.

Downloaded archives (JDK, upstream source, upstream release) stay pinned by SHA-256.
The host's own Java is never used. A runtime is identified by a readable name
(`<upstream tag>-<wrapper revision[:12]>`, or `dev`), which is also its directory name.
It is made read-only after the build. The trade-off is the loss of container isolation:
commands and candidate releases under qualification run without network isolation,
read-only binds, or hidden trusted files. Integrity rests on pinned inputs, independent
trusted gates, read-only validation copies, and byte comparisons of the owner's files.
The owner accepted that trade-off.
