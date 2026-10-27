#!/usr/bin/env bash
# Checks the rootless runtime build: checksum refusal, layout, pinned JDK,
# read-only result, identity and removal helpers.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
work=$(mktemp -d)
trap 'chmod -R u+w "$work" 2>/dev/null; rm -rf "$work"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

grep -Eq '^[^#]*\bsudo\b' tools/build-runtime && fail 'build script must not use sudo'

# (a) A corrupted archive is refused before anything is assembled.
mkdir "$work/bad"
for name in upstream-source.tar.gz upstream-release.tar.gz jdk.tar.gz; do
  printf 'not the pinned archive\n' > "$work/bad/$name"
done
if tools/build-runtime --inputs "$work/bad" --output "$work/out/dev" > "$work/a.out" 2>&1; then
  fail 'corrupted inputs were accepted'
else
  status=$?
fi
[[ $status == 3 ]] || fail "corrupted inputs exit $status, expected 3"
grep -q 'SHA-256 mismatch' "$work/a.out" || fail 'missing SHA-256 mismatch message'
[[ ! -e $work/out/dev ]] || fail 'output exists after a failed build'
! compgen -G "$work/out/.build-*" > /dev/null || fail 'temporary build directory left over'

# (d) An existing output directory is refused.
mkdir -p "$work/exists/dev"
if tools/build-runtime --output "$work/exists/dev" > /dev/null 2>&1; then
  fail 'existing output accepted'
else
  status=$?
fi
[[ $status == 2 ]] || fail "existing output exit $status, expected 2"

# (b) A real build from verified archives.
tools/build-runtime --output "$work/dev" > "$work/b.out"
[[ $(tail -n 1 "$work/b.out") == dev ]] || fail 'identity is not the last stdout line'
python3 - "$work/dev/runtime.json" <<'PY' || fail 'runtime.json labels'
import json, sys
data = json.load(open(sys.argv[1]))
assert data['schema'] == 1 and data['identity'] == 'dev' and data['xmlModel'] == '70', data
assert data['upstreamRevision'] == '8de1fe41d71a2c1b74a6bfbe913f885899aaf123' and data['upstreamRelease'] == '0.88.0', data
assert data['jdk'] == 'Temurin-21.0.11+10' and data['platform'] == 'linux/amd64', data
PY
"$work/dev/jdk/bin/java" -version 2>&1 | grep -q 'Temurin-21.0.11+10' || fail 'runtime JDK is not Temurin 21.0.11+10'
unzip -p "$work/dev/classes.jar" help.txt | cmp - runtime/help.txt || fail 'help.txt differs in classes.jar'
[[ ! -e $work/dev/classes ]] || fail 'classes directory left next to classes.jar'
# Deterministic classpath: never filesystem order. Older versions of a duplicated
# artifact come last (PDFBox 1.8 must never shadow PDFBox 3), every jar exactly once.
python3 - "$work/dev" <<'PY' || fail 'classpath order'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
entries = (root / 'classpath').read_text().split()
assert entries[0] == 'classes.jar', entries[:3]
jars = sorted('lib/' + path.name for path in (root / 'lib').glob('*.jar'))
assert sorted(entries[1:]) == jars and len(set(entries)) == len(entries), 'classpath must list every lib jar once'
legacy = ['lib/org.apache.pdfbox_1.8.17.jar', 'lib/org.apache.pdfbox.fontbox_1.8.17.jar']
assert entries[-2:] == legacy, entries[-3:]
assert all(entries.index(old) > entries.index('lib/org.apache.pdfbox.io_3.0.8.jar') for old in legacy)
import zipfile
manifest = zipfile.ZipFile(root / 'classes.jar').read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '').replace('\n ', '')
class_path = next(line for line in manifest.splitlines() if line.startswith('Class-Path: '))[len('Class-Path: '):].split()
assert class_path == entries[1:], 'classes.jar manifest Class-Path differs from the fixed order'
PY
# The class-data-sharing archive is built for the final location and used silently.
[[ -s $work/dev/app.jsa ]] || fail 'missing AppCDS archive'
PP_PAL_RUNTIME="$work/dev" tools/engine main --help > /dev/null
cp "$root/fixtures/v1/portfolio.xml" "$work/cds.xml"
# -Xshare:on refuses to start unless the archive maps for exactly this classpath.
"$work/dev/jdk/bin/java" -Xshare:on -XX:SharedArchiveFile="$work/dev/app.jsa" -Djava.awt.headless=true \
  -cp "$(PP_PAL_RUNTIME="$work/dev" tools/engine classpath)" pal.Main inspect "$work/cds.xml" > "$work/cds.out" 2>&1 \
  || { head -20 "$work/cds.out" >&2; fail 'AppCDS archive does not map for the engine classpath'; }
rg -q 'SharedArchiveFile' tools/engine || fail 'engine does not use the AppCDS archive'
! compgen -G "$work/dev/lib/name.abuchen.portfolio_*.jar" > /dev/null || fail 'core release jar left in lib'
! compgen -G "$work/dev/lib/name.abuchen.portfolio.ui_*.jar" > /dev/null || fail 'ui release jar left in lib'
[[ -f $work/dev/attribution/UPSTREAM-LICENSE ]] || fail 'attribution missing'

# (c) The finished runtime is read-only.
[[ -z $(find "$work/dev" -perm -u+w -type f | head -n 1) ]] || fail 'runtime contains writable files'

# (e) Identity and removal helpers.
[[ $(python3 tools/runtime_dir.py identity "$work/dev") == dev ]] || fail 'identity helper'
python3 -c 'import sys; sys.path.insert(0, "tools"); import runtime_dir, pathlib; runtime_dir.remove_runtime(pathlib.Path(sys.argv[1]))' "$work/dev"
[[ ! -e $work/dev ]] || fail 'remove_runtime left the runtime'
echo PASS
