# Pinned headless core proof

The initial Java boundary compiles Portfolio Performance core directly from
[revision 8de1fe41d71a2c1b74a6bfbe913f885899aaf123](https://github.com/portfolio-performance/portfolio/tree/8de1fe41d71a2c1b74a6bfbe913f885899aaf123),
release **0.88.0**, with `javac --release 21`. Maven, Tycho, an Eclipse application,
and a display server are unnecessary for the exercised paths. Financial models,
cross entries, XML persistence, PDF-to-text conversion and Baader extraction are
upstream Java code. No financial model or serializer is recreated in Python.

Build and validate on Linux amd64:

```sh
docker build --platform linux/amd64 -t pp-pal:0.88.0 .
bash tests/runtime.sh
docker image inspect pp-pal:0.88.0 --format '{{.Id}}'
```

The Docker context admits only `Dockerfile` and `runtime/`; portfolios, reference
samples, fixtures, configuration and metadata are excluded. The source archive
and upstream release dependency archive are SHA-256 checked by Docker. Dependency
versions are fixed by the checked release's plugin jars. Core and PDFBox3 adapter
release jars are removed from the runtime classpath so rebuilt classes take
precedence. The desktop application jar is also removed. Eclipse/SWT dependency
jars remain available for upstream class linkage; no desktop entry point is run.
The fixture tests use `--network none`, an explicit numeric host UID/GID and an
unset display; Java runs with `-Djava.awt.headless=true`.

The base is Temurin **21.0.11+10-LTS**, Ubuntu Jammy, pinned by image digest in the
Dockerfile. `docker build` uses the system Docker daemon and needs access to
GitHub and the base registry on first build. Only a local image is built; nothing
is uploaded to a registry. A rebuild can have a different image identity; validate
that identity before activation. Build reproducibility here means checked source,
dependencies and commands, not identical build timestamps or attestations.

The wrapper provides `pal.Core.load(Path)`, `save(Client, Path)`,
`inspect(Client)` and `semanticState(Client)` for downstream modules. The current
container commands `inspect`, `roundtrip`, `prove`, and `pdf` are developer proof
commands; `roundtrip` and `prove` write their explicitly selected disposable
output. They are not yet the owner's transaction CLI or safe portfolio writer.
The owner-facing launcher now provides read-only `holdings`/`history` and explicit
`configure`; see `docs/cli.md`. Its cumulative gate is `tests/all.sh`.
Use temporary files when running them. Subsequent tickets add preview, update,
backup, writer coordination and atomic replacement at the owner-facing boundary.

## Headless adapter change

`PDFBox3Adapter.getPDFBoxVersion()` dereferences an OSGI bundle in upstream code.
On a plain JVM that bundle is absent. The reviewed patch uses PDFBox's own
`Version.getVersion()` in that case, retaining the OSGI path otherwise. Conversion
still calls upstream `PDFInputFile.convertPDFtoText()` and PDFBox3. The exact diff
is `runtime/patches/pdfbox-headless.patch`; `HeadlessPatch.java` checks the original
adapter's SHA-256 before applying precisely that change and fails on every source
change. Updates must retain this fixed reviewed patch or stop for manual adapter
maintenance; they must not generate fixes automatically.

Persistence needed no patch. `ClientFactory.load/saveAs`, its XStream ID-reference
serializer and linked `BuySellEntry.insert()` work headlessly on this combination.
The upstream logger prints status lines to stderr when Eclipse logging is absent.
Quantity scaling is upstream `Values.Share`: **100,000,000 units per share**;
money is **100 units per EUR/USD**. Transaction enums must use `name()` where a
stable symbolic representation is needed because `toString()` is localized.

## Evidence and scope

On 2026-10-07 the source build succeeded and the exact image recorded in
`runtime/support-matrix.json` passed `tests/runtime.sh`. Independent public-model
assertions check account/security identity, purchase, partial and full sale,
dividend, dates/times, fees/taxes, FX gross values and rates, bookmarks,
classification assignments and settings. Tests compare complete canonical
upstream persistence before/after no-op reload, and compare intended mutation
against the original persisted state after removing only its new linked records
from the inspection copy. Loading alone is not the compatibility oracle.

The generated synthetic PDF is converted by real upstream PDFBox3 and extracted
by `BaaderBankPDFExtractor`; its literal expected purchase is also extracted from
the paired text. The rendered PDF was inspected. This proves a representative
Baader purchase path; it does not declare support for every current Smartbroker
layout. Sale/dividend document coverage belongs to the extractor tickets.

Only ID-reference **XML input 70 -> XML output 70** is proven initially; no
migration path is supported. The boundary rejects other model versions and
non-numeric reference attributes before core loading/writing. Binary, ZIP,
encrypted, protobuf and XPath formats are untested and unsupported. Linux arm64,
macOS and Windows are untested. No desktop release installation is consulted.

## Attribution and licenses

Portfolio Performance is copyright Andreas Buchen and its contributors and is
provided under the upstream **Eclipse Public License 1.0**. The local image retains
the exact source archive, license, core manifest and reviewed patch in
`/opt/pp/attribution`; third-party dependency jars retain their upstream embedded
notices. PDFBox is an Apache Software Foundation project under Apache License 2.0.
The Temurin image retains its JDK/OS notices. Consult those retained licenses for
redistribution obligations. No image publication is part of this workflow.

Fixtures use invented identities and amounts. Their text layout is independently
constructed from the public Baader extractor's documented patterns, not copied
from an owner's statement or retained reference samples. See `fixtures/v1/README.md`.
