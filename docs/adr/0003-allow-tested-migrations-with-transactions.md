# Allow Tested Portfolio Migrations With Transactions

Allow a transaction command to persist a tested upstream portfolio-model migration alongside its intended financial changes, with the migration explicitly shown in preview and a separate backup of the exact pre-migration state. This avoids requiring a separate desktop migration step, at the cost of additional migration validation; the owner manages desktop upgrades and compatibility independently. Restrict this behavior to validated migration paths; updating the wrapper alone leaves the portfolio unchanged.
