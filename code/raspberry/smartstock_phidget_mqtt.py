from datetime import datetime, timezone
import getpass
import os
from uuid import uuid4
import json
import time
import threading
import paho.mqtt.client as mqtt
from Phidget22.Devices.TemperatureSensor import TemperatureSensor
from Phidget22.Devices.HumiditySensor import HumiditySensor

MQTT_HOST = os.environ.get("SMARTSTOCK_MQTT_HOST", "127.0.0.1")
MQTT_PORT = int(os.environ.get("SMARTSTOCK_MQTT_PORT", "1883"))
MQTT_USER = os.environ.get("SMARTSTOCK_MQTT_USER", "smartstock_mqtt")
MQTT_TOPIC = os.environ.get("SMARTSTOCK_MQTT_TOPIC", "smartstock/events")


class Sensor:
    def __init__(self, name, constructor, read_value, unit):
        self.name = name
        self.constructor = constructor
        self.read_value = read_value
        self.unit = unit
        self.device = None
        self.online = None
        self.next_attempt = 0

    def update_status(self, connected, client, ready):
        if self.online == connected:
            return
        self.online = connected
        status = "connected" if connected else "disconnected"
        print(f"[PHIDGET] {self.name}: {status}", flush=True)
        publish(client, ready, {
            "sensor": "sensor_status", "name": self.name,
            "status": status, "source": "raspberry-phidget"
        })

    def read(self, client, ready):
        now = time.monotonic()
        if self.device is None:
            if now < self.next_attempt:
                return
            try:
                self.device = self.constructor()
                self.device.openWaitForAttachment(3000)
                self.update_status(True, client, ready)
            except Exception as error:
                print(f"[PHIDGET] {self.name}: {error}", flush=True)
                if self.device is not None:
                    self.device.close()
                self.device = None
                self.next_attempt = now + 5
                self.update_status(False, client, ready)
                return

        try:
            value = round(float(self.read_value(self.device)), 3)
            self.update_status(True, client, ready)
            publish(client, ready, {
                "sensor": self.name, "value": value, "unit": self.unit,
                "source": "raspberry-phidget"
            })
        except Exception as error:
            print(f"[PHIDGET] {self.name}: {error}", flush=True)
            self.device.close()
            self.device = None
            self.next_attempt = now + 5
            self.update_status(False, client, ready)


def publish(client, ready, event):
    if not ready.is_set():
        return
    event["id"] = str(uuid4())
    event["timestamp"] = datetime.now(timezone.utc).isoformat()
    client.publish(MQTT_TOPIC, json.dumps(event), qos=1)


def main():
    password = os.environ.get("SMARTSTOCK_MQTT_PASSWORD") or getpass.getpass("Mot de passe MQTT : ")
    connected = threading.Event()
    sensors = []
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.username_pw_set(MQTT_USER, password)

    def on_connect(_client, _userdata, _flags, reason_code, _properties):
        if reason_code.is_failure:
            print(f"[MQTT] Connexion refusee : {reason_code}", flush=True)
            connected.clear()
        else:
            print("[MQTT] Phidget connecte", flush=True)
            connected.set()
            for sensor in sensors:
                sensor.online = None  # Republish actual status after broker reconnection.

    def on_disconnect(_client, _userdata, _flags, reason_code, _properties):
        print(f"[MQTT] Deconnecte : {reason_code}", flush=True)
        connected.clear()

    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.reconnect_delay_set(min_delay=1, max_delay=30)
    client.connect(MQTT_HOST, MQTT_PORT, 60)
    client.loop_start()

    sensors = [
        Sensor("temperature", TemperatureSensor, lambda d: d.getTemperature(), "C"),
        Sensor("humidity", HumiditySensor, lambda d: d.getHumidity(), "%"),
    ]

    try:
        while True:
            for sensor in sensors:
                sensor.read(client, connected)
            time.sleep(2)
    except KeyboardInterrupt:
        print("[SMARTSTOCK] Arret", flush=True)
    finally:
        for sensor in sensors:
            if sensor.device is not None:
                sensor.device.close()
        client.disconnect()
        client.loop_stop()


if __name__ == "__main__":
    main()
