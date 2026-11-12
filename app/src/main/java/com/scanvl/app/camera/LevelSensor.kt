package com.scanvl.app.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Trạng thái cân bằng điện thoại theo mặt phẳng ngang (khi chụp tài liệu từ trên xuống):
 * - [roll]: độ nghiêng trái/phải (-180°..180°)
 * - [pitch]: độ nghiêng trước/sau (-90°..90°)
 * - [tiltAngle]: góc lệch tổng thể so với mặt phẳng ngang (0° = hoàn toàn song song với mặt bàn)
 * - [isBalanced]: điện thoại gần như song song hoàn hảo (lệch ≤ 2.5°)
 * - [bubbleX], [bubbleY]: tọa độ chuẩn hóa (-1f..1f) của bóng nước bên trong vòng tròn cân bằng
 */
data class DeviceLevelState(
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val tiltAngle: Float = 0f,
    val isBalanced: Boolean = false,
    val bubbleX: Float = 0f,
    val bubbleY: Float = 0f
)

@Composable
fun rememberDeviceLevel(): State<DeviceLevelState> {
    val context = LocalContext.current
    val levelState = remember { mutableStateOf(DeviceLevelState()) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var lastEmitTime = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val now = System.currentTimeMillis()
                // Giới hạn tần suất cập nhật UI ~ 30fps để mượt mà và tiết kiệm pin
                if (now - lastEmitTime < 33) return
                lastEmitTime = now

                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val norm = sqrt(x * x + y * y + z * z).coerceAtLeast(0.0001f)
                val nx = x / norm
                val ny = y / norm
                val nz = z / norm

                // Góc nghiêng so với phương vuông góc thẳng đứng (z = ±1 là song song với mặt đất)
                val cosTilt = abs(nz).coerceIn(0f, 1f)
                val tiltDeg = Math.toDegrees(acos(cosTilt.toDouble())).toFloat()

                // Roll & Pitch cho việc vẽ bóng nước cân bằng
                val rollDeg = Math.toDegrees(atan2(-x.toDouble(), z.toDouble())).toFloat()
                val pitchDeg = Math.toDegrees(atan2(y.toDouble(), sqrt(x * x + z * z).toDouble())).toFloat()

                // Chuẩn hóa tọa độ bóng nước (tối đa ±1f trong bán kính vòng tròn)
                // x hướng sang phải -> bóng nước dịch ngược lại
                val bx = (-nx * 3.5f).coerceIn(-1f, 1f)
                val by = (ny * 3.5f).coerceIn(-1f, 1f)

                val balanced = tiltDeg <= 2.5f

                levelState.value = DeviceLevelState(
                    roll = rollDeg,
                    pitch = pitchDeg,
                    tiltAngle = tiltDeg,
                    isBalanced = balanced,
                    bubbleX = bx,
                    bubbleY = by
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (gravitySensor != null) {
            sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    return levelState
}
