# Upstream extraction authority regressions

These wholly invented PDF/text pairs exercise public PP Baader grammar beyond
the former wrapper regex subset: explanatory headers/footers, two-page statements,
fund distributions, multiple fees/taxes, zero costs, cancellation and EUR/USD gross
units. No owner PDF or financial content is copied. They are synthetic adapter
regressions and do not qualify every current Smartbroker layout.

`tests/upstream-reuse.sh` uses unmodified `BaaderBankPDFExtractor` as the financial
oracle and independent timestamp literals. It compares detached plans and saved
records, all cost/FX units, original notes/sources, linked entries and complete
unrelated model state. It also checks exact-file repeats without broker references,
unsupported/failure members, immutable refusals and PP's intentional ignored-field
behavior in the retained purchase/sale/dividend fixtures.

Generate with Python 3 and ReportLab 4.4.7:

```sh
python3 scripts/generate-pdf-purchase-fixtures.py pp-extraction
cd fixtures/v1/pp-extraction
sha256sum *.pdf *.txt > SHA256SUMS
```

See [the reuse boundary](../../../docs/upstream-reuse.md) for the approved change
from independently parsing and validating every printed field to PP authority.

Current-layout evidence was added separately on 2026-10-11 in the
[current-layout contract](../smartbroker-current/README.md) and mandatory
`tests/smartbroker-current.sh` gate. These earlier fixture notes retain the scope
and limitations of their historical validation; they do not describe the new
complete purchase, sale and CHF-distribution specimens.
