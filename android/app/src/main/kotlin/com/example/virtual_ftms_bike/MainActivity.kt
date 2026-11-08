package com.example.virtual_ftms_bike

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FlutterActivity() {
    private val channelName = "virtual_ftms_bike/ble"
    private val ftms = uuid("1826")
    private val indoor = uuid("2AD2")
    private val feature = uuid("2ACC")
    private val control = uuid("2AD9")
    private val status = uuid("2ADA")
    private val machineStatus = uuid("2AD3")
    private val cccd = uuid("2902")
    private val handler = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private fun log(message: String) {
        synchronized(logLines) {
            logLines.addLast("${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}  $message")
            while (logLines.size > 250) logLines.removeFirst()
        }
        android.util.Log.i("VirtualFTMS", message)
    }
    private fun logs(): List<String> = synchronized(logLines) { logLines.toList() }
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisingCallback: AdvertiseCallback? = null
    private val subscribers = mutableMapOf<String, MutableSet<UUID>>()
    private val devices = mutableMapOf<String, BluetoothDevice>()
    private var speed = 20.0
    private var cadence = 60.0
    private var power = 100
    private var startedAt = 0L
    private var elapsedBefore = 0L
    private var running = false
    private var indoorCharacteristic: BluetoothGattCharacteristic? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null

    private fun uuid(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    private fun characteristic(id: UUID, properties: Int, permissions: Int = BluetoothGattCharacteristic.PERMISSION_READ): BluetoothGattCharacteristic =
        BluetoothGattCharacteristic(id, properties, permissions)
    private fun notifyCharacteristic(id: UUID, props: Int = BluetoothGattCharacteristic.PROPERTY_NOTIFY): BluetoothGattCharacteristic =
        characteristic(id, props, if (id == control) BluetoothGattCharacteristic.PERMISSION_WRITE else BluetoothGattCharacteristic.PERMISSION_READ).apply {
            addDescriptor(BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "start" -> {
                    speed = (call.argument<Number>("speed") ?: 20).toDouble()
                    cadence = (call.argument<Number>("cadence") ?: 60).toDouble()
                    power = (call.argument<Number>("power") ?: 100).toInt()
                    try { start(result) } catch (e: Exception) { result.error("BLE", e.message, null) }
                }
                "stop" -> { stop(); result.success(null) }
                "logs" -> result.success(logs())
                "clearLogs" -> { synchronized(logLines) { logLines.clear() }; result.success(null) }
                "update" -> {
                    speed = (call.argument<Number>("speed") ?: 20).toDouble()
                    cadence = (call.argument<Number>("cadence") ?: 60).toDouble()
                    power = (call.argument<Number>("power") ?: 100).toInt()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun start(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT >= 31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT), 101)
            result.error("PERMISSION", "Autoriza Bluetooth e volta a carregar em Iniciar.", null)
            return
        }
        stop()
        log("START requested")
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) { result.error("BLUETOOTH", "Ativa o Bluetooth.", null); return }
        if (!adapter.isMultipleAdvertisementSupported) { result.error("UNSUPPORTED", "O telemóvel não suporta BLE advertising.", null); return }
        advertiser = adapter.bluetoothLeAdvertiser ?: run { result.error("UNSUPPORTED", "BLE advertiser indisponível.", null); return }
        server = manager.openGattServer(this, callback) ?: run { result.error("GATT", "Não foi possível criar o servidor BLE.", null); return }
        val service = BluetoothGattService(ftms, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val feat = characteristic(feature, BluetoothGattCharacteristic.PROPERTY_READ)
        // 8-byte Fitness Machine Feature: cadence and power measurement, no target-setting features.
        feat.value = byteArrayOf(0x02, 0x40, 0, 0, 0, 0, 0, 0)
        service.addCharacteristic(feat)
        indoorCharacteristic = notifyCharacteristic(indoor)
        service.addCharacteristic(indoorCharacteristic)
        controlCharacteristic = notifyCharacteristic(control, BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_WRITE)
        service.addCharacteristic(controlCharacteristic)
        statusCharacteristic = notifyCharacteristic(status)
        service.addCharacteristic(statusCharacteristic)
        service.addCharacteristic(characteristic(machineStatus, BluetoothGattCharacteristic.PROPERTY_READ).apply { value = byteArrayOf(0x02) })
        if (!server!!.addService(service)) { stop(); result.error("GATT", "Falha ao adicionar serviço FTMS.", null); return }
        log("GATT addService requested")
        startedAt = System.currentTimeMillis()
        elapsedBefore = 0
        result.success("A preparar serviço FTMS")
    }

    private fun beginAdvertising() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return
        val adv = advertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(true).build()
        val data = AdvertiseData.Builder().addServiceUuid(android.os.ParcelUuid(ftms)).build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        try { adapter.name = "Virtual FTMS Bike" } catch (e: Exception) { log("Name: ${e.message}") }
        advertisingCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                log("ADVERTISING started")
                running = true
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
            override fun onStartFailure(errorCode: Int) {
                log("ADVERTISING FAILED code=$errorCode")
                running = false
            }
        }
        try {
            adv.startAdvertising(settings, data, scan, advertisingCallback!!)
            log("ADVERTISING requested")
        } catch (e: Exception) { log("ADVERTISING exception ${e.message}") }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val bytes = indoorData()
            indoorCharacteristic?.let { c -> notifyAll(c, bytes, false) }
            handler.postDelayed(this, 1000)
        }
    }

    private fun indoorData(): ByteArray {
        // Indoor Bike Data: Flags bits 2, 6, 9 -> cadence, instantaneous power, elapsed time.
        // Speed is present when bit 0 is zero.
        val flags = (1 shl 2) or (1 shl 6) or (1 shl 11)
        val seconds = ((System.currentTimeMillis() - startedAt + elapsedBefore) / 1000).toInt().coerceIn(0, 65535)
        val b = ByteArray(10)
        put16(b, 0, flags)
        put16(b, 2, (speed * 100).toInt().coerceIn(0, 65535))
        put16(b, 4, (cadence * 2).toInt().coerceIn(0, 65535))
        put16(b, 6, power.coerceIn(0, 32767))
        put16(b, 8, seconds)
        return b
    }
    private fun put16(b: ByteArray, pos: Int, v: Int) { b[pos] = v.toByte(); b[pos + 1] = (v shr 8).toByte() }

    private fun notifyAll(c: BluetoothGattCharacteristic, value: ByteArray, confirm: Boolean) {
        val s = server ?: return
        c.value = value
        for ((address, enabled) in subscribers.toMap()) {
            if (!enabled.contains(c.uuid)) continue
            val device = devices[address] ?: continue
            try {
                if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(device, c, confirm, value)
                else @Suppress("DEPRECATION") s.notifyCharacteristicChanged(device, c, confirm)
            } catch (_: Exception) { }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            log("SERVICE ADDED status=$status uuid=${service.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) handler.post { beginAdvertising() }
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("NOTIFY ERROR ${device.address} status=$status")
        }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            log("CONNECTION ${device.address} status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) devices[device.address] = device
            else { devices.remove(device.address); subscribers.remove(device.address); log("DISCONNECTED; advertising remains active") }
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            log("READ ${device.address} characteristic=${c.uuid} offset=$offset")
            val value = when (c.uuid) {
                feature -> byteArrayOf(0x02, 0x40, 0, 0, 0, 0, 0, 0)
                machineStatus -> byteArrayOf(0x02)
                else -> byteArrayOf()
            }
            if (offset > value.size) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            else server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            log("DESCRIPTOR READ ${device.address} char=${d.characteristic.uuid}")
            val active = subscribers[device.address]?.contains(d.characteristic.uuid) == true
            val value = if (!active) byteArrayOf(0, 0) else if (d.characteristic.uuid == control) byteArrayOf(2, 0) else byteArrayOf(1, 0)
            if (offset > value.size) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            else server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
                                               preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val ok = !preparedWrite && offset == 0 && value.size == 2 && (value.contentEquals(byteArrayOf(0,0)) ||
                value.contentEquals(byteArrayOf(1,0)) || value.contentEquals(byteArrayOf(2,0)))
            log("DESCRIPTOR WRITE ${device.address} char=${d.characteristic.uuid} value=${value.joinToString("") { "%02X".format(it.toInt() and 255) }} ok=$ok")
            if (ok) {
                val set = subscribers.getOrPut(device.address) { mutableSetOf() }
                if (value[0].toInt() == 0) set.remove(d.characteristic.uuid) else set.add(d.characteristic.uuid)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            log("CHAR WRITE ${device.address} char=${c.uuid} bytes=${value.joinToString("") { "%02X".format(it.toInt() and 255) }}")
            val valid = c.uuid == control && !preparedWrite && offset == 0 && value.isNotEmpty()
            if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            if (valid) {
                val op = value[0].toInt() and 0xff
                // FTMS response: 0x80, request opcode, result (0x01 success / 0x02 unsupported).
                val supported = op == 0x00 || op == 0x07 || op == 0x08
                if (op == 0x07) { startedAt = System.currentTimeMillis(); elapsedBefore = 0 }
                val response = byteArrayOf(0x80.toByte(), op.toByte(), if (supported) 0x01 else 0x02)
                log("CONTROL op=$op supported=$supported response=${response.joinToString("") { "%02X".format(it.toInt() and 255) }}")
                // Schedule after ATT write response, for clients requiring that ordering.
                handler.postDelayed({ controlCharacteristic?.let { notifyAll(it, response, true) } }, 100)
            }
        }
    }

    private fun stop() {
        log("STOP requested")
        running = false
        handler.removeCallbacks(tick)
        try { advertisingCallback?.let { advertiser?.stopAdvertising(it) } } catch (_: Exception) { }
        advertisingCallback = null
        try { server?.clearServices(); server?.close() } catch (_: Exception) { }
        server = null
        subscribers.clear(); devices.clear()
    }
    override fun onDestroy() { stop(); super.onDestroy() }
}
