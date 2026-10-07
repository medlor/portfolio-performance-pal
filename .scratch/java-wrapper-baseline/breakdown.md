1. **Prove a reproducible headless Java core and PDF runtime** — Blocked by: none. Build a local Docker image that can load, inspect, save, and reload an anonymized portfolio and convert and extract a representative PDF without launching the desktop application.

2. **Query holdings through the Linux launcher and configuration** — Blocked by: 1. Run a short-lived container command to inspect holdings in the selected supported portfolio with predictable paths, account selection, filters, sorting, and limits.

3. **Query transaction history including dividend relevance** — Blocked by: 2. Inspect purchases, sales, and dividends across open and closed holdings while making ambiguous securities-account attribution visible.

4. **Protect launch and upgrade attempts with retained backups** — Blocked by: 2. Protect the selected portfolio with due monthly backups at launch and exact pre-upgrade backups before actual upgrade attempts.

5. **Validate and activate released wrapper/upstream pairs locally** — Blocked by: 1, 4. Use --update to discover, build, validate, and automatically activate a released wrapper/upstream pair while preserving the selected portfolio and previous validated pair on failure.

6. **Preview a manual purchase with validated decimal amounts** — Blocked by: 5. Preview a same-currency purchase of an existing security using resolved accounts and upstream financial models without recording financial changes.

7. **Apply purchases with atomic replacement and daily protection** — Blocked by: 6. Record a validated purchase with --apply, preserving unrelated data and protected recovery state while refusing stale or unsafe writes.

8. **Create a security explicitly when recording its first purchase** — Blocked by: 7. Preview and record a purchase that explicitly creates a required security while preserving existing definitions and avoiding ambiguous reuse.

9. **Record partial and full sales with complete quantity-history validation** — Blocked by: 7. Preview and record sales, including backdated entries, only when the affected holding remains nonnegative throughout its entire quantity history.

10. **Record security-associated dividends with fees and taxes** — Blocked by: 7. Preview and record dividend cash credits for an existing security without changing held quantity or holding last updated.

11. **Record foreign-currency purchases, sales, and dividends** — Blocked by: 9, 10. Preview and record manual foreign-currency transactions with explicit exchange information and preserved upstream currency/rate semantics.

12. **Persist tested model migrations only with financial changes** — Blocked by: 7. Allow a financial command to apply a fixture-validated upstream model migration while clearly disclosing the output version and protecting the exact original state.

13. **Preview a verified current-layout Smartbroker purchase PDF** — Blocked by: 6. Convert one supported purchase PDF headlessly and preview all recognized transactions with authoritative booked amounts and explicit missing-value supplementation.

14. **Apply purchase PDFs with duplicate-safe bookkeeping and recovery** — Blocked by: 8, 13. Apply all recognized purchases in one PDF together while skipping confirmed re-imports and requiring a document-wide override for every suspected duplicate.

15. **Import verified current-layout Smartbroker sale PDFs** — Blocked by: 9, 14. Preview and atomically apply supported sale statements with complete quantity-history validation and duplicate protection.

16. **Import verified current-layout Smartbroker dividend PDFs** — Blocked by: 10, 14. Preview and atomically apply supported dividend statements as security-associated cash credits without quantity changes.

17. **Import foreign-currency and mixed-transaction Smartbroker PDFs** — Blocked by: 11, 15, 16. Import supported foreign-currency and mixed purchase/sale/dividend documents as one validated financial decision using stated exchange information.
