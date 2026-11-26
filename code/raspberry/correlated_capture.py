"""Explicit, supervised capture -> canonical v1 JSONL. No automatic last-scan pairing.

The operation UUID must be created and armed before physical movement. Captures
are supplied by the operator; a receiver-generated UUID is not physical evidence.
No calibration default, network write, registration or stock mutation here.
"""
import argparse
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID, uuid4

def resolve_batch(fields, inventory, requested=None):
    products=[p for p in inventory.get('products',[]) if p['dataMatrix']==fields['gtin']]
    if len(products)!=1:raise ValueError('Ambiguous or missing product DataMatrix')
    product=products[0]
    matches=[b for b in inventory.get('batches',[]) if b['productId']==product['id'] and
             b['location']=='cabinet' and b['expiresOn']==fields['expiration'] and
             (b.get('lotCode')==fields['lot'] or b['id']==fields['gtin']+'-'+fields['lot'])]
    if len(matches)!=1:raise ValueError('Ambiguous or missing physical batch mapping; explicit lotCode required for legacy IDs')
    if requested is not None and matches[0]['id']!=requested:raise ValueError('Requested batch contradicts physical mapping')
    return matches[0],product

def envelopes(operation, scan, weight, calibration, batch_id, observed_at, inventory):
    op = str(UUID(operation))
    identities=[str(UUID(str(scan.get('id','')))),str(UUID(str(weight.get('captureId',''))))]
    if any(UUID(v).version!=4 for v in identities) or identities[0]==identities[1]:
        raise ValueError('Distinct producer UUIDv4 event IDs required')
    if scan.get('operationId') != op or weight.get('operationId') != op:
        raise ValueError('Both producers must explicitly carry the same pre-armed operationId')
    if weight.get('sensor') != 'weight' or weight.get('unit') != 'raw':
        raise ValueError('Raw HX711 capture required')
    if not calibration or calibration.get('deviceSource') != weight.get('source'):
        raise ValueError('Explicit calibration bound to this physical device required')
    factor,offset = calibration.get('gramsPerRaw'),calibration.get('offsetGrams')
    if any(isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in (factor,offset)) or factor == 0:
        raise ValueError('Invalid supplied calibration coefficients')
    raw_delta=weight.get('delta')
    if isinstance(raw_delta,bool) or not isinstance(raw_delta,int):raise ValueError('Integer raw delta required')
    if weight.get('event') != ('entry' if raw_delta > 0 else 'exit'):raise ValueError('Raw movement direction mismatch')
    # Firmware SENS_POIDS must remain 1; changing it requires a matching calibration convention.
    grams=raw_delta*factor
    if not math.isfinite(grams) or grams==0:raise ValueError('Nonzero finite calibrated delta required')
    from gs1_decoder import validate_fields
    fields=validate_fields(scan)
    batch,product=resolve_batch(fields,inventory,batch_id)
    batch_id=batch['id']
    dates=[]; instants=[]
    for value in (scan.get('timestamp'),observed_at):
        if not isinstance(value,str):raise ValueError('UTC capture dates required')
        parsed=datetime.fromisoformat(value.replace('Z','+00:00'))
        if parsed.utcoffset() is None:raise ValueError('Timestamp timezone required')
        instants.append(parsed)
        dates.append(parsed.astimezone(timezone.utc).isoformat().replace('+00:00','Z'))
    if abs((instants[1]-instants[0]).total_seconds())>90:raise ValueError('Capture timestamps exceed the explicit 90s operation window')
    result=[]
    for seq,(kind,data,ts) in enumerate([
        ('datamatrix.detected',{'code':fields['gtin'],'serial':fields['serial'],'lot':fields['lot'],'expiration':fields['expiration']},dates[0]),
        ('weight.changed',{'grams':grams},dates[1]),
    ],1):
        identity=identities[seq-1]
        result.append(dict(v=1,id=identity,type=kind,src='capture-'+UUID(op).hex,seq=seq,ts=ts,corr=None,data=dict(batchId=batch_id,operationId=op,**data)))
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--begin',action='store_true',help='Create session IDs BEFORE scan/movement; never arm implicitly')
    for name in ('operation','scan','weight','calibration','batch','observed-at','snapshot'):p.add_argument('--'+name)
    p.add_argument('--output',required=True)
    a=p.parse_args()
    if a.begin:
        operation=str(uuid4());capture=str(uuid4())
        value=dict(operationId=operation,captureId=capture,armCommand=f'ARM {operation} {capture}')
        with Path(a.output).open('x',encoding='utf8') as out:json.dump(value,out,indent=2)
        print(value['armCommand'])
        raise SystemExit(0)
    if not all(getattr(a,name) for name in ('operation','scan','weight','calibration','batch','observed_at','snapshot')):p.error('Capture arguments required without --begin')
    read=lambda path:json.loads(Path(path).read_text(encoding='utf-8-sig'))
    result=envelopes(a.operation,read(a.scan),read(a.weight),read(a.calibration),a.batch,a.observed_at,read(a.snapshot))
    target=Path(a.output)
    with target.open('x',encoding='utf8') as out:
        for item in result:out.write(json.dumps(item)+'\n')
