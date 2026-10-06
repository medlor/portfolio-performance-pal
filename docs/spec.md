# Java wrapper baseline

## Goal and status

Provide a CLI for the portfolio owner to query holdings and transaction history, record manual purchases, sales, and dividends, and import current-layout Smartbroker statements. Preserve the portfolio's financial meaning and unrelated data so it remains usable in Portfolio Performance.

This is the current design baseline. There is no implementation or tested release, upstream revision, image, or model-format combination yet. The earlier Python design is on `python-design`; its GitHub tickets are historical and do not authorize a Python reimplementation.

## Architecture

Run each command in a short-lived Docker container with explicit access to the portfolio, adjacent metadata/backups, configuration, and optional input PDF. There is no persistent service. A Java backend directly reuses Portfolio Performance core persistence, financial models, linked transaction handling, and PDF extraction. Any optional thin Python CLI only handles invocation and presentation; it does not implement XML persistence, financial models, or broker extraction.

Pin the exact tested upstream source revision and resulting container image, and record reproducible build/JDK dependencies. Upstream core classes are integration points, not a promised stable exported API. First establish that the needed code can be built and run headlessly without the GUI, including PDF extraction. Headless feasibility has not been validated yet.

Initially support only tested ID-reference XML. Track application release, exact upstream revision, XML model version/representation, and image identity separately in an explicit support matrix. Reject unsupported input before mutation. Upstream loading may perform migration; identify it before saving and permit only behavior explicitly validated and documented for that matrix entry. Successful loading does not establish rewrite safety.

Use the upstream model and persistence for changes. Portfolio Performance remains responsible for FIFO and profit calculations; do not reimplement or persist their results. Preserve security definitions, historical transactions, account links, settings, classifications, and unrelated supported data. Save to a temporary file and validate semantic contents before atomic replacement. Successful output need not preserve XML whitespace, but must preserve financial meaning and unrelated data.

## User behavior

- **Holdings:** group quantities by security and securities account. Filter by security identifier/name and securities account; sort by name, quantity, or holding last updated. Omit zero holdings by default and show newest quantity updates first. Holding last updated excludes dividends and metadata edits, as defined in [the glossary](../GLOSSARY.md).
- **History:** retain purchases and sales after a holding closes. Filter by security identifier/name, securities account, transaction type, and date range. Sort by date, newest first by default. Both queries use stable ordering, simple text output, and `--limit` defaulting to 15; zero means unlimited. Show quantities and recorded amounts, without live valuations or calculated profit.
- **Manual transactions:** purchases and sales create consistent linked securities-account and cash-account entries. Reuse an unambiguously identified security or create one when required for a purchase. Partial/full sales retain security definitions and history. Dividends associate a cash credit, fees, and taxes with a security without changing quantity. Missing or ambiguous required account/security selections prevent application.
- **Amounts and FX:** accept quantity and price, gross value, or booked total with fees/taxes as appropriate. Derive only determined missing values using decimal arithmetic and tested upstream scaling/rounding. Reject contradictions beyond supported rounding and unrepresentable precision. Preserve foreign-currency amounts and exchange-rate semantics; require explicit or PDF-extracted rates where needed. Do not fetch rates automatically.
- **Dates:** allow backdated entries, preserve supplied times, and use visible `11:00:00` fallback for date-only input. A sale must leave the entire affected security/securities-account quantity history nonnegative at its date and afterwards, using deterministic ordering for equal timestamps.
- **Smartbroker PDFs:** import one PDF per command using the upstream extractor selected by verified bank identifiers and layout, rather than marketing name alone. Support purchase, sale, and dividend layouts represented by validated fixtures. The statement's booked amount is authoritative. CLI values may complete missing information and must be identified in preview; conflicting extracted financial values cannot be overridden. Any unsupported, ambiguous, incomplete, contradictory, or invalid transaction prevents application of the entire document. All recognized transactions in a PDF apply together.
- **Configuration:** discover configuration in the working directory or select it with `--config`. Store the portfolio path and default account pair. `--file` and explicit account parameters override configuration. Default the portfolio filename to `portfolio_performance.xml`; the configuration filename is undecided. Resolve configured relative paths from the configuration directory and explicit `--file` paths from the working directory. Container mounts must preserve these path semantics.

## Preview, duplicates, and safe application

Mutation commands preview by default without changing the portfolio, import metadata, or backups. Show proposed records, account/security choices, derived amounts, supplemental PDF values, and fallback times. `--apply` validates and writes without another interactive prompt; unattended application stops on uncertainty. Use the input snapshot that produced the validated preview and reject changed input before replacement.

Keep document hashes, broker references, and associated transaction IDs in adjacent metadata following [ADR 0001](adr/0001-separate-import-bookkeeping.md). Confirmed re-imports are skipped only when evidence agrees with the current portfolio. Similar dates, quantities, prices, or amounts are suspected duplicates requiring an explicit override. Missing metadata, stale IDs, inconsistent evidence, external edits, or backup restoration fall back to suspected-duplicate checks instead of assuming a new import. Do not put bookkeeping in custom XML fields or user-visible notes.

Before the first successful change of a Europe/Berlin calendar day, preserve the original alongside it as `<filename>.YYYY-MM-DD.bak` unless that daily backup exists. Never overwrite it; failure to create a required backup prevents writing. Portfolio Performance must be closed while applying changes. Serialize to a temporary file on the same filesystem, validate, recheck stale input, and atomically replace the portfolio. Validation or pre-replacement failure leaves the original intact. Atomic replacement alone does not protect against concurrent writers; design writer coordination and stale checks without claiming the GUI participates in a lock.

After a successful write, prune only this tool's backups. Retain the three most recent daily dates per month for the current Europe/Berlin month and preceding 23 months, or all dates when fewer exist; remove older tool-owned dates outside that window. Preview, failed operations, and skipped duplicates never prune backups.

Portfolio replacement and adjacent metadata cannot be assumed to be one filesystem transaction. Define failure recovery so incomplete bookkeeping cannot authorize another import. A metadata or pruning failure after successful replacement must report that the financial change succeeded and must not encourage repeating it.

## Validation and maintenance gates

Establish anonymized, versioned fixtures and reproducible commands during implementation. Retained sample files are reference material, not automatically safe public fixtures. Never use the ignored real portfolio as a committed fixture or copy financial contents into issues.

Test the real command/container boundary against temporary portfolios, configuration, and metadata. Cover holdings/history behavior; purchase, partial/full sale, dividend and FX semantics; decimal precision; fallback and explicit times; backdated quantity failures; account ambiguity; and all-or-nothing multi-transaction imports. Test upstream PDF-to-text conversion as well as extraction from representative PDF/text fixtures. Keep older supported layouts as regressions.

Load saved outputs with the pinned core and inspect the model, rather than merely checking XML parsing. Compare no-op round trips and intended changes semantically: quantities, transaction types, dates/times, amounts, fees, taxes, currencies/rates, security references, linked entries, and unrelated settings/classifications. Inspect model state before and after migration. Cover every declared release/revision/model/image combination; none are declared yet.

Verify preview immutability, duplicate overrides and uncertain evidence, stale input refusal, existing backup preservation, backup failure, Europe/Berlin boundaries and 24-month retention, and failures before/after replacement. Rejected operations preserve original bytes. Validate the image's headless behavior and host file permissions/mounts before claiming deployment support.

Follow [the version-support guide](upgrading-version-support.md) and [the extractor guide](updating-broker-extractor.md) for manual updates. Record exact revisions, migration behavior, supported layouts, commands, and limitations only after checks pass. Respect upstream licensing and attribution.

## Deferred scope

Binary/compressed/encrypted/protobuf and XPath-reference editing; additional brokers or unvalidated historical layouts; multiple-PDF batch import; security/transaction deletion; general repair/migration tools; live prices/FX; FIFO/profit reimplementation; JSON or a separate table output mode; a GUI or multi-user service; automatic upstream updates; and writing while Portfolio Performance is open.
