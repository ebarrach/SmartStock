"""Publish an immutable, explicitly prepared v1 capture; never replace its IDs on retry."""
import argparse
import json
import os
import getpass
import threading
from pathlib import Path
from uuid import UUID
import paho.mqtt.client as mqtt

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('capture')
    p.add_argument('--host',default=os.environ.get('SMARTSTOCK_MQTT_HOST','127.0.0.1'))
    p.add_argument('--port',type=int,default=int(os.environ.get('SMARTSTOCK_MQTT_PORT','1883')))
    p.add_argument('--topic',default=os.environ.get('SMARTSTOCK_MQTT_TOPIC','smartstock/events'))
    args=p.parse_args()
    events=[json.loads(line) for line in Path(args.capture).read_text(encoding='utf8').splitlines() if line.strip()]
    if len(events)!=2 or {e['type'] for e in events}!={'datamatrix.detected','weight.changed'}:raise ValueError('Exactly one scan and weight capture required')
    if len({(e['src'],e['data']['operationId'],e['data']['batchId']) for e in events})!=1:raise ValueError('Capture association mismatch')
    for e in events:
        if e['v']!=1 or UUID(e['id']).version!=4:raise ValueError('Valid v1 event ID required')
    ready=threading.Event()
    client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    user=os.environ.get('SMARTSTOCK_MQTT_USER')
    if user:client.username_pw_set(user,os.environ.get('SMARTSTOCK_MQTT_PASSWORD') or getpass.getpass('Mot de passe MQTT : '))
    def connected(_client,_userdata,_flags,reason,_properties):
        if not reason.is_failure:ready.set()
    client.on_connect=connected
    try:
        client.connect(args.host,args.port,60);client.loop_start()
        if not ready.wait(10):raise ConnectionError('Broker unavailable or refused')
        for event in events:
            receipt=client.publish(args.topic,json.dumps(event),qos=1)
            receipt.wait_for_publish(timeout=10)
            if not receipt.is_published():raise ConnectionError('Publication not acknowledged; retain and replay this file')
        print('Broker received capture. Verify API stock/history; PUBACK is not stock confirmation. File preserved for retry.')
    finally:
        client.disconnect();client.loop_stop()

if __name__=='__main__':main()
