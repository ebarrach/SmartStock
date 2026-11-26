"""Real HTTP gateway -> Scala API -> isolated file. Synthetic data, no phone claim."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4

root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'code/raspberry'))
from qrbot_usb import make_server
from usb_fixture import create, CODE

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--java',default='java')
    parser.add_argument('--api-port',type=int,default=18129)
    args=parser.parse_args()
    folder=Path(tempfile.mkdtemp(prefix='smartstock-usb-test-'));print('Artifacts:',folder,flush=True)
    data=folder/'inventory.json';create(data)
    with socket.socket() as probe:probe.bind(('127.0.0.1',args.api_port))
    base=f'http://127.0.0.1:{args.api_port}'
    env=os.environ.copy();env.update(SMARTSTOCK_PORT=str(args.api_port),SMARTSTOCK_BIND='127.0.0.1',SMARTSTOCK_DEMO_DATA=str(data))
    for name in ('SMARTSTOCK_MQTT_URL','SMARTSTOCK_CATALOG','ANEMONE_JAR','SMARTSTOCK_ANEMONE_URL'):env.pop(name,None)
    process=None;logs=[];server=None
    def request(url,payload=None,method=None):
        raw=None if payload is None else json.dumps(payload).encode()
        try:
            with urlopen(Request(url,data=raw,method=method,headers={'Content-Type':'application/json','X-Command-Id':str(uuid4())}),timeout=10) as r:return r.status,json.load(r)
        except HTTPError as error:return error.code,json.load(error)
    def snapshot():return request(base+'/api/v1/snapshot')[1]
    def quantity():return next(b['quantity'] for b in snapshot()['batches'] if b['id']=='USB-LOT')
    def stop():
        nonlocal process
        if process is not None and process.poll() is None:
            process.terminate()
            try:process.wait(10)
            except subprocess.TimeoutExpired:process.kill();process.wait(5)
    def start(enabled,absent):
        stop();env.update(SMARTSTOCK_SCAN_ONLY_TEST_MODE=str(enabled).lower(),SMARTSTOCK_TEST_SCALE_ABSENT=str(absent).lower())
        log=open(folder/f'agent-{len(logs)}.log','w');logs.append(log)
        nonlocal process
        process=subprocess.Popen([args.java,'-jar',str(args.jar.resolve()),'--demo'],cwd=root/'code/smartstock-agent',env=env,stdout=log,stderr=log)
        end=time.time()+30
        while time.time()<end:
            try:
                if request(base+'/api/v1/health')[0]==200:return
            except OSError:pass
            if process.poll() is not None:raise AssertionError('Backend exited; inspect '+str(folder))
            time.sleep(.2)
        raise AssertionError('Backend timeout')
    try:
        server=make_server(base,0,'software-test')
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        gateway=f'http://127.0.0.1:{server.server_port}'
        def arm(direction='auto'):
            with urlopen(Request(gateway+'/software-test/arm',data=('direction='+direction).encode()),timeout=5) as r:
                return gateway+'/scan/software-test/'+r.url.rsplit('/',1)[1]
        def scan(url,code=CODE):
            try:
                with urlopen(Request(url,data=code.encode(),headers={'Content-Type':'text/plain'}),timeout=10) as r:return r.status,json.load(r)
            except HTTPError as error:return error.code,json.load(error)
        start(False,True);first=arm()
        assert scan(first)[1]['code']=='scan_only_disabled' and quantity()==0
        start(True,False)
        assert scan(first)[1]['code']=='scale_absence_unconfirmed' and quantity()==0
        start(True,True)
        status,response=scan(first)
        assert status==200 and quantity()==1 and response['movement']['delta']==1
        assert 'test sans balance' in response['movement']['reason'] and not snapshot()['readings']
        assert scan(first)[0]==200 and quantity()==1
        second=arm();assert scan(second)[0]==200 and quantity()==0
        start(True,True)
        assert scan(first)[0]==200 and scan(second)[0]==200 and quantity()==0
        assert len([m for m in snapshot()['movements'] if m['batchId']=='USB-LOT'])==3
        unknown=CODE.replace('05400711223344','99999999999999')
        assert scan(arm(),unknown)[1]['code']=='unknown_product' and quantity()==0
        no_serial=CODE.split('(21)')[0]
        assert scan(arm(),no_serial)[1]['code']=='presence_unknown'
        assert scan(arm('entry'),no_serial)[0]==200 and quantity()==1
        assert scan(arm('exit'),no_serial)[0]==200 and quantity()==0
        batch=next(b for b in snapshot()['batches'] if b['id']=='USB-LOT')
        assert request(base+'/api/v1/batches',dict(batch,id='USB-DUP'))[0]==200
        assert scan(arm())[1]['code']=='ambiguous_batch' and quantity()==0
        from datetime import datetime, timezone
        reading=dict(v=1,id=str(uuid4()),type='weight.raw',src='synthetic-test-scale',seq=1,ts=datetime.now(timezone.utc).isoformat(),corr=None,data={'value':0})
        assert request(base+'/api/v1/events',reading)[0]==200
        assert scan(arm())[1]['code']=='scale_connected' and quantity()==0
        contract=os.environ.copy();contract['SMARTSTOCK_API_URL']=base
        with open(folder/'contracts.log','w') as log:
            subprocess.run([sys.executable,'-m','unittest','discover','-s','code/connectivity','-v'],cwd=root,env=contract,stdout=log,stderr=log,check=True)
        print('PASS gateway -> Scala -> file: 0->1, retry unchanged, voluntary scan ->0, restart/replay unchanged; mode/absence/identity/scale gates; no synthetic weight in operational flow.',flush=True)
    finally:
        if server:server.shutdown();server.server_close()
        stop()
        for log in logs:log.close()

if __name__=='__main__':main()
