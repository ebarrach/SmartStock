# SmartStock
Smart Medication Cabinet
------------------------------------------------------------
📘 Official Documentation
------------------------------------------------------------

The project includes official documentation available in the `/doc/` directory:

- 📙 **User Guide**
  [doc/UserGuide/PDF/UserGuide.pdf](doc/UserGuide/PDF/UserGuide.pdf)

- 🛠️ **Programmer Guide**
  [doc/ProgrammerGuide/PDF/ProgrammerGuide.pdf](doc/ProgrammerGuide/PDF/ProgrammerGuide.pdf)

The official guides will be written and maintained by the team in LaTeX.
For local startup and test commands, see [Quick start](method/DEMARRAGE.md).

------------------------------------------------------------
📌 Project Description
------------------------------------------------------------

- **Project Name:** SmartStock
- **Project Type:** Smart medication cabinet / AIoT academic project
- **Project Purpose:** Automated medication stock management, monitoring and traceability
- **Target Platform:** Smart cabinet connected to a computer and smartphone application

SmartStock is a connected medication cabinet designed to improve medication
inventory management, monitoring and traceability.

The system identifies medications using their **DataMatrix codes** and automatically
tracks medication entries and removals from the cabinet.

SmartStock also monitors the environmental conditions inside the cabinet,
including temperature and humidity, and generates alerts when abnormal
conditions or medication-related issues are detected.

Main features include:

- Register medications in the cabinet.
- Identify medications using DataMatrix codes.
- View medications currently available in the cabinet.
- Check the remaining quantity of each medication.
- Automatically update stock when medications are added or removed.
- Manage multiple batches of the same medication.
- Manually correct stock quantities when necessary.
- Monitor cabinet temperature and humidity.
- Detect object movement using an infrared sensor.
- Generate alerts when temperature or humidity is abnormal.
- Notify users when medications are approaching their expiration dates.
- Alert users when medications have expired.
- Detect sensor failures.
- View the history of medication additions and removals.
- View system and medication alerts.
- Access information from a computer or smartphone.
- Keep information updated after each operation.
- Preserve stored data after the application is restarted.

The ecosystem also includes an **emergency bag**, connected over **Bluetooth/BLE**,
while the cabinet uses **Wi-Fi**. The current embedded reference is the
**Raspberry Pi 3 Model B**. DataMatrix is the current identification technology;
older NFC or Raspberry Pi 4 references in historical documents do not define the
current architecture.

### Current implementation

The feature list above describes the product scope. The software foundation
currently provides a persistent product and batch inventory, stock movements,
environmental readings, alerts and an HTTP API. DataMatrix and weight events are
correlated using a shared operation identifier; commands and retries are
deduplicated to avoid recording the same movement twice.

The autonomous **Scala/Akka engine** confirms stock operations and computes
temperature-based fan decisions, including hysteresis. The tested withdrawal
scenario is:

```text
weight change + DataMatrix detection
→ Scala stock validation
→ stock 40 → 39
→ one persisted stock movement
```

The mobile application supports both explicitly simulated data and the real API.
Physical sensor drivers, calibration, fan actuation, BLE and native-device
acceptance still require validation on the actual equipment. Software tests do
not constitute proof of physical integration.

------------------------------------------------------------
🧱 Code Content
------------------------------------------------------------

- **Code Directory:** `code/`

The project is divided into several components:

- **Mobile Application:**
  [code/mobile-app/](code/mobile-app/)
  React Native / TypeScript application used to access SmartStock information,
  medication inventory, alerts and cabinet status. A repository abstraction
  allows the same screens to use mock data or the HTTP API.

- **SmartStock Agent:**
  [code/smartstock-agent/](code/smartstock-agent/)
  Scala-based intelligent system responsible for processing events,
  coordinating SmartStock logic and communication between components.
  The Scala/Akka agent remains an event intermediary, with separate components
  for correlation, inventory persistence, API transport and thermal decisions.

- **Shared API:**
  [code/shared/api/openapi.yaml](code/shared/api/openapi.yaml)
  OpenAPI specification defining communication interfaces between
  SmartStock components. Shared examples are in `code/shared/api/examples/`,
  and versioned event contracts are in `code/shared/events/`.

- **Raspberry Pi Gateway:**
  [code/gateway/](code/gateway/)
  Event emitter with a durable SQLite outbox, stable identifiers, replay support,
  calibration tooling and a prepared systemd service definition.

- **Connectivity and Tools:**
  `code/connectivity/` and `code/tools/`
  Protocol and contract checks and integration smoke tests.

Main technologies used in the project include:

- Scala
- Akka
- React Native
- TypeScript
- Raspberry Pi
- DataMatrix
- Infrared sensors
- Temperature and humidity sensors
- REST / OpenAPI
- Python / SQLite
- Wi-Fi / Bluetooth BLE

------------------------------------------------------------
🚀 How to Run the Project
------------------------------------------------------------

The project is currently under development.

Each SmartStock component has its own development environment and dependencies.

### SmartStock Agent

The Scala agent is located in:

`code/smartstock-agent/`

Requirements:

- JDK 21
- sbt

From the SmartStock agent directory:

```bash
cd code/smartstock-agent
sbt compile
sbt run
```

To explicitly select the HTTP service entry point:

```bash
sbt "runMain smartstock.ServiceMain"
```

Alternatively, build and run the standalone JAR with a simulated initial inventory:

```bash
sbt assembly
java -jar target/scala-2.13/smartstock-agent.jar --demo
```

`--demo` seeds an empty data store with demonstration stock. Omit it for an
unseeded inventory. The service listens on `127.0.0.1:8081` by default:

- Health: `http://127.0.0.1:8081/api/v1/health`
- Inventory snapshot: `http://127.0.0.1:8081/api/v1/snapshot`

Environment variables include:

| Variable | Purpose | Default |
| --- | --- | --- |
| `SMARTSTOCK_DATA` | Persistent inventory file | `data/inventory.json` |
| `SMARTSTOCK_BIND` | Listening address | `127.0.0.1` |
| `SMARTSTOCK_PORT` | HTTP port | `8081` |
| `SMARTSTOCK_CORRELATION_MS` | DataMatrix/weight correlation window | `5000` |
| `SMARTSTOCK_WEIGHT_TOLERANCE_GRAMS` | Weight tolerance | `0.5` |

The service also accepts temperature and humidity alert limits through
`SMARTSTOCK_TEMP_MIN`, `SMARTSTOCK_TEMP_MAX`, `SMARTSTOCK_HUMIDITY_MIN` and
`SMARTSTOCK_HUMIDITY_MAX`.

### Mobile Application

The React Native application is located in:

`code/mobile-app/`

Requirements:

- Node.js 22.11 or newer
- npm
- React Native development environment

From the mobile application directory:

```bash
cd code/mobile-app
npm install
npm start
```

For reproducible installation using the committed lockfile, use `npm ci`.
In a second terminal, run `npm run android` with an Android SDK and a configured
emulator or phone. iOS development requires macOS, Xcode and CocoaPods.

Set `apiBaseUrl` in `code/mobile-app/app.json` to select the HTTP repository;
`null` keeps explicitly simulated data. The API mode refreshes every five seconds
and reports communication errors without silently falling back to mock data.
See the [mobile README](code/mobile-app/README.md) for device networking and setup.

### API

The communication contract is defined in:

`code/shared/api/openapi.yaml`

The API specification is used as the common interface between the different
SmartStock software components.

The OpenAPI 3.1 document uses JSON syntax, which is also valid YAML. The HTTP
service exposes products, stock, movements, readings, alerts and snapshots under
`/api/v1/`. Write requests use stable command identifiers for idempotency;
event messages follow the shared versioned envelope.

### Raspberry Pi Gateway

The deployment target is the **Raspberry Pi 3 Model B**. Build the agent JAR on
the development workstation and follow the
[gateway instructions](code/gateway/README.md) for event emission, durable replay,
service configuration and physical acceptance checks.

------------------------------------------------------------
🧪 Tests and Validation
------------------------------------------------------------

From `code/smartstock-agent/`:

```bash
sbt scalafmtCheckAll
sbt test
sbt assembly
```

The integration tests exercise the local Scala/Akka engine through the HTTP API,
including stock correlation, replay, restart and fan hysteresis. No external engine
or bridge is required.

From the repository root:

```bash
python -m pip install -r code/connectivity/requirements-test.txt
python -m unittest discover -s code/connectivity -v
python -m unittest discover -s code/gateway -v
```

`SMARTSTOCK_API_URL` enables contract checks against a running agent.
`code/tools/integration_smoke.py` exercises the HTTP/Scala-Akka pipeline
against an explicitly simulated dataset.

From `code/mobile-app/`:

```bash
npm run format:check
npm run lint -- --max-warnings 0
npm run typecheck
npm test -- --runInBand
```

Native Android/iOS builds, BLE, physical measurements and hardware operation
must be verified on their respective platforms.

Useful technical references:

- [MVP acceptance criteria](method/analysis/2026-10-29%20–%20MVP_ACCEPTANCE.md)
- [Current hardware interfaces](method/analysis/2026-10-29%20–%20CORRECTIF_MATERIEL.md)
- [Scala/Akka architecture and concepts](method/design/SCALA_ACTORS.md)

------------------------------------------------------------
👩‍💻 Academic Context
------------------------------------------------------------

SmartStock is developed as an academic project at the **University of Namur (UNamur)**.

The project focuses on the design and implementation of a mobile and ambient
computing system combining software development, IoT sensors, embedded
components and distributed communication.

Authors:

- Samuel AMANI KINYONGOLO
- Esteban BARRACHO
- Ayoub EL MANSOURI
- Jonathan KONDOLI NKEBI
- Pablo ROJAS LOPEZ
- Ana TELLERIA RODRIGUEZ

The final defense is planned for **January 2027**, with the exact date to be confirmed.

------------------------------------------------------------
📄 License
------------------------------------------------------------

This project is distributed under the **Creative Commons Attribution–NoDerivatives 4.0 (CC BY-ND 4.0)** license.

You may:

- Share and redistribute the work with proper attribution.

You may **not**:

- Modify or distribute modified versions of the work.
- Remove or alter attribution.
- Reuse the name “SmartStock” or its visual identity without permission.

For details, see the full license in the `LICENSE` file.

### 📌 Contributions

External contributions are accepted under the following condition:

All submitted contributions (pull requests) are automatically licensed under
**CC BY-ND 4.0** and must comply with the constraints of the license.

See **`CONTRIBUTING.md`** for the full policy.

### ⚠️ DMCA & Copyright Notice

Any unauthorized reproduction, redistribution or publication of the codebase,
documentation or the SmartStock application, including source code, assets
and visual identity, may constitute a copyright violation.

In case of infringement on GitHub or other platforms, a DMCA takedown request
may be issued to the service provider.
