#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/sale.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_IMAGE=$1; fi
launcher=${PP_PAL_LAUNCHER:-$root/pp-pal}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio.xml"
cd "$work"
sale_file=portfolio.xml
sale() { "$launcher" sale --file "$sale_file" --security DE0000000001 --securities-account 'Synthetic Securities A' --cash-account 'Synthetic Cash A' --date 2025-03-01 --quantity 2.5 --price 10 --fees 1 --taxes .5 "$@"; }
sale > preview
rg -q '^PREVIEW SALE$' preview
rg -Fq 'fallback time 11:00:01' preview
rg -Fq 'GROSS EUR 25.00' preview
rg -Fq 'BOOKED CREDIT EUR 23.50' preview
cmp portfolio.xml "$root/fixtures/v1/portfolio.xml"
echo 'PASS sale preview amounts and immutability'
# Keep a representative apply through the owner launcher as well as preview.
cp portfolio.xml owner-applied.xml
sale_file=owner-applied.xml
sale --apply --desktop-closed > applied
rg -q '^APPLIED SALE$' applied
sale_file=portfolio.xml
launcher=${PP_PAL_BEHAVIOR_LAUNCHER:-$root/tools/run-container}
python3 - "$root" "$launcher" "$work" <<'PY'
import os, pathlib, subprocess, sys, xml.etree.ElementTree as ET
root, launcher, work = map(pathlib.Path, sys.argv[1:])
sys.path.insert(0, str(root/'tools'))
from test_jobs import parallel_cases
source = (root/'fixtures/v1/portfolio.xml').read_bytes()
base = ['sale','--file','portfolio.xml','--security','DE0000000001','--securities-account','Synthetic Securities A','--cash-account','Synthetic Cash A','--date','2025-03-01','--quantity','2.5','--price','10','--fees','1','--taxes','.5']
def run(changes=None, apply=False, directory=work):
    values=dict(zip(base[1::2],base[2::2])); values.update(changes or {})
    args=['sale']+[item for key,value in values.items() if value is not None for item in (key,str(value))]
    if apply: args+=['--apply','--desktop-closed']
    return subprocess.run([str(launcher),*args],cwd=directory,text=True,capture_output=True)
def expect(result, status, text):
    assert result.returncode==status and text in result.stdout+result.stderr, result.stdout+result.stderr
def reset(content=source): (work/'portfolio.xml').write_bytes(content)
def oracle(quantity, date, directory=work):
    subprocess.run(['python3',str(root/'tests/oracle.py'),'run','--rm','--network','none','--user',f'{os.getuid()}:{os.getgid()}',
        '-v',f'{root}/runtime/src/test/java:/tests:ro','-v',f'{directory}:/work:ro',
        '--entrypoint','java',os.environ['PP_PAL_IMAGE'],'-cp','/opt/pp/classes:/opt/pp/lib/*',
        '/tests/pal/SaleAppliedOracle.java','/work/before.xml','/work/portfolio.xml',quantity,date],check=True)
(work/'before.xml').write_bytes(source)
(work/'portfolio.xml').write_bytes((work/'owner-applied.xml').read_bytes())
oracle('2.5', '2025-03-01T11:00:01')
reset()
# Decimal alternatives, origins, rounding, and explicit midnight/nanoseconds.
for options,text in [
    ({'--price':None,'--gross':'25'},'BOOKED CREDIT EUR 23.50'),
    ({'--price':None,'--total':'23.50'},'GROSS EUR 25.00 (derived from booked + fees + taxes)'),
    ({'--time':'00:00:00'},'2025-03-01T00:00:00 (supplied date and time)'),
    ({'--time':'12:34:56.123456789'},'2025-03-01T12:34:56.123456789'),
    ({'--quantity':'1','--price':'1.005','--fees':'0','--taxes':'0'},'BOOKED CREDIT EUR 1.01')]:
    expect(run(options),0,text)
    assert (work/'portfolio.xml').read_bytes()==source
for options in [
    {'--quantity':'0'}, {'--quantity':'0.000000001'}, {'--price':'0.000000001'},
    {'--fees':'0.001'}, {'--taxes':'-1'}, {'--gross':'24.99'}, {'--total':'25'},
    {'--price':None,'--gross':'1'}, {'--date':'2025-02-30'}, {'--time':'25:00'},
    {'--security':'unknown'}, {'--securities-account':'missing'}, {'--cash-account':'missing'}]:
    expect(run(options,True),2,'ERROR:')
    assert (work/'portfolio.xml').read_bytes()==source
print('PASS sale shared decimal alternatives, rounding, selectors and invalid apply immutability',flush=True)
# A has ten before its existing four-share sale, but only six afterwards.
for options,text in [
    ({'--date':'2025-01-03','--quantity':'7'},'negative holding at 2025-02-02T11:00:01'),
    ({'--date':'2025-01-01','--quantity':'1'},'negative holding at 2025-01-01T11:00:01'),
    ({'--quantity':'6.00000001'},'negative holding at 2025-03-01T11:00:01'),
    ({'--date':'2025-01-02','--time':'10:59:59'},'negative holding at 2025-01-02T10:59:59')]:
    for apply in (False,True):
        expect(run(options,apply),2,text)
        assert (work/'portfolio.xml').read_bytes()==source
        assert not list(work.glob('portfolio.xml.????-??-??.bak'))
print('PASS complete future history validation rejects later deficits without backups or financial changes',flush=True)
# Persist the existing decrease before the increase at an identical timestamp.
# Admission must use quantity direction rather than source order or UUID order.
tree = ET.fromstring(source.replace(b'2025-02-02T11:00:01',b'2025-01-02T11:00'))
transactions = tree.find(".//portfolio[@id='7']/transactions")
transactions[:] = list(reversed(list(transactions)))
tied = ET.tostring(tree)
def check_applied(case):
    number,(content,options,quantity,date) = case
    directory = work / f'applied-{number}'
    directory.mkdir()
    (directory/'portfolio.xml').write_bytes(content)
    (directory/'before.xml').write_bytes(content)
    expect(run(options, directory=directory),0,'PREVIEW SALE')
    assert (directory/'portfolio.xml').read_bytes()==content
    expect(run(options,True,directory),0,'APPLIED SALE')
    oracle(quantity,date,directory)
parallel_cases(check_applied, enumerate([
    (source,{},'2.5','2025-03-01T11:00:01'),
    (source,{'--quantity':'6'},'6','2025-03-01T11:00:01'),
    (source,{'--date':'2025-01-03'},'2.5','2025-01-03T11:00:01'),
    (source,{'--date':'2025-01-02','--time':'11:00:00','--quantity':'6'},'6','2025-01-02T11:00:00'),
    (tied,{'--date':'2025-01-02','--time':'11:00:00','--quantity':'6'},'6','2025-01-02T11:00:00'),
    (source,{'--time':'00:00:00'},'2.5','2025-03-01T00:00:00'),
    (source,{'--time':'12:34:56.123456789'},'2.5','2025-03-01T12:34:56.123456789')]))
print('PASS partial/full sale, valid backdating, same-time increases/decreases, explicit/fallback persistence',flush=True)
reset()
# The same security in another securities account cannot fund this sale.
query=(root/'fixtures/v1/query-portfolio.xml').read_bytes(); reset(query)
for options,text in [
    ({'--security':'ALPHA','--securities-account':'30000000-0000-4000-8000-000000000002','--cash-account':'20000000-0000-4000-8000-000000000001','--quantity':'4'},'negative holding'),
    ({'--security':'DE0000000003','--securities-account':'30000000-0000-4000-8000-000000000001','--cash-account':'20000000-0000-4000-8000-000000000001','--date':'2025-01-02','--quantity':'3'},'negative holding at 2025-01-16T08:00')]:
    expect(run(options,True),2,text); assert (work/'portfolio.xml').read_bytes()==query
expect(run({'--security':'DE0000000003','--securities-account':'30000000-0000-4000-8000-000000000001','--cash-account':'20000000-0000-4000-8000-000000000001','--date':'2025-01-02','--quantity':'2'}),0,'PREVIEW SALE')
print('PASS securities-account isolation, inbound deliveries and later outbound transfers',flush=True)
PY
