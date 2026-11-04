# SmartStock — Raspberry Pi and connectivity

*Technical summary · connected cabinet*

Reference architecture: local Wi-Fi communication between the cabinet and the phone, and Bluetooth Low Energy (BLE) communications between the bag, the cabinet and the phone. Choices not made are marked “to be confirmed by the team”.

> Current S1 decisions and remaining hardware checks: [2026-10-29 addendum](../../method/analysis/2026-10-29%20–%20CONNECTIVITY_DECISIONS.md).
> Historical examples below do not prove live radio communication.

## 1. Summary table of tools and technologies

| Tool/technology | Role in the project | Status |
| ---| ---| ---|
| Raspberry Pi | On-board computer from the cabinet; hosts local functions and the SmartStock API. | Raspberry Pi 3 Model B (current decision, 2026-10-29). |
| Raspberry Pi OS | Raspberry Pi operating system. | Version/configuration: to be confirmed by the team. |
| Wi-Fi hotspot | Place the Pi and the phone on the same local network. | Configuration and deployment: to be confirmed by the team. |
| REST API | Exposes operations accessible by HTTP(S) requests, for example consulting the status of the cabinet. | Routes and methods: to be confirmed by the team. |
| JSON | Structured format of API requests and responses. | Data schema: to be confirmed by the team. |
| HTTPS (HTTP + TLS) | Protects HTTP exchanges by encryption and server authentication if correctly configured. | Certificates and mobile trust: to be confirmed by the team. |
| TCP/IP (IPv4) | Provides transport and network addressing between Wi-Fi equipment. | DHCP, fixed IP or reservation: to be confirmed by the team. |
| Bluetooth Low Energy (BLE) | Proximity connection between the bag and its two clients: cabinet and phone. | BLE module and simultaneous connections: to be confirmed by the team. |
| GATT | Services and features by which the bag exposes its data to BLE clients. | Services, features and UUID: to be confirmed by the team. |
| ATT / L2CAP | BLE layers used to access GATT attributes and transport exchanges. | Typically managed by the BLE stack. |
| React Native app | Phone interface; uses the REST API over Wi-Fi and connects to the bag using BLE. | Libraries and permissions: to be confirmed by the team. |
| SmartStock service on the Pi | Connects sensors to local functions and REST API. | Language and organization: to be confirmed by the team. |
| Cabinet sensors | Weight, DataMatrix identification, temperature/humidity and infrared. | References and interfaces: to be confirmed by the team. |

## 2. Glossary: role and ease of implementation

### Raspberry Pi and Raspberry Pi OS

The Pi is the little computer in the cabinet; its OS runs the SmartStock service and drivers. Ease: average; standard installation, network configuration and hardware access required.

### Wi-Fi hotspot
Provides the local network shared by the cabinet and the phone. Internet is not necessary if the local network and service remain available. Ease: easy to medium; check client isolation on the hotspot.

### REST API and JSON

The API defines the operations; JSON defines the data structure. Example: the phone requests stock status and receives a JSON response. Ease: average; define routes, fields, response codes and validation.

### HTTPS/TLS
HTTPS is HTTP protected by TLS. The server certificate must be accepted by the phone. Ease: average; certificates on local network require careful configuration.

### TCP/IP and IPv4

TCP transports data reliably; IPv4 addresses the equipment. DHCP generally assigns addresses automatically. Ease: easy if the network is configured.

### Bluetooth Low Energy (BLE)
Allows the phone and Raspberry Pi to communicate directly with the bag. BLE is independent of Wi-Fi. Ease: average; compatible hardware, permissions and range testing required.

### GATT, ATT and L2CAP

GATT describes services and features; ATT allows read/write/notify attributes; L2CAP provides the lower logical transport. BLE stacks typically manage these layers. Ease: average with suitable libraries.

### React Native app
Mobile interface with two distinct paths: REST API via Wi-Fi and BLE via compatible library. Ease: average; permissions and Android/iOS compatibility to be tested.

### SmartStock and sensors service

The service exposes the API and processes the metrics. Sensors may require converter or interface card. Ease: average, to be specified after selection of equipment.

## 3. Implementation model

### Wi-Fi route: cabinet ↔ phone

```text
React Native mobile app
└ REST API /JSON over HTTPS (HTTP + TLS)
  └ TCP/IPv4 over local Wi-Fi network
  └ Wi-Fi hotspot
  └ SmartStock service on Raspberry Pi
```

### Bluetooth route: cabinet ↔ bag ↔ phone

```text
Raspberry Pi (GATT client/central) ↔ BLE ↔ Bag (GATT device/server)
Mobile application (GATT client/central) ↔ BLE ↔ Bag (GATT device/server)
```

The bag exposes a common GATT contract. Simultaneous dual connection must be verified on hardware.

### REST/JSON request example

```http
GET /api/v1/stock HTTP/1.1
Host: cabinet-01.local
Accept: application/json
```

```json
{
  "deviceId": "cabinet-01",
  "observedAt": "2026-10-14T10:30:00Z",
  "items": [{ "productId": "compresses-001", "quantity": 40, "unit": "package" }]
}
```

### BLE data example

Conceptual example of GATT “bag state” characteristic: `{"batteryPercent":82,"bagState":"READY"}`. UUID, permissions, format and read/write/notify operations to be defined after hardware selection.

## 4. Offline operation

| Location | Expected behavior |
| ---| ---|
| Internet unavailable, local Wi-Fi active | The application can still reach the local API if the network and service remain available. |
| Wi-Fi hotspot unavailable | The Wi-Fi API becomes inaccessible; BLE may remain usable if the phone and bag are operational and within range. |
| BLE link interrupted | The client detects the loss and attempts a reconnection. Important data must be retained by its producer. |
| Raspberry Pi/service stopped | The local API becomes unavailable; the application should report status and not present old data as current. |
| Data to synchronize | To be confirmed by the team: queued data, persistence location and conflict resolution. |

## 5. Checks to be carried out

Initial state: architecture described, physical tests not yet executed. The actual results must be reported after execution.

| Test | Procedure | Expected result | State |
| ---| ---| ---| ---|
| Local WiFi | Place phone and Pi on the same network; call the API. | Valid answer without internet dependence. | To test |
| HTTPS/TLS | Call the API with the certificate configured on the phone. | Encrypted connection and certificate accepted. | To test |
| BLE bag | Read a GATT characteristic from the Pi then from the phone. | Both clients obtain compliant data. | To test |
| Simultaneous BLE | Connect both clients to the bag simultaneously. | Stable connections if the hardware supports it. | To be confirmed by the team |
| Wi-Fi Loss | Turn off the access point leaving BLE active. | API unavailable; BLE remains independent if the equipment is working. | To test |
| BLE reconnection | Move a BLE client away and then back together. | Disconnection detected and reconnection conforming to defined policy. | To test |