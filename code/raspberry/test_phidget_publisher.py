"""Publisher tests with mocked vendor imports/devices; never hardware validation."""
import importlib.util
import json
from pathlib import Path
import threading
import types
import unittest
from unittest.mock import patch

def module():
    names=['paho','paho.mqtt','paho.mqtt.client','Phidget22','Phidget22.Devices','Phidget22.Devices.TemperatureSensor','Phidget22.Devices.HumiditySensor']
    modules={name:types.ModuleType(name) for name in names}
    for m in modules.values():m.__path__=[]
    modules['paho'].mqtt=modules['paho.mqtt'];modules['paho.mqtt'].client=modules['paho.mqtt.client']
    modules['Phidget22.Devices.TemperatureSensor'].TemperatureSensor=object
    modules['Phidget22.Devices.HumiditySensor'].HumiditySensor=object
    spec=importlib.util.spec_from_file_location('_tested_phidget',Path(__file__).with_name('smartstock_phidget_mqtt.py'))
    loaded=importlib.util.module_from_spec(spec)
    with patch.dict('sys.modules',modules):spec.loader.exec_module(loaded)
    return loaded

class Client:
    def __init__(self):self.messages=[]
    def publish(self,topic,payload,qos):self.messages.append(json.loads(payload))

class PublisherTests(unittest.TestCase):
    def test_identical_legitimate_measurements_have_different_producer_ids(self):
        m=module();client=Client();ready=threading.Event();ready.set()
        for _ in range(2):m.publish(client,ready,dict(sensor='temperature',value=20))
        self.assertNotEqual(client.messages[0]['id'],client.messages[1]['id'])
        self.assertTrue(all(v['timestamp'] for v in client.messages))
    def test_status_restored_after_outage_without_spurious_repeats(self):
        m=module();client=Client();ready=threading.Event()
        class Device:
            def openWaitForAttachment(self,_timeout):pass
            def close(self):pass
        sensor=m.Sensor('temperature',Device,lambda device:20,'C')
        sensor.read(client,ready)
        self.assertEqual(client.messages,[])
        sensor.online=None;ready.set();sensor.read(client,ready);sensor.read(client,ready)
        statuses=[v for v in client.messages if v['sensor']=='sensor_status']
        self.assertEqual(len(statuses),1);self.assertEqual(statuses[0]['status'],'connected')
