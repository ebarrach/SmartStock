import json
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4
from smartstock_queue_sim import Outbox, Receiver, retry_delay, validate


def event(seq=1, source='cabinet-01'):
    return dict(v=1, id=str(uuid4()), type='stock.changed', src=source,
                seq=seq, ts='2026-10-29T00:00:00Z', corr=None, data={})


class QueueTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)
        self.outbox = Outbox(self.path / 'outbox.sqlite')
        self.receiver = Receiver(self.path / 'received.sqlite')

    def tearDown(self):
        self.outbox.close()
        self.receiver.close()
        self.temp.cleanup()

    def drain(self, now=100):
        while self.outbox.deliver_one(self.receiver.receive, now):
            pass

    def test_backoff(self):
        self.assertEqual([retry_delay(i) for i in range(1, 9)], [1, 2, 4, 8, 16, 32, 60, 60])

    def test_order_and_ack_removal(self):
        for seq in [3, 1, 5, 2, 4]:
            self.outbox.enqueue(event(seq))
        self.drain()
        self.assertEqual(self.receiver.sequences('cabinet-01'), [1, 2, 3, 4, 5])
        self.assertEqual(self.outbox.count(), 0)

    def test_duplicate_insert_and_identity_conflict(self):
        item = event()
        self.assertTrue(self.outbox.enqueue(item))
        self.assertFalse(self.outbox.enqueue(item))
        with self.assertRaises(ValueError):
            self.outbox.enqueue(dict(item, data={'changed': True}))

    def test_outage_blocks_following_events_then_replays(self):
        for seq in range(1, 11):
            self.outbox.enqueue(event(seq))
        def offline(_):
            raise ConnectionError('simulated outage')
        self.assertFalse(self.outbox.deliver_one(offline, 0))
        self.assertFalse(self.outbox.deliver_one(self.receiver.receive, 0.5))
        self.assertEqual(self.receiver.sequences('cabinet-01'), [])
        self.drain(1)
        self.assertEqual(self.receiver.sequences('cabinet-01'), list(range(1, 11)))

    def test_lost_ack_and_receiver_restart_deduplicate(self):
        self.outbox.enqueue(event())
        def lose_ack(item):
            self.receiver.receive(item)
            raise TimeoutError('ack lost')
        self.assertFalse(self.outbox.deliver_one(lose_ack, 0))
        self.receiver.close()
        self.receiver = Receiver(self.path / 'received.sqlite')
        self.drain(1)
        self.assertEqual(self.receiver.sequences('cabinet-01'), [1])
        self.assertEqual(self.outbox.count(), 0)

    def test_restart_resets_sending_and_keeps_pending(self):
        self.outbox.enqueue(event())
        with self.outbox.db:
            self.outbox.db.execute("UPDATE outbox SET state='sending'")
        self.outbox.close()
        self.outbox = Outbox(self.path / 'outbox.sqlite')
        self.assertEqual(self.outbox.db.execute('SELECT state FROM outbox').fetchone()[0], 'pending')
        self.drain()
        self.assertEqual(self.receiver.sequences('cabinet-01'), [1])

    def test_wrong_or_error_ack_keeps_event(self):
        self.outbox.enqueue(event())
        self.assertFalse(self.outbox.deliver_one(lambda _: {'corr': str(uuid4()), 'status': 'ok'}, 0))
        self.assertFalse(self.outbox.deliver_one(lambda item: {'corr': item['id'], 'status': 'error'}, 1))
        self.assertEqual(self.outbox.count(), 1)

    def test_capacity_never_deletes_unacknowledged_events(self):
        self.outbox.capacity = 2
        self.outbox.enqueue(event(1))
        self.outbox.enqueue(event(2))
        with self.assertRaises(BufferError):
            self.outbox.enqueue(event(3))
        self.drain()
        self.assertEqual(self.receiver.sequences('cabinet-01'), [1, 2])

    def test_invalid_envelopes_and_ack_loop(self):
        for changes in [dict(v=2), dict(seq=True), dict(seq=-1), dict(data=[]),
                        dict(ts='2026-10-29T00:00:00'), dict(src=''), dict(data={'x': float('nan')})]:
            with self.assertRaises((ValueError, TypeError)):
                validate(dict(event(), **changes))
        with self.assertRaises(ValueError):
            self.outbox.enqueue(dict(event(), type='ack'))

    def test_independent_source_sequences(self):
        self.outbox.enqueue(event(1, 'bag-01'))
        self.outbox.enqueue(event(1, 'cabinet-01'))
        self.drain()
        self.assertEqual(self.receiver.sequences('bag-01'), [1])
        self.assertEqual(self.receiver.sequences('cabinet-01'), [1])

    def test_examples_match_the_documented_envelope(self):
        root = Path(__file__).resolve().parents[1] / 'shared' / 'events'
        for example in root.glob('*.json'):
            if example.name != 'envelope.schema.json':
                validate(json.loads(example.read_text(encoding='utf-8')))


if __name__ == '__main__':
    unittest.main()
