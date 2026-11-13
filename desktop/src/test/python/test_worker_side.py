import importlib.util
import pathlib
import sys
import types
import unittest

bleak = types.ModuleType('bleak')
bleak.BleakScanner = object
bleak.BleakClient = object
exc = types.ModuleType('bleak.exc')
exc.BleakError = Exception
sys.modules['bleak'] = bleak
sys.modules['bleak.exc'] = exc
path = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/ble/zwift_ble_worker.py'
spec = importlib.util.spec_from_file_location('worker', path)
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)

class SideTest(unittest.TestCase):
    def test_identification_and_pins(self):
        adv = lambda n: types.SimpleNamespace(manufacturer_data={worker.ZWIFT_MANUFACTURER_ID: bytes([n])})
        w = worker.ZwiftBleWorker()
        self.assertEqual('RIGHT', w.side_for('AA', adv(7)))
        self.assertEqual('LEFT', w.side_for('AA', adv(8)))
        self.assertIsNone(w.side_for('AA', adv(9)))
        self.assertIsNone(w.side_for('AA', adv(42)))
        legacy = worker.ZwiftBleWorker(legacy_shared=True)
        self.assertEqual('RIGHT', legacy.side_for('AA', adv(9)))
        self.assertEqual('RIGHT', legacy.side_for('AA', adv(42)))
        self.assertIsNone(worker.ZwiftBleWorker(pinned_right='BB', legacy_shared=True).side_for('AA', adv(9)))
        self.assertEqual('RIGHT', worker.ZwiftBleWorker(pinned_right='AA').side_for('AA', adv(9)))
        self.assertIsNone(worker.ZwiftBleWorker(pinned_right='AA', pinned_left='AA').side_for('AA', adv(7)))

    def test_play_controllers(self):
        adv = lambda n: types.SimpleNamespace(manufacturer_data={worker.ZWIFT_MANUFACTURER_ID: bytes([n])})
        w = worker.ZwiftBleWorker()
        self.assertEqual('RIGHT', w.side_for('AA', adv(worker.DEVICE_ID_PLAY_RIGHT)))
        self.assertEqual('LEFT', w.side_for('AA', adv(worker.DEVICE_ID_PLAY_LEFT)))
        self.assertTrue(worker.is_left_pod(adv(worker.DEVICE_ID_PLAY_LEFT)))
        self.assertFalse(worker.is_left_pod(adv(worker.DEVICE_ID_PLAY_RIGHT)))

    def test_left_reconnect_window_configuration(self):
        w = worker.ZwiftBleWorker(left_reconnect_sec=12)
        self.assertEqual(12, w.left_reconnect_sec)
        self.assertTrue(w.running)
        self.assertEqual(0, worker.ZwiftBleWorker(left_reconnect_sec=-5).left_reconnect_sec)

if __name__ == '__main__':
    unittest.main()
