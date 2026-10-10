# SmartStock
Smart Medication Cabinet
------------------------------------------------------------
📘 Official Documentation
------------------------------------------------------------

The project includes official documentation available in the `/doc/` directory:

- 📙 **User Guide**  
  `doc/UserGuide/PDF/UserGuide.pdf`

- 🛠️ **Programmer Guide**  
  `doc/ProgrammerGuide/PDF/ProgrammerGuide.pdf`

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

------------------------------------------------------------
🧱 Code Content
------------------------------------------------------------

- **Code Directory:** `code/`

The project is divided into several components:

- **Mobile Application:**  
  `code/mobile-app/`  
  React Native / TypeScript application used to access SmartStock information,
  medication inventory, alerts and cabinet status.

- **SmartStock Agent:**  
  `code/smartstock-agent/`  
  Scala-based intelligent system responsible for processing events,
  coordinating SmartStock logic and communication between components.

- **Shared API:**  
  `code/shared/api/openapi.yaml`  
  OpenAPI specification defining communication interfaces between
  SmartStock components.

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

### Mobile Application

The React Native application is located in:

`code/mobile-app/`

Requirements:

- Node.js
- npm
- React Native development environment

From the mobile application directory:

```bash
cd code/mobile-app
npm install
npm start
```

### API

The communication contract is defined in:

`code/shared/api/openapi.yaml`

The API specification is used as the common interface between the different
SmartStock software components.

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
