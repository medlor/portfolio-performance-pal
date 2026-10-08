# Model 69 -> 70 expectations

`model69.xml` is an invented ID-reference XML fixture using the same independently
specified financial records as `../portfolio.xml`, with input model 69. The
separate model70 fixture is the expected complete post-migration state, not a
candidate-generated expected output. Both fixtures contain two account pairs,
an open EUR holding, closed USD history with FX units, a dividend, settings,
security definitions and classification assignments.

Pinned upstream 0.88.0 (`8de1fe41d71a2c1b74a6bfbe913f885899aaf123`),
`ClientFactory.upgradeModel`, case 69, adds optional client-filter weights.
Existing selections without weights and all existing financial/persisted state
remain unchanged; the saved version becomes 70. No earlier migrations are
declared. `MigrationOracle` loads pre/post/expected files directly through pinned
upstream APIs and compares complete canonical state. `FixtureOracle` checks
independent literal financial records and relationships before and after.

`tests/migrations.sh` covers read/preview/disposable-roundtrip immutability,
purchase/sale/dividend writes and unrelated-state oracles, exact separate backups
with an existing daily backup, repeated/restored model69 inputs, same-version
writes, required-backup failure, unsupported paths, invalid financial plans,
stale input, two-year Berlin retention and post-commit recovery without replay.
The updater suite separately exercises released owner `--update`, preview and
apply on model69. Input stays local and is never used as a build input.
