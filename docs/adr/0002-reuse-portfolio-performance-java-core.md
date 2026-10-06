# Reuse Portfolio Performance's Java Core

Reuse Portfolio Performance's Java persistence, transaction models, and broker PDF extraction through a wrapper packaged in Docker. Maintaining Python equivalents would require independently tracking file-model and broker-layout changes; direct reuse trades that duplicated work for upstream Java API coupling and a more involved build/runtime environment. Pin and validate upstream revisions before declaring support; Docker packaging does not establish headless feasibility or automatic compatibility with new releases.
