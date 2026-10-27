# Upgrading pp-pal to a new Portfolio Performance release

This runbook is for an agent, and is readable by a human. Follow it when pp-pal stops
with:

```
UPGRADE REQUIRED: Portfolio Performance <tag> (<revision>) has no tested pp-pal support.
```

The prompt printed under that line names the target release and the operation to
resume. The same data is saved in `$STATE/upgrades/<tag>/request.json`:
- `upstream.tag` and `upstream.revision`,
- `argv`,
- `cwd`,
- `prompt`.

`$STATE` is `${PP_PAL_STATE_DIR:-${XDG_STATE_HOME:-~/.local/state}/pp-pal}`.

The work runs unattended, from the upgrade through to the final re-run of the original
command. Everything runs as the invoking user; never use `sudo`. Read
[ADR 0006](docs/adr/0006-agent-local-upgrades.md) for why this is allowed and what it
must not do.

## Rules

- **Never approve the PR.** The owner approves and merges it. GitHub does not allow
  self-approval anyway.
- **Never delete a gate** from `runtime/candidate-gates.json`. Never weaken a financial
  assertion or oracle to make a check pass.
- Adapt existing tests, fixtures or expected results only where the new upstream
  genuinely changes them, for example a new XML model version. Explain every adaptation
  in the PR.
- **Never read, copy or commit the owner's real portfolio** to build fixtures. Fixtures
  are invented: see `fixtures/v1/README.md`.
- Do not generate fixes for the reviewed headless PDFBox patch. If
  `runtime/patches/HeadlessPatch.java` refuses the new source, stop: that needs
  manual adapter maintenance.

## Steps

1. **Work area.** Clone and branch:

   ```sh
   STATE=${PP_PAL_STATE_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/pp-pal}
   TAG=<tag from request.json>
   git clone https://github.com/medlor/portfolio-performance-pal "$STATE/upgrades/$TAG/checkout"
   cd "$STATE/upgrades/$TAG/checkout"
   git switch -c "upgrade/pp-$TAG"
   ```

   If that checkout already exists from an earlier attempt, reuse it and continue on
   its branch.

2. **Pins.** In `runtime/release-package.json`, set `upstream.tag`, `upstream.revision`,
   `upstream.releaseAsset`, `upstream.sourceSha256` and `upstream.releaseSha256` for the
   new release. Compute both digests from the official assets with `sha256sum`:
   - `https://api.github.com/repos/portfolio-performance/portfolio/tarball/<revision>`
   - `https://github.com/portfolio-performance/portfolio/releases/download/<tag>/PortfolioPerformance-<tag>-linux.gtk.x86_64.tar.gz`

   Use the revision from `request.json`, which the updater resolved from the release tag.

3. **Build and adapt.** Read `docs/upgrading-version-support.md` and
   `docs/updating-broker-extractor.md` first. Then build:

   ```sh
   R="$STATE/upgrades/$TAG/runtime/dev"   # a development build must be named dev
   tools/build-runtime --output "$R"
   ```

   The build fails if the patch, compilation or checksums fail. Adapt the wrapper Java
   code in `runtime/src/main/java/pal` where upstream APIs changed. The runtime is
   read-only, so delete it before each rebuild:

   ```sh
   python3 -c 'import sys; sys.path.insert(0, "tools"); import runtime_dir; runtime_dir.remove_runtime(sys.argv[1])' "$R"
   tools/build-runtime --output "$R"
   ```

4. **Fixtures and gates.** Add fixtures and gates for new behaviour, for example a new
   model version or a migration, following `docs/agents/test-execution.md`. Register new
   gates in `runtime/candidate-gates.json`. Extend `runtime/release-package.json`
   (`input`, `output`, `migrations`, `layouts`, `features`) only for combinations that
   your new tests prove.

5. **Suite.** Run the focused scripts first, then the full suite. Record the elapsed
   time:

   ```sh
   bash tests/<focused>.sh "$R"
   PP_PAL_TEST_JOBS=4 bash tests/all.sh "$R"
   ```

   Record the evidence in `runtime/support-matrix.json`, appending a new entry and
   keeping the earlier ones. Update `docs/headless-runtime.md` too.

6. **Commit, push, PR.**

   ```sh
   git add -A && git commit -m "Support Portfolio Performance $TAG"
   git push -u origin "upgrade/pp-$TAG"
   gh pr create --base main --title "Support Portfolio Performance $TAG" --body-file pr-body.md
   ```

   `pr-body.md` includes:
   - the target release and revision,
   - each code adaptation and why it was needed,
   - the full `TRUSTED CHANGES` list from step 7 (run step 7 first if needed, then edit
     the PR body),
   - the suite command, its result and the elapsed time,
   - a request for the owner to review and merge.

   If `git push` or `gh` lacks permission, do not stop. Report it to the owner, keep the
   branch local, and continue with step 7.

7. **Activate locally.** The original command's `--file` or `--config` selects the
   portfolio to validate. Run from the original `cwd`:

   ```sh
   cd "<cwd from request.json>"
   <pp-pal path from the prompt> --update --local-upgrade "$STATE/upgrades/$TAG/checkout" [--file … | --config …]
   ```

   The checkout must be committed and clean. On success the output ends with
   `TRUSTED CHANGES …` and `ACTIVATED local-upgrade <identity>`.

8. **Resume.** Re-run the original command exactly, as saved in `request.json`
   (`shlex.join(argv)` in `cwd`). This includes `--apply --desktop-closed` when present.
   Report its outcome to the owner, together with the PR link.

## Stop conditions

Stop, report to the owner, and activate nothing if any of these happen:
- the headless patch fails to apply;
- a gate still fails after genuine adaptation;
- a gate would have to be removed;
- the selected-copy validation in step 7 fails.

A missing GitHub permission is not a stop condition.
