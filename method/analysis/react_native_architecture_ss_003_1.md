# React Native Mobile Application Architecture & Structure 

## 1. Technical Stack & Architectural Choices

* **Framework:** React Native with TypeScript (Expo Bare Workflow / React Native CLI)

  * *Justification:* Cross-platform development (iOS/Android) featuring strict type safety to prevent runtime errors when handling critical stock levels, sensor states, and expiration dates.

* **Navigation:** `@react-navigation/native` (Stack Navigator + Bottom Tab Navigator)

  * *Justification:* Fluid, structured navigation across the main app modules: Dashboard, Cabinet Inventory, Emergency Bag, and Alerts Center.

* **State Management:** `Zustand`

  * *Justification:* Lightweight, predictable, and highly performant state container ideal for real-time sensor event reactive updates.

* **IoT & Hardware Communication:**

  * **Bluetooth LE:** `react-native-ble-plx` for low-power direct connection with the Smart Emergency Bag during home visits.

  * **HTTP / REST Client:** `Axios` for communicating over local Wi-Fi with the Connected Cabinet backend server (Raspberry Pi 4).

* **Persistence & Offline Mode:** `@react-native-async-storage/async-storage`

  * *Justification:* Local queuing of stock transactions during home calls in areas without network coverage, with automatic background sync upon rejoining Wi-Fi or cellular networks.

## 2. Directory Structure (`/src`)

```
src/
├── assets/            # Images, icons, custom fonts
├── components/        # Reusable UI components (Button, Card, Badge, Modal)
│   ├── common/
│   ├── cabinet/       # Cabinet-specific UI components
│   └── bag/           # Emergency Bag-specific UI components
├── config/            # Environment constants, API endpoints, color palette
├── navigation/        # Route configurations (AppNavigator, TabNavigator)
├── screens/           # Primary application screens
│   ├── HomeScreen.tsx
│   ├── CabinetScreen.tsx
│   ├── BagScreen.tsx
│   ├── AlertsScreen.tsx
│   └── SettingsScreen.tsx
├── services/          # API client, Bluetooth BLE, and Sync handlers
│   ├── api/
│   ├── bluetooth/
│   └── sync/
├── store/             # Global Zustand store for inventory and active alerts
├── types/             # TypeScript interfaces (Product, Alert, BagState, Stock)
└── utils/             # Helper utilities (expiration calculation, date formatters)

```

## 3. Connection & Synchronization Strategy

1. **Cabinet Mode (Wi-Fi / REST API):**

   * Interrogates the local REST API running on the Raspberry Pi 4 when the doctor is at the clinic.

2. **Emergency Bag Mode (Bluetooth LE):**

   * Real-time streaming of weight strain gauge readings and NFC scan events directly from the bag.

3. **Offline Resilience:**

   * Stores all item additions and removals locally during visits, automatically reconciling with the central inventory server once a network connection is re-established.