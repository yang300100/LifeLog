package com.lifelog.camera.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.lifelog.camera.util.CrashLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleFileTransfer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BleXfer"

        val SERVICE_UUID       = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val CHAR_SYNC_CONTROL  = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val CHAR_FILE_INDEX    = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val CHAR_FILE_TRANSFER = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb")
        val CHAR_DEVICE_INFO   = UUID.fromString("0000ff04-0000-1000-8000-00805f9b34fb")

        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT = 15_000L
    }

    private sealed class BleEvent {
        data class Notify(val charUuid: UUID, val data: ByteArray) : BleEvent()
        data object Disconnected : BleEvent()
    }

    private val eventChannel = Channel<BleEvent>(64)

    sealed class SyncState {
        data object Idle : SyncState()
        data object Scanning : SyncState()
        data object Connecting : SyncState()
        data class Syncing(val current: Int, val total: Int) : SyncState()
        data class Done(val files: Int) : SyncState()
        data class Error(val msg: String) : SyncState()
    }

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state

    private var gatt: BluetoothGatt? = null
    private var syncControl: BluetoothGattCharacteristic? = null
    private var fileIndex: BluetoothGattCharacteristic? = null
    private var fileTransfer: BluetoothGattCharacteristic? = null

    data class FileEntry(val id: Long, val size: Long)

    // ── 主入口 ──

    // ── 权限和蓝牙状态检查 ──

    private fun checkReady(): String? {
        val mgr = context.getSystemService(BluetoothManager::class.java)
        val adapter = mgr.adapter
        if (adapter == null || !adapter.isEnabled) return "请开启蓝牙"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!hasScan || !hasConnect) return "缺少蓝牙权限，请在系统设置中授权"
        } else {
            val hasBt = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val hasAdmin = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            if (!hasBt || !hasAdmin) return "缺少蓝牙权限"
        }

        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return "缺少位置权限（BLE 扫描需要）"

        return null // OK
    }

    suspend fun startSync(videoDir: File): Result<Int> = withContext(Dispatchers.IO) {
        try {
            CrashLogger.i(TAG, "startSync 开始, videoDir=${videoDir.absolutePath}")
            val error = checkReady()
            if (error != null) {
                CrashLogger.w(TAG, "权限检查失败: $error")
                _state.value = SyncState.Error(error)
                return@withContext Result.failure(SecurityException(error))
            }

            _state.value = SyncState.Scanning
            CrashLogger.i(TAG, "开始扫描 BLE 设备...")

            val device = scanForDevice() ?: run {
                CrashLogger.w(TAG, "未发现 lifelog-cam 设备")
                _state.value = SyncState.Error("未发现 lifelog-cam 设备")
                return@withContext Result.failure(Exception("Device not found"))
            }
            Log.i(TAG, "发现设备: ${device.name} (${device.address})")
            CrashLogger.i(TAG, "发现设备: ${device.name} (${device.address})")

            _state.value = SyncState.Connecting
            CrashLogger.i(TAG, "正在连接 GATT...")
            val connected = connectGatt(device)
            if (!connected) {
                CrashLogger.w(TAG, "GATT 连接失败")
                _state.value = SyncState.Error("BLE 连接失败")
                return@withContext Result.failure(Exception("Connection failed"))
            }
            Log.i(TAG, "GATT 已连接")

            val files = requestFileList()
            Log.i(TAG, "新文件: ${files.size}")
            CrashLogger.i(TAG, "获取文件列表: ${files.size} 个文件")
            if (files.isEmpty()) {
                sendSyncDone(0)
                _state.value = SyncState.Done(0)
                disconnect()
                return@withContext Result.success(0)
            }

            var transferred = 0
            for ((idx, entry) in files.withIndex()) {
                _state.value = SyncState.Syncing(idx + 1, files.size)
                CrashLogger.i(TAG, "拉取文件 ${idx + 1}/${files.size}: id=${entry.id}, size=${entry.size}")
                if (pullFile(entry, videoDir)) transferred++
            }

            sendSyncDone(files.maxOf { it.id })
            _state.value = SyncState.Done(transferred)
            CrashLogger.i(TAG, "同步完成: $transferred 个文件")
            disconnect()
            Result.success(transferred)

        } catch (e: Exception) {
            Log.e(TAG, "sync failed", e)
            CrashLogger.e(TAG, "同步异常: ${e.message}", e)
            _state.value = SyncState.Error(e.message ?: "未知错误")
            disconnect()
            Result.failure(e)
        }
    }

    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    // ── 扫描 (suspendCancellableCoroutine, 安全取消) ──

    @SuppressLint("MissingPermission")
    private suspend fun scanForDevice(): BluetoothDevice? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            val scanner = adapter.bluetoothLeScanner

            if (scanner == null) {
                cont.resume(null) {}
                return@suspendCancellableCoroutine
            }

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.device?.let {
                        if (cont.isActive) {
                            cont.resume(it) { scanner.stopScan(this) }
                        }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    if (cont.isActive) cont.resume(null) {}
                }
            }

            scanner.startScan(listOf(filter), settings, cb)

            // 超时处理 — 保存 Runnable 引用以便取消
            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (cont.isActive) {
                    cont.resume(null) { scanner.stopScan(cb) }
                }
            }
            handler.postDelayed(timeoutRunnable, SCAN_TIMEOUT)

            // 协程取消时清理
            cont.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                try { scanner.stopScan(cb) } catch (_: Exception) {}
            }
        }

    // ── GATT 连接 (CompletableDeferred) ──

    @SuppressLint("MissingPermission")
    private suspend fun connectGatt(device: BluetoothDevice): Boolean {
        val deferred = CompletableDeferred<Boolean>()

        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt,
                                                 status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    eventChannel.trySend(BleEvent.Disconnected)
                    if (!deferred.isCompleted) deferred.complete(false)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (deferred.isCompleted) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    deferred.complete(false); return
                }
                val svc = g.getService(SERVICE_UUID)
                if (svc == null) { deferred.complete(false); return }

                syncControl  = svc.getCharacteristic(CHAR_SYNC_CONTROL)
                fileIndex    = svc.getCharacteristic(CHAR_FILE_INDEX)
                fileTransfer = svc.getCharacteristic(CHAR_FILE_TRANSFER)

                if (syncControl == null || fileIndex == null || fileTransfer == null) {
                    deferred.complete(false); return
                }

                val allNotified = enableNotify(g, syncControl!!)
                    && enableNotify(g, fileIndex!!)
                    && enableNotify(g, fileTransfer!!)

                if (!allNotified) {
                    // CCCD descriptor 缺失，设备可能未正确实现 BLE 规范
                    Log.w(TAG, "部分 characteristic 缺少 CCCD descriptor")
                }

                // 等待 CCCD descriptor 写入完成（BLE 异步操作，300ms 足够绝大多数设备）
                Thread.sleep(300)
                deferred.complete(true)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt, c: BluetoothGattCharacteristic
            ) {
                c.value?.let { eventChannel.trySend(BleEvent.Notify(c.uuid, it)) }
            }
        })

        return deferred.await()
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        gatt.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD)
        if (cccd == null) {
            Log.w(TAG, "CCCD descriptor 不存在: ${c.uuid}")
            return false
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(cccd)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(json: String) {
        syncControl?.let { c ->
            c.value = json.toByteArray(Charsets.UTF_8)
            gatt?.writeCharacteristic(c)
        }
    }

    private suspend fun awaitNotify(uuid: UUID, timeoutMs: Long = 5000): ByteArray? {
        return withTimeoutOrNull(timeoutMs) {
            while (currentCoroutineContext().isActive) {
                when (val event = eventChannel.receive()) {
                    is BleEvent.Notify -> if (event.charUuid == uuid) return@withTimeoutOrNull event.data
                    is BleEvent.Disconnected -> return@withTimeoutOrNull null
                }
            }
            null
        }
    }

    private suspend fun requestFileList(): List<FileEntry> {
        writeCommand("""{"cmd":"list"}""")
        val data = awaitNotify(CHAR_FILE_INDEX, 5000) ?: return emptyList()
        return parseFileIndexJson(String(data, Charsets.UTF_8))
    }

    private fun parseFileIndexJson(json: String): List<FileEntry> {
        val result = mutableListOf<FileEntry>()
        val filesStart = json.indexOf("\"files\":[")
        if (filesStart < 0) return result

        var pos = filesStart + 9
        while (pos < json.length) {
            if (json[pos] == ']') break
            val idStart = json.indexOf("\"id\":", pos)
            if (idStart < 0) break
            val idEnd = json.indexOfAny(charArrayOf(',', '}'), idStart + 5)
            val id = json.substring(idStart + 5, idEnd).trim().toLongOrNull() ?: break

            val szStart = json.indexOf("\"size\":", idEnd)
            if (szStart < 0) break
            val szEnd = json.indexOfAny(charArrayOf(',', '}'), szStart + 7)
            val size = json.substring(szStart + 7, szEnd).trim().toLongOrNull() ?: break

            result.add(FileEntry(id, size))
            pos = json.indexOf('{', szEnd)
            if (pos < 0) break
        }
        return result
    }

    @SuppressLint("MissingPermission")
    private suspend fun pullFile(entry: FileEntry, videoDir: File): Boolean {
        // 文件名格式与 ESP32 固件一致: clip_XXXX.mjpg (4位零填充)
        val file = File(videoDir, "clip_%04d.mjpg".format(entry.id))
        val fos = FileOutputStream(file)
        var offset = 0L
        var ok = false
        var eofReceived = false

        try {
            while (offset < entry.size) {
                val chunk = minOf(488L, entry.size - offset).toInt()
                writeCommand(
                    """{"cmd":"pull","file_id":${entry.id},"offset":$offset,"chunk_size":$chunk}"""
                )

                val raw = awaitNotify(CHAR_FILE_TRANSFER, 5000) ?: break

                if (raw.size < 10) {
                    val s = String(raw, Charsets.UTF_8)
                    if (s.contains("\"eof\"")) {
                        eofReceived = true
                        break
                    }
                    continue
                }

                val dataLen = ((raw[8].toInt() and 0xFF) or
                               ((raw[9].toInt() and 0xFF) shl 8))
                if (10 + dataLen <= raw.size) {
                    fos.write(raw, 10, dataLen)
                    offset += dataLen
                } else {
                    // 数据长度异常，跳过此块
                    Log.w(TAG, "数据块长度异常: dataLen=$dataLen, raw.size=${raw.size}")
                }
            }
            // 传输成功条件: 已读取全部数据 或 收到 EOF 且 offset > 0
            ok = offset >= entry.size || (eofReceived && offset > 0)
        } catch (e: Exception) {
            Log.e(TAG, "pull failed", e)
        } finally {
            fos.close()
            if (!ok) {
                Log.w(TAG, "文件传输不完整，删除: ${file.name}")
                file.delete()
            }
        }
        return ok
    }

    private suspend fun sendSyncDone(lastId: Long) {
        writeCommand("""{"cmd":"sync_done","last_id":$lastId}""")
        awaitNotify(CHAR_SYNC_CONTROL, 2000)
    }
}
