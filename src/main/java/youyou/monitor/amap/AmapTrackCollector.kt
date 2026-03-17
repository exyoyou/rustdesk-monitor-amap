package youyou.monitor.amap

import android.content.Context
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class AMapTrackOptions(
    val intervalMs: Long = 10000L,
    val needAddress: Boolean = true,
    val onceLocation: Boolean = false,
    val adaptiveInterval: Boolean = true,
    val movingIntervalMs: Long = 10000L,
    val staticIntervalMs: Long = 60000L,
    val movementThresholdMeters: Double = 30.0,
    val maxAcceptAccuracyMeters: Float = 120f
)

data class AMapTrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
    val accuracyMeters: Float?,
    val poiName: String?
)

class AmapTrackCollector(
    private val context: Context
) {
    companion object {
        private const val TAG = "AmapTrackCollector"
    }

    @Volatile
    private var locationClient: AMapLocationClient? = null

    @Volatile
    private var started: Boolean = false

    @Volatile
    private var currentIntervalMs: Long = 0L

    private var lastPoint: AMapTrackPoint? = null

    private var activeOptions: AMapTrackOptions = AMapTrackOptions()

    fun start(
        options: AMapTrackOptions = AMapTrackOptions(),
        onPoint: (AMapTrackPoint) -> Unit
    ) {
        if (started) return

        try {
            AMapLocationClient.updatePrivacyShow(context, true, true)
            AMapLocationClient.updatePrivacyAgree(context, true)

            activeOptions = options
            val initialInterval = if (options.adaptiveInterval && !options.onceLocation) {
                options.staticIntervalMs.coerceAtLeast(2000L)
            } else {
                options.intervalMs.coerceAtLeast(2000L)
            }
            currentIntervalMs = initialInterval
            lastPoint = null

            val client = AMapLocationClient(context.applicationContext)
            client.setLocationOption(buildLocationOption(initialInterval, options))

            client.setLocationListener { loc: AMapLocation? ->
                if (loc == null) {
                    Log.w(TAG, "定位为空")
                    return@setLocationListener
                }

                if (loc.errorCode != 0) {
                    Log.w(TAG, "定位失败: code=${loc.errorCode}, msg=${loc.errorInfo}")
                    return@setLocationListener
                }

                val point = AMapTrackPoint(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    timestampMs = if (loc.time > 0L) loc.time else System.currentTimeMillis(),
                    accuracyMeters = loc.accuracy,
                    poiName = loc.poiName
                )

                val acc = point.accuracyMeters
                if (acc != null && acc > activeOptions.maxAcceptAccuracyMeters) {
                    Log.d(TAG, "忽略低精度点: accuracy=${acc}m")
                    return@setLocationListener
                }

                if (activeOptions.adaptiveInterval && !activeOptions.onceLocation) {
                    val moving = isMoving(lastPoint, point, activeOptions.movementThresholdMeters)
                    val targetInterval = if (moving) {
                        activeOptions.movingIntervalMs.coerceAtLeast(2000L)
                    } else {
                        activeOptions.staticIntervalMs.coerceAtLeast(2000L)
                    }
                    updateIntervalIfNeeded(client, targetInterval)
                }

                lastPoint = point
                onPoint(point)
            }

            client.startLocation()
            locationClient = client
            started = true
            Log.i(TAG, "高德轨迹采集已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动高德轨迹采集失败: ${e.message}", e)
        }
    }

    fun stop() {
        val client = locationClient ?: return
        try {
            client.stopLocation()
            client.onDestroy()
        } catch (e: Exception) {
            Log.w(TAG, "停止高德轨迹采集异常: ${e.message}")
        } finally {
            locationClient = null
            started = false
            currentIntervalMs = 0L
            lastPoint = null
        }
    }

    private fun buildLocationOption(intervalMs: Long, options: AMapTrackOptions): AMapLocationClientOption {
        return AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            interval = intervalMs
            isNeedAddress = options.needAddress
            isOnceLocation = options.onceLocation
            isMockEnable = false
            isLocationCacheEnable = false
        }
    }

    private fun updateIntervalIfNeeded(client: AMapLocationClient, targetIntervalMs: Long) {
        if (targetIntervalMs == currentIntervalMs) return
        try {
            client.setLocationOption(buildLocationOption(targetIntervalMs, activeOptions))
            currentIntervalMs = targetIntervalMs
            Log.d(TAG, "定位间隔动态调整为 ${targetIntervalMs}ms")
        } catch (e: Exception) {
            Log.w(TAG, "动态调整定位间隔失败: ${e.message}")
        }
    }

    private fun isMoving(
        previous: AMapTrackPoint?,
        current: AMapTrackPoint,
        thresholdMeters: Double
    ): Boolean {
        val prev = previous ?: return false
        val dtMs = (current.timestampMs - prev.timestampMs).coerceAtLeast(1L)
        val distance = haversineMeters(
            prev.latitude,
            prev.longitude,
            current.latitude,
            current.longitude
        )
        val speedMps = distance / (dtMs / 1000.0)
        val acc = current.accuracyMeters?.toDouble() ?: 0.0
        val adaptiveThreshold = kotlin.math.max(thresholdMeters, acc * 1.5)

        return distance >= adaptiveThreshold || speedMps >= 1.2
    }

    private fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
