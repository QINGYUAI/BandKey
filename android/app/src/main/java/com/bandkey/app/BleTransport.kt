package com.bandkey.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

@SuppressLint("MissingPermission")
class BleTransport(context: Context) : Transport {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val TARGET_MTU = 517
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = manager?.adapter

    private val stateLock = Any()
    private var generation = 0L

    @Volatile
    private var gatt: BluetoothGatt? = null
    @Volatile
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var listener: ((String) -> Unit)? = null

    @Volatile
    private var connected = CompletableDeferred<Unit>()
    @Volatile
    private var ready = CompletableDeferred<Unit>()
    private val writeDone = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val writeMutex = Mutex()
    private val receiveBuffer = java.io.ByteArrayOutputStream()
    @Volatile
    private var mtu = 23

    override fun setListener(listener: (String) -> Unit) {
        this.listener = listener
    }

    override suspend fun open() {
        if (gatt != null) {
            return
        }

        val bluetoothAdapter = adapter ?: throw TransportException("设备不支持蓝牙")
        if (!bluetoothAdapter.isEnabled) {
            throw TransportException("蓝牙未开启")
        }

        val requestToken = synchronized(stateLock) { generation }
        val device = scanForBandKey(bluetoothAdapter)
        val token = synchronized(stateLock) {
            if (generation != requestToken) {
                throw TransportException("连接已取消")
            }
            ++generation
        }
        connected = CompletableDeferred()
        ready = CompletableDeferred()
        synchronized(receiveBuffer) { receiveBuffer.reset() }
        rxCharacteristic = null
        mtu = 23

        val callback = createCallback(token)
        val newGatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw TransportException("发起 BLE 连接失败")
        if (!isCurrent(token)) {
            runCatching { newGatt.close() }
            throw TransportException("连接已取消")
        }
        gatt = newGatt

        try {
            withTimeout(20_000) { connected.await() }
            withTimeout(20_000) { ready.await() }
        } catch (e: Exception) {
            if (gatt === newGatt) {
                gatt = null
            }
            rxCharacteristic = null
            runCatching { newGatt.disconnect() }
            runCatching { newGatt.close() }
            throw e
        }
    }

    override suspend fun send(text: String) {
        writeMutex.withLock {
            val activeGatt = gatt ?: throw TransportException("未连接")
            val characteristic = rxCharacteristic ?: throw TransportException("未发现 RX 特征")
            val payload = (text + "\n").toByteArray(Charsets.UTF_8)
            val chunkSize = (mtu - 3).coerceIn(20, 512)

            var offset = 0
            while (offset < payload.size) {
                val length = minOf(chunkSize, payload.size - offset)
                val chunk = payload.copyOfRange(offset, offset + length)
                writeChunk(activeGatt, characteristic, chunk)
                offset += length
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun writeChunk(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray
    ) {
        val deferred = CompletableDeferred<Boolean>()
        writeDone.set(deferred)
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = chunk

        if (!activeGatt.writeCharacteristic(characteristic)) {
            writeDone.compareAndSet(deferred, null)
            throw TransportException("BLE 写入未能启动")
        }

        try {
            val ok = withTimeout(10_000) { deferred.await() }
            if (!ok) {
                throw TransportException("BLE 写入被拒绝")
            }
        } finally {
            writeDone.compareAndSet(deferred, null)
        }
    }

    override fun close() {
        synchronized(stateLock) { generation++ }
        if (!connected.isCompleted) {
            connected.completeExceptionally(TransportException("连接已关闭"))
        }
        if (!ready.isCompleted) {
            ready.completeExceptionally(TransportException("连接已关闭"))
        }
        writeDone.getAndSet(null)?.completeExceptionally(TransportException("连接已关闭"))
        synchronized(receiveBuffer) { receiveBuffer.reset() }

        val activeGatt = gatt
        gatt = null
        rxCharacteristic = null
        if (activeGatt == null) {
            return
        }
        try {
            activeGatt.disconnect()
            activeGatt.close()
        } catch (_: Exception) {
            // 忽略关闭异常
        }
    }

    private suspend fun scanForBandKey(bluetoothAdapter: BluetoothAdapter): BluetoothDevice {
        val scanner = bluetoothAdapter.bluetoothLeScanner
            ?: throw TransportException("蓝牙扫描不可用")

        val found = CompletableDeferred<BluetoothDevice>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!found.isCompleted && matches(result)) {
                    found.complete(result.device)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                val match = results.firstOrNull { matches(it) }
                if (match != null && !found.isCompleted) {
                    found.complete(match.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                if (!found.isCompleted) {
                    found.completeExceptionally(TransportException("BLE 扫描失败：$errorCode"))
                }
            }
        }

        scanner.startScan(null, settings, callback)
        return try {
            withTimeout(15_000) { found.await() }
        } catch (e: Exception) {
            throw TransportException("未找到电脑的蓝牙广播，请确认电脑托盘显示“蓝牙广播中”", e)
        } finally {
            scanner.stopScan(callback)
        }
    }

    private fun matches(result: ScanResult): Boolean {
        val record = result.scanRecord
        if (record?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true) {
            return true
        }

        val name = try {
            result.device.name
        } catch (_: SecurityException) {
            null
        }
        return name?.startsWith("BandKey", ignoreCase = true) == true
    }

    private fun isCurrent(token: Long): Boolean = synchronized(stateLock) {
        generation == token
    }

    private fun createCallback(token: Long): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (!isCurrent(token)) {
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!connected.isCompleted) {
                        connected.complete(Unit)
                    }
                    gatt.requestMtu(TARGET_MTU)
                    return
                }

                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail(TransportException("BLE 连接已断开（status=$status）"))
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (!isCurrent(token)) {
                    return
                }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    this@BleTransport.mtu = mtu
                }
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (!isCurrent(token)) {
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(TransportException("服务发现失败：$status"))
                    return
                }

                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    fail(TransportException("未找到 BandKey 服务"))
                    return
                }

                val rx = service.getCharacteristic(RX_UUID)
                val tx = service.getCharacteristic(TX_UUID)
                if (rx == null || tx == null) {
                    fail(TransportException("未找到 BandKey 特征"))
                    return
                }

                rxCharacteristic = rx
                if (!gatt.setCharacteristicNotification(tx, true)) {
                    fail(TransportException("开启通知失败"))
                    return
                }

                val descriptor = tx.getDescriptor(CCCD_UUID)
                if (descriptor == null) {
                    fail(TransportException("未找到 CCCD 描述符"))
                    return
                }

                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }

                if (!started) {
                    fail(TransportException("写入 CCCD 失败"))
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (!isCurrent(token)) {
                    return
                }
                if (descriptor.uuid == CCCD_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (!ready.isCompleted) {
                            ready.complete(Unit)
                        }
                    } else {
                        fail(TransportException("订阅通知失败：$status"))
                    }
                }
            }

            @Deprecated("Android 13 起由新的回调重载替代，此处仍需实现以兼容旧版本")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (!isCurrent(token) || characteristic.uuid != TX_UUID) {
                    return
                }

                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleIncoming(value)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (!isCurrent(token)) {
                    return
                }
                writeDone.getAndSet(null)?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            private fun fail(error: Exception) {
                if (!isCurrent(token)) {
                    return
                }
                if (!ready.isCompleted) {
                    ready.completeExceptionally(error)
                }
                if (!connected.isCompleted) {
                    connected.completeExceptionally(error)
                }
            }
        }
    }

    private fun handleIncoming(value: ByteArray) {
        val messages = mutableListOf<String>()
        synchronized(receiveBuffer) {
            receiveBuffer.write(value)
            val buffered = receiveBuffer.toByteArray()
            var start = 0
            for (index in buffered.indices) {
                if (buffered[index] != '\n'.code.toByte()) {
                    continue
                }
                if (index > start) {
                    messages.add(String(buffered, start, index - start, Charsets.UTF_8))
                }
                start = index + 1
            }

            if (start > 0) {
                receiveBuffer.reset()
                if (start < buffered.size) {
                    receiveBuffer.write(buffered, start, buffered.size - start)
                }
            }
        }

        messages.forEach { listener?.invoke(it) }
    }
}
