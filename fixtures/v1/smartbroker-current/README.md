# Current Smartbroker/Baader layout regressions

Three complete private owner PDFs were inspected locally on 2026-10-11: a
single-page EUR purchase, a two-page EUR sale with a tax continuation, and a
two-page CHF fund distribution paid into EUR cash. Pinned Portfolio Performance
0.88.0 (`8de1fe41d71a2c1b74a6bfbe913f885899aaf123`) converted and extracted
one financial record from each without errors. The existing wrapper admitted
all three complete statements. The owner's portfolio was not read or changed.

These public PDFs are **reconstructed anonymized layout specimens**, not redacted
copies of the originals. Their matched bank labels, execution-table line shapes,
fund-distribution sections, and physical page sequences follow the inspected
PP-converted layouts. Customer/address headers, bank contact/legal footers, and
original PDF metadata are omitted. Every security, identifier, account, reference,
quantity, amount, date and time is invented. No original personal or financial
content is copied. Fresh PDFs are rendered from the reviewed text with PDFBox;
paired text is the exact pinned PP conversion of those PDFs. This verifies real
conversion of the anonymized layouts as well as local acceptance of the originals,
without placing a private original in a fixture or build context.

| Specimen | Pages | Independent expected record |
| --- | --- | --- |
| `purchase` | 1 | Buy 2 EUR shares, EUR 50 debit, execution 2025-03-01 09:30:00 |
| `sale` | 2 | Sell 2 EUR shares, EUR 60 credit, execution 2025-03-02 09:30:01 |
| `dividend-chf` | 2 | 10 informational shares, CHF 20 gross, EUR 16 gross, EUR 4.05 taxes, EUR 11.95 credit, 2025-03-05 11:00:00 fallback |
| `mixed` | 5 | All three statements together, with unchanged per-member values |
| `incomplete-sale` | 1 | Refusal: physical continuation 2/2 missing |
| `mixed-invalid` | 3 | Refusal of the whole document: later distribution lacks booked payment |

The mixed PDF is a constructed concatenation of the anonymized complete statement
layouts, not a claim that the owner supplied a mixed broker document. CHF rate
1.25 printed as EUR/CHF is retained as EUR 0.8 per CHF. Dividend continuation
calculations are not extra financial records or additional taxes. Execution times
come from the trade table rather than order time or cash value date. The wrappers
retain upstream extraction authority, including fields intentionally ignored by PP.

`tests/smartbroker-current.sh` is a mandatory candidate gate. It exercises the
released owner launcher for mixed preview/application, the container boundary for
individual statements and refusals, literal saved-model checks, complete unrelated
model preservation, linked cash entries, confirmed repeats with and without an
override, daily protection, document-wide suspected-duplicate refusal/override, and actual paired PDF conversion. All seventeen prior
gates remain mandatory. Earlier synthetic EUR/USD and monetary/recovery/migration
regressions remain in their existing suites.

Regenerate using the pinned local image and existing test scheduling boundary:

```sh
python3 tests/oracle.py run --rm --network none --user "$(id -u):$(id -g)" \
  -v "$PWD/fixtures/v1/smartbroker-current:/fixtures:rw" \
  -v "$PWD/scripts:/scripts:ro" --entrypoint java pp-pal:issue34 \
  -cp '/opt/pp/classes:/opt/pp/lib/*' /scripts/RenderCurrentLayouts.java /fixtures
(cd fixtures/v1/smartbroker-current && sha256sum *.pdf *.txt > SHA256SUMS)
PP_PAL_LAUNCHER="$PWD/tools/run-container" bash tests/smartbroker-current.sh pp-pal:issue34
```

This evidence covers these complete Baader/Smartbroker layouts only. It does not
qualify arbitrary brokers, missing pages, other FX trade layouts/currency pairs,
or writing the private portfolio. Exact full-suite revision/image evidence is
recorded separately in `runtime/support-matrix.json` after qualification passes.
