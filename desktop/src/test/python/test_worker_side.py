import importlib.util
import pathlib
import sys
import types
import unittest
from unittest.mock import patch

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
        w = worker.ZwiftBleWorker(pod_mode='right_only')
        self.assertEqual('RIGHT', w.side_for('AA', adv(7)))
        self.assertIsNone(w.side_for('AA', adv(8)))
        self.assertIsNone(w.side_for('AA', adv(9)))
        self.assertIsNone(w.side_for('AA', adv(42)))

        single = worker.ZwiftBleWorker(pod_mode='single')
        self.assertEqual('RIGHT', single.side_for('AA', adv(9)))
        self.assertEqual('RIGHT', single.side_for('AA', adv(42)))
        self.assertIsNone(worker.ZwiftBleWorker(pod_mode='single', pinned_right='BB').side_for('AA', adv(9)))
        self.assertEqual('RIGHT', worker.ZwiftBleWorker(pod_mode='right_only', pinned_right='AA').side_for('AA', adv(9)))
        self.assertIsNone(worker.ZwiftBleWorker(pod_mode='both', pinned_right='AA', pinned_left='AA').side_for('AA', adv(7)))

    def test_four_arrow_ride_controller_0x0b_and_single_isolation(self):
        adv = lambda n: types.SimpleNamespace(manufacturer_data={worker.ZWIFT_MANUFACTURER_ID: bytes([n])})
        both = worker.ZwiftBleWorker(pod_mode='both')
        self.assertEqual('LEFT', both.side_for('02:00:00:00:00:01', adv(0x0B)))
        self.assertTrue(worker.is_left_pod(adv(0x0B)))

        single_pinned = worker.ZwiftBleWorker(pod_mode='single', pinned_left='02:00:00:00:00:01')
        self.assertEqual('LEFT', single_pinned.side_for('02:00:00:00:00:01', adv(0x0B)))
        self.assertIsNone(single_pinned.side_for('11:22:33:44:55:66', adv(7)))

    def test_play_controllers(self):
        adv = lambda n: types.SimpleNamespace(manufacturer_data={worker.ZWIFT_MANUFACTURER_ID: bytes([n])})
        w = worker.ZwiftBleWorker(pod_mode='both')
        self.assertEqual('RIGHT', w.side_for('AA', adv(worker.DEVICE_ID_PLAY_RIGHT)))
        self.assertEqual('LEFT', w.side_for('AA', adv(worker.DEVICE_ID_PLAY_LEFT)))
        self.assertTrue(worker.is_left_pod(adv(worker.DEVICE_ID_PLAY_LEFT)))
        self.assertFalse(worker.is_left_pod(adv(worker.DEVICE_ID_PLAY_RIGHT)))

    def test_left_reconnect_window_and_error_formatting(self):
        w = worker.ZwiftBleWorker(pod_mode='both', left_reconnect_sec=12)
        self.assertEqual(12, w.left_reconnect_sec)
        self.assertTrue(w.running)
        self.assertEqual(0, worker.ZwiftBleWorker(left_reconnect_sec=-5).left_reconnect_sec)
        self.assertIn('turned off', worker.format_bleak_error(Exception('org.bluez.Error.NotReady: Resource Not Ready')))


class FakeClient:
    def __init__(self, device, disconnected_callback, timeout):
        self.on_disconnect = disconnected_callback
        self.is_connected = False

    async def connect(self, timeout):
        self.is_connected = True
        return True

    async def disconnect(self):
        self.is_connected = False
        self.on_disconnect(self)


class ConnectionLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def test_replaced_subscription_cannot_emit_button_packets(self):
        mac = '02:00:00:00:00:01'
        bridge = worker.ZwiftBleWorker(pod_mode='both')
        measure = types.SimpleNamespace(uuid=worker.MEASUREMENT_CHAR_UUID, properties=['notify'])
        control = types.SimpleNamespace(uuid=worker.CONTROL_POINT_CHAR_UUID, properties=['write'])

        class SubscribedClient(FakeClient):
            services = [types.SimpleNamespace(uuid=worker.ZWIFT_FC82_SERVICE_UUID,
                                              characteristics=[measure, control])]

            async def start_notify(self, characteristic, callback):
                self.notify = callback

            async def write_gatt_char(self, characteristic, data, response):
                pass

        with patch.object(worker, 'BleakClient', SubscribedClient), patch.object(worker, 'emit_packet') as emit:
            await bridge.connect_device(object(), mac, 'LEFT')
            old = bridge.active_clients[mac]
            await old.disconnect()
            await bridge.connect_device(object(), mac, 'LEFT')
            current = bridge.active_clients[mac]
            old.notify(measure, bytes([1]))
            current.notify(measure, bytes([2]))
            await bridge.stop()
            current.notify(measure, bytes([3]))
            emit.assert_called_once_with(mac, '02')

    async def test_late_disconnect_cannot_remove_replacement_client(self):
        bridge = worker.ZwiftBleWorker(pod_mode='both')
        mac = '02:00:00:00:00:01'
        with patch.object(worker, 'BleakClient', FakeClient), \
                patch.object(worker, 'setup_and_handshake', return_value=True), \
                patch.object(worker, 'emit_state') as state:
            await bridge.connect_device(object(), mac, 'LEFT')
            original = bridge.active_clients[mac]
            await original.disconnect()
            await bridge.connect_device(object(), mac, 'LEFT')
            replacement = bridge.active_clients[mac]

            state.reset_mock()
            original.on_disconnect(original)

            self.assertIs(replacement, bridge.active_clients.get(mac))
            self.assertEqual({mac}, bridge.connected_macs)
            self.assertEqual({mac: 'LEFT'}, bridge.connected_sides)
            state.assert_not_called()

    async def test_disconnect_during_handshake_cannot_publish_a_connection(self):
        bridge = worker.ZwiftBleWorker(pod_mode='both')
        mac = '02:00:00:00:00:01'

        async def handshake(client, _mac, _is_current):
            await client.disconnect()
            return True

        with patch.object(worker, 'BleakClient', FakeClient), \
                patch.object(worker, 'setup_and_handshake', handshake), \
                patch.object(worker, 'emit_state') as state:
            await bridge.connect_device(object(), mac, 'LEFT')

        self.assertEqual({}, bridge.active_clients)
        self.assertEqual(set(), bridge.connected_macs)
        self.assertEqual({}, bridge.connected_sides)
        self.assertIsNone(bridge.left_connected_at)
        self.assertEqual(['CONNECTING', 'DISCONNECTED'], [call.args[0] for call in state.call_args_list])


if __name__ == '__main__':
    unittest.main()
