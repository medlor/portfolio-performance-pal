# Domain Docs

## Layout

This is a single-context repo:

- GLOSSARY.md contains the domain vocabulary.
- docs/adr/ contains architectural decisions.

## Consumer rules

Before exploring domain behavior, read GLOSSARY.md and ADRs relevant
to the work. If GLOSSARY-MAP.md is introduced later, follow its pointers
to the relevant context glossaries and ADRs.

If domain documents are absent, proceed silently. Domain-modeling
work creates them when terms or decisions are resolved.

Use glossary terms in specifications, issue titles, code discussions,
and tests. Reconsider invented synonyms; flag real vocabulary gaps
for domain modeling.

Surface conflicts with an existing ADR explicitly rather than silently
overriding the decision.
