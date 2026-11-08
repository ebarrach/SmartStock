"""Pi gateway: durable event IDs/sequences and outbox; no stock fusion here."""

import argparse
import json
import logging
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import URLError
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'connectivity'))
from smartstock_queue_sim import Outbox, validate  # noqa: E402


class Gateway:
    def __init__(self, database, source):
        self.outbox = Outbox(database)
        self.source = source
        self.outbox.db.execute('CREATE TABLE IF NOT EXISTS sequences (src TEXT PRIMARY KEY, seq INTEGER NOT NULL)')
        self.outbox.db.commit()

    def close(self):
        self.outbox.close()

    def emit(self, kind, data):
        envelope = dict(v=1, id=str(uuid4()), type=kind, src=self.source, seq=0,
                        ts=datetime.now(timezone.utc).isoformat(), corr=None, data=data)
        validate(envelope)
        with self.outbox.db:
            previous = self.outbox.db.execute('SELECT seq FROM sequences WHERE src=?', (self.source,)).fetchone()
            envelope['seq'] = previous[0] + 1 if previous else 1
            self.outbox.db.execute('INSERT OR REPLACE INTO sequences VALUES (?,?)', (self.source, envelope['seq']))
            self.outbox.enqueue(envelope)
        logging.info('queued source=%s seq=%s id=%s type=%s', self.source, envelope['seq'], envelope['id'], kind)
        return envelope

    def drain(self, endpoint):
        def send(envelope):
            request = Request(endpoint.rstrip('/') + '/api/v1/events',
                              data=json.dumps(envelope).encode('utf-8'),
                              headers={'Content-Type': 'application/json'}, method='POST')
            try:
                with urlopen(request, timeout=3) as response:
                    return json.load(response)
            except (URLError, ValueError) as error:
                logging.warning('delivery failed: %s', type(error).__name__)
                raise ConnectionError('Delivery not acknowledged') from error
        delivered = 0
        while self.outbox.deliver_one(send, time.time()):
            delivered += 1
        return delivered


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--database', required=True)
    parser.add_argument('--url', default='http://127.0.0.1:8081')
    parser.add_argument('--source', default='simulated-cabinet')
    parser.add_argument('--demo', action='store_true', help='Explicitly simulated observations')
    parser.add_argument('--replay', action='store_true', help='Replay pending events without generating inputs')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    Path(args.database).resolve().parent.mkdir(parents=True, exist_ok=True)
    gateway = Gateway(args.database, args.source)
    try:
        if args.demo:
            logging.info('SIMULATED INPUTS: no physical sensor')
            for kind, value in [('temperature.measured', 20), ('humidity.measured', 50), ('infrared.detected', 1)]:
                gateway.emit(kind, {'value': value})
        elif not args.replay:
            for line in sys.stdin:
                try:
                    item = json.loads(line)
                    gateway.emit(item['type'], item['data'])
                except (ValueError, KeyError, TypeError) as error:
                    logging.error('invalid input: %s', type(error).__name__)
                    continue
                gateway.drain(args.url)
        logging.info('delivered=%s pending=%s', gateway.drain(args.url), gateway.outbox.count())
    finally:
        gateway.close()


if __name__ == '__main__':
    main()
