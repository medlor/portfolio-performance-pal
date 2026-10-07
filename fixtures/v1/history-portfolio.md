# History relevance fixture v1

Every record is invented. `HistoryFixtureBuilder.java` loads the committed query
fixture through the pinned Java core and adds three independently specified cash
dividends. Normal tests use this committed XML; candidates cannot regenerate
expectations. Owner commands run against temporary copies. No private portfolio
or reference PDF was used.

| Added record | Owner/security | Date/time | Booked EUR | Expected relevance |
|---|---|---|---:|---|
| `60000000-...000001` | Shared Cash / Alpha | 2025-01-20 11:00 | 1.00 | Twin1 and Twin2, ambiguous |
| `60000000-...000002` | Other Cash / Beta | 2025-01-22 11:00 | 77.77 | None; Other Depot has no Beta transaction |
| `60000000-...000003` | Unreferenced Cash / Alpha | 2025-01-23 11:00 | 99.99 | None; no securities account references this cash account |

The first record intentionally equals the existing Alpha dividend's date, amount,
currency, security and zero recorded shares. Both distinct original transactions
must appear, once each, and both retain ambiguous Twin1/Twin2 relevance. The two
unrelated added dividends must be absent even without a securities-account filter.

Independent upstream dividend counts for a filter selecting each account are
Twin1 = **3** (two Alpha, one Beta), Twin2 = **2** (two Alpha), Other = **1** (Alpha).
Beta's old purchase/full sale still makes its original Shared Cash dividend
relevant to Twin1. Filtering to Twin2 must exclude every Beta record/dividend.
Filtering dates/types must not remove the historical evidence used for relevance.

The original query fixture has 27 actual securities transaction records and 3
relevant dividends = 30 history rows, plus a header. Its default-15 history ends
with Tie01; unlimited ends Tie16. Sixteen original delivery transactions share
2024-01-01 08:00; both date directions must preserve their UUID order Tie01..Tie16.
The extended fixture adds one relevant dividend, so its unfiltered history has
31 rows, excluding the two unrelated additions. Exact output and immutability
checks live in `tests/history.sh`.
