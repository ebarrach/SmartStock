# SmartStock — Reconnection and local queue

*Raspberry Pi and connectivity · step 3*

> [Step 1 — architecture and tools](SmartStock_Raspberry_Connectivite.md) · [Step 2 — event envelope](SmartStock_Raspberry_Connectivite_Enveloppe_Evenements.md) · **Step 3 — reconnection and local queue**

A Wi-Fi or BLE link may drop and then come back. This document sets the expected behavior at that time: how the connection is reestablished, and how the Pi keeps the envelope events (step 2) without loss or duplicates. The figures quoted are test values.

## 1. Reconnection

| Subject | Rule | Status |
| ---| ---| ---|
| Loss detection | Wi-Fi: timeout or error on HTTPS request. BLE: GATT disconnect event. | Proposed |
| Who reconnects | The bag announces its presence in BLE; the cabinet and the phone connect to it. The phone attaches to the Pi's hotspot. | Proposed (simultaneous connections to be confirmed) |
| Delay between two attempts | The delay doubles for each failure (1, 2, 4, 8, 16, 32 s) then caps at 60 s. It restarts 1 s after a success. | Test values |
| Order during outage | Sending stops on the first failure: subsequent events wait, which preserves the `seq` order. | Proposed |
| Resynchronization | The receiver announces the last `seq` received per sender; the sender skips what is already received and sends the rest. | Proposed |
| Connection states | Connected, reconnecting, offline — one state per link. | Proposed (display in the application) |

## 2. Local queue on the Pi

Each pending event occupies an entry in the queue:

| Column | Role | Status |
| ---| ---| ---|
| `id` | Event ID, unique key: prevents duplicates. | Proposed |
| `src`, `seq` | Sender and sequence number: used for ordering and resynchronization. | Proposed |
| `env` | Full JSON envelope of the event. | Proposed |
| `state` | `pending` or `sending`. An acknowledged entry is deleted. | Proposed |
| `attempts`, `next_try` | Number of attempts and next try time, for the increasing delay. | Proposed |

### Planned SQLite schema
```sql
CREATE TABLE outbox (
  id TEXT PRIMARY KEY, -- envelope id
  src TEXT NOT NULL, -- sender
  seq INTEGER NOT NULL, -- order
  env TEXT NOT NULL, -- JSON envelope
  state TEXT NOT NULL, -- pending | sending
  attempts INTEGER NOT NULL DEFAULT 0,
  next_try TEXT -- time of next attempt
);
```

## 3. Operating rules

| Subject | Rule | Status |
| ---| ---| ---|
| Storage | Persistent local database that survives a power outage (SQLite intended). | To be decided (SD card wear) |
| Send and ack | Sending in `seq` order, deletion after ack. A “duplicate” ack counts as a success. | Proposed |
| Starting the Pi | `sending` entries revert to `pending` and are resent; the receiver ignores duplicates. | Proposed |
| Queue full | 100 events under test. The oldest ones are deleted and the hole remains visible in the `seq`. | To be decided (delete, block, alert) |
| Retention | Maximum lifetime of an unacknowledged event. Should acknowledged events be kept so that the phone can read them back from a `seq`? | To be decided |
| Queue on the bag side | The bag probably only has a small buffer: its size still has to be sized. | To be decided |

## 4. Verification
The queue logic has been simulated (SQLite, simulated link, receiver with deduplication): it validates the logic, not the actual radio links. Script: `smartstock_queue_sim.py`.

| Verification | Observation | Result |
| ---| ---| ---|
| Delay between attempts | Sequence obtained: 1, 2, 4, 8, 16, 32, 60, 60 s. | Passed |
| Sending order | Events added out of order (3, 1, 5, 2, 4): received in order 1 to 5. | Passed |
| Duplicate on insert | Two insertions of the same `id`: only one entry in the queue. | Passed |
| Deletion after ack | 5 events sent: queue empty afterwards. | Passed |
| Outage then replay | Nothing sent offline; on return, 10 events received in order, only once. | Passed |
| Restart, queue not empty | 7 events found when the database is reopened, then all delivered in order. | Passed |
| Lost ack | The event stays in the queue, is resent, and then processed once. | Passed |
| Stopping during a send | `sending` entry reset to `pending` on restart. | Passed |
| Resume from last `seq` | Receiver at 6 out of 10 events: 7 to 10 left. | Passed |
| Queue full | 150 added for 100 capacity: `seq` 51 to 150 kept. | Passed |

## 5. Tests remaining to be done on the prototype

| Test | Method | Result |
| ---| ---| ---|
| Actual Wi-Fi outage | Switch the access point off during sending, restore it, check replay and order. | To do |
| Real BLE outage | Move the bag away, reconnect, check recovery from the last `seq`. | To do |
| Pi power outage | Unplug the Pi with a non-empty queue: no lost or corrupted events. | To do |
| Simultaneous BLE connections | Check that the bag holds two clients at the same time (cabinet and phone). | To do |