package com.lifelog.camera.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 步数传感器辅助工具（异步版）
 */
object StepCounter {
    private var todaySteps: Int = 0
    private var lastTotal: Long = 0
    private var lastDate: String = ""

    /** 异步读取今日步数 */
    suspend fun getTodaySteps(context: Context): Int = withContext(Dispatchers.Default) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (today != lastDate) { lastDate = today; todaySteps = 0; lastTotal = 0 }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return@withContext todaySteps
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return@withContext todaySteps

        // 用 suspendCancellableCoroutine 等待传感器读数
        suspendCancellableCoroutine<Int> { cont ->
            var resolved = false
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event != null && !resolved) {
                        resolved = true
                        val currentTotal = event.values[0].toLong()
                        if (lastTotal > 0 && currentTotal > lastTotal) {
                            todaySteps = (currentTotal - lastTotal).toInt()
                        }
                        lastTotal = currentTotal
                        sensorManager.unregisterListener(this)
                        cont.resumeWith(Result.success(todaySteps))
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)

            // 500ms 超时
            val timeout = scope.launch {
                delay(500)
                if (!resolved) {
                    resolved = true
                    sensorManager.unregisterListener(listener)
                    cont.resumeWith(Result.success(todaySteps))
                }
            }
            cont.invokeOnCancellation { timeout.cancel(); sensorManager.unregisterListener(listener) }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
}
