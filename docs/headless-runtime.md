# Pinned headless core proof

> Evidence sections below that cite `sha256:` image identities and `docker` commands
> record runs on the former Docker runtime. They are kept as history; the current
> runtime is described in this introduction and in [ADR 0005](adr/0005-native-runtime.md).

The initial Java boundary compiles Portfolio Performance core directly from
[revision 8de1fe41d71a2c1b74a6bfbe913f885899aaf123](https://github.com/portfolio-performance/portfolio/tree/8de1fe41d71a2c1b74a6bfbe913f885899aaf123),
release **0.88.0**, with `javac --release 21`. Maven, Tycho, an Eclipse application,
and a display server are unnecessary for the exercised paths. Financial models,
cross entries, XML persistence, PDF-to-text conversion and Baader extraction are
upstream Java code. No financial model or serializer is recreated in Python.

Build and validate on Linux amd64, rootless and without Docker:

```sh
tools/build-runtime --output ~/.local/state/pp-pal/runtimes/dev
bash tests/runtime.sh ~/.local/state/pp-pal/runtimes/dev
python3 tools/runtime_dir.py identity ~/.local/state/pp-pal/runtimes/dev
```

`tools/build-runtime` is annotated step by step ([ADR 0005](adr/0005-native-runtime.md)).
It reads only `runtime/` from the wrapper tree; portfolios, reference samples,
fixtures, configuration and metadata are never inputs. The upstream source
archive, the upstream release dependency archive and the Temurin JDK archive are
SHA-256 checked against `runtime/release-package.json` before unpacking. Dependency
versions are fixed by the checked release's plugin jars. Core and PDFBox3 adapter
release jars are removed from the runtime classpath so rebuilt classes take
precedence. The desktop application jar is also removed. Eclipse/SWT dependency
jars remain available for upstream class linkage; no desktop entry point is run.
Commands run as the invoking user with an unset display, `LANG=C.UTF-8`, `TZ=UTC`
and a pruned environment; Java runs with `-Djava.awt.headless=true`.

The JDK is Temurin **21.0.11+10-LTS** for Linux x64. It is unpacked from the pinned
archive into `<runtime>/jdk`; a host Java is never used. The first build needs the
network access listed in the README; archives are then cached by digest. The
result is a read-only runtime directory named by its identity, and nothing is
uploaded. `runtime.json` records the wrapper and upstream revisions, the XML
model, the JDK, the platform and the input digests. Build reproducibility here
means checked source, dependencies and commands, not identical build timestamps
or attestations.

The wrapper provides `pal.Core.load(Path)`, `save(Client, Path)`,
`inspect(Client)` and `semanticState(Client)` for downstream modules. The current
engine commands (`tools/engine main inspect|roundtrip|prove|pdf`) are developer proof
commands; `roundtrip` and `prove` write their explicitly selected disposable
output. Use these proof commands only with disposable output files.
The owner-facing launcher provides `holdings`/`history`, explicit `configure`, and
manual `purchase`/`sale`/`dividend` preview/application, including explicit FX,
with verified recovery; see `docs/cli.md`.
Its cumulative release/feature gate is `tests/updater.sh IMAGE --apply-slice`;
`tests/all.sh` also runs the complete historical updater adversarial suite.
Use temporary files when running them. The launcher now includes monthly/pre-upgrade protection and released-pair updates.
The shared Java writer owns same-filesystem replacement, pinned complete-model validation,
writer coordination, daily protection and committed-only retention. Initial stable
wrapper bootstrap publication remains a prerequisite for production mutations.

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

Story #29 adds the separately validated **XML ID-reference 69 -> 70** migration
on the same pinned upstream revision. The existing **70 -> 70** combination stays
covered. Queries/previews/update validation leave selected bytes unchanged;
financial apply protects exact model69 input in a separate migration backup before
atomic replacement. See `fixtures/v1/migrations/README.md` for independent pre/post
expectations and `docs/cli.md` for backup/retention/recovery rules. No other
migration or representation is declared.

Final development image
`sha256:91fb26246364053e22dddb521e65f77ea4b3957d4e3734fe32e1909b6c064285`
passed focused migration/holdings checks and `bash tests/all.sh pp-pal:issue29-final`
on 2026-10-08. Full elapsed time was 1234 seconds (20 minutes 34 seconds).
The released candidate
`sha256:c5a332eb3a9cd5871d7d763a127b3666c1375e317808d2a88278a04f3ae5eb1c`
passed all twelve mandatory gates exactly once, selected-copy preservation,
owner migration update/preview/apply, and historical updater adversarial checks.
Exact identities and both model combinations are recorded in the support matrix.

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

## Baader purchase document preview

On 2026-10-08, `bash tests/updater.sh
sha256:ef6c75e4ee83101698ff7e74c45bf3bfd1d8b4edfd41a9d90df69b786afe4136
--pdf-slice` exited 0. The actual local stable released wrapper snapshot
`f9ba1764b3d7245e38c99ef729c0ed278d3af9ff` and pinned official upstream archives
built combined candidate
`sha256:0fb2c3d55aa9fe16b5d1bcef3795370c771186e6976e8a7496321313ddcb34cf`,
retained with its full-ID `pp-pal:retained-0fb2c3d55aa9fe16b5d1bcef3795370c771186e6976e8a7496321313ddcb34cf`
tag. The frozen implementation tree was `a4bedb58b8b3baa662e81e155f1391438871e899`,
including the validated #24 writer integration. Only evidence/documentation changed
after this passing gate.

Every registered trusted gate passed: PDF purchase, runtime, holdings/configuration,
history, backups, purchase preview and purchase application. Isolated selected-copy
validation, activation, and the mandatory-release fixed-image owner PDF preview
also passed. The PDF gate uses 21 exact upstream-converted PDF/text pairs and
independent literal financial/model facts. It verifies complete two-member linked
plans, unchanged owner models, legitimate broker notes/source, immutable snapshots,
strict physical-page/member coverage, monetary contradictions/precision, existing
security identifier intersections, missing-only supplements and explicit-midnight
versus fallback time. Actual owner containers use numeric host UID/GID and an
individual read-only PDF bind; portfolio, PDF, configuration/opaque metadata and
existing financial backups remain unchanged by previews.

The scope remains the invented flat single-page and Baader `Seite 1/1` German EUR
purchase profiles in [the fixture contract](../fixtures/v1/pdf-purchase/README.md).
Other banks, uncovered/blank pages, continuations, FX, sales/dividends, unknown or
malformed later members reject the complete document. This is no current-owner
Baader/Smartbroker layout claim. Only XML ID-reference model70 on Linux amd64 is
supported; no migration or PDF application is added. Initial official stable
wrapper bootstrap publication remains a prerequisite.

Earlier final runs stopped on test-only container-selection and blank-page paired
text formatting errors; no passing result is claimed for them. The final scoped
updater retained all seven gates and stopped before unrelated historical release/
retry attacks already verified in #23. No full `tests/all.sh` exit-0 claim is made.

## Purchase PDF application evidence (#31)

On 2026-10-10, the final focused PDF application, retained PDF preview and sale
suites passed against `pp-pal:issue31`, immutable image
`sha256:d91da318e0f34612f66326d8fd9c48790d8ffac40003e3379a327720b057d0c4`.
`bash tests/all.sh pp-pal:issue31` then exited 0 in **1310 seconds (21 minutes
50 seconds)**. All four distinct baseline suites and all thirteen candidate gates
passed. The twelve earlier gates and assertions remain; `tests/pdf-apply.sh` is the
additional mandatory gate. Its released-candidate run took 134.61 seconds.

The frozen synthetic released-wrapper revision was
`5f037f5a607475f9d214f64d9fd714af3c34ac79`, paired with upstream
`8de1fe41d71a2c1b74a6bfbe913f885899aaf123` (0.88.0) and candidate image
`sha256:3dbe276b4b2f4813ea450c2766e15442c00a5c81b423864f6a45b1e767bd0f90`.
See the appended model69/70 entries in the support matrix for exact dependencies,
commands, protected migration and fixture-scoped layout evidence. These are local
validation artifacts; no container or wrapper bootstrap release was published.

Independent saved-model oracles inspect complete linked purchases and unrelated
state. Real commands cover document/reference confirmation, every suspected match,
uncertainty overrides, partial confirmations, degraded evidence and restored
backups, invalid later members, later quantity-history failures, stale PDF/portfolio/
metadata, metadata failure after financial success, hard interruption before/after
replacement and idempotent recovery without financial replay. A changed missing-time
supplement cannot override an already confirmed broker identity. Released owner
commands also verify mandatory updates, fixed engine, refusal after release failure
and confirmed repeats. The full historical updater adversity suite passed.

Both review axes had no remaining findings after cleanup-error reporting and shared
preview/application batch validation were corrected. Qualification inputs stayed
fixed during the full run; subsequent changes only record documentation/evidence.
This adds application of the retained synthetic EUR purchase profiles, with tested
transaction-only model69-to70 migration; it establishes no new statement layout.
