#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
image=${PP_PAL_IMAGE:-pp-pal:0.88.0}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
run() { docker run --rm --network none --user "$(id -u):$(id -g)" -v "$PWD/fixtures:/fixtures:ro" -v "$work:/work" "$image" "$@"; }
oracle() { python3 "$PWD/tests/oracle.py" run --rm --network none --user "$(id -u):$(id -g)" -v "$PWD/fixtures:/fixtures:ro" -v "$PWD/runtime/src/test/java:/tests:ro" -v "$work:/work" --entrypoint java "$image" -cp '/opt/pp/classes:/opt/pp/lib/*' /tests/pal/FixtureOracle.java "$1"; }
oracle /fixtures/v1/portfolio.xml
run inspect /fixtures/v1/portfolio.xml > "$work/before"
test "$(head -n 1 "$work/before")" = 'MODEL 70 EUR'
run roundtrip /fixtures/v1/portfolio.xml /work/roundtrip.xml
run inspect /work/roundtrip.xml > "$work/after"
cmp "$work/before" "$work/after"
oracle /work/roundtrip.xml
run prove /fixtures/v1/portfolio.xml /work/changed.xml
run pdf /fixtures/v1/baader-buy.pdf > "$work/pdf"
diff -u fixtures/v1/baader-buy.expected "$work/pdf"
run pdf /fixtures/v1/baader-buy.txt > "$work/text"
cmp "$work/pdf" "$work/text"
oracle /fixtures/v1/baader-buy.pdf
oracle /fixtures/v1/baader-buy.txt
for unsupported in model xpath; do
  if [[ $unsupported == model ]]; then
    sed 's/<version>70<\/version>/<version>68<\/version>/' fixtures/v1/portfolio.xml > "$work/unsupported.xml"
  else
    sed 's/reference="2"/reference="..\/..\/securities\/security"/' fixtures/v1/portfolio.xml > "$work/unsupported.xml"
  fi
  printf 'existing output must survive\n' > "$work/rejected-output.xml"
  cp "$work/rejected-output.xml" "$work/output-before"
  cp "$work/unsupported.xml" "$work/input-before"
  if run roundtrip /work/unsupported.xml /work/rejected-output.xml > "$work/error" 2>&1; then
    echo "ERROR: accepted unsupported $unsupported" >&2; exit 1
  fi
  cmp "$work/input-before" "$work/unsupported.xml"
  cmp "$work/output-before" "$work/rejected-output.xml"
done
test "$(stat -c %u "$work/changed.xml")" = "$(id -u)"
echo 'PASS: real headless core, linked mutation, semantic preservation, PDF/text extraction'
