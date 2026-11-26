#!/usr/bin/env python3
"""
Zwift Click v2 Cross-Platform BLE Worker
Connects to Zwift Click / Zwift Play / Ride controllers via Bleak (BlueZ, CoreBluetooth, WinRT).
Emits STATE, PACKET, and LOG lines to stdout for consumption by DesktopZwiftBleController.
"""

import sys
import time
import asyncio
import argparse
import signal

# Service and Characteristic UUIDs for Zwift Click & Zwift Play
ZWIFT_FC82_SERVICE_UUID = "0000fc82-0000-1000-8000-00805f9b34fb"
ZWIFT_LEGACY_SERVICE_UUID = "00000001-19ca-4651-86e5-fa29dcdd09d1"
MEASUREMENT_CHAR_UUID = "00000002-19ca-4651-86e5-fa29dcdd09d1"
CONTROL_POINT_CHAR_UUID = "00000003-19ca-4651-86e5-fa29dcdd09d1"
COMMAND_RESPONSE_CHAR_UUID = "00000004-19ca-4651-86e5-fa29dcdd09d1"

ZWIFT_MANUFACTURER_ID = 0x094A  # 2378 decimal
RIDE_ON_HANDSHAKE = b"RideOn"   # 526964654F6E

DEVICE_ID_PLAY_RIGHT = 0x02
DEVICE_ID_PLAY_LEFT = 0x03
DEVICE_ID_RIGHT_POD = 0x07
DEVICE_ID_LEFT_POD = 0x08
DEVICE_ID_CLICK = 0x09
DEVICE_ID_RIDE_LEFT = 0x0B


def log(level: str, msg: str):
    print(f"LOG {level} {msg}", flush=True)


def emit_state(state: str, mac: str = ""):
    if mac:
        print(f"STATE {state} {mac}", flush=True)
    else:
        print(f"STATE {state}", flush=True)


def emit_packet(mac: str, hex_bytes: str):
    print(f"PACKET {mac} {hex_bytes}", flush=True)


def _build_arg_parser():
    parser = argparse.ArgumentParser(description="Zwift Click v2 BLE Worker")
    parser.add_argument("--pinned-right", default="", help="Pinned MAC address or UUID for Right Pod")
    parser.add_argument("--pinned-left", default="", help="Pinned MAC address or UUID for Left Pod")
    parser.add_argument("--pod-mode", default="", choices=["", "single", "right_only", "both"], help="Controller mode")
    parser.add_argument("--right-only", default="true", help="Legacy flag for single/right pod mode (true/false)")
    parser.add_argument("--legacy-shared", default="false", help="Legacy flag for single controller mode (true/false)")
    parser.add_argument("--left-reconnect-sec", type=int, default=55, help="Periodic reconnect interval for left pod (0=disabled)")
    parser.add_argument("--scan-test", action="store_true", help="Run in scan-test mode (scan, connect, stream, exit 0)")
    parser.add_argument("--duration", type=int, default=15, help="Duration for scan-test mode in seconds")
    return parser


if "--protocol-version" in sys.argv:
    print("CLICKCONTROL_BLE_PROTOCOL_4", flush=True)
    sys.exit(0)

if "--help" in sys.argv or "-h" in sys.argv:
    _build_arg_parser().parse_args()
    sys.exit(0)


try:
    from bleak import BleakScanner, BleakClient
    from bleak.exc import BleakError
except ImportError:
    log("ERROR", "Python module 'bleak' is not installed. Please run: pip install bleak")
    emit_state("ERROR", "bleak_not_installed")
    if "--scan-test" in sys.argv:
        sys.exit(1)
    while True:
        time.sleep(10)
        log("WARN", "Waiting for 'bleak' to be installed (pip install bleak)...")


def is_zwift_device(device, adv_data) -> bool:
    uuids = [str(u).lower() for u in (adv_data.service_uuids or [])]
    if ZWIFT_FC82_SERVICE_UUID.lower() in uuids or ZWIFT_LEGACY_SERVICE_UUID.lower() in uuids:
        return True

    if adv_data.manufacturer_data and ZWIFT_MANUFACTURER_ID in adv_data.manufacturer_data:
        return True

    name = (device.name or adv_data.local_name or "").strip().lower()
    for prefix in ("zwift click", "zwift ride", "zwift play", "zwift"):
        if name.startswith(prefix) or prefix in name:
            return True

    return False


def is_left_pod(adv_data) -> bool:
    if adv_data.manufacturer_data and ZWIFT_MANUFACTURER_ID in adv_data.manufacturer_data:
        data = adv_data.manufacturer_data[ZWIFT_MANUFACTURER_ID]
        if len(data) > 0 and data[0] in (DEVICE_ID_LEFT_POD, DEVICE_ID_PLAY_LEFT, DEVICE_ID_RIDE_LEFT):
            return True
    return False


def format_bleak_error(err: Exception) -> str:
    msg = str(err).strip()
    lower = msg.lower()
    if any(k in lower for k in ("notready", "powered", "no bluetooth adapter", "adapter not found", "resource is not ready")):
        return "Bluetooth adapter is turned off or unavailable — enable Bluetooth in system settings"
    if "org.freedesktop.dbus" in lower or "permission denied" in lower:
        return f"Bluetooth D-Bus permission error: {msg}"
    return msg or err.__class__.__name__


async def setup_and_handshake(client: BleakClient, mac: str, is_current=None) -> bool:
    emit_state("HANDSHAKING", mac)
    log("INFO", f"Connected to {mac}. Performing 'RideOn' handshake & characteristic discovery...")

    measurement_char = None
    control_point_char = None
    zwift_services = []

    for service in client.services:
        s_uuid = service.uuid.lower()
        if (s_uuid == ZWIFT_FC82_SERVICE_UUID.lower() or
            s_uuid == ZWIFT_LEGACY_SERVICE_UUID.lower() or
            "fc82" in s_uuid or
            "19ca-4651-86e5-fa29dcdd09d1" in s_uuid):
            zwift_services.append(service)

    search_services = zwift_services if zwift_services else client.services

    for service in search_services:
        for char in service.characteristics:
            c_uuid = char.uuid.lower()
            if c_uuid == MEASUREMENT_CHAR_UUID.lower():
                measurement_char = char
            elif c_uuid == CONTROL_POINT_CHAR_UUID.lower():
                control_point_char = char

    if not measurement_char:
        for service in zwift_services:
            for char in service.characteristics:
                props = [p.lower() for p in char.properties]
                if "notify" in props or "indicate" in props:
                    measurement_char = char
                    log("INFO", f"Discovered fallback measurement characteristic {char.uuid} on {mac}")
                    break
            if measurement_char:
                break

    if not control_point_char:
        for service in zwift_services:
            for char in service.characteristics:
                props = [p.lower() for p in char.properties]
                if "write-without-response" in props or "write" in props:
                    control_point_char = char
                    log("INFO", f"Discovered fallback control point characteristic {char.uuid} on {mac}")
                    break
            if control_point_char:
                break

    def notification_handler(sender, data: bytearray):
        if is_current is not None and not is_current():
            return
        hex_str = data.hex().upper()
        emit_packet(mac, hex_str)

    if not measurement_char or not control_point_char:
        log("ERROR", f"Required Zwift characteristics missing on {mac}")
        return False

    try:
        await client.start_notify(measurement_char, notification_handler)
        log("INFO", f"Subscribed to measurement notifications ({measurement_char.uuid}) on {mac}")
    except Exception as ne:
        log("ERROR", f"Failed to subscribe to measurement char on {mac}: {ne}")
        return False

    props = [p.lower() for p in control_point_char.properties]
    initial_response = "write" in props or "write-without-response" not in props
    try:
        await client.write_gatt_char(control_point_char, RIDE_ON_HANDSHAKE, response=initial_response)
        log("INFO", f"RideOn handshake write succeeded (response={initial_response}) on {mac}")
    except Exception as we:
        fallback_response = not initial_response
        log("WARN", f"RideOn write with response={initial_response} failed ({we}), retrying with response={fallback_response}...")
        try:
            await client.write_gatt_char(control_point_char, RIDE_ON_HANDSHAKE, response=fallback_response)
            log("INFO", f"RideOn handshake fallback succeeded (response={fallback_response}) on {mac}")
        except Exception as we2:
            log("ERROR", f"RideOn handshake failed on {mac}: {we2}")
            return False

    for service in zwift_services:
        for char in service.characteristics:
            c_uuid = char.uuid.lower()
            if measurement_char and c_uuid == measurement_char.uuid.lower():
                continue
            if c_uuid == COMMAND_RESPONSE_CHAR_UUID.lower() or "00000004-19ca-4651-86e5-fa29dcdd09d1" in c_uuid:
                continue
            props = [p.lower() for p in char.properties]
            if "notify" in props:
                try:
                    await client.start_notify(char, notification_handler)
                    log("INFO", f"Subscribed to additional notify characteristic {char.uuid} on {mac}")
                except Exception as ne:
                    log("WARN", f"Failed to subscribe to additional notify char {char.uuid} on {mac}: {ne}")

    return True


class ZwiftBleWorker:
    def __init__(
        self,
        pinned_right: str = "",
        pinned_left: str = "",
        pod_mode: str = "",
        right_only: bool = True,
        legacy_shared: bool = False,
        left_reconnect_sec: int = 55
    ):
        self.pinned_right = pinned_right.strip().upper() if pinned_right else ""
        self.pinned_left = pinned_left.strip().upper() if pinned_left else ""
        mode = (pod_mode or "").strip().lower()
        if mode in ("single", "right_only", "both"):
            self.pod_mode = mode
        elif legacy_shared:
            self.pod_mode = "single"
        elif right_only:
            self.pod_mode = "right_only"
        else:
            self.pod_mode = "both"
        self.right_only = (self.pod_mode == "right_only")
        self.legacy_shared = (self.pod_mode == "single")
        self.left_reconnect_sec = max(0, int(left_reconnect_sec))
        self.left_connected_at = None
        self.stop_task = None
        self.discovered_macs = set()
        self.reported_unknown = set()
        self.running = True
        self.active_clients = {}  # mac -> BleakClient
        self.connected_macs = set()
        self.connected_sides = {}

    def _pins(self):
        return {p for p in (self.pinned_right, self.pinned_left) if p}

    def side_for(self, mac, adv_data):
        mac = mac.upper()
        if mac == self.pinned_right and mac == self.pinned_left:
            return None
        pins = self._pins()
        data = (adv_data.manufacturer_data or {}).get(ZWIFT_MANUFACTURER_ID, b"")

        if self.pod_mode == "single":
            if pins and mac not in pins:
                return None
            if mac == self.pinned_left:
                return "LEFT" if "LEFT" not in self.connected_sides.values() else None
            if mac == self.pinned_right:
                return "RIGHT" if "RIGHT" not in self.connected_sides.values() else None
            if is_left_pod(adv_data):
                return "LEFT" if "LEFT" not in self.connected_sides.values() else None
            return "RIGHT" if "RIGHT" not in self.connected_sides.values() else "LEFT"

        if self.pod_mode == "right_only":
            if mac == self.pinned_right:
                return "RIGHT"
            if not self.pinned_right and data and data[0] in (DEVICE_ID_RIGHT_POD, DEVICE_ID_PLAY_RIGHT):
                return "RIGHT"
            return None

        # pod_mode == "both"
        if mac == self.pinned_right:
            return "RIGHT"
        if mac == self.pinned_left:
            return "LEFT"
        if data and data[0] in (DEVICE_ID_LEFT_POD, DEVICE_ID_PLAY_LEFT, DEVICE_ID_RIDE_LEFT):
            return "LEFT" if not self.pinned_left else None
        if data and data[0] in (DEVICE_ID_RIGHT_POD, DEVICE_ID_PLAY_RIGHT):
            return "RIGHT" if not self.pinned_right else None
        return None

    def update_connection_state(self, changed_mac: str = ""):
        pass

    def _forget_client(self, mac, client):
        if self.active_clients.get(mac) is not client:
            return
        del self.active_clients[mac]
        self.connected_macs.discard(mac)
        if self.connected_sides.pop(mac, None) == "LEFT":
            self.left_connected_at = None
        emit_state("DISCONNECTED", mac)
        self.update_connection_state(mac)

    async def run(self):
        log("INFO", f"Starting Zwift BLE Worker (pod_mode={self.pod_mode}, pinned_right='{self.pinned_right}', pinned_left='{self.pinned_left}')")
        emit_state("SCANNING")

        while self.running:
            try:
                if self.pod_mode == "both" and self.left_reconnect_sec > 0 and self.left_connected_at is not None:
                    left_macs = [m for m, s in self.connected_sides.items() if s == "LEFT"]
                    if left_macs and (time.monotonic() - self.left_connected_at) >= self.left_reconnect_sec:
                        left_mac = left_macs[0]
                        log("INFO", f"Left pod watchdog refreshing {left_mac} after {self.left_reconnect_sec}s")
                        client = self.active_clients.get(left_mac)
                        if client is not None:
                            self._forget_client(left_mac, client)
                            try:
                                await client.disconnect()
                            except Exception:
                                pass

                if self.pod_mode in ("single", "right_only") and len(self.connected_macs) >= 1:
                    await asyncio.sleep(1.0)
                    continue

                if len(self.connected_macs) >= 2:
                    await asyncio.sleep(1.0)
                    continue

                emit_state("SCANNING")

                found_device = None
                found_adv = None
                found_side = None
                fallback_device = None
                fallback_adv = None
                fallback_first_seen = None
                found_event = asyncio.Event()

                def detection_callback(device, adv_data):
                    nonlocal found_device, found_adv, found_side
                    nonlocal fallback_device, fallback_adv, fallback_first_seen
                    if not self.running or found_event.is_set():
                        return
                    mac_upper = device.address.upper()
                    if mac_upper in self.connected_macs or mac_upper in self.active_clients:
                        return
                    if not is_zwift_device(device, adv_data):
                        return

                    if mac_upper not in self.discovered_macs:
                        self.discovered_macs.add(mac_upper)
                        emit_state("DISCOVERED", mac_upper)

                    side = self.side_for(mac_upper, adv_data)
                    if side is not None:
                        if side in self.connected_sides.values():
                            return
                        found_device = device
                        found_adv = adv_data
                        found_side = side
                        found_event.set()
                        return

                    pins = self._pins()
                    if self.pod_mode == "single" and pins and mac_upper not in pins:
                        if mac_upper not in self.reported_unknown:
                            self.reported_unknown.add(mac_upper)
                            log("INFO", f"Ignored controller {mac_upper} (pinned to {', '.join(sorted(pins))} so your second controller stays free)")
                        return

                    if self.pod_mode == "right_only":
                        if mac_upper not in self.reported_unknown:
                            self.reported_unknown.add(mac_upper)
                            log("INFO", f"Ignored controller {mac_upper} because ClickControl is set to Right controller only")
                        return

                    # Only in unpinned 'both' mode do we allow delayed fallback for unidentified pods
                    fallback_side = None
                    if self.pod_mode == "both":
                        if "RIGHT" not in self.connected_sides.values() and not self.pinned_right and mac_upper != self.pinned_left:
                            fallback_side = "RIGHT"
                        elif "LEFT" not in self.connected_sides.values() and not self.pinned_left and mac_upper != self.pinned_right:
                            fallback_side = "LEFT"

                    if fallback_side is not None:
                        fallback_device = device
                        fallback_adv = adv_data
                        if fallback_first_seen is None:
                            fallback_first_seen = time.monotonic()
                        if time.monotonic() - fallback_first_seen >= 0.6:
                            found_device = device
                            found_adv = adv_data
                            found_side = fallback_side
                            found_event.set()
                    elif mac_upper not in self.reported_unknown:
                        self.reported_unknown.add(mac_upper)
                        log("INFO", f"Ignored unassigned Zwift device {mac_upper}; pin its address in Hardware settings if needed")

                scanner = BleakScanner(detection_callback=detection_callback)
                try:
                    await scanner.start()
                    try:
                        await asyncio.wait_for(found_event.wait(), timeout=4.0)
                    except asyncio.TimeoutError:
                        pass
                finally:
                    try:
                        await scanner.stop()
                    except Exception as se:
                        log("WARN", f"Scanner stop exception: {se}")

                await asyncio.sleep(0.25)

                if found_device is None and fallback_device is not None and self.running and self.pod_mode == "both":
                    mac_upper = fallback_device.address.upper()
                    fallback_side = None
                    if "RIGHT" not in self.connected_sides.values() and not self.pinned_right and mac_upper != self.pinned_left:
                        fallback_side = "RIGHT"
                    elif "LEFT" not in self.connected_sides.values() and not self.pinned_left and mac_upper != self.pinned_right:
                        fallback_side = "LEFT"

                    if fallback_side is not None:
                        found_device = fallback_device
                        found_adv = fallback_adv
                        found_side = fallback_side

                if found_device is not None and self.running:
                    mac_upper = found_device.address.upper()
                    target_side = found_side or self.side_for(mac_upper, found_adv)
                    if target_side:
                        await self.connect_device(found_device, mac_upper, target_side)

                await asyncio.sleep(0.5)

            except BleakError as be:
                friendly = format_bleak_error(be)
                log("ERROR", f"Bluetooth scan error: {friendly}")
                emit_state("ERROR", friendly)
                await asyncio.sleep(3.0)
            except Exception as e:
                friendly = format_bleak_error(e)
                log("ERROR", f"Unexpected scan exception: {friendly}")
                emit_state("ERROR", friendly)
                await asyncio.sleep(3.0)

    async def connect_device(self, device, mac: str, side: str):
        if mac in self.connected_macs or mac in self.active_clients:
            return

        log("INFO", f"Connecting GATT to {mac} (side={side})...")
        emit_state("CONNECTING", mac)

        def on_disconnect(client):
            if self.active_clients.get(mac) is not client:
                return
            log("WARN", f"Device disconnected (asleep or out of range): {mac}")
            self._forget_client(mac, client)

        client = None
        handshaken = False
        try:
            connected = False
            for attempt in range(1, 3):
                attempt_client = BleakClient(device, disconnected_callback=on_disconnect, timeout=10.0)
                try:
                    connected = await attempt_client.connect(timeout=10.0)
                    if connected or attempt_client.is_connected:
                        client = attempt_client
                        break
                    try:
                        await attempt_client.disconnect()
                    except Exception:
                        pass
                except Exception as ce:
                    log("WARN", f"Connection attempt {attempt} to {mac} failed: {ce}")
                    try:
                        await attempt_client.disconnect()
                    except Exception:
                        pass
                if attempt < 2:
                    await asyncio.sleep(0.4)

            if client is None or (not connected and not client.is_connected):
                log("WARN", f"Failed to connect to {mac} — press a button on the controller to wake it and retry")
                emit_state("DISCONNECTED", mac)
                return

            self.active_clients[mac] = client
            if not self.running or not await setup_and_handshake(
                client, mac, lambda: self.running and self.active_clients.get(mac) is client
            ):
                return
            if not self.running or self.active_clients.get(mac) is not client or not client.is_connected:
                return

            handshaken = True
            self.connected_macs.add(mac)
            self.connected_sides[mac] = side
            if side == "LEFT":
                self.left_connected_at = time.monotonic()
            emit_state("CONNECTED_" + side, mac)
            log("INFO", f"Handshake complete & subscribed to notifications on {mac}")
            self.update_connection_state(mac)

        except Exception as e:
            log("ERROR", f"Error during connection to {mac}: {e}")
            if client is None:
                emit_state("DISCONNECTED", mac)
        finally:
            if client is not None and not handshaken:
                self._forget_client(mac, client)
                if client.is_connected:
                    try:
                        await client.disconnect()
                    except Exception:
                        pass

    async def stop(self):
        self.running = False
        log("INFO", "Stopping BLE worker and disconnecting devices...")
        for mac, client in list(self.active_clients.items()):
            self._forget_client(mac, client)
            try:
                await client.disconnect()
            except Exception:
                pass
        emit_state("DISCONNECTED")


async def run_scan_test(duration: int = 15):
    log("INFO", f"Starting Zwift BLE scan-test mode (duration={duration}s)...")
    emit_state("SCANNING")

    found_device = None
    found_adv = None
    found_event = asyncio.Event()

    def detection_callback(device, adv_data):
        nonlocal found_device, found_adv
        if not is_zwift_device(device, adv_data):
            return
        log("INFO", f"Discovered candidate Zwift device: {device.address} (name='{device.name}', local_name='{adv_data.local_name}')")
        found_device = device
        found_adv = adv_data
        found_event.set()

    scanner = BleakScanner(detection_callback=detection_callback)
    try:
        await scanner.start()
        try:
            await asyncio.wait_for(found_event.wait(), timeout=float(duration))
        except asyncio.TimeoutError:
            log("WARN", f"No Zwift device found within {duration} seconds.")
    except BleakError as be:
        friendly = format_bleak_error(be)
        log("ERROR", f"Bluetooth scan error: {friendly}")
        emit_state("ERROR", friendly)
    except Exception as e:
        friendly = format_bleak_error(e)
        log("ERROR", f"Error during scan: {friendly}")
        emit_state("ERROR", friendly)
    finally:
        try:
            await scanner.stop()
        except Exception:
            pass

    await asyncio.sleep(0.15)

    if found_device is None:
        emit_state("DISCONNECTED")
        log("INFO", "Scan test completed (no device found). Exiting 0.")
        sys.exit(0)

    mac = found_device.address.upper()
    emit_state("CONNECTING", mac)
    log("INFO", f"Connecting to {mac}...")

    def on_disconnect(client):
        log("WARN", f"Device disconnected: {mac}")
        emit_state("DISCONNECTED", mac)

    client = BleakClient(found_device, disconnected_callback=on_disconnect, timeout=10.0)
    try:
        await client.connect(timeout=10.0)
    except Exception as ce:
        log("ERROR", f"Failed to connect to {mac}: {ce}")
        emit_state("DISCONNECTED", mac)
        sys.exit(0)

    if not client.is_connected:
        log("ERROR", f"Client not connected to {mac}")
        emit_state("DISCONNECTED", mac)
        sys.exit(0)

    success = await setup_and_handshake(client, mac)
    if not success:
        log("ERROR", f"Handshake failed on {mac}")
        try:
            await client.disconnect()
        except Exception:
            pass
        emit_state("DISCONNECTED", mac)
        sys.exit(0)

    emit_state("CONNECTED_RIGHT_ONLY", mac)
    log("INFO", f"Streaming live packets for {duration} seconds (press buttons now)...")

    end_time = time.time() + float(duration)
    while time.time() < end_time and client.is_connected:
        await asyncio.sleep(0.1)

    log("INFO", "Scan test complete. Disconnecting cleanly...")
    try:
        await client.disconnect()
    except Exception:
        pass
    emit_state("DISCONNECTED", mac)
    sys.exit(0)


def main():
    args = _build_arg_parser().parse_args()
    right_only_bool = args.right_only.strip().lower() in ("true", "1", "yes")
    legacy_shared_bool = args.legacy_shared.strip().lower() in ("true", "1", "yes")

    worker = ZwiftBleWorker(
        pinned_right=args.pinned_right,
        pinned_left=args.pinned_left,
        pod_mode=args.pod_mode,
        right_only=right_only_bool,
        legacy_shared=legacy_shared_bool,
        left_reconnect_sec=args.left_reconnect_sec
    )

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    def handle_signal():
        log("INFO", "Received termination signal, shutting down...")
        if args.scan_test:
            sys.exit(0)
        else:
            worker.running = False
            if worker.stop_task is None or worker.stop_task.done():
                worker.stop_task = loop.create_task(worker.stop())

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, handle_signal)
        except (NotImplementedError, RuntimeError):
            pass

    try:
        if args.scan_test:
            loop.run_until_complete(run_scan_test(duration=args.duration))
        else:
            loop.run_until_complete(worker.run())
    except KeyboardInterrupt:
        if not args.scan_test:
            loop.run_until_complete(worker.stop())
    finally:
        if not args.scan_test and worker.stop_task is not None and not worker.stop_task.done():
            try:
                loop.run_until_complete(worker.stop_task)
            except Exception:
                pass
        loop.close()


if __name__ == "__main__":
    main()
