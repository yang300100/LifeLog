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

        // 窗口化拉取：每次 pull 只让固件发 K 块，收齐再拉下一窗口。
        // 固件 NimBLE 栈底层只能真正缓冲约 6 个通知，超出会被静默丢弃
        // （notify() 却返回 true），所以在途包必须压到个位数。
        private const val PULL_WINDOW_CHUNKS = 4
        // 窗口内静默超过 1.5s 视为丢窗（原来 15s 太慢，拖垮整轮同步）
        private const val PULL_NOTIFY_TIMEOUT_MS = 1_500L
        // 连续无进展达到此次数即判定连接楔死，交给上层断开重连
        // （偶发丢窗 1-2 次就能自愈，4 次基本可以确诊）
        private const val STALL_THRESHOLD = 4
        // 通知通道无进展几次后切到 GATT Read 兜底（通知在尾部"多数据块+EOF"组合中会楔死，
        // GATT Read 走 ATT 请求-响应通道，和上行写入一样可靠）
        private const val NO_PROGRESS_READ_FALLBACK = 2
    }

    private sealed class BleEvent {
        data class Notify(val charUuid: UUID, val data: ByteArray) : BleEvent()
        data object Disconnected : BleEvent()
    }

    private val eventChannel = Channel<BleEvent>(1024)

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
    private var deviceInfo: BluetoothGattCharacteristic? = null

    data class FileEntry(val id: Long, val size: Long, val ageS: Long = -1L)

    /** 设备信息（从 CHAR_DEVICE_INFO GATT Read 解析） */
    data class DeviceInfo(
        val name: String,
        val fwVersion: String,
        val totalClips: Int = 0,
        val syncedClips: Int = 0,
        val sdFreeMb: Int = 0,
        val battery: Int = -1,
        val interval: Int = 300000,
        val videoDuration: Int = 5000,
        val videoResolution: String = "UXGA",
        val videoQuality: Int = 12,
        val videoFps: Int = 3,
        val bleAdvertiseTimeout: Int = 300000,
        val bleDeviceName: String = "lifelog-cam",
        val bleAdvInterval: Int = 100
    )

    /** 最近一次同步解析出的文件真实录制时间 (id → epoch ms)，供 Service 注册实体用 */
    var lastCapturedAt: Map<Long, Long> = emptyMap()
        private set

    /** 获取指定文件的录制时间：优先精确值，取不到时从已知文件按 5 分钟间隔估算 */
    fun estimatedCapturedAt(id: Long): Long {
        val exact = lastCapturedAt[id]
        if (exact != null) return exact
        // 回退：从 map 中最新的文件往前推
        val maxId = lastCapturedAt.keys.maxOrNull()
        val maxTime = maxId?.let { lastCapturedAt[it] }
        return if (maxId != null && maxTime != null) {
            maxTime - (maxId - id) * 300_000L
        } else {
            System.currentTimeMillis()
        }
    }

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
            if (!ensureConnected()) {
                _state.value = SyncState.Error("未发现设备或 BLE 连接失败")
                disconnect()
                return@withContext Result.failure(Exception("Connection failed"))
            }
            CrashLogger.i(TAG, "CCCD 全部完成，开始请求文件列表")

            // 每次连接时自动刷新设备信息（电量/SD/配置），设置页面始终看到最新状态
            try { refreshCachedDeviceInfo() } catch (_: Exception) {}

            val files = requestFileList()
            if (files == null) {
                // 读取失败 ≠ 没有新文件！不能发 sync_done，直接中止等下次
                CrashLogger.w(TAG, "获取文件列表失败，本次同步中止")
                _state.value = SyncState.Error("读取文件列表失败")
                disconnect()
                return@withContext Result.failure(Exception("List read failed"))
            }
            Log.i(TAG, "新文件: ${files.size}")
            CrashLogger.i(TAG, "获取文件列表: ${files.size} 个文件")
            if (files.isEmpty()) {
                sendSyncDone(0)
                _state.value = SyncState.Done(0)
                disconnect()
                return@withContext Result.success(0)
            }

            var transferred = 0
            // 还原每个文件的真实录制时间：新固件在列表里带相对年龄(秒)，
            // 老固件没有就按 5 分钟拍摄间隔从新到旧估算
            val now = System.currentTimeMillis()
            val maxId = files.maxOf { it.id }
            val capMap = HashMap<Long, Long>(files.size)
            for (f in files) {
                capMap[f.id] = if (f.ageS >= 0) now - f.ageS * 1000
                               else now - (maxId - f.id) * 300_000L
            }
            lastCapturedAt = capMap
            val withAge = files.count { it.ageS >= 0 }
            CrashLogger.i(TAG, "录制时间: ${withAge}/${files.size} 个文件有精确时间戳，其余按间隔估算")

            // lastContiguous = 连续成功前缀；successIds = 所有成功文件（含不连续）。
            // 补考通过后前沿可以越过它推进，已成功文件不会下周冤枉重传
            var lastContiguous = files.first().id - 1
            val successIds = sortedSetOf<Long>()
            val failedEntries = mutableListOf<FileEntry>()

            suspend fun reportProgressIfAdvanced() {
                var advanced = false
                while (successIds.contains(lastContiguous + 1)) {
                    lastContiguous++
                    advanced = true
                }
                if (advanced) {
                    // 每推进一次立刻告诉设备（固件顺带删除已同步文件），
                    // 不等会话结束 —— 防止会话中断时已传完的文件下周重传
                    writeCommandAndWait("""{"cmd":"progress","last_id":$lastContiguous}""")
                }
            }

            for ((idx, entry) in files.withIndex()) {
                _state.value = SyncState.Syncing(idx + 1, files.size)
                CrashLogger.i(TAG, "拉取文件 ${idx + 1}/${files.size}: id=${entry.id}, size=${entry.size}")
                var result = pullFile(entry, videoDir)
                if (result == PullResult.Stalled) {
                    // 下行通知通道连接级楔死：断开重连刷新两端链路状态，
                    // pullFile 会自动从磁盘已有的 offset 续传，不浪费已收数据
                    CrashLogger.w(TAG, "文件 ${entry.id} 连接楔死，断开重连后续传")
                    if (reconnect()) {
                        result = pullFile(entry, videoDir)
                    }
                }
                if (result == PullResult.Success) {
                    transferred++
                    successIds.add(entry.id)
                    reportProgressIfAdvanced()
                } else {
                    CrashLogger.w(TAG, "文件 ${entry.id} 拉取失败(${result})，本次会话稍后备考")
                    failedEntries.add(entry)
                }
            }

            // 第二遍补考：尾块停顿是瞬时现象（下一个文件立刻恢复），
            // 同会话内重试一次基本都能成功
            if (failedEntries.isNotEmpty()) {
                val retryList = failedEntries.toList()
                failedEntries.clear()
                CrashLogger.i(TAG, "开始补考: ${retryList.size} 个文件")
                for (entry in retryList) {
                    CrashLogger.i(TAG, "补考文件: id=${entry.id}, size=${entry.size}")
                    var result = pullFile(entry, videoDir)
                    if (result == PullResult.Stalled && reconnect()) {
                        result = pullFile(entry, videoDir)
                    }
                    if (result == PullResult.Success) {
                        transferred++
                        successIds.add(entry.id)
                        reportProgressIfAdvanced()
                    } else {
                        CrashLogger.w(TAG, "文件 ${entry.id} 补考仍失败(${result})，留待下次同步")
                    }
                }
            }

            sendSyncDone(lastContiguous)
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
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        g.discoverServices()
                    } else {
                        Log.w(TAG, "GATT 连接失败: status=$status")
                        if (!deferred.isCompleted) deferred.complete(false)
                    }
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
                deviceInfo   = svc.getCharacteristic(CHAR_DEVICE_INFO)

                if (syncControl == null || fileIndex == null || fileTransfer == null) {
                    deferred.complete(false); return
                }

                // 不在 BLE 回调线程写 CCCD！立即返回，在协程里逐个写入
                Log.i(TAG, "GATT 服务发现完成")
                deferred.complete(true)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int
            ) {
                CrashLogger.i(TAG, "onDescriptorWrite char=${d.characteristic.uuid.toString().take(8)}... status=$status")
                descWriteDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
                descWriteDeferred = null
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
            ) {
                val v = c.getValue()
                CrashLogger.i(TAG, "onCharacteristicRead uuid=${c.uuid.toString().take(8)}... status=$status len=${v?.size ?: 0}")
                if (status == BluetoothGatt.GATT_SUCCESS && v != null) {
                    readDeferred?.complete(v)
                } else {
                    readDeferred?.complete(null)
                }
                readDeferred = null
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
            ) {
                writeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
                writeDeferred = null
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt, c: BluetoothGattCharacteristic
            ) {
                val v = c.getValue()
                if (v != null) {
                    val result = eventChannel.trySend(BleEvent.Notify(c.uuid, v))
                    if (result.isFailure) {
                        CrashLogger.w(TAG, "eventChannel 满！通知丢弃")
                    }
                }
            }
        })

        // 15 秒超时保护，避免 GATT 异常状态下永久挂起
        return withTimeoutOrNull(15_000L) { deferred.await() } ?: false
    }

    /** 拉取结果三态：成功 / 彻底失败（删残片） / 连接楔死（保留残片，等重连续传） */
    private enum class PullResult { Success, Failed, Stalled }

    /** 扫描 + 连接 + MTU/优先级 + CCCD 订阅，一条龙的完整建连流程 */
    @SuppressLint("MissingPermission")
    private suspend fun ensureConnected(): Boolean {
        val device = scanForDevice() ?: run {
            CrashLogger.w(TAG, "未发现 lifelog-cam 设备")
            return false
        }
        Log.i(TAG, "发现设备: ${device.name} (${device.address})")
        CrashLogger.i(TAG, "发现设备: ${device.name} (${device.address})")

        _state.value = SyncState.Connecting
        CrashLogger.i(TAG, "正在连接 GATT...")
        val connected = connectGatt(device)
        if (!connected) {
            CrashLogger.w(TAG, "GATT 连接失败")
            return false
        }
        Log.i(TAG, "GATT 已连接")
        // 请求 MTU 517 + 高优先级连接
        gatt?.requestMtu(517)
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

        // 逐个写入 CCCD（BLE 串行化，必须等前一个完成再写下一个）
        val cccds = listOf(syncControl!!, fileIndex!!, fileTransfer!!)
        for (c in cccds) {
            val ok = enableNotifyAndWait(gatt!!, c)
            CrashLogger.i(TAG, "CCCD ${c.uuid.toString().take(8)}... ${if (ok) "OK" else "FAIL"}")
            if (!ok) return false
        }
        return true
    }

    /**
     * 连接楔死时的自愈：主动断开并重连。
     * 实测发现 ESP32→手机 的下行通知通道会发生连接级楔死（数据通知全灭、
     * 小 EOF 独活、GATT 写不受影响），重连可重置两端控制器的链路状态。
     * 固件端断开后会自动重新广播，协议本身无状态，重连后续传即可。
     */
    private suspend fun reconnect(): Boolean {
        CrashLogger.w(TAG, "主动断开并重连 BLE...")
        disconnect()
        delay(800)  // 等固件端检测到断链并重新广播
        val ok = ensureConnected()
        CrashLogger.i(TAG, "重连${if (ok) "成功" else "失败"}")
        return ok
    }

    /** 逐个写入 CCCD，等 onDescriptorWrite 回调确认后再返回 */
    @SuppressLint("MissingPermission")
    private suspend fun enableNotifyAndWait(gatt: BluetoothGatt,
                                            c: BluetoothGattCharacteristic): Boolean {
        gatt.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return false
        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        val deferred = CompletableDeferred<Boolean>()
        descWriteDeferred = deferred
        if (!gatt.writeDescriptor(cccd)) return false
        return withTimeoutOrNull(3000) { deferred.await() } ?: false
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, c: BluetoothGattCharacteristic,
                             indication: Boolean = false): Boolean {
        gatt.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD)
        if (cccd == null) {
            Log.w(TAG, "CCCD descriptor 不存在: ${c.uuid}")
            return false
        }
        val cccdVal = if (indication)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        cccd.setValue(cccdVal)
        val ok = gatt.writeDescriptor(cccd)
        CrashLogger.i(TAG, "enableNotify ${c.uuid.toString().take(8)}... writeDescriptor=$ok")
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(json: String) {
        syncControl?.let { c ->
            c.setValue(json.toByteArray(Charsets.UTF_8))
            gatt?.writeCharacteristic(c)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeCommandAndWait(json: String): Boolean {
        val c = syncControl ?: return false
        c.setValue(json.toByteArray(Charsets.UTF_8))
        val deferred = CompletableDeferred<Boolean>()
        writeDeferred = deferred
        if (gatt?.writeCharacteristic(c) != true) return false
        return withTimeoutOrNull(3000) { deferred.await() } ?: false
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

    // GATT 异步操作回调同步
    private var readDeferred: CompletableDeferred<ByteArray?>? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private var descWriteDeferred: CompletableDeferred<Boolean>? = null

    /** 返回 null = 读取失败（必须区别于「真的没有新文件」，否则会把读取抖动误判成空列表） */
    private suspend fun requestFileList(): List<FileEntry>? {
        for (attempt in 1..2) {
            CrashLogger.i(TAG, "发送 list 命令...(第${attempt}次)")
            writeCommand("""{"cmd":"list"}""")
            // 用 GATT Read 读取文件列表（而非等待 Notification，避免 CCCD 兼容问题）
            delay(300)  // 等 ESP32 准备好数据
            val data = gattRead(CHAR_FILE_INDEX)
            if (data != null && data.isNotEmpty()) {
                val json = String(data, Charsets.UTF_8)
                CrashLogger.i(TAG, "读到 list 响应(${data.size}B): ${json.take(200)}")
                val result = parseFileIndexJson(json)
                CrashLogger.i(TAG, "解析出 ${result.size} 个文件")
                return result
            }
            // 读到 0 字节 = 固件端 setValue 失败/数据未就绪，绝不能当空列表！
            CrashLogger.w(TAG, "list 读取为空(第${attempt}次)")
        }
        CrashLogger.w(TAG, "list 读取失败！GATT Read 连续返回空")
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun gattRead(uuid: UUID, timeoutMs: Long = 5000): ByteArray? {
        val c = when (uuid) {
            CHAR_FILE_INDEX -> fileIndex
            CHAR_FILE_TRANSFER -> fileTransfer
            CHAR_DEVICE_INFO -> deviceInfo
            else -> null
        } ?: return null
        val deferred = CompletableDeferred<ByteArray?>()
        readDeferred = deferred
        val ok = gatt?.readCharacteristic(c) ?: false
        CrashLogger.i(TAG, "gattRead uuid=${uuid.toString().take(8)}... initiated=$ok")
        if (!ok) return null
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
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

            // 可选年龄字段（秒）：新固件提供，用于还原真实录制时间
            var ageS = -1L
            val entryEnd = json.indexOf('}', szEnd)
            val ageStart = json.indexOf("\"age\":", szEnd)
            if (entryEnd > 0 && ageStart in (szEnd + 1) until entryEnd) {
                val ageEnd = json.indexOfAny(charArrayOf(',', '}'), ageStart + 6)
                if (ageEnd > 0) {
                    ageS = json.substring(ageStart + 6, ageEnd).trim().toLongOrNull() ?: -1L
                }
            }

            result.add(FileEntry(id, size, ageS))
            pos = json.indexOf('{', szEnd)
            if (pos < 0) break
        }
        return result
    }

    @SuppressLint("MissingPermission")
    private suspend fun pullFile(entry: FileEntry, videoDir: File): PullResult {
        val file = File(videoDir, "clip_%04d.mjpg".format(entry.id))
        var result = PullResult.Failed

        // 幂等短路：磁盘上已有完整文件就直接算成功
        // （上次传完但 progress 丢失的场景，跳过整段重传）
        if (file.exists() && file.length() == entry.size) {
            CrashLogger.i(TAG, "文件 ${entry.id} 已在磁盘且完整，跳过传输")
            return PullResult.Success
        }

        // 自动续传：磁盘上已有连续校验过的前缀就直接接着传
        // （楔死重连/补考/下周期都受益，不重传已收数据）
        val resumeFrom = if (file.exists() && file.length() in 1 until entry.size)
            file.length() else 0L

        // 清掉残留的通知（如上次的 EOF），防止误判
        while (eventChannel.tryReceive().getOrNull() != null) { /* drain */ }

        fun pullCmd(offset: Long) =
            """{"cmd":"pull","file_id":${entry.id},"offset":$offset,"chunk_size":500,"max_chunks":$PULL_WINDOW_CHUNKS}"""

        try {
            FileOutputStream(file, resumeFrom > 0).use { fos ->
                var offset = resumeFrom
                var chunkCount = 0
                var winCount = 0     // 本窗口已收块数
                var noProgress = 0   // 连续无进展的重拉次数
                var failed = false

                // 首窗口
                if (!writeCommandAndWait(pullCmd(offset))) {
                    CrashLogger.w(TAG, "文件 ${entry.id} pull 命令发送失败")
                    return PullResult.Stalled  // 写都失败 = 连接已死，重连试试
                }

                while (offset < entry.size && !failed) {
                    val raw = awaitNotify(CHAR_FILE_TRANSFER, timeoutMs = PULL_NOTIFY_TIMEOUT_MS)
                    if (raw == null) {
                        // 窗口内静默 = 有块丢失/命令丢失/下行楔死 → 从断点重拉
                        if (++noProgress > NO_PROGRESS_READ_FALLBACK) {
                            // 通知通道不可靠 → 跳出循环转 GATT Read 兜底
                            CrashLogger.w(TAG, "文件 ${entry.id} 通知静默 ($offset/${entry.size})，转 GATT Read")
                            break
                        }
                        CrashLogger.w(TAG, "文件 ${entry.id} offset=$offset 静默超时，重拉(第${noProgress}次)")
                        if (!writeCommandAndWait(pullCmd(offset))) {
                            CrashLogger.w(TAG, "文件 ${entry.id} 重拉命令发送失败（连接已断开?）")
                            return PullResult.Stalled
                        }
                        continue
                    }
                    chunkCount++

                    // 控制消息检测：EOF JSON 以 '{' 开头，必须在解析 dataLen 之前识别
                    if (raw.size < 10 || raw[0] == '{'.code.toByte()) {
                        val s = String(raw, Charsets.UTF_8)
                        if (s.contains("\"eof\"") && offset < entry.size) {
                            // 固件已读到文件尾但本地有缺口 → 重拉补齐
                            // 注：实测这是下行楔死的典型症状 —— 数据块全灭、EOF 独活
                            if (++noProgress > NO_PROGRESS_READ_FALLBACK) {
                                // 通知通道不可靠 → 跳出循环转 GATT Read 兜底
                                CrashLogger.w(TAG, "文件 ${entry.id} EOF 空转 ($offset/${entry.size})，转 GATT Read")
                                break
                            }
                            // 带上内容片段：区分「每次 pull 收到 3 个 EOF（数据块被 EOF 内容顶替）」
                            // 和「每次 pull 只收到 1 个 EOF（数据块纯丢失）」这两种黑盒状态
                            CrashLogger.w(TAG, "文件 ${entry.id} 缺口 EOF ($offset/${entry.size})，重拉(第${noProgress}次) 内容=${s.take(48)}")
                            delay(100)
                            if (!writeCommandAndWait(pullCmd(offset))) {
                                CrashLogger.w(TAG, "文件 ${entry.id} 重拉命令发送失败（连接已断开?）")
                                return PullResult.Stalled
                            }
                        }
                        // offset >= size 时 EOF 是正常收尾，while 条件会自然退出
                        continue
                    }

                    // 数据块：块格式 [4B file_id][4B offset LE][2B len LE][data...]
                    val chunkOffset = ((raw[4].toInt() and 0xFF).toLong()) or
                            (((raw[5].toInt() and 0xFF).toLong()) shl 8) or
                            (((raw[6].toInt() and 0xFF).toLong()) shl 16) or
                            (((raw[7].toInt() and 0xFF).toLong()) shl 24)
                    val dataLen = ((raw[8].toInt() and 0xFF) or
                            ((raw[9].toInt() and 0xFF) shl 8))

                    if (10 + dataLen > raw.size) {
                        CrashLogger.w(TAG, "块长度异常: dataLen=$dataLen raw=${raw.size} offset=$offset")
                        continue
                    }
                    if (chunkOffset != offset) {
                        // 乱序/重复/缺口 → 丢弃不写；缺口由重拉覆盖
                        // 诊断埋点：楔死排查需要知道数据块是「没到」还是「到了但乱了」
                        CrashLogger.w(TAG, "文件 ${entry.id} 块乱序: 期望=$offset 收到=$chunkOffset len=$dataLen")
                        continue
                    }
                    fos.write(raw, 10, dataLen)
                    offset += dataLen
                    noProgress = 0  // 有进展就重置

                    // 本窗口收满 → 立刻拉下一窗口（应用层自时钟，绝不压垮链路）
                    if (++winCount >= PULL_WINDOW_CHUNKS && offset < entry.size) {
                        winCount = 0
                        if (!writeCommandAndWait(pullCmd(offset))) {
                            CrashLogger.w(TAG, "文件 ${entry.id} 下一窗口命令发送失败（连接已断开?）")
                            return PullResult.Stalled
                        }
                    }
                }

                // ── GATT Read 兜底 ──
                // 通知通道在「尾部多数据块+EOF」组合中会反复楔死（固件发了、手机收不到，
                // 重连也没用）—— 改用 GATT Read，和上行 GATT Write 走同一条可靠通道。
                if (offset < entry.size && !failed) {
                    CrashLogger.i(TAG, "文件 ${entry.id} 通知通道不可靠，转 GATT Read 读剩余 ${entry.size - offset}B")
                    // 发一次 pull 置好固件端的偏移量
                    if (!writeCommandAndWait(pullCmd(offset))) {
                        return PullResult.Stalled
                    }
                    var readTries = 0
                    while (offset < entry.size && readTries < 10) {
                        val raw = gattRead(CHAR_FILE_TRANSFER, timeoutMs = 3000)
                        readTries++
                        if (raw == null || raw.isEmpty()) {
                            CrashLogger.w(TAG, "文件 ${entry.id} GATT Read 空 ($offset/${entry.size})")
                            continue
                        }
                        // 和通知通道相同的块格式解析
                        if (raw[0] == '{'.code.toByte()) {
                            val s = String(raw, Charsets.UTF_8)
                            if (s.contains("\"eof\"")) continue  // EOF 时重读下一个块
                            continue
                        }
                        if (raw.size < 10) continue
                        val chunkOffset = ((raw[4].toInt() and 0xFF).toLong()) or
                                (((raw[5].toInt() and 0xFF).toLong()) shl 8) or
                                (((raw[6].toInt() and 0xFF).toLong()) shl 16) or
                                (((raw[7].toInt() and 0xFF).toLong()) shl 24)
                        val dataLen = ((raw[8].toInt() and 0xFF) or
                                ((raw[9].toInt() and 0xFF) shl 8))
                        if (10 + dataLen > raw.size || chunkOffset != offset) continue
                        fos.write(raw, 10, dataLen)
                        offset += dataLen
                        chunkCount++
                        readTries = 0  // 有进展就重置
                    }
                    if (offset < entry.size) failed = true
                }

                CrashLogger.i(TAG, "文件 ${entry.id}: ${chunkCount}块 ${offset}B" +
                        (if (resumeFrom > 0) " (续传自${resumeFrom})" else "") +
                        (if (failed) " (失败)" else ""))
                // 只有字节数完整才算成功 —— 残缺文件绝不能落库
                result = if (!failed && offset >= entry.size) PullResult.Success else PullResult.Failed
            }
        } catch (e: Exception) {
            Log.e(TAG, "pull failed", e)
        }

        // Failed 才删残片；Stalled 已提前 return（残片留给重连续传）
        if (result == PullResult.Failed) {
            Log.w(TAG, "文件传输不完整，删除: ${file.name}")
            file.delete()
        }
        return result
    }

    private suspend fun sendSyncDone(lastId: Long) {
        writeCommand("""{"cmd":"sync_done","last_id":$lastId}""")
        awaitNotify(CHAR_SYNC_CONTROL, 2000)
    }

    // ── 设备信息与配置（设置页面用）──

    /** 最近一次从设备读到的信息（同步会话结束时更新，设置页面展示用） */
    var cachedDeviceInfo: DeviceInfo? = null
        private set

    /** 待发送的配置（用户改完还没同步时暂存，下次同步会话自动发送） */
    private var pendingConfig: String? = null

    /** 是否有待发送的配置 */
    val hasPendingConfig: Boolean get() = pendingConfig != null

    /** 读取设备状态——优先用缓存（同步时会刷新），无缓存且正好连着设备时尝试实时读 */
    suspend fun readDeviceInfo(): DeviceInfo? {
        // 有缓存直接返回
        if (cachedDeviceInfo != null) return cachedDeviceInfo
        // 无缓存但正好连着（比如同步会话刚结束还没断）→ 尝试实时读
        val data = gattRead(CHAR_DEVICE_INFO, timeoutMs = 5000) ?: return null
        return try {
            parseDeviceInfoJson(String(data, Charsets.UTF_8)).also { cachedDeviceInfo = it }
        } catch (e: Exception) {
            CrashLogger.w(TAG, "设备信息解析失败: ${e.message}")
            null
        }
    }

    /** 暂存配置，下次同步会话时自动发送到设备 */
    fun saveConfig(
        interval: Int? = null,
        videoDuration: Int? = null,
        videoResolution: String? = null,
        videoQuality: Int? = null,
        videoFps: Int? = null,
        bleAdvertiseTimeout: Int? = null,
        bleDeviceName: String? = null,
        bleAdvInterval: Int? = null
    ) {
        val parts = mutableListOf<String>()
        interval?.let { parts.add("\"interval\":$it") }
        videoDuration?.let { parts.add("\"video_duration\":$it") }
        videoResolution?.let { parts.add("\"video_resolution\":\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"") }
        videoQuality?.let { parts.add("\"video_quality\":$it") }
        videoFps?.let { parts.add("\"video_fps\":$it") }
        bleAdvertiseTimeout?.let { parts.add("\"ble_advertise_timeout\":$it") }
        bleDeviceName?.let { parts.add("\"ble_device_name\":\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"") }
        bleAdvInterval?.let { parts.add("\"ble_adv_interval\":$it") }
        if (parts.isEmpty()) return
        pendingConfig = """{"cmd":"config",${parts.joinToString(",")}}"""
        CrashLogger.i(TAG, "配置已暂存: ${pendingConfig!!.take(200)}")
    }

    /** 发送暂存的配置（同步会话中调用），成功返回 true */
    suspend fun flushPendingConfig(): Boolean {
        val json = pendingConfig ?: return false
        if (!writeCommandAndWait(json)) return false
        pendingConfig = null
        CrashLogger.i(TAG, "配置已发送到设备")
        return true
    }

    /** 在同步会话中刷新设备信息缓存 */
    suspend fun refreshCachedDeviceInfo() {
        val data = gattRead(CHAR_DEVICE_INFO, timeoutMs = 5000) ?: return
        try {
            cachedDeviceInfo = parseDeviceInfoJson(String(data, Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    /** 解析设备信息 JSON → DeviceInfo */
    @Suppress("UNCHECKED_CAST")
    private fun parseDeviceInfoJson(json: String): DeviceInfo {
        fun int(key: String): Int {
            val regex = "\"$key\":([-\\d]+)".toRegex()
            return regex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        fun str(key: String): String {
            val regex = "\"$key\":\"([^\"]+)\"".toRegex()
            return regex.find(json)?.groupValues?.get(1) ?: ""
        }
        return DeviceInfo(
            name = str("name"),
            fwVersion = str("fw_version"),
            totalClips = int("total_clips"),
            syncedClips = int("synced_clips"),
            sdFreeMb = int("sd_free_mb"),
            battery = if (json.contains("\"battery\":")) int("battery") else -1,            interval = int("interval"),
            videoDuration = int("video_duration"),
            videoResolution = str("video_resolution"),
            videoQuality = int("video_quality"),
            videoFps = int("video_fps"),
            bleAdvertiseTimeout = int("ble_advertise_timeout"),
            bleDeviceName = str("ble_device_name"),
            bleAdvInterval = int("ble_adv_interval")
        )
    }
}
