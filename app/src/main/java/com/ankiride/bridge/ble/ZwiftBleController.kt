package com.ankiride.bridge.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.ankiride.bridge.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class ZwiftBleController(
    private val context: Context,
    private val scope: CoroutineScope
) : ClickBleController {

    companion object {
        val ZWIFT_FC82_SERVICE_UUID: UUID = UUID.fromString("0000fc82-0000-1000-8000-00805f9b34fb")
        val ZWIFT_LEGACY_SERVICE_UUID: UUID = UUID.fromString("00000001-19ca-4651-86e5-fa29dcdd09d1")

        val MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00000002-19ca-4651-86e5-fa29dcdd09d1")
        val CONTROL_POINT_CHAR_UUID: UUID = UUID.fromString("00000003-19ca-4651-86e5-fa29dcdd09d1")
        val COMMAND_RESPONSE_CHAR_UUID: UUID = UUID.fromString("00000004-19ca-4651-86e5-fa29dcdd09d1")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val ZWIFT_MANUFACTURER_ID = 0x094A
        const val DEVICE_ID_RIGHT_POD: Byte = 0x07
        const val DEVICE_ID_LEFT_POD: Byte = 0x08
        const val DEVICE_ID_CLICK: Byte = 0x09

        val RIDE_ON_HANDSHAKE = byteArrayOf(0x52, 0x69, 0x64, 0x65, 0x4F, 0x6E) // "RideOn"

        private val _discoveredAddresses = MutableStateFlow<List<String>>(emptyList())
        val discoveredAddresses: StateFlow<List<String>> = _discoveredAddresses.asStateFlow()

        private val _lastRightMac = MutableStateFlow<String?>(null)
        val lastRightMac: StateFlow<String?> = _lastRightMac.asStateFlow()

        private val _lastLeftMac = MutableStateFlow<String?>(null)
        val lastLeftMac: StateFlow<String?> = _lastLeftMac.asStateFlow()
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState

    private val _gestureEvents = MutableSharedFlow<ButtonGestureEvent>(extraBufferCapacity = 64)
    override val gestureEvents: SharedFlow<ButtonGestureEvent> = _gestureEvents

    private val gestureEngine = ZwiftGestureEngine(
        isRepeatEnabled = { button -> PresetStore.isRepeatEnabled(currentConfig, button) }
    ) { event ->
        DiagnosticLogger.info(
            LogCategory.BLE,
            "Gesture detected: button=${event.button.name} (${event.button.displayName}), type=${event.type.name}"
        )
        scope.launch { _gestureEvents.emit(event) }
    }

    private val activeGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val connectedPods = ConcurrentHashMap<String, Boolean>() // address -> isLeftPod
    private val podActiveMasks = ConcurrentHashMap<String, Int>() // address -> last activeMask
    private val lastLoggedPayloadHex = ConcurrentHashMap<String, String>()
    private var leftPodWatchdogJob: Job? = null
    private var tickerJob: Job? = null
    private var currentConfig = AppConfiguration()
    private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val address = device.address ?: return
            if (!_discoveredAddresses.value.contains(address)) {
                _discoveredAddresses.value = (_discoveredAddresses.value + address).takeLast(20)
            }
            if (activeGatts.containsKey(address)) return

            // In 1-Pod Mode, once 1 pod is connected (since Click v2 mirrors both pods over 1 link), ignore second pod
            if ((currentConfig.podAssignment == PodAssignment.RIGHT_ANKI_ONLY ||
                    (currentConfig.podAssignment == PodAssignment.LEGACY_SHARED && currentConfig.rightPodOnlyNoZwift)) && activeGatts.isNotEmpty()) {
                return
            }

            val mfgData = result.scanRecord?.getManufacturerSpecificData(ZWIFT_MANUFACTURER_ID)
            val podType = if (mfgData != null && mfgData.isNotEmpty()) mfgData[0] else null
            val side = PodSelectionPolicy.sideFor(address, podType, currentConfig) ?: return
            val isLeftPod = side == PodSelectionPolicy.Side.LEFT
            val podLabel = when (podType) {
                DEVICE_ID_RIGHT_POD -> "RightPod(0x07)"
                DEVICE_ID_LEFT_POD -> "LeftPod(0x08)"
                DEVICE_ID_CLICK -> "Click(0x09)"
                null -> "ZwiftPod"
                else -> "Pod(0x%02X)".format(podType)
            }

            DiagnosticLogger.info(
                LogCategory.BLE,
                "Discovered Zwift controller: name=${device.name ?: "Zwift Click"}, mac=$address, pod=$podLabel, rssi=${result.rssi}dBm"
            )
            connectToDevice(device, isLeftPod = isLeftPod)
        }

        override fun onScanFailed(errorCode: Int) {
            DiagnosticLogger.error(LogCategory.BLE, "BLE Scan failed with errorCode=$errorCode")
            _connectionState.value = BleConnectionState.ERROR
        }
    }

    fun updateConfiguration(config: AppConfiguration) {
        val ownershipChanged = currentConfig.podAssignment != config.podAssignment ||
            currentConfig.rightPodOnlyNoZwift != config.rightPodOnlyNoZwift ||
            currentConfig.pinnedRightPodMac != config.pinnedRightPodMac ||
            currentConfig.pinnedLeftPodMac != config.pinnedLeftPodMac
        currentConfig = config
        if (ownershipChanged && isScanning) {
            disconnect()
            startScanAndConnect(config)
        }
    }

    override fun startScanAndConnect(config: AppConfiguration) {
        currentConfig = config
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            DiagnosticLogger.error(LogCategory.BLE, "Bluetooth adapter is disabled or unavailable")
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            DiagnosticLogger.error(LogCategory.BLE, "BluetoothLeScanner is null")
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(ZWIFT_FC82_SERVICE_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(ZWIFT_LEGACY_SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        DiagnosticLogger.info(
            LogCategory.BLE,
            "Starting BLE scan (1-Pod Default=${config.rightPodOnlyNoZwift})"
        )
        _connectionState.value = BleConnectionState.SCANNING
        isScanning = true
        startTicker()
        scanner.startScan(filters, settings, scanCallback)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(25L)
                if (podActiveMasks.values.any { it != 0 }) {
                    val combinedMask = podActiveMasks.values.fold(0) { acc, m -> acc or m }
                    val combinedMap = EnumMap<ClickButton, Boolean>(ClickButton::class.java)
                    ClickButton.entries.forEach { button ->
                        combinedMap[button] = (combinedMask and button.bitmask) != 0
                    }
                    gestureEngine.processButtonStates(combinedMap, System.currentTimeMillis())
                }
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice, isLeftPod: Boolean) {
        _connectionState.value = BleConnectionState.CONNECTING
        DiagnosticLogger.info(LogCategory.BLE, "Connecting GATT to ${device.address}...")
        val callback = createGattCallback(device, isLeftPod)
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
        if (gatt != null) {
            activeGatts[device.address] = gatt
        }
    }

    private fun updateConnectionState() {
        val count = connectedPods.size
        val nextState = when {
            count >= 2 -> BleConnectionState.CONNECTED_BOTH_PODS
            count == 1 -> BleConnectionState.CONNECTED_RIGHT_ONLY
            activeGatts.isEmpty() -> BleConnectionState.DISCONNECTED
            else -> BleConnectionState.CONNECTING
        }
        _connectionState.value = nextState
    }

    private fun createGattCallback(device: BluetoothDevice, isLeftPod: Boolean) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && gatt != null) {
                DiagnosticLogger.info(LogCategory.BLE, "GATT connected to ${device.address}. Discovering services...")
                _connectionState.value = BleConnectionState.HANDSHAKING
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                DiagnosticLogger.warn(LogCategory.BLE, "GATT disconnected from ${device.address} (status=$status)")
                gatt?.close()
                if (gatt != null && !activeGatts.remove(device.address, gatt)) return
                connectedPods.remove(device.address)
                podActiveMasks.remove(device.address)
                gestureEngine.reset()
                updateConnectionState()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                _connectionState.value = BleConnectionState.ERROR
                return
            }
            val service = gatt.getService(ZWIFT_FC82_SERVICE_UUID)
                ?: gatt.getService(ZWIFT_LEGACY_SERVICE_UUID)
                ?: run {
                    _connectionState.value = BleConnectionState.ERROR
                    return
                }

            val notifyChar = service.getCharacteristic(MEASUREMENT_CHAR_UUID) ?: return
            gatt.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                writeRideOnHandshake(gatt, service)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (gatt == null) return
            val service = gatt.getService(ZWIFT_FC82_SERVICE_UUID)
                ?: gatt.getService(ZWIFT_LEGACY_SERVICE_UUID)
                ?: return
            writeRideOnHandshake(gatt, service)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (characteristic?.uuid == CONTROL_POINT_CHAR_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                connectedPods[device.address] = isLeftPod
                if (isLeftPod) _lastLeftMac.value = device.address
                else _lastRightMac.value = device.address
                DiagnosticLogger.info(LogCategory.BLE, "'RideOn' handshake complete on ${device.address}")
                updateConnectionState()
                if (isLeftPod && !currentConfig.rightPodOnlyNoZwift && gatt != null) {
                    scheduleLeftPodWatchdog(device)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleMeasurementNotification(device.address, isLeftPod, characteristic.uuid, value)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            if (characteristic != null) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleMeasurementNotification(device.address, isLeftPod, characteristic.uuid, value)
            }
        }
    }

    private fun handleMeasurementNotification(address: String, isLeftPod: Boolean, charUuid: UUID, value: ByteArray) {
        if (charUuid != MEASUREMENT_CHAR_UUID || value.isEmpty()) return
        val msgId = value[0]
        // Ignore periodic idle keep-alive heartbeats (0x15, 0x19)
        if (msgId == 0x15.toByte() || msgId == 0x19.toByte()) return

        val hex = value.joinToString(" ") { "%02X".format(it) }
        var parsedMask = ZwiftPacketParser.parseActiveButtonMask(value)
        if (parsedMask != null && currentConfig.podAssignment != PodAssignment.LEGACY_SHARED && value[0] == ZwiftPacketParser.MSG_CLICK_V1_SHIFT) {
            parsedMask = when {
                isLeftPod && parsedMask == ClickButton.SHIFT_UP_RIGHT.bitmask -> ClickButton.SHIFT_UP_LEFT.bitmask
                !isLeftPod && parsedMask == ClickButton.SHIFT_DOWN_LEFT.bitmask -> ClickButton.SHIFT_DOWN_RIGHT.bitmask
                else -> parsedMask
            }
        }
        val mask = parsedMask?.let {
            PodSelectionPolicy.filterMask(it, if (isLeftPod) PodSelectionPolicy.Side.LEFT else PodSelectionPolicy.Side.RIGHT, currentConfig.podAssignment)
        }

        if (mask == null) {
            DiagnosticLogger.info(LogCategory.PACKET, "Non-0x23 packet from $address (msgId=0x${"%02X".format(msgId)}): [$hex]")
            return
        }

        val prevMask = podActiveMasks.put(address, mask) ?: 0
        if (mask != 0 || prevMask != 0 || lastLoggedPayloadHex.put(address, hex) != hex) {
            if (mask != 0) {
                val matchedNames = ClickButton.entries.filter { (mask and it.bitmask) != 0 }.joinToString(",") { it.name }
                DiagnosticLogger.info(
                    LogCategory.PACKET,
                    "0x23 packet from $address: [$hex] -> activeMask=0x${"%05X".format(mask)} (${matchedNames.ifEmpty { "UNMAPPED_BIT" }})"
                )
            }
        }

        // Combine active button masks across connected pods so mirrored pods don't cancel or double-trigger
        val combinedMask = podActiveMasks.values.fold(0) { acc, m -> acc or m }
        val combinedMap = EnumMap<ClickButton, Boolean>(ClickButton::class.java)
        ClickButton.entries.forEach { button ->
            combinedMap[button] = (combinedMask and button.bitmask) != 0
        }
        gestureEngine.processButtonStates(combinedMap)
    }

    private fun writeRideOnHandshake(gatt: BluetoothGatt, service: BluetoothGattService) {
        val controlChar = service.getCharacteristic(CONTROL_POINT_CHAR_UUID) ?: return
        val writeType = if ((controlChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 &&
            (controlChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(controlChar, RIDE_ON_HANDSHAKE, writeType)
        } else {
            @Suppress("DEPRECATION")
            controlChar.writeType = writeType
            @Suppress("DEPRECATION")
            controlChar.value = RIDE_ON_HANDSHAKE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(controlChar)
        }
    }

    private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun scheduleLeftPodWatchdog(leftDevice: BluetoothDevice) {
        leftPodWatchdogJob?.cancel()
        leftPodWatchdogJob = scope.launch {
            delay(currentConfig.leftPodAutoReconnectSec * 1000L)
            val existing = activeGatts.remove(leftDevice.address)
            connectedPods.remove(leftDevice.address)
            podActiveMasks.remove(leftDevice.address)
            existing?.disconnect()
            existing?.close()
            delay(150L)
            connectToDevice(leftDevice, isLeftPod = true)
        }
    }

    override fun disconnect() {
        leftPodWatchdogJob?.cancel()
        tickerJob?.cancel()
        tickerJob = null
        if (isScanning && bluetoothAdapter?.isEnabled == true) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        }
        activeGatts.values.forEach {
            it.disconnect()
            it.close()
        }
        activeGatts.clear()
        connectedPods.clear()
        podActiveMasks.clear()
        gestureEngine.reset()
        _connectionState.value = BleConnectionState.DISCONNECTED
    }
}
