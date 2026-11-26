"""Create a NEW demonstration inventory, never modify an existing store.

The fixture explicitly records a prior example withdrawal so USB0001 is KNOWN
absent. Quantity zero alone never establishes a unit's absence.
"""
import argparse
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
from uuid import uuid4

CODE='(01)05400711223344(17)291231(10)USB(21)USB0001'

def create(path):
    now=datetime.now(timezone.utc)
    product=dict(id='usb-test',name='Boîte USB — EXEMPLE sans balance',dataMatrix='05400711223344',unitWeightGrams=10,minStock=0)
    batch=dict(id='USB-LOT',productId=product['id'],location='cabinet',quantity=0,expiresOn='2029-12-31',lotCode='USB',serialInventoryStatus='inventoried',traceabilityNote='EXEMPLE logiciel : USB0001 deja retiree dans le journal de preparation')
    movement=dict(id=str(uuid4()),batchId=batch['id'],delta=-1,quantityAfter=0,
                  occurredAt=(now-timedelta(hours=1)).isoformat(),eventId=str(uuid4()),productId=product['id'],
                  kind='consumption',source='usb-fixture',reason='EXEMPLE de preparation : absence connue, aucune action physique',serial='USB0001',initialPresenceUnknown=False)
    inventory=dict(products=[product],batches=[batch],movements=[movement],readings=[],receipts={},sensorInputs=[],
                   baselines={batch['id']:dict(quantity=1,recordedAt=(now-timedelta(hours=2)).isoformat())},dataset='demo',dailyMeasures=[])
    with Path(path).open('x',encoding='utf8') as destination:json.dump(inventory,destination,ensure_ascii=False,indent=2)
    return CODE

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    print('Nouveau stockage DEMO uniquement :',args.output)
    print(create(args.output))
