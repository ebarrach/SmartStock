"""Exercise the Scala/Akka agent path against an explicitly simulated dataset."""
import argparse
import json
from urllib.request import Request, urlopen
from uuid import uuid4


def run(base):
    def get(path):
        with urlopen(base + path, timeout=5) as response:
            return json.load(response)
    if not get('/api/v1/health')['simulated']:
        raise RuntimeError('This test requires the explicit --demo service')
    source = 'integration-' + uuid4().hex[:12]
    def event(sequence, value):
        return dict(v=1, id=str(uuid4()), type='temperature.measured', src=source,
                    seq=sequence, ts='2026-10-29T12:00:00Z', corr=None, data={'value': value})
    def send(item):
        request = Request(base + '/api/v1/events', data=json.dumps(item).encode('utf-8'),
                          headers={'Content-Type': 'application/json'}, method='POST')
        with urlopen(request, timeout=10) as response:
            result = json.load(response)
        assert result['corr'] == item['id'] and result['status'] in ('ok', 'duplicate')
    first = event(1, 27)
    send(first)
    send(first)
    snapshot = get('/api/v1/snapshot')
    assert sum(r['source'] == source for r in snapshot['readings']) == 1
    assert snapshot['readings'][-1]['kind'] == 'fan.changed'
    assert snapshot['readings'][-1]['source'] == 'scala-engine'
    assert snapshot['readings'][-1]['value'] == 75
    send(event(2, 25.9))
    assert get('/api/v1/snapshot')['readings'][-1]['value'] == 75
    send(event(3, 25.4))
    assert get('/api/v1/snapshot')['readings'][-1]['value'] == 50
    send(first)  # A late replay after newer readings must not rewind fan output.
    assert get('/api/v1/snapshot')['readings'][-1]['value'] == 50
    snapshot = get('/api/v1/snapshot')
    batch = next(b for b in snapshot['batches'] if b['location'] == 'cabinet')
    product = next(p for p in snapshot['products'] if p['id'] == batch['productId'])
    before = batch['quantity']
    movement_count = len(snapshot['movements'])
    def stock_event(seq, kind, operation, **data):
        item = event(seq, 0)
        item.update(type=kind, data=dict(batchId=batch['id'], operationId=operation, **data))
        return item
    operation = str(uuid4())
    weight = stock_event(4, 'weight.changed', operation, grams=-product['unitWeightGrams'])
    scan = stock_event(5, 'datamatrix.detected', operation, code=product['dataMatrix'])
    send(weight)
    assert next(b for b in get('/api/v1/snapshot')['batches'] if b['id'] == batch['id'])['quantity'] == before
    send(scan)
    send(scan)
    updated = get('/api/v1/snapshot')
    assert next(b for b in updated['batches'] if b['id'] == batch['id'])['quantity'] == before - 1
    assert len(updated['movements']) == movement_count + 1
    addition = str(uuid4())
    send(stock_event(6, 'datamatrix.detected', addition, code=product['dataMatrix']))
    send(stock_event(7, 'weight.changed', addition, grams=product['unitWeightGrams']))
    updated = get('/api/v1/snapshot')
    assert next(b for b in updated['batches'] if b['id'] == batch['id'])['quantity'] == before
    assert len(updated['movements']) == movement_count + 2
    print('PASS API/Scala-Akka: hysteresis, durable stock inputs, inverse order, withdrawal, duplicate, addition')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://127.0.0.1:8081')
    run(parser.parse_args().url.rstrip('/'))
