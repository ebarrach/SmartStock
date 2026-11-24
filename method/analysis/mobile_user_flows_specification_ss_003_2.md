# Mobile Application User Flows Specification 

## 1. Overview & User Context

* **Project:** SmartStock Mobile Ecosystem

* **Primary Persona:** Dr. Deborah Lefebvre (65-year-old General Practitioner)

* **Operational Scope:** Dual-environment management covering the fixed **SmartStock Cabinet** (Clinic) and the portable **Smart Emergency Bag** (Home visits/On-call).

* **Key Goal:** Provide frictionless, automatic stock monitoring, zero manual inventory logging, and proactive expiration prevention.

## 2. Core User Flows

### Flow 1: Cabinet Stock Inspection (Clinic Mode • Wi-Fi / REST)

* **User Goal:** Quickly audit clinic inventory levels and identify items needing reorder upon arriving at the clinic.

* **Preconditions:** Mobile device connected to local clinic Wi-Fi network; Raspberry Pi 4 server active.

```
[Open App] ➔ [Auto-connect to Cabinet Wi-Fi REST API] ➔ [Dashboard View]
                                                            │
       ┌────────────────────────────────────────────────────┴────────────────────────────────────────────────────┐
       ▼                                                                                                         ▼
[View Overall Stock Level]                                                                        [Filter by Category / Expiration]
       │                                                                                                         │
       └────────────────────────────────────────────────────┬────────────────────────────────────────────────────┘
                                                            ▼
                                           [Inspect Specific Item Quantities]
                                           (Measured via Strain Gauges + NFC)

```

* **Step-by-Step Sequence:**

  1. Dr. Deborah opens the SmartStock mobile application.

  2. The app automatically connects via local REST API to the Cabinet's Raspberry Pi 4.

  3. The **Dashboard** displays live stock status indicators (*Stock Normal*, *Low Stock Warning*, *Expiring Soon*).

  4. Deborah taps a category (e.g., *Injectables*, *Sterile Compresses*) or searches for a specific medication.

  5. Real-time strain gauge weight data and NFC registry display exact quantities available per shelf.

### Flow 2: Emergency Bag Preparation & Smart Transfer (Cabinet ➔ Bag)

* **User Goal:** Prepare and complete the emergency bag prior to leaving for home visits or on-call duties without manual logging.

* **Preconditions:** Doctor is physically at the clinic near both the cabinet and bag; Bluetooth LE enabled.

```
[Select "Prepare Bag"] ➔ [BLE Connect to Bag] ➔ [Auto-Compare Bag Contents vs. Target Profile]
                                                                  │
                                                                  ▼
                                                      [App Identifies Missing Item]
                                                                  │
                                                                  ▼
                                              [Suggests Transfer from Cabinet (FEFO Batch)]
                                                                  │
                                                                  ▼
                                            [Doctor Moves Item: Cabinet ➔ Bag]
                                                                  │
                                                                  ▼
                                        [NFC / Weight Sensors Detect Movement]
                                                                  │
                                                                  ▼
                                      [Simultaneous Live Update: Cabinet↓, Bag↑]

```

* **Step-by-Step Sequence:**

  1. Deborah selects **"Prepare On-Call Bag"** from the app dashboard.

  2. The app pairs with the Smart Emergency Bag over Bluetooth LE and compares current compartment contents (NFC + weight sensors) against her active reference checklist (e.g., *Standard Home Visit Profile*).

  3. The app highlights missing or depleted items (e.g., *Missing 1x Solution X*).

  4. The app checks cabinet stock and recommends transferring an available batch, prioritizing the item closest to expiration (*FEFO: First Expired, First Out*).

  5. Deborah opens the cabinet, retrieves the recommended unit, and places it inside the emergency bag.

  6. Cabinet and Bag NFC/Weight sensors detect the movement in real time.

  7. The app instantly updates both inventories simultaneously without requiring any manual button press or barcode scanning.

### Flow 3: Home Visit & Offline Material Tracking (Bag Mode • BLE / Offline)

* **User Goal:** Track items removed and used during a patient visit at a location with no cellular or Wi-Fi network coverage.

* **Preconditions:** Smart Bag powered via USB-C battery; BLE active on smartphone; mobile internet unavailable.

```
[Remove Item from Bag during Patient Visit] ➔ [Bag Sensors Detect Removal]
                                                             │
                                                             ▼
                                                [BLE Transmits Event to App]
                                                             │
                                                             ▼
                                              [No Network Connection Detected]
                                                             │
                                                             ▼
                                            [App Stores Event Locally in Queue]
                                                             │
                                                             ▼
                                         [Return to Clinic / Network Restored]
                                                             │
                                                             ▼
                                           [Auto Sync Queue with Main Backend]

```

* **Step-by-Step Sequence:**

  1. During a home visit, Deborah opens her emergency bag and removes a dressing kit and an ampoule.

  2. The compartment weight sensors and embedded NFC reader register the removal.

  3. The bag transmits the sensor event to the mobile app via low-power Bluetooth LE.

  4. Sensing no mobile data coverage, the app immediately writes the transaction to local encrypted storage (`AsyncStorage`).

  5. The app UI displays a visual sync indicator (*Saved Offline*).

  6. Upon returning to the clinic, the app detects local Wi-Fi, connects to the Cabinet server, and automatically reconciles the queued transactions with the main database.

### Flow 4: Proactive Alert Handling & Expiration Prevention

* **User Goal:** Prevent medical waste and ensure patient safety by acting on automated expiration and low-stock warnings.

```
[System Background Check] ➔ [Identify Expiration Threshold (< 30 Days)]
                                                  │
                                                  ▼
                                      [Push Notification Sent]
                                                  │
                                                  ▼
                                     [Doctor Opens Alerts Center]
                                                  │
                                                  ▼
                                 [App Recommends Smart Action]
                     ("Transfer Expiring Item from Cabinet to Bag for Today's Visits")
                                                  │
                                                  ▼
                                 [Doctor Confirms / Executes Action]

```

* **Step-by-Step Sequence:**

  1. The system continually evaluates product batch expiration dates.

  2. When an item reaches a critical threshold (e.g., expiration within 30 days), the app generates a high-priority push notification.

  3. Deborah taps the notification to open the **Alerts Center**.

  4. The app proposes a resolution: *"Item Y in Cabinet expires in 12 days. Transfer to Emergency Bag for immediate consumption."*

  5. Deborah accepts the recommendation, performs the physical transfer, and the alert resolves automatically.

### Flow 5: Automated Pre-Departure Audit (Safety Check)

* **User Goal:** Receive final 5-second green-light confirmation that the bag is fully equipped before rushing out for an emergency call.

* **Preconditions:** Bluetooth connection between phone and bag active.

```
[Tap "Pre-Departure Audit"] ➔ [Instant BLE Query] ➔ [Evaluate All Compartments]
                                                            │
                                  ┌─────────────────────────┴─────────────────────────┐
                                  ▼                                                   ▼
                          [ALL ITEMS PRESENT]                                 [ITEMS MISSING/EXPIRED]
                                  │                                                   │
                                  ▼                                                   ▼
                       [Green "READY" Screen]                             [Red Warning & Item List]

```

## 3. Edge Cases & Exception Handling

| Scenario / Risk | System Response & Resolution Flow | 
| ----- | ----- | 
| **Bluetooth Disconnection in Field** | App displays "Bag Offline (BLE Disconnected)". Sensor events are buffered inside the bag's local micro-controller memory and transmitted in bulk as soon as BLE reconnects. | 
| **Weight Mismatch (Unregistered Item)** | If weight changes on a shelf without an NFC tag read, the app prompts: *"Unknown item detected in Compartment 2. Please verify or label item."* | 
| **Network Outage at Clinic** | App automatically switches to Local Offline Cache mode; operations continue locally and re-sync once the Raspberry Pi REST service restores. | 

**Target Audience:** React Native Frontend Developers, UX/UI Designers, Academic Defense Panel.