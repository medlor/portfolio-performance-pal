# Allow Validated Agent Upgrades to Activate Locally

When the update check finds a stable Portfolio Performance release that the released
wrapper does not support, stop the operation, and print a prompt that points an agent
at `UPGRADE.md`. The agent adapts the wrapper in a git checkout, runs the full suite,
and opens a PR for the owner to approve. It then activates the pair with
`pp-pal --update --local-upgrade CHECKOUT`, and re-runs the original command exactly,
including `--apply --desktop-closed`, without further confirmation. This is an exception
to ADR 0004, which activates only released wrapper tags: the owner may not approve the
PR for a while, and the locally tested version must still serve every operation on the
portfolio in the meantime.

In unattended mode, the agent may add tests and fixtures, and may also adapt existing
ones, for example for a model-version bump. The checkout's validation files then become
the trusted bundle. These safeguards apply:
- No gate may be removed.
- Every changed or removed trusted file is listed in the activation report and in the PR.
- The owner's selected portfolio is still validated by the *previous* trusted oracle on
  the *previous* runtime, independently of the agent's adapted tests.

The previous pair and the previous trusted bundle are retained. A later stable wrapper
release that qualifies replaces the local pair through normal release discovery.

The cost is that the agent's changes to expectations take effect before the owner has
reviewed them; the PR is the record of what changed. Failed gates, a removed gate, a
failed selected-copy validation, or an inexact headless patch stop the upgrade without
activating anything.
