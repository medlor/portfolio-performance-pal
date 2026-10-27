# Query fixture v1: independent facts

Every record and identifier is invented. `QueryFixtureBuilder.java` uses upstream
model/persistence APIs to construct this committed ID-reference XML model 70.
It replaces random UUIDs and update timestamps with deterministic synthetic
values. No reference samples or private portfolio were read. Expectations live
in `tests/holdings.sh` and must not be regenerated from a candidate.

Securities account UUIDs `30000000-0000-4000-8000-000000000001` and `...000002`
are both named `Twin Depot` and share cash account `20000000-...000001` (`Shared
Cash`). `...000003` is `Other Depot`, referencing `20000000-...000002` (`Other
Cash`). Duplicate securities account names deliberately require UUID selection.

| Account | Security | Quantity history | Final quantity | Holding last updated |
|---|---|---|---:|---|
| Twin 1 | Alpha | Jan 10 10:00 purchase 10; Jan 12 09:00 sale 4 | 6 | 2025-01-12 09:00 |
| Twin 2 | Alpha | Jan 11 08:00 purchase 3 | 3 | 2025-01-11 08:00 |
| Twin 1 | Beta | Jan 9 08:00 purchase 5; Jan 13 08:00 full sale 5 | 0 | 2025-01-13 08:00 |
| Other | Alpha | Jan 14 08:00 delivery inbound 2 | 2 | 2025-01-14 08:00 |
| Twin 1 | Movement | Jan 1 08:00 delivery inbound 5; Jan 16 08:00 transfer out 3 | 2 | 2025-01-16 08:00 |
| Other | Movement | Jan 16 08:00 linked transfer in 3; Jan 17 08:00 delivery inbound 2; Jan 18 08:00 delivery outbound 1 | 4 | 2025-01-18 08:00 |

Alpha's UUID is `10000000-...000001`, ISIN `DE0000000001`, WKN `TEST01`, ticker
`ALPHA`. Beta and Movement have following security UUID suffixes 2 and 3. `Tie
01` through `Tie 16` have ascending security UUID suffixes 4 through 19, all in
Other Depot, quantity 1 and identical 2024-01-01 08:00 quantity dates. They prove
default-15 truncation and stable ties. There are 21 nonzero holdings, 22 with zero
holdings included; output includes one additional header line.

Default newest-first order: Other/Movement, Twin1/Movement, Other/Alpha,
Twin1/Alpha, Twin2/Alpha, then Tie01 through Tie16. Including zero inserts
Twin1/Beta between Other/Alpha and Twin1/Alpha. Name-ascending Alpha ties must
remain Twin1 (6), Twin2 (3), Other (2), based on UUIDs rather than incidental
loading/list order. Quantity-descending starts 6,4,3,2,2 with the Alpha security
before Movement in the quantity-2 tie.

Shared Cash holds an Alpha dividend dated Jan20 and Beta dividend Jan21; Other
Cash holds an Alpha dividend Jan15. They must not change holdings timestamps.
Alpha's metadata note, historical price and all object update timestamps are
later than the quantity dates; a synthetic watchlist and client setting are
unrelated persisted data. Read/rejected commands must preserve the exact XML,
adjacent metadata and configuration bytes.
