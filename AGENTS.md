## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for medlor/portfolio-performance-pal.
Before tracker operations, read `docs/agents/issue-tracker.md`.

### Triage labels

Use the five canonical triage labels.
Before applying triage labels, read `docs/agents/triage-labels.md`.

### Domain docs

This repo uses a single-context glossary and ADR layout.
Before exploring domain behavior, read `docs/agents/domain.md`.

### Runtime implementation and tests

Java commands live in `runtime/src/main/java/pal`; upstream model oracles
live in `runtime/src/test/java/pal`.

Before implementing runtime commands, adding tests, running validation,
or changing suite execution, read `docs/agents/test-execution.md`.

### Source inspection

Locate symbols with `rg` before reading source. Read relevant methods and
their immediate context; expand when needed. Prefer targeted metadata
queries and filtered log summaries. Narrow truncated reads.
