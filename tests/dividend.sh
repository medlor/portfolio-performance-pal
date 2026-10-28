#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/dividend.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_RUNTIME=$1; fi
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
export PP_PAL_NOW=2026-10-08T12:00:00Z
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio.xml"
cd "$work"
printf 'opaque dividend preview metadata\n' > portfolio.xml.pp-pal.json
cp portfolio.xml.pp-pal.json metadata-before
"$launcher" dividend --file portfolio.xml --security DE0000000001 --cash-account 'Synthetic Cash A' --date 2025-03-01 --gross 25 --fees 1 --taxes .5 > preview
rg -q '^PREVIEW DIVIDEND$' preview
rg -Fq 'fallback time 11:00:00' preview
rg -Fq 'GROSS EUR 25.00 (supplied)' preview
rg -Fq 'BOOKED CREDIT EUR 23.50 (derived from gross - fees - taxes)' preview
cmp portfolio.xml "$root/fixtures/v1/portfolio.xml"
cmp portfolio.xml.pp-pal.json metadata-before
! compgen -G 'portfolio.xml.????-??-??.bak'
! compgen -G 'portfolio.xml.pp-pal.migration.*.bak'
echo 'PASS dividend amounts, fallback time and immutable preview without securities-account selection'
# Keep a successful preview and apply through the released owner launcher.
"$launcher" dividend --file portfolio.xml --security DE0000000001 --cash-account 'Synthetic Cash A' --date 2025-03-01 --gross 25 --fees 1 --taxes .5 --apply --desktop-closed > applied
rg -q '^APPLIED DIVIDEND$' applied
cp portfolio.xml owner-applied.xml
launcher=${PP_PAL_BEHAVIOR_LAUNCHER:-$root/tools/run-engine}
python3 -u - "$root" "$launcher" "$work" <<'PY'
import os, pathlib, socket, subprocess, sys, threading, xml.etree.ElementTree as ET
root, launcher, work = map(pathlib.Path, sys.argv[1:])
source = (root/'fixtures/v1/portfolio.xml').read_bytes()
query = (root/'fixtures/v1/query-portfolio.xml').read_bytes()
base = {'--file':'portfolio.xml','--security':'DE0000000001','--cash-account':'Synthetic Cash A','--date':'2025-03-01','--gross':'25','--fees':'1','--taxes':'.5'}
metadata = work/'portfolio.xml.pp-pal.json'; metadata.write_bytes(b'opaque dividend sentinel\n')
def run(changes=None, apply=False, env=None):
    values = base | (changes or {})
    args = ['dividend']+[item for key,value in values.items() if value is not None for item in (key,str(value))]
    if apply: args += ['--apply','--desktop-closed']
    return subprocess.run([str(launcher),*args],text=True,capture_output=True,env=dict(os.environ, **(env or {})))
def expect(result, status, text):
    assert result.returncode == status and text in result.stdout+result.stderr, result.stdout+result.stderr
    assert metadata.read_bytes() == b'opaque dividend sentinel\n'
def reset(content=source):
    (work/'portfolio.xml').write_bytes(content)
    (work/'before.xml').write_bytes(content)
def oracle(cash, security, date, relevance):
    subprocess.run(['python3',str(root/'tests/oracle.py'),os.environ['PP_PAL_RUNTIME'],'DividendAppliedOracle',
        f'{work}/before.xml',f'{work}/portfolio.xml',cash,security,date,relevance],check=True)
tree = ET.fromstring(source)
cash = tree.find('./accounts/account/uuid').text
security = tree.find('./securities/security/uuid').text
portfolio = tree.find('.//portfolio/uuid').text
reset(); (work/'portfolio.xml').write_bytes((work/'owner-applied.xml').read_bytes())
oracle(cash,security,'2025-03-01T11:00:00',portfolio)
reset()
for options, text in [
    ({'--gross':None,'--total':'23.50'},'GROSS EUR 25.00 (derived from booked + fees + taxes)'),
    ({'--total':'23.50'},'BOOKED CREDIT EUR 23.50 (supplied)'),
    ({'--fees':None,'--taxes':None},'BOOKED CREDIT EUR 25.00'),
    ({'--time':'00:00:00'},'2025-03-01T00:00:00 (supplied date and time)'),
    ({'--time':'12:34:56.123456789'},'2025-03-01T12:34:56.123456789'),
    ({'--gross':'1.50'},'BOOKED CREDIT EUR 0.00')]:
    expect(run(options),0,text); assert (work/'portfolio.xml').read_bytes() == source
for options in [
    {'--gross':None}, {'--gross':'24.99','--total':'23.50'}, {'--gross':'1.49'},
    {'--gross':'0.001'}, {'--gross':'-1'}, {'--total':'-1'}, {'--fees':'0.001'}, {'--taxes':'-1'},
    {'--gross':'92233720368547758.08'}, {'--gross':None,'--total':'92233720368547758.07'},
    {'--date':None}, {'--date':'2025-02-30'}, {'--time':'25:00'},
    {'--security':None}, {'--security':'unknown'}, {'--cash-account':None}, {'--cash-account':'missing'},
    {'--isin':'DE0000000002'}, {'--quantity':'1'}, {'--price':'1'}, {'--create-security':'true'}]:
    expect(run(options,True),2,'ERROR:'); assert (work/'portfolio.xml').read_bytes() == source
print('PASS determined decimal alternatives, contradictions, precision/range, missing selectors and unsupported inputs',flush=True)
# Stable-ID configuration needs only cash; obsolete securities defaults are irrelevant.
(work/'pp-pal.properties').write_text(f'file=portfolio.xml\ncash-account={cash}\nsecurities-account=missing\n')
expect(run({'--cash-account':None}),0,'CASH ACCOUNT Synthetic Cash A')
(work/'pp-pal.properties').unlink()
for options,date in [({},'2025-03-01T11:00:00'),
    ({'--gross':None,'--total':'23.50'},'2025-03-01T11:00:00'),
    ({'--time':'00:00:00'},'2025-03-01T00:00:00'),
    ({'--date':'2024-01-01','--time':'12:34:56.123456789'},'2024-01-01T12:34:56.123456789')]:
    reset(); expect(run(options,True),0,'APPLIED DIVIDEND'); oracle(cash,security,date,portfolio)
print('PASS gross/booked derivation, explicit/fallback/backdated saved cash records',flush=True)
# Closed Beta remains relevant to Twin1; Alpha shares cash/history with Twin1/Twin2.
shared = '20000000-0000-4000-8000-000000000001'
twin1 = '30000000-0000-4000-8000-000000000001'
twin2 = '30000000-0000-4000-8000-000000000002'
def command(*args):
    result = subprocess.run([str(launcher),*args,'--file','portfolio.xml'],text=True,capture_output=True)
    expect(result,0,' | '); return result.stdout
for isin,sid,relevant,ambiguous in [
    ('DE0000000002','10000000-0000-4000-8000-000000000002',twin1,False),
    ('DE0000000001','10000000-0000-4000-8000-000000000001',twin1+','+twin2,True)]:
    reset(query)
    before = command('holdings','--include-zero','--limit','0')
    options = {'--security':isin,'--cash-account':shared}
    expect(run(options),0,'PREVIEW DIVIDEND'); assert (work/'portfolio.xml').read_bytes() == query
    expect(run(options,True),0,'APPLIED DIVIDEND')
    oracle(shared,sid,'2025-03-01T11:00:00',relevant)
    assert command('holdings','--include-zero','--limit','0') == before
    history = command('history','--type','dividend','--from','2025-03-01','--limit','0')
    assert len(history.splitlines()) == 2 and 'EUR 23.50' in history and 'Shared Cash' in history
    assert ('AMBIGUOUS:' in history) == ambiguous
    filtered = command('history','--securities-account',twin2,'--type','dividend','--from','2025-03-01','--limit','0')
    assert len(filtered.splitlines()) == (2 if ambiguous else 1)
print('PASS closed holdings, identical holdings/timestamps, unique shared-cash history and upstream relevance',flush=True)
reset(query)
expect(run({'--cash-account':'Shared Cash','--security':'ALPHA'}),0,'PREVIEW DIVIDEND')
expect(run({'--cash-account':'Shared Cash','--security':'Alpha Instr'}),2,'Unknown security')
# Invent ambiguity in an isolated copy without relying on candidate-generated fixtures.
ambiguous = ET.fromstring(query)
ambiguous.find('./securities/security[2]/name').text = 'Alpha Instrument'
next(n for n in ambiguous.findall('.//name') if n.text == 'Other Cash').text = 'Shared Cash'
content = ET.tostring(ambiguous); reset(content)
for options in [{'--security':'Alpha Instrument','--cash-account':shared}, {'--cash-account':'Shared Cash'}]:
    expect(run(options,True),2,'Ambiguous'); assert (work/'portfolio.xml').read_bytes() == content
foreign = source.replace(b'<currencyCode>EUR</currencyCode>',b'<currencyCode>USD</currencyCode>',1)
reset(foreign); expect(run(apply=True),2,'requires --exchange-rate'); assert (work/'portfolio.xml').read_bytes() == foreign
print('PASS exact selection, security/cash ambiguity and explicit FX refusal',flush=True)
# Fresh filename for backup/receipt failures and stale input, free from earlier writes.
reset(); base['--file'] = 'protected.xml'
protected = work/'protected.xml'; protected.write_bytes(source)
(work/'protected.xml.2026-10-08.bak').mkdir()
expect(run(apply=True,env={'PP_PAL_NOW':'2026-10-08T12:00:00Z'}),3,'regular files')
assert protected.read_bytes() == source
(work/'protected.xml.2026-10-08.bak').rmdir()
(work/'.pp-pal-disposable-fixture').write_text('pp-pal-write-observer-v1\n')
observer = work/'observer.sock'
server = socket.socket(socket.AF_UNIX); server.bind(str(observer)); server.listen(1)
errors = []
def stale():
    try:
        server.settimeout(30)
        connection,_ = server.accept()
        with connection:
            message = b''
            while message.count(b'\n') < 3: message += connection.recv(4096)
            assert message.splitlines()[0] == b'before-final-check'
            protected.write_bytes(source+b'\n')
            connection.sendall(b'ACK before-final-check\n')
    except BaseException as e: errors.append(e)
thread = threading.Thread(target=stale); thread.start()
expect(run(apply=True,env={'PP_PAL_WRITE_OBSERVER':str(observer)}),3,'changed')
thread.join(); server.close(); observer.unlink(); assert not errors, errors
assert protected.read_bytes() == source+b'\n'
assert (work/'protected.xml.2026-10-08.bak').read_bytes() == source
print('PASS required backup failure, exact daily snapshot and final stale-input rejection',flush=True)
PY
