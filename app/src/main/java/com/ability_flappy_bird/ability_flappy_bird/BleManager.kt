package com.ability_flappy_bird.ability_flappy_bird

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

// ── Enums / data ──────────────────────────────────────────────────────────────

enum class BleConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED }
data class EmgSettings(
    val usePositive: Boolean,
    val useNegative: Boolean,
    val thresholdPositive: Float,
    val thresholdNegative: Float,
    val dualSiteTraining: Boolean = false
)

data class BleDeviceItem(
    val name: String,
    val address: String,
    val rssi: Int,
    val isAbilityHand: Boolean = name.contains("ABILITY HAND", ignoreCase = true)
)

/**
 * One parsed EMG frame from the ABILITY HAND P2 plotting stream.
 *
 * Binary packet layout (11 bytes minimum):
 *   [0..2]  ASCII "DIR"  (0x44 0x49 0x52)
 *   [3..6]  float32 LE   ch1 (EMG channel 1)
 *   [7..10] float32 LE   ch2 (EMG channel 2)
 */
data class EmgFrame(
    val ch1: Float,
    val ch2: Float,
    val timestamp: Long = System.currentTimeMillis()
)

// ── BLE UUIDs ─────────────────────────────────────────────────────────────────
//
// Primary: Nordic UART Service – the de-facto standard for UART-over-BLE.
// Fallback: any service with a writable + a notifiable characteristic.

private val UART_SERVICE  = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
private val UART_WRITE    = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
private val UART_NOTIFY   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
private val CCCD_UUID     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private val P2_BYTES = "P2\n".toByteArray(Charsets.US_ASCII)
private val RD_BYTES = "RD\n".toByteArray(Charsets.US_ASCII)   // read positive threshold
private val RE_BYTES = "RE\n".toByteArray(Charsets.US_ASCII)   // read negative threshold

private const val TAG = "BleManager"

// ── BleManager ────────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ── Public state ──────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(BleConnectionState.IDLE)
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<BleDeviceItem>>(emptyList())
    val devices: StateFlow<List<BleDeviceItem>> = _devices.asStateFlow()

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    /** Latest parsed EMG frame from the P2 stream. Null until the first valid packet is received. */
    private val _emgFrame = MutableStateFlow<EmgFrame?>(null)
    val emgFrame: StateFlow<EmgFrame?> = _emgFrame.asStateFlow()

    /** Threshold for the positive EMG channel (RD response). Null until read. */
    private val _thresholdPositive = MutableStateFlow<Float?>(null)
    val thresholdPositive: StateFlow<Float?> = _thresholdPositive.asStateFlow()

    /** Threshold for the negative EMG channel (RE response). Null until read. */
    private val _thresholdNegative = MutableStateFlow<Float?>(null)
    val thresholdNegative: StateFlow<Float?> = _thresholdNegative.asStateFlow()

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ── Internal GATT state ───────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanCb: ScanCallback? = null
    private var p2Sent        = false   // true after P2 is written to the char
    private var p2AckReceived = false   // true after the first valid DIR frame arrives
    private var retryJob: Job? = null   // 1-second retry timer

    private enum class PendingRead { NONE, POSITIVE, NEGATIVE }
    private var pendingRead = PendingRead.NONE

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun startScan() {
        _devices.value = emptyList()
        _state.value = BleConnectionState.SCANNING

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name?.takeIf { it.isNotBlank() } ?: return
                val item = BleDeviceItem(name, result.device.address, result.rssi)
                val list = _devices.value.toMutableList()
                val idx  = list.indexOfFirst { it.address == item.address }
                if (idx >= 0) list[idx] = item else list.add(item)
                _devices.value = list.sortedWith(
                    compareByDescending<BleDeviceItem> { it.isAbilityHand }
                        .thenByDescending { it.rssi }
                )
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                _state.value = BleConnectionState.IDLE
            }
        }
        scanCb = cb
        adapter?.bluetoothLeScanner?.startScan(cb)
    }

    fun stopScan() {
        scanCb?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCb = null
        if (_state.value == BleConnectionState.SCANNING) _state.value = BleConnectionState.IDLE
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(address: String) {
        stopScan()
        _state.value = BleConnectionState.CONNECTING

        val device = try { adapter?.getRemoteDevice(address) } catch (e: Exception) { null }
            ?: run { _state.value = BleConnectionState.IDLE; return }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    // ── GATT callback ─────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        // 1. Connection established → discover services
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected, discovering services…")
                    _state.value = BleConnectionState.CONNECTED
                    _connectedName.value = g.device.name ?: g.device.address
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected")
                    retryJob?.cancel()
                    p2Sent = false
                    p2AckReceived = false
                    pendingRead = PendingRead.NONE
                    _state.value = BleConnectionState.IDLE
                    _connectedName.value = null
                    _emgFrame.value = null
                    _thresholdPositive.value = null
                    _thresholdNegative.value = null
                    writeChar = null
                    g.close()
                    gatt = null
                }
            }
        }

        // 2. Services discovered → locate chars, enable notifications
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status"); return
            }

            val (wChar, nChar) = resolveCharacteristics(g)
            if (wChar == null || nChar == null) {
                Log.e(TAG, "Could not resolve write/notify characteristics"); return
            }

            writeChar = wChar
            Log.i(TAG, "Write char: ${wChar.uuid}  Notify char: ${nChar.uuid}")

            // Enable notifications on the device side
            g.setCharacteristicNotification(nChar, true)

            // Write ENABLE_NOTIFICATION_VALUE to the CCCD descriptor
            val cccd = nChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                Log.w(TAG, "CCCD not found – starting threshold + P2 sequence")
                scope.launch { this@BleManager.readThresholdsThenP2(g) }
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        // 3. Notifications subscribed → read thresholds, then start P2 stream
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Notifications enabled – reading thresholds before P2")
                scope.launch { this@BleManager.readThresholdsThenP2(g) }
            } else {
                Log.e(TAG, "Descriptor write failed: $status")
            }
        }

        // 3b. Log P2 write result (only fires for WRITE_TYPE_DEFAULT)
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "P2 write ACK received from device")
            } else {
                Log.e(TAG, "P2 write NACK: status=$status")
            }
        }

        // 4a. Receive plotting data (API 33+)
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = handleIncoming(value)

        // 4b. Receive plotting data (< API 33, kept for compatibility)
        @Suppress("DEPRECATION")
        @Deprecated("Use onCharacteristicChanged(gatt, char, value) on API 33+")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleIncoming(characteristic.value ?: return)
            }
        }
    }

    // ── Generic write helper ──────────────────────────────────────────────────

    private fun sendCommand(g: BluetoothGatt, data: ByteArray) {
        val char = writeChar ?: run { Log.e(TAG, "sendCommand: no write char"); return }
        val writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
    }

    // ── Threshold read sequence (RD → RE) then start P2 stream ───────────────

    private suspend fun readThresholdsThenP2(g: BluetoothGatt) {
        _thresholdPositive.value = null
        _thresholdNegative.value = null

        // RD → positive threshold
        pendingRead = PendingRead.POSITIVE
        Log.i(TAG, ">>> Sending RD (positive threshold)")
        sendCommand(g, RD_BYTES)
        withTimeoutOrNull(2_000) { _thresholdPositive.first { it != null } }
            ?: Log.w(TAG, "RD: no response within 2 s")

        // RE → negative threshold
        pendingRead = PendingRead.NEGATIVE
        Log.i(TAG, ">>> Sending RE (negative threshold)")
        sendCommand(g, RE_BYTES)
        withTimeoutOrNull(2_000) { _thresholdNegative.first { it != null } }
            ?: Log.w(TAG, "RE: no response within 2 s")

        pendingRead = PendingRead.NONE
        sendP2(g)
    }

    // ── P2 stream command ─────────────────────────────────────────────────────

    private fun sendP2(g: BluetoothGatt) {
        if (p2Sent) return
        Log.i(TAG, ">>> Sending P2 (start EMG stream)")
        sendCommand(g, P2_BYTES)
        p2Sent = true

        // Retry every 1 s until the first DIR frame confirms the stream is live
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(1_000)
            if (!p2AckReceived) {
                Log.w(TAG, "No DIR response after 1 s – resending P2")
                p2Sent = false
                sendP2(g)
            }
        }
    }

    // ── Incoming data handler ─────────────────────────────────────────────────

    private fun handleIncoming(bytes: ByteArray) {
        when (pendingRead) {
            PendingRead.POSITIVE -> {
                val v = parseThreshold(bytes)
                if (v != null) {
                    _thresholdPositive.value = v
                    Log.i(TAG, "<<< Positive threshold (RD) = $v")
                } else {
                    Log.w(TAG, "RD response unreadable (${bytes.size} bytes)")
                }
            }
            PendingRead.NEGATIVE -> {
                val v = parseThreshold(bytes)
                if (v != null) {
                    _thresholdNegative.value = v
                    Log.i(TAG, "<<< Negative threshold (RE) = $v")
                } else {
                    Log.w(TAG, "RE response unreadable (${bytes.size} bytes)")
                }
            }
            PendingRead.NONE -> {
                val frame = parseEmgPacket(bytes)
                if (frame != null) {
                    if (!p2AckReceived) {
                        p2AckReceived = true
                        retryJob?.cancel()
                        Log.i(TAG, "<<< First DIR frame – stream active")
                    }
                    _emgFrame.value = frame
                    Log.v(TAG, "EMG ch1=${frame.ch1}  ch2=${frame.ch2}")
                } else {
                    Log.w(TAG, "Dropped packet (${bytes.size} bytes): bad header or too short")
                }
            }
        }
    }

    // ── Threshold response parser ─────────────────────────────────────────────

    private fun parseThreshold(bytes: ByteArray): Float? {
        // Packet layout mirrors DIR: 3-byte header + 4-byte LE float at offset 3.
        // (firmware: bleTxConfigData.dcElectrodeXThreshold[i + 3] = tmp.d[i])
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val v = when {
            bytes.size >= 7 -> try { buf.getFloat(3) } catch (e: Exception) { null }
            bytes.size >= 4 -> try { buf.getFloat(0) } catch (e: Exception) { null }
            else -> null
        } ?: return null
        return if (v in 0f..5f) v else null   // sanity-check: valid range is 0–5
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    /**
     * Parses one binary P2 notification from the ABILITY HAND.
     *
     * Packet layout:
     *   [0..2]  ASCII "DIR"  (0x44 0x49 0x52)  – magic header
     *   [3..6]  float32 little-endian           – EMG ch1
     *   [7..10] float32 little-endian           – EMG ch2
     *
     * Returns null if the packet is too short or the header doesn't match.
     */
    private fun parseEmgPacket(value: ByteArray): EmgFrame? {
        if (value.size < 11) return null
        if (value[0] != 0x44.toByte() ||   // 'D'
            value[1] != 0x49.toByte() ||   // 'I'
            value[2] != 0x52.toByte()      // 'R'
        ) return null

        val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        return EmgFrame(
            ch1 = buf.getFloat(3),
            ch2 = buf.getFloat(7)
        )
    }

    // ── Characteristic resolution ─────────────────────────────────────────────

    /**
     * Tries the Nordic UART service first, then falls back to scanning all
     * services for a writable + a notifiable characteristic.
     */
    private fun resolveCharacteristics(
        g: BluetoothGatt
    ): Pair<BluetoothGattCharacteristic?, BluetoothGattCharacteristic?> {
        val uartService = g.getService(UART_SERVICE)
        if (uartService != null) {
            return uartService.getCharacteristic(UART_WRITE) to
                   uartService.getCharacteristic(UART_NOTIFY)
        }

        Log.w(TAG, "UART service not found – scanning all services for write/notify chars")
        var writeChar: BluetoothGattCharacteristic? = null
        var notifyChar: BluetoothGattCharacteristic? = null

        for (service in g.services) {
            for (char in service.characteristics) {
                val props = char.properties
                if (writeChar == null &&
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                     props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                ) writeChar = char

                if (notifyChar == null &&
                    props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                ) notifyChar = char
            }
            if (writeChar != null && notifyChar != null) break
        }
        return writeChar to notifyChar
    }

    // ── Disconnect / cleanup ──────────────────────────────────────────────────

    fun disconnect() {
        retryJob?.cancel()
        p2Sent = false
        p2AckReceived = false
        pendingRead = PendingRead.NONE
        _thresholdPositive.value = null
        _thresholdNegative.value = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        _state.value = BleConnectionState.IDLE
        _connectedName.value = null
        _emgFrame.value = null
    }

    fun cleanup() {
        stopScan()
        disconnect()
    }
}
