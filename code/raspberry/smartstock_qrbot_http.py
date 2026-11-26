from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import getpass
import os
from uuid import uuid4, UUID
import json
import secrets
import subprocess
import threading
from urllib.parse import parse_qs
from gs1_decoder import decode_gs1, GS1Error

MQTT_HOST = os.environ.get("SMARTSTOCK_MQTT_HOST", "127.0.0.1")
MQTT_PORT = int(os.environ.get("SMARTSTOCK_MQTT_PORT", "1883"))
MQTT_USER = os.environ.get("SMARTSTOCK_MQTT_USER", "smartstock_mqtt")
MQTT_TOPIC = os.environ.get("SMARTSTOCK_MQTT_TOPIC", "smartstock/events")
HTTP_PORT = int(os.environ.get("SMARTSTOCK_QRBOT_PORT", "8083"))


def main():
    if os.environ.get('SMARTSTOCK_QRBOT_TRANSPORT') == 'api-test':
        from qrbot_usb import main as usb_main
        usb_main()
        return
    import paho.mqtt.client as mqtt
    password = os.environ.get("SMARTSTOCK_MQTT_PASSWORD") or getpass.getpass("Mot de passe MQTT : ")
    access_token = secrets.token_urlsafe(24)
    connected = threading.Event()
    refused = threading.Event()

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    client.username_pw_set(MQTT_USER, password)
    client.reconnect_delay_set(min_delay=1, max_delay=30)

    def on_connect(_client, _userdata, _flags, reason_code, _properties):
        if reason_code.is_failure:
            print(f"[MQTT] Refuse : {reason_code}", flush=True)
            refused.set()
            connected.clear()
        else:
            connected.set()
            refused.clear()
            print("[MQTT] QRbot bridge connecte a Mosquitto", flush=True)

    def on_disconnect(_client, _userdata, _flags, reason_code, _properties):
        connected.clear()
        print(f"[MQTT] QRbot bridge deconnecte : {reason_code}", flush=True)

    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.connect(MQTT_HOST, MQTT_PORT, 60)
    client.loop_start()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, _fmt, *_args):
            return

        def respond(self, status, text):
            body = (text + "\n").encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/health":
                self.respond(200, "SmartStock QRbot receiver actif" if connected.is_set() else "MQTT indisponible")
            else:
                self.respond(404, "Introuvable")

        def do_POST(self):
            if self.path != "/scan/" + access_token:
                self.respond(404, "Introuvable")
                return
            if not connected.is_set():
                self.respond(503, "Broker MQTT indisponible")
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                self.respond(400, "Content-Length invalide")
                return
            if not 0 < length <= 8192:
                self.respond(413, "Contenu manquant ou trop volumineux")
                return
            raw = self.rfile.read(length).decode("utf-8", errors="strict")
            try:
                content_type = self.headers.get("Content-Type", "")
                if "application/json" in content_type:
                    obj = json.loads(raw)
                    code = obj.get("content", obj.get("code", ""))
                    operation = obj.get("operationId")
                else:
                    params = parse_qs(raw, keep_blank_values=True)
                    code = params.get("content", params.get("code", [""]))[0]
                    operation = params.get("operationId", [None])[0]
                if operation is not None:
                    operation = str(UUID(operation))
            except (ValueError, AttributeError, TypeError):
                self.respond(400, "Corps de requete invalide")
                return
            if not isinstance(code, str) or not 1 <= len(code) <= 2048:
                self.respond(400, "DataMatrix absent")
                return
            try:
                fields = decode_gs1(code)
            except GS1Error as exc:
                self.respond(422, f"GS1 invalide : {exc}")
                return
            message = {
                "sensor": "datamatrix",
                "id": str(uuid4()),
                **fields,
                "source": "iphone-qrbot",
                "timestamp": datetime.now(timezone.utc).isoformat(),
            }
            if operation is not None:
                message["operationId"] = operation
            try:
                info = client.publish(MQTT_TOPIC, json.dumps(message, ensure_ascii=False), qos=1)
                if info.rc != mqtt.MQTT_ERR_SUCCESS:
                    raise RuntimeError(f"publish rc={info.rc}")
                info.wait_for_publish(timeout=5)
                if not info.is_published():
                    raise RuntimeError("MQTT publication non confirmee")
            except Exception as exc:
                print(f"[MQTT] Echec de publication QRbot : {exc}", flush=True)
                self.respond(503, "Publication MQTT impossible")
                return
            print(f"[QRBOT] Scan decode transmis : GTIN={fields['gtin']} lot={fields['lot']}", flush=True)
            self.respond(202, "Scan publie uniquement ; aucune mise a jour de stock confirmee. Operation correlee requise.")

    try:
        raspberry_ip = subprocess.check_output(["hostname", "-I"], text=True).split()[0]
    except (IndexError, OSError, subprocess.CalledProcessError):
        raspberry_ip = "<IP-RASPBERRY>"

    server = None
    try:
        if not connected.wait(10):
            raise RuntimeError("MQTT non connecte : verifier utilisateur/mot de passe et Mosquitto")
        server = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Handler)
        print(f"\n[QRBOT] Serveur en ecoute sur le port {HTTP_PORT}")
        print(f"[QRBOT] Safari iPhone : http://{raspberry_ip}:{HTTP_PORT}/health")
        print(f"[QRBOT] API Endpoint : http://{raspberry_ip}:{HTTP_PORT}/scan/{access_token}")
        print("[QRBOT] HTTP Body : content=[code]")
        print("[QRBOT] Ctrl+C pour arreter\n", flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        print("[QRBOT] Arret demande", flush=True)
    finally:
        if server is not None:
            server.server_close()
        if client.is_connected():
            client.disconnect()
        client.loop_stop()


if __name__ == "__main__":
    main()
