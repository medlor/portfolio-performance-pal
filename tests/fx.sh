#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${PP_PAL_TEST_BUDGET:-} ]]; then
  exec python3 "$root/tests/run.py" -- bash "$root/tests/fx.sh" "$@"
fi
if [[ $# -gt 0 ]]; then export PP_PAL_RUNTIME=$1; fi
launcher=${2:-${PP_PAL_LAUNCHER:-$root/pp-pal}}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/fixtures/v1/portfolio.xml" "$work/portfolio.xml"
cd "$work"
"$launcher" purchase --file portfolio.xml --security US0000000002 \
  --securities-account 'Synthetic Securities B' --cash-account 'Synthetic Cash B' \
  --date 2025-03-01 --quantity 2 --price 12.5 --exchange-rate .8 --fees 1 --taxes .5 > preview
rg -Fq 'FOREIGN GROSS USD 25.00' preview
rg -Fq 'GROSS EUR 20.00' preview
rg -Fq 'BOOKED DEBIT EUR 21.50' preview
cmp portfolio.xml "$root/fixtures/v1/portfolio.xml"
echo 'PASS foreign purchase preview and immutability'
cp portfolio.xml before.xml
"$launcher" purchase --file portfolio.xml --security US0000000002 \
  --securities-account 'Synthetic Securities B' --cash-account 'Synthetic Cash B' \
  --date 2025-03-01 --quantity 2 --price 12.5 --exchange-rate .8 --fees 1 --taxes .5 \
  --apply --desktop-closed > applied
rg -q '^APPLIED PURCHASE$' applied
cmp before.xml portfolio.xml.????-??-??.bak
python3 "$root/tests/oracle.py" "$PP_PAL_RUNTIME" ForeignExchangeOracle "$work"/before.xml "$work"/portfolio.xml \
  purchase 2150 2000 2500 100 50 .8 2025-03-01T11:00:00 200000000
launcher=${PP_PAL_BEHAVIOR_LAUNCHER:-$root/tools/run-engine}
python3 - "$root" "$launcher" "$work" <<'PY'
import os, pathlib, subprocess, sys
root, launcher, work = map(pathlib.Path, sys.argv[1:])
source = (root/'fixtures/v1/portfolio.xml').read_bytes()
purchased = (work/'portfolio.xml').read_bytes()
def run(command, changes=None, apply=False):
    options = {'--file':'portfolio.xml','--security':'US0000000002',
        '--cash-account':'Synthetic Cash B','--date':'2025-03-02',
        '--exchange-rate':'.8','--fees':'1','--taxes':'.5'}
    if command != 'dividend':
        options.update({'--securities-account':'Synthetic Securities B','--quantity':'2','--price':'12.5'})
    else: options['--forex-gross'] = '25'
    options.update(changes or {})
    args = [str(launcher), command] + [x for k,v in options.items() if v is not None for x in (k,str(v))]
    if apply: args += ['--apply','--desktop-closed']
    return subprocess.run(args,text=True,capture_output=True)
def expect(result, status, text):
    assert result.returncode == status and text in result.stdout+result.stderr, result.stdout+result.stderr
def reset(content):
    (work/'portfolio.xml').write_bytes(content); (work/'before.xml').write_bytes(content)
def oracle(command, booked, gross, foreign, fee=100, tax=50, rate='.8', quantity=200000000,
           date='2025-03-02T11:00:00'):
    subprocess.run(['python3',str(root/'tests/oracle.py'),os.environ['PP_PAL_RUNTIME'],'ForeignExchangeOracle',
        f'{work}/before.xml',f'{work}/portfolio.xml',command,*map(str,(booked,gross,foreign,fee,tax,rate,date,quantity))],check=True)
# The original USD holding is closed; the purchase above funds this full sale.
reset(purchased)
expect(run('sale'),0,'BOOKED CREDIT EUR 18.50')
assert (work/'portfolio.xml').read_bytes() == purchased
expect(run('sale',apply=True),0,'APPLIED SALE')
oracle('sale',1850,2000,2500,date='2025-03-02T11:00:01')
print('PASS foreign sale with existing quantity admission and linked upstream persistence',flush=True)
reset(source)
expect(run('dividend'),0,'BOOKED CREDIT EUR 18.50')
assert (work/'portfolio.xml').read_bytes() == source
expect(run('dividend',apply=True),0,'APPLIED DIVIDEND')
oracle('dividend',1850,2000,2500)
print('PASS foreign dividend for closed holding without changing quantities or history',flush=True)
# Each form must produce the same saved literal financial units.
for command in ('purchase','sale','dividend'):
    content = purchased if command == 'sale' else source
    booked = 2150 if command == 'purchase' else 1850
    for options in [
        {'--price':None,'--forex-gross':'25'},
        {'--price':None,'--forex-gross':None,'--gross':'20'},
        {'--price':None,'--forex-gross':None,'--total':str(booked/100)},
        {'--price':None,'--forex-gross':'25','--gross':'20','--total':str(booked/100),'--time':'00:00:00.123456789'}]:
        reset(content)
        expect(run(command,options),0,'FOREIGN GROSS USD 25.00')
        assert (work/'portfolio.xml').read_bytes() == content
        expect(run(command,options,True),0,'APPLIED '+command.upper())
        date = '2025-03-02T'+options.get('--time', '11:00:01' if command == 'sale' else '11:00:00')
        oracle(command,booked,2000,2500,date=date)
print('PASS all three FX commands: foreign/cash gross, booked-only derivation, consistent redundant inputs and exact time',flush=True)
# Independently worked decimal boundaries, persisted rather than only previewed.
for command,options,booked,gross,foreign,rate,quantity in [
    ('purchase',{'--quantity':'1','--price':'1.005'},81,81,101,'.8',100000000),
    ('sale',{'--quantity':'0.00000001','--price':'1000000.00000000'},1,1,1,'.8',1),
    ('dividend',{'--forex-gross':'6','--exchange-rate':'.8333333333'},500,500,600,'.8333333333',0),
    ('dividend',{'--forex-gross':'.01','--exchange-rate':'0.0000000001'},0,0,1,'0.0000000001',0),
    ('dividend',{'--forex-gross':'.01','--exchange-rate':'10000000000'},10000000000,10000000000,1,'10000000000',0)]:
    reset(purchased if command == 'sale' else source)
    options.update({'--fees':'0.00','--taxes':'0'})
    expect(run(command,options,True),0,'APPLIED '+command.upper())
    oracle(command,booked,gross,foreign,fee=0,tax=0,rate=rate,quantity=quantity,
           date='2025-03-02T11:00:01' if command == 'sale' else '2025-03-02T11:00:00')
print('PASS monetary HALF_UP, eight-place quantity/quote, ten-place/significant-digit rate boundaries and exact saved units',flush=True)
# Refusals must leave original bytes, import metadata and existing backup intact.
metadata = work/'portfolio.xml.pp-pal.json'; metadata.write_bytes(b'opaque import metadata\n')
backup = next(work.glob('portfolio.xml.????-??-??.bak')); backup_bytes = backup.read_bytes()
for command in ('purchase','sale','dividend'):
    content = purchased if command == 'sale' else source
    for options in [
        {'--exchange-rate':None}, {'--exchange-rate':'0'}, {'--exchange-rate':'-1'},
        {'--exchange-rate':'nan'}, {'--exchange-rate':'.12345678901'},
        {'--exchange-rate':'.00000000001'}, {'--exchange-rate':'100000000000'},
        {'--forex-gross':'25.001'}, {'--forex-gross':'-1'}, {'--forex-gross':'24.99','--gross':'20'},
        {'--gross':'19.99'}, {'--total':'20'}, {'--fees':'.001'}, {'--taxes':'-1'},
        {'--forex-gross':'92233720368547758.08'},
        {'--exchange-rate':'10000000000','--forex-gross':'92233720368547758.07','--price':None},
        {'--price':None,'--forex-gross':None,'--gross':'.01','--exchange-rate':'2'},
        {'--security':'DE0000000001'},
    ] + ([] if command == 'dividend' else [
        {'--quantity':'.000000001'}, {'--price':'.000000001'},
        {'--quantity':'0'}, {'--forex-gross':'25','--price':'12.49'}]):
        reset(content)
        expect(run(command,options,True),2,'ERROR:')
        assert (work/'portfolio.xml').read_bytes() == content
        assert metadata.read_bytes() == b'opaque import metadata\n'
        assert backup.read_bytes() == backup_bytes
    reset(content)
    expect(run(command,{'--security':'DE0000000001','--exchange-rate':None,'--forex-gross':'25'},True),2,'FX options require')
    assert (work/'portfolio.xml').read_bytes() == content
reset(source)
expect(run('sale',apply=True),2,'negative holding')
assert (work/'portfolio.xml').read_bytes() == source
reset(purchased)
expect(run('sale',{'--date':'2025-01-04'},True),2,'negative holding at 2025-02-03')
assert (work/'portfolio.xml').read_bytes() == purchased
print('PASS missing/invalid rates, precision/overflow, inconsistent currencies/amounts and historical quantity refusals preserve original and metadata',flush=True)
PY
