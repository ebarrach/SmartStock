import sys,os,time,json,socket,subprocess,threading,argparse,tempfile
from pathlib import Path
from uuid import uuid4
from datetime import datetime,timezone
from urllib.request import Request,urlopen
root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description='Isolated MQTT broker/API integration; synthetic hardware, never physical validation.')
parser.add_argument('--jar',type=Path,required=True)
parser.add_argument('--java',default='java')
parser.add_argument('--api-port',type=int,default=18112)
parser.add_argument('--mqtt-port',type=int,default=18894)
args=parser.parse_args()
args.jar=args.jar.resolve(strict=True)
if not (0 < args.api_port <= 65535 and 0 < args.mqtt_port <= 65535) or args.api_port == args.mqtt_port:
    parser.error('Distinct non-zero API and MQTT ports are required')
artifacts=Path(tempfile.mkdtemp(prefix='smartstock-mqtt-test-'))
print('Isolated test artifacts:',artifacts,flush=True)
import paho.mqtt.client as mqtt
sys.path.insert(0,str(root/'code/raspberry'))
from correlated_capture import envelopes
base=f'http://127.0.0.1:{args.api_port}/api/v1'
def request(path,body=None):
    headers={'Content-Type':'application/json','X-Command-Id':str(uuid4())}
    with urlopen(Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers),timeout=5) as r:return json.load(r)
def wait(check,seconds=30):
    end=time.time()+seconds
    while time.time()<end:
        try:
            if check():return
        except Exception:pass
        time.sleep(.25)
    raise AssertionError('Condition timeout')
def stop(p):
    if p and p.poll() is None:
        p.terminate()
        try:p.wait(10)
        except subprocess.TimeoutExpired:p.kill();p.wait(5)
env=os.environ.copy();env['TEST_MQTT_PORT']=str(args.mqtt_port)
env.update(SMARTSTOCK_BIND='127.0.0.1',SMARTSTOCK_PORT=str(args.api_port),SMARTSTOCK_MQTT_URL=f'tcp://127.0.0.1:{args.mqtt_port}',SMARTSTOCK_DATA_DIR=str(artifacts/'data'),SMARTSTOCK_HOME=str(root))
for k in ['SMARTSTOCK_MQTT_USER','SMARTSTOCK_MQTT_PASSWORD','SMARTSTOCK_CATALOG','ANEMONE_JAR','SMARTSTOCK_ANEMONE_URL','SMARTSTOCK_DATA','SMARTSTOCK_DEMO_DATA']:env.pop(k,None)
env.update(SMARTSTOCK_TEMP_MIN='15',SMARTSTOCK_TEMP_MAX='25',SMARTSTOCK_HUMIDITY_MIN='30',SMARTSTOCK_HUMIDITY_MAX='60',SMARTSTOCK_CORRELATION_MS='5000',SMARTSTOCK_WEIGHT_TOLERANCE_GRAMS='0.5')
for port in (args.mqtt_port,args.api_port):
    with socket.socket() as s:s.bind(('127.0.0.1',port))
logs=[];broker=agent=None;client=None
def spawn_broker():
    log=open(artifacts/'broker.log','a');logs.append(log)
    return subprocess.Popen([sys.executable,str(root/'code/connectivity/mqtt_test_broker.py')],env=env,stdout=log,stderr=log)
try:
    def broker_ready():
        with socket.create_connection(('127.0.0.1',args.mqtt_port),timeout=1):return True
    log=open(artifacts/'agent.log','w');logs.append(log)
    agent=subprocess.Popen([args.java,'-jar',str(args.jar),'--demo'],cwd=root/'code/smartstock-agent',env=env,stdout=log,stderr=log)
    wait(lambda:agent.poll() is None and request('/health')['simulated'])
    def status():return [r for r in request('/readings') if r['source']=='mqtt-broker'][-1]['value']
    wait(lambda:status()==0)
    broker=spawn_broker();wait(broker_ready);wait(lambda:status()==1)
    connected=threading.Event()
    client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.on_connect=lambda *args:connected.set()
    client.on_disconnect=lambda *args:connected.clear()
    client.connect('127.0.0.1',args.mqtt_port);client.loop_start();assert connected.wait(10)
    time.sleep(2)
    def send(payload):
        client.publish('smartstock/events',json.dumps(payload),qos=1).wait_for_publish(5)
    source='mqtt-test-'+uuid4().hex[:8]
    timestamp=datetime.now(timezone.utc).isoformat()
    event={'sensor':'temperature','value':20,'source':source,'timestamp':timestamp,'id':str(uuid4())}
    send(event);send(event)
    wait(lambda:sum(r['source']==source for r in request('/readings'))==1)
    send({**event,'id':str(uuid4())})
    wait(lambda:sum(r['source']==source for r in request('/readings'))==2)
    client.publish('smartstock/events','{',qos=1).wait_for_publish(5)
    send({'sensor':'humidity','value':101,'source':source})
    for payload in [{'sensor':'humidity','value':50},{'sensor':'door','value':'open'},{'sensor':'weight','event':'exit','value':1000,'delta':-600,'unit':'raw'}]:send({**payload,'source':source})
    wait(lambda:any(r['kind']=='weight.raw' and r['source']==source for r in request('/readings')))
    wait(lambda:any(r['kind']=='weight.delta.raw' and r['value']==-600 and r['source']==source for r in request('/readings')))
    assert sum(r['kind']=='weight.raw' and r['source']==source for r in request('/readings'))==1
    send(dict(event,sensor='weight',event='exit',unit='raw',value=1000,delta=-900))
    time.sleep(.5)
    assert sum(r['kind']=='weight.delta.raw' and r['source']==source for r in request('/readings'))==1
    from datetime import timedelta
    def hardware(sensor,**fields):
        send(dict(sensor=sensor,source=source,id=str(uuid4()),timestamp=datetime.now(timezone.utc).isoformat(),**fields))
    hardware('sensor_status',name='temperature',status='disconnected')
    wait(lambda:any(a['code']=='sensor_failure' and a['subjectId']==source+'-temperature' for a in request('/alerts')))
    hardware('sensor_status',name='temperature',status='connected')
    wait(lambda:not any(a['code']=='sensor_failure' and a['subjectId']==source+'-temperature' for a in request('/alerts')))
    hardware('door',value='closed')
    wait(lambda:[r for r in request('/readings') if r['kind']=='door.changed' and r['source']==source][-1]['value']==0)
    send(dict(sensor='door',value='open',source=source,id=str(uuid4()),timestamp=(datetime.now(timezone.utc)-timedelta(seconds=65)).isoformat()))
    wait(lambda:any(a['code']=='door_open' and a['subjectId']==source for a in request('/alerts')))
    hardware('door',value='closed')
    wait(lambda:not any(a['code']=='door_open' and a['subjectId']==source for a in request('/alerts')))
    hardware('temperature',value=40)
    wait(lambda:any(a['code']=='temperature_alert' and a['subjectId']==source for a in request('/alerts')))
    hardware('humidity',value=90)
    wait(lambda:any(a['code']=='humidity_alert' and a['subjectId']==source for a in request('/alerts')))
    assert any(r['kind']=='humidity.measured' and r['value']==90 and r['source']==source for r in request('/snapshot')['readings'])
    product='mqtt-'+uuid4().hex[:8];gtin=f'{int(uuid4().hex[:11],16):014d}';batch=gtin+'-LOT'
    request('/products',{'id':product,'name':'MQTT test','dataMatrix':gtin,'unitWeightGrams':10,'minStock':1})
    request('/batches',{'id':batch,'productId':product,'location':'cabinet','quantity':40,'expiresOn':'2027-12-31',
                        'lotCode':'LOT','serialInventoryStatus':'historical-uninventoried','traceabilityNote':'Synthetic historical fixture, no initial serial census'})
    sequence=0
    def operation(serial=None):
        op=str(uuid4()) # Software fixture, not a physical arming test.
        scan=dict(id=str(uuid4()),operationId=op,gtin=gtin,lot='LOT',serial=serial or uuid4().hex[:20],expiration='2027-12-31',timestamp=datetime.now(timezone.utc).isoformat())
        weight=dict(captureId=str(uuid4()),operationId=op,sensor='weight',event='exit',unit='raw',delta=-1000,source='simulated-mkr')
        calibration=dict(gramsPerRaw=.01,offsetGrams=0,deviceSource='simulated-mkr') # Synthetic test only.
        return envelopes(op,scan,weight,calibration,batch,datetime.now(timezone.utc).isoformat(),request('/snapshot'))
    def quantity():return next(b['quantity'] for b in request('/snapshot')['batches'] if b['id']==batch)
    first=operation()
    for item in first:send(item)
    wait(lambda:quantity()==39)
    firstSerial=first[0]['data']['serial']
    firstMovement=[m for m in request('/movements') if m['batchId']==batch][0]
    assert firstMovement['serial']==firstSerial and firstMovement['initialPresenceUnknown'] is True
    for item in operation(firstSerial):send(item)
    time.sleep(1);assert quantity()==39
    for item in first:send(item)
    time.sleep(1);assert quantity()==39
    for item in operation():send(item)
    wait(lambda:quantity()==38)
    statusCount=sum(r['source']=='mqtt-broker' for r in request('/readings'))
    stop(broker);wait(lambda:not connected.is_set());wait(lambda:status()==0)
    wait(lambda:any(a['code']=='connection_lost' and a['subjectId']=='mqtt-broker' for a in request('/alerts')))
    broker=spawn_broker();wait(broker_ready);assert connected.wait(20)
    time.sleep(4)
    last=operation()
    for item in last:send(item)
    wait(lambda:quantity()==37)
    statuses=[r['value'] for r in request('/readings') if r['source']=='mqtt-broker']
    assert len(statuses)==statusCount+2 and statuses[-2:]==[0,1],statuses
    assert len([m for m in request('/movements') if m['batchId']==batch])==3
    beforeRestart=len([r for r in request('/readings') if r['source']=='mqtt-broker'])
    stop(agent)
    agent=subprocess.Popen([args.java,'-jar',str(args.jar),'--demo'],cwd=root/'code/smartstock-agent',env=env,stdout=log,stderr=log)
    wait(lambda:agent.poll() is None and request('/health')['simulated'])
    wait(lambda:len([r for r in request('/readings') if r['source']=='mqtt-broker'])>beforeRestart)
    for item in last:send(item)
    time.sleep(1)
    assert quantity()==37 and len([m for m in request('/movements') if m['batchId']==batch])==3
    for item in operation(firstSerial):send(item)
    time.sleep(1);assert quantity()==37
    capture=artifacts/'replay.jsonl'
    capture.write_text(''.join(json.dumps(e)+'\n' for e in last),encoding='utf8')
    publishing=os.environ.copy();publishing['PYTHONPATH']=os.environ.get('PYTHONPATH','')
    for k in ['SMARTSTOCK_MQTT_USER','SMARTSTOCK_MQTT_PASSWORD']:publishing.pop(k,None)
    subprocess.run([sys.executable,str(root/'code/raspberry/publish_capture.py'),str(capture),'--port',str(args.mqtt_port)],env=publishing,check=True)
    time.sleep(1);assert quantity()==37
    stored=dict(batches=json.loads((artifacts/'data/batches.json').read_text(encoding='utf8')),movements=json.loads((artifacts/'data/movements.json').read_text(encoding='utf8')))
    assert next(b['quantity'] for b in stored['batches'] if b['id']==batch)==37
    assert len([m for m in stored['movements'] if m['batchId']==batch])==3
    contractEnv=os.environ.copy();contractEnv['SMARTSTOCK_API_URL']=f'http://127.0.0.1:{args.api_port}'
    with open(artifacts/'contract.log','w') as contractLog:
        subprocess.run([sys.executable,'-m','unittest','discover','-s','code/connectivity','-v'],cwd=root,env=contractEnv,stdout=contractLog,stderr=contractLog,check=True)
    print('PASS REAL LOCAL BROKER: initial outage retry, readings, identifiable duplicate, identical legitimate measurements, raw diagnostics, MQTT->API->persistent stock40->39->38, broker restart->37 with connection alert/recovery, agent restart/replay remains37, exactly3 movements',flush=True)
finally:
    if client:
        client.disconnect();client.loop_stop()
    stop(agent);stop(broker)
    for log in logs:log.close()
