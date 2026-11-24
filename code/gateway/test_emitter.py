import tempfile
import unittest
from pathlib import Path
from emitter import Gateway


class GatewayTests(unittest.TestCase):
    def test_sequence_and_pending_events_survive_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.sqlite'
            first = Gateway(path, 'simulated-cabinet')
            event = first.emit('temperature.measured', {'value': 20})
            first.close()
            second = Gateway(path, 'simulated-cabinet')
            try:
                next_event = second.emit('humidity.measured', {'value': 50})
                self.assertEqual((event['seq'], next_event['seq']), (1, 2))
                self.assertNotEqual(event['id'], next_event['id'])
                self.assertEqual(second.outbox.count(), 2)
            finally:
                second.close()

    def test_queue_full_preserves_counter_and_pending_events(self):
        with tempfile.TemporaryDirectory() as directory:
            gateway = Gateway(Path(directory) / 'events.sqlite', 'source')
            try:
                gateway.outbox.capacity = 1
                gateway.emit('temperature.measured', {'value': 20})
                with self.assertRaises(BufferError):
                    gateway.emit('humidity.measured', {'value': 50})
                self.assertEqual(gateway.outbox.db.execute('SELECT seq FROM sequences').fetchone()[0], 1)
            finally:
                gateway.close()
