# Portfolio Performance reuse boundary

Portfolio Performance (PP) is authoritative for bank PDF grammar, extraction,
transaction types, linked entries, monetary units, gross values, quantities and
portfolio persistence. This implements [ADR 0002](adr/0002-reuse-portfolio-performance-java-core.md).
Do not introduce a second PDF parser to predict what PP should extract.

The October 2026 audit found custom bank regexes, independently constructed
expected transactions, invented supplemental PDF text, and duplicated history
relevance. Purchase, sale and dividend PDF paths now share the same PP adapter.

| Responsibility | Implementation and reason |
| --- | --- |
| PDF conversion and bank recognition | `PDFInputFile` and `BaaderBankPDFExtractor`, using the pinned bank identifiers and grammar. |
| PDF financial records | Preserve PP-produced `BuySellEntry` / `AccountTransaction`, shares, amounts, units, currencies, notes and sources. Do not call the manual planners to recreate extracted transactions. |
| Extraction provenance | `ObservedBaaderExtractor` observes PP's existing assignment/conversion hooks. It adds no bank regexes and modifies no upstream source. Its package placement permits using PP's package-private `DocumentType` hook parameter. |
| Import validation | PP's `CheckValidTypesAction`, `CheckSecurityRelatedValuesAction`, `CheckTransactionDateAction`, `CheckCurrenciesAction`, `CheckForexGrossValueAction`. |
| Insertion and security registration | PP's `InsertAction`, without its optional investment-plan replacement or dividend-removal modes. |
| Holdings quantities | PP's `SecurityPosition.getShares()`. No valuation, FIFO or profit is requested. |
| Dividend history relevance | PP's `PortfolioClientFilter`, projected separately for each securities account; the wrapper displays original cash ownership and shared-account ambiguity. |
| Duplicate detection | PP's `DetectDuplicatesAction` plus the spec's broader resemblance checks and adjacent document/reference/transaction evidence. A PP warning is uncertainty, never proof of a confirmed import. |
| Manual decimal input | Wrapper policy: exact representability, deterministic derivation and explicit rates. PP's `Values`, `Money`, `Quote`, `Transaction.Unit` and linked model remain the representation. PP's locale/UI parsers round inputs and do not enforce this CLI's exact-input contract. |
| Security/account selection | Wrapper policy: exact, conjunctive selectors and ambiguity rejection. PP's `SecurityCache` can resolve by the first matching identifier; it cannot enforce this stronger CLI rule. Extraction uses an isolated client, then binds to the selected existing security. |
| Sale admission | Wrapper policy: complete history must stay nonnegative, increases precede decreases at equal timestamps. PP's import actions do not implement this rule. No FIFO or profit calculation is reproduced. |
| Dates/defaults | Wrapper policy: preserve PP-extracted explicit time, including midnight; otherwise apply disclosed 11:00:00 / 11:00:01 or supplied time. |
| Document envelope | Wrapper policy: check physical page ordinals/counts, complete continuations and one supported financial item per statement; reject failure/skip/additional unsupported items before the whole document applies. PDFBox only groups actual pages, and PP converts every resulting PDF. |
| Filesystem/update policies | Wrapper-only: release qualification, immutable input, coordination, atomic replacement, semantic preservation, adjacent bookkeeping and exact backups/retention. PP core serializes and reloads the financial model; desktop save behavior does not implement the CLI contract. |

## Consequences of using PP as authoritative

An extraction error rejects the entire document, even when CLI values could fill
the mandatory field. No fake statement text is added to make extraction succeed.
Fees/taxes can supplement an absent **upstream-extracted** cost; an extracted zero
is present and cannot be overridden. A CLI date/amount/quantity is a
consistency assertion against PP's result. An extracted FX rate/gross is also a
consistency assertion. If PP produced no conversion and observed no exchange-rate
assignment, the wrapper can add an explicitly supplied conversion to the extracted
record using booked amount and costs. This does not reconstruct bank grammar.
Financial options still require a single statement.

PP calculates domestic gross from booked amount and costs, and derives a trade's
unit price from that gross. It does not retain every printed gross/price, unknown
line, repeated fee, or invented payment-time field. The wrapper does not promise
to validate these independently. Preview identifies PP derivations; a dividend
`--price` is rejected because PP has no dividend unit-price field.

The former synthetic assertions that required validating ignored content have
been replaced with explicit PP-authority regressions. Financial preservation,
duplicate recovery, entire-history sale checks and atomic rejection remain gates.
All existing gates remain mandatory.

Statements without a readable reference use their exact document hash and member
ordinal for identity. This can confirm only an identical document; other encodings
fall back to suspected-duplicate checks. PP's original note is preserved and
validated against adjacent evidence rather than used for wrapper bookkeeping.

This is fixture-scoped qualification, not a claim that PP diagnoses every
malformed source document. Changes to ignored-field behavior are intentional and
were approved by the owner for this refactor. More envelope types or mandatory
field supplementation need upstream support or an explicitly reviewed adapter;
they must not recreate bank grammar in the wrapper.

## Validation evidence

The final cumulative `bash tests/all.sh pp-pal:upstream-reuse-final` run passed
in 1,945 seconds (32 minutes 25 seconds). All four baseline suites and all sixteen
mandatory candidate gates passed; successful qualification ran each gate once.
All fifteen previous gates remain, with the direct upstream authority gate added.
The release snapshot inputs stayed unchanged throughout the successful run.

The development image was
`sha256:cd085dee8c573723cc6b04ff8828a4125a35065bc4642b9e7a193cf07b00a3e3`;
the qualified released image was
`sha256:41d3d8e731c04473ef6fd468672762b8caaa736ae2291ac9afdc30f7d9ddb83b`,
with synthetic released wrapper revision
`399a3583ddc804fde79a228cc32766f5606eb2b1`. Both used pinned PP revision
`8de1fe41d71a2c1b74a6bfbe913f885899aaf123`.

Coverage includes direct unadapted PP extraction comparisons, saved transactions
and full unrelated-state preservation, nine invented paired PDF/text fixtures,
complete continuation pages, synthetic EUR/USD trades and dividends, zero costs,
zero informational dividend shares, reference-free duplicate handling, PP import
actions, model69 migration, metadata recovery and actual wrong-output rejection
despite candidate-controlled passing tests. This historical run did not qualify
current owner layouts: its local dividend reference was incomplete (1/2).

Standards review found no blocking documented violation. The spec review accepted
the approved PP-authority correction. Complete current-layout evidence was added
subsequently through the [current-layout contract](../fixtures/v1/smartbroker-current/README.md)
and mandatory `tests/smartbroker-current.sh` gate. The new layouts use the same
upstream extraction authority, including its intentionally ignored printed fields.
