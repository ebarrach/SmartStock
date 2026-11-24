# Mobile UI Mockups & Screen Acceptance Criteria

## 1. Context & Design Guidelines

* **Target User:** Dr. Deborah Lefebvre (General Practitioner, mobility & clinic settings)

* **Design Philosophy:** High-contrast medical interface, minimal cognitive load, immediate visual status feedback (Green = OK, Orange = Warning, Red = Action Required).

* **Core Navigation:** Persistent bottom tab bar with 4 primary destinations: `Dashboard`, `Cabinet`, `Emergency Bag`, and `Alerts`.

## 2. Screen Specifications & Wireframes

### Screen 1: Dashboard / Home (`HomeScreen.tsx`)

#### Layout & Text Wireframe

```
+-------------------------------------------------------+
|  SmartStock | Dr. Deborah Lefebvre        [Sync: OK]  |
+-------------------------------------------------------+
|  SYSTEM STATUS                                        |
|  [ Cabinet: Connected (Wi-Fi) ] [ Bag: Connected BLE ]|
+-------------------------------------------------------+
|  QUICK SUMMARY                                        |
|  +-------------------+  +---------------------------+ |
|  | Cabinet Stock: OK |  | Bag Status: 1 Item Low    | |
|  | 142 items total   |  | 12 / 13 items ready       | |
|  +-------------------+  +---------------------------+ |
+-------------------------------------------------------+
|  ACTIVE ALERTS (2)                      [View All >]  |
|  - [!] Solution X (Bag) expires in 8 days             |
|  - [?] Compresses (Cabinet) below minimum stock       |
+-------------------------------------------------------+
|  QUICK ACTIONS                                        |
|  [ Prepare On-Call Bag ]     [ Audit Cabinet Stock ]  |
+-------------------------------------------------------+
| (Home)      (Cabinet)      (Bag)             (Alerts) |
+-------------------------------------------------------+

```

#### Acceptance Criteria (AC-001)

1. **AC-001.1 (Connection Status):** The top bar MUST display live connection indicators for both the Connected Cabinet (Wi-Fi) and the Emergency Bag (BLE).

2. **AC-001.2 (Alert Summary):** A maximum of 2 active high-priority alerts MUST be displayed on the main dashboard, with a direct link to the full Alerts screen.

3. **AC-001.3 (Quick Action Trigger):** Tapping "Prepare On-Call Bag" MUST immediately initiate the BLE sync sequence with the emergency bag.

### Screen 2: Connected Cabinet (`CabinetScreen.tsx`)

#### Layout & Text Wireframe

```
+-------------------------------------------------------+
| < Back     Connected Cabinet Inventory    [Refresh]   |
+-------------------------------------------------------+
|  CLIMATE MONITORING                                   |
|  Temp: 21.4 °C | Humidity: 42% | Door: CLOSED         |
+-------------------------------------------------------+
|  SHELF BREAKDOWN                                      |
|  ▼ Shelf 1 - Injectables & Ampoules                   |
|    - Product A (10ml) ....... Qty: 24 (Target: 25)    |
|    - Product B (5ml) ........ Qty: 8  (Target: 10)    |
|                                                       |
|  ► Shelf 2 - Bandages & Sterile Compresses            |
|  ► Shelf 3 - Diagnostic Tools                         |
+-------------------------------------------------------+
|  SENSOR TELEMETRY                                     |
|  Weight Sensors: 100% Calibrated | NFC Reader: Ready  |
+-------------------------------------------------------+
| (Home)      (Cabinet)      (Bag)             (Alerts) |
+-------------------------------------------------------+

```

#### Acceptance Criteria (AC-002)

1. **AC-002.1 (Live Telemetry):** Temperature, humidity, and door state MUST update in real time based on data from the Raspberry Pi 4 REST service.

2. **AC-002.2 (Shelf Quantity Display):** Quantity counts per shelf MUST reflect real-time strain gauge weight calculations combined with registered NFC tags.

3. **AC-002.3 (Visual Hierarchy):** Items below their minimum threshold MUST be highlighted with an orange badge indicating the deficit.

### Screen 3: Emergency Bag (`BagScreen.tsx`)

#### Layout & Text Wireframe

```
+-------------------------------------------------------+
| < Back      Smart Emergency Bag           [BLE: ON]   |
+-------------------------------------------------------+
|  BAG HEALTH                                           |
|  Battery: [████████░░] 82% | Status: Ready for Visit  |
+-------------------------------------------------------+
|  CHECKLIST FOR ON-CALL VISITS             (12/13 OK)  |
|  [x] Emergency Ampoule Kit (4/4)                      |
|  [x] Stethoscope & Sphygmomanometer (1/1)             |
|  [!] Dressing Set ........ Qty: 1 (Target: 2) [LOW]   |
|  [x] Antiseptic Solution (1/1) - Exp: 14/11/2026      |
+-------------------------------------------------------+
|  SMART RESTOCK ASSISTANT                              |
|  "Missing 1 Dressing Set. 15 available in Cabinet     |
|   (Batch #B204 closest to expiry)."                   |
|  [ Transfer 1x Dressing Set to Bag ]                  |
+-------------------------------------------------------+
| (Home)      (Cabinet)      (Bag)             (Alerts) |
+-------------------------------------------------------+

```

#### Acceptance Criteria (AC-003)

1. **AC-003.1 (BLE Diagnostics):** The screen MUST display current USB-C battery level and BLE signal strength (RSSI).

2. **AC-003.2 (Automated Checklist):** Items in the bag MUST automatically check/uncheck based on sensor detection without manual touch input.

3. **AC-003.3 (FEFO Transfer Recommendation):** When an item is missing, the assistant MUST suggest the exact batch from the cabinet closest to expiration.

### Screen 4: Alerts & Expiration Center (`AlertsScreen.tsx`)

#### Layout & Text Wireframe

```
+-------------------------------------------------------+
| Alerts & Expiration Center               [Filter ▼]   |
+-------------------------------------------------------+
|  URGENT ATTENTION REQUIRED (2)                        |
|                                                       |
|  [!] EXPIRING SOON (< 30 Days)                        |
|      Item: Solution X (100ml) - Lot #L-9982           |
|      Location: Emergency Bag (Compartment 1)          |
|      Expires in: 8 days                               |
|      [ Action: Mark as Consumed ] [ Discard ]         |
|                                                       |
|  [?] LOW STOCK WARNING                                |
|      Item: Sterile Gauze 10x10                        |
|      Location: Connected Cabinet (Shelf 2)            |
|      Current: 4 units | Minimum: 10 units             |
|      [ Action: Add to Reorder List ]                  |
+-------------------------------------------------------+
| (Home)      (Cabinet)      (Bag)             (Alerts) |
+-------------------------------------------------------+

```

#### Acceptance Criteria (AC-004)

1. **AC-004.1 (Threshold Filtering):** Items with less than 30 days remaining until expiration MUST automatically generate a high-priority alert card.

2. **AC-004.2 (One-Tap Action Execution):** Tapping "Mark as Consumed" or "Discard" MUST update the central store (`Zustand`) and reconcile the item status across all views.

3. **AC-004.3 (Offline Queuing):** If an action is taken offline, it MUST be marked with a pending sync icon and transmitted once connectivity is restored.