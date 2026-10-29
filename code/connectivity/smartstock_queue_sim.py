"""Reproducible local protocol simulation. No Wi-Fi/BLE hardware driver."""

import json
import math
import sqlite3
from datetime import datetime
from uuid import UUID


def validate(envelope):
    if set(envelope) != {"v", "id", "type", "src", "seq", "ts", "corr", "data"}:
        raise ValueError("Expected the eight v1 envelope fields")
    if type(envelope["v"]) is not int or envelope["v"] != 1:
        raise ValueError("Unsupported version")
    if UUID(envelope["id"]).version != 4:
        raise ValueError("Expected UUID v4")
    if not isinstance(envelope["src"], str) or not envelope["src"].strip():
        raise ValueError("Missing source")
    if type(envelope["seq"]) is not int or envelope["seq"] < 0:
        raise ValueError("Invalid sequence")
    if not isinstance(envelope["type"], str) or not envelope["type"].strip():
        raise ValueError("Missing event type")
    timestamp = datetime.fromisoformat(envelope["ts"].replace("Z", "+00:00"))
    if timestamp.utcoffset() is None or timestamp.utcoffset().total_seconds() != 0:
        raise ValueError("Expected UTC timestamp")
    if envelope["corr"] is not None:
        UUID(envelope["corr"])
    if not isinstance(envelope["data"], dict):
        raise ValueError("Expected data object")
    json.dumps(envelope, allow_nan=False)


def retry_delay(attempts):
    return 2 ** min(max(attempts - 1, 0), 6) if attempts < 7 else 60


class Outbox:
    def __init__(self, path, capacity=100):
        self.db = sqlite3.connect(path)
        self.capacity = capacity
        self.db.execute("""CREATE TABLE IF NOT EXISTS outbox (
            id TEXT PRIMARY KEY, src TEXT NOT NULL, seq INTEGER NOT NULL,
            env TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending',
            attempts INTEGER NOT NULL DEFAULT 0, next_try REAL NOT NULL DEFAULT 0,
            UNIQUE(src, seq))""")
        self.db.execute("UPDATE outbox SET state='pending' WHERE state='sending'")
        self.db.commit()

    def close(self):
        self.db.close()

    def enqueue(self, envelope):
        validate(envelope)
        if envelope['type'] == 'ack':
            raise ValueError('Acknowledgments must not enter the acknowledged outbox')
        serialized = json.dumps(envelope, sort_keys=True, allow_nan=False)
        with self.db:
            previous = self.db.execute("SELECT env FROM outbox WHERE id=?", (envelope['id'],)).fetchone()
            if previous:
                if previous[0] != serialized:
                    raise ValueError('Conflicting event identity')
                return False
            if self.count() >= self.capacity:
                raise BufferError('Queue full: refuse new input, retain unacknowledged events')
            self.db.execute("INSERT INTO outbox(id,src,seq,env) VALUES (?,?,?,?)",
                            (envelope['id'], envelope['src'], envelope['seq'], serialized))
        return True

    def count(self):
        return self.db.execute("SELECT count(*) FROM outbox").fetchone()[0]

    def deliver_one(self, send, now):
        if not math.isfinite(now):
            raise ValueError('Invalid simulation clock')
        row = self.db.execute("SELECT id,env,attempts,next_try FROM outbox ORDER BY src,seq LIMIT 1").fetchone()
        if row is None or row[3] > now:
            return False
        event_id, serialized, attempts, _ = row
        with self.db:
            self.db.execute("UPDATE outbox SET state='sending' WHERE id=?", (event_id,))
        try:
            ack = send(json.loads(serialized))
            accepted = (isinstance(ack, dict) and ack.get('corr') == event_id
                        and ack.get('status') in ('ok', 'duplicate'))
        except (ConnectionError, TimeoutError):
            accepted = False
        with self.db:
            if accepted:
                self.db.execute("DELETE FROM outbox WHERE id=?", (event_id,))
            else:
                attempts += 1
                self.db.execute("UPDATE outbox SET state='pending',attempts=?,next_try=? WHERE id=?",
                                (attempts, now + retry_delay(attempts), event_id))
        return accepted


class Receiver:
    """Persistent deduplication + received-event log, not a production stock service."""
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute('CREATE TABLE IF NOT EXISTS received (id TEXT PRIMARY KEY, src TEXT, seq INTEGER, env TEXT, UNIQUE(src,seq))')
        self.db.commit()

    def close(self):
        self.db.close()

    def receive(self, envelope):
        validate(envelope)
        serialized = json.dumps(envelope, sort_keys=True, allow_nan=False)
        previous = self.db.execute('SELECT env FROM received WHERE id=?', (envelope['id'],)).fetchone()
        if previous:
            status = 'duplicate' if previous[0] == serialized else 'error'
        else:
            try:
                with self.db:
                    self.db.execute('INSERT INTO received VALUES (?,?,?,?)',
                                    (envelope['id'], envelope['src'], envelope['seq'], serialized))
                status = 'ok'
            except sqlite3.IntegrityError:
                status = 'error'
        return {'corr': envelope['id'], 'status': status}

    def sequences(self, source):
        return [r[0] for r in self.db.execute('SELECT seq FROM received WHERE src=? ORDER BY rowid', (source,))]


if __name__ == '__main__':
    import unittest
    unittest.main(module='test_queue', verbosity=2)
