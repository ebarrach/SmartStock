"""Software HTTP recipe, isolated storage only; no physical sensor or user stock."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4
from datetime import datetime, timezone


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    with tempfile.TemporaryDirectory(prefix='smartstock-wifi-') as temp:
        folder = Path(temp)
        data = folder / 'state.json'
        subprocess.run([sys.executable, str(root / 'code/raspberry/usb_fixture.py'), '--output', str(data)], check=True)
        active = folder / 'active'
        subprocess.run([args.java, '-cp', str(args.jar.resolve()), 'smartstock.inventory.StorageMigration',
                        str(data), str(active), '--allow-examples'], check=True)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        base = f'http://127.0.0.1:{port}/api/v1'
        env = os.environ.copy()
        for key in ('SMARTSTOCK_MQTT_URL', 'SMARTSTOCK_CATALOG', 'ANEMONE_JAR', 'SMARTSTOCK_ANEMONE_URL', 'SMARTSTOCK_DATA', 'SMARTSTOCK_DEMO_DATA'):
            env.pop(key, None)
        env.update(SMARTSTOCK_PORT=str(port), SMARTSTOCK_BIND='127.0.0.1', SMARTSTOCK_DATA_DIR=str(active), SMARTSTOCK_HOME=str(root),
                   SMARTSTOCK_SCAN_ONLY_TEST_MODE='false', SMARTSTOCK_TEST_SCALE_ABSENT='false')
        process = None
        def call(path, value=None, method='POST', command=None):
            headers = {'Content-Type': 'application/json', 'X-Command-Id': command or str(uuid4())}
            request = Request(base + path, data=None if value is None else json.dumps(value).encode(),
                              headers=headers, method='GET' if value is None else method)
            try:
                with urlopen(request, timeout=10) as response:
                    return response.status, json.load(response)
            except HTTPError as error:
                return error.code, json.load(error)
        def stop():
            nonlocal process
            if process is not None:
                process.terminate()
                try: process.wait(10)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
                process = None
        def start():
            nonlocal process
            process = subprocess.Popen([args.java, '-jar', str(args.jar.resolve()), '--demo'],
                                       cwd=root / 'code/smartstock-agent', env=env, stdout=log, stderr=log)
            for _ in range(100):
                if process.poll() is not None: raise RuntimeError((folder / 'server.log').read_text())
                try:
                    if call('/health')[0] == 200: return
                except OSError: pass
                time.sleep(.1)
            raise RuntimeError('API startup timeout')
        def quantity():
            return next(b['quantity'] for b in call('/snapshot')[1]['batches'] if b['productId'] == 'usb-test')
        scan = {'gtin': '05400711223344', 'lot': 'USB', 'expiration': '2029-12-31', 'serial': 'USB0001'}
        with (folder / 'server.log').open('w') as log:
            try:
                start()
                assert call('/scan-only-test', scan)[0] == 409
                assert call('/settings/sensors', {'withoutSensors': True}, 'PUT')[0] == 200
                command = str(uuid4())
                assert call('/scan-only-test', scan, command=command)[0] == 200 and quantity() == 1
                assert call('/scan-only-test', scan, command=command)[0] == 200 and quantity() == 1
                assert call('/scan-only-test', scan)[0] == 200 and quantity() == 0
                stop(); start()
                assert call('/settings')[1]['withoutSensors'] is True
                assert call('/scan-only-test', scan)[0] == 200 and quantity() == 1
                assert call('/settings/sensors', {'withoutSensors': False}, 'PUT')[0] == 200
                assert call('/scan-only-test', scan)[0] == 409 and quantity() == 1
                sequence = 0
                def event(kind, content, source='hx711'):
                    nonlocal sequence
                    sequence += 1
                    return dict(v=1, id=str(uuid4()), type=kind, src=source, seq=sequence,
                                ts=datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z'), corr=None, data=content)
                for _ in range(3): assert call('/events', event('weight.raw', {'value': 100}))[0] == 200
                assert call('/calibration/tare', {'source': 'hx711'})[0] == 200
                tare_id = call('/settings')[1]['tare']['id']
                for _ in range(3): assert call('/events', event('weight.raw', {'value': 1100}))[0] == 200
                assert call('/calibration/confirm', {'source': 'hx711', 'tareId': tare_id, 'referenceGrams': 100})[0] == 200
                snapshot = call('/snapshot')[1]
                batch = next(b for b in snapshot['batches'] if b['productId'] == 'usb-test')
                product = next(p for p in snapshot['products'] if p['id'] == 'usb-test')
                operation = str(uuid4())
                common = dict(batchId=batch['id'], operationId=operation)
                assert call('/events', event('datamatrix.detected', dict(common, code=product['dataMatrix'], serial='USB0001', lot='USB', expiration=batch['expiresOn']), 'paired'))[0] == 200
                assert quantity() == 1
                weight = event('weight.changed', dict(common, rawDelta=-product['unitWeightGrams'] / .1, deviceSource='hx711'), 'paired')
                assert call('/events', weight)[0] == 200 and quantity() == 0
                assert call('/events', weight)[0] == 200 and quantity() == 0
                before = call('/snapshot')[1]
                assert call('/products/usb-test/archive', {'expectedGTIN': '05400711223344'})[0] == 200
                after = call('/snapshot')[1]
                assert before['movements'] == after['movements'] and before['batches'] == after['batches']
                assert after['archivedProductIds'] == ['usb-test']
                stop(); start()
                assert call('/settings')[1]['withoutSensors'] is False
                assert call('/settings')[1]['calibration']['source'] == 'hx711'
                contract_env = os.environ.copy()
                contract_env['SMARTSTOCK_API_URL'] = base.removesuffix('/api/v1')
                subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', 'code/connectivity', '-v'], cwd=root, env=contract_env, check=True)
                print('PASS HTTP: persistent explicit modes, scan/retry/rearm, simulated measured calibration, correlated raw weight, archive without deletion, restart and contracts.')
            finally: stop()


if __name__ == '__main__':
    main()
