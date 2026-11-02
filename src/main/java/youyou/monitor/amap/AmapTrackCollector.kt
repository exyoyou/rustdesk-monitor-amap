package youyou.monitor.amap

import android.content.Context
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import youyou.monitor.logger.Log
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
    val singleShotPolling: Boolean = false,
    val idleOnly: Boolean = false,
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
        private const val MIN_INTERVAL_MS = 2000L
        private const val SINGLE_SHOT_TIMEOUT_MS = 25_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var locationClient: AMapLocationClient? = null

    @Volatile
    private var started: Boolean = false

    @Volatile
    private var currentIntervalMs: Long = 0L

    @Volatile
    private var singleShotInFlight: Boolean = false
    @Volatile
    private var lastMovingState: Boolean = false

    private var pollingRunnable: Runnable? = null

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
            // Key 在运行时设置（来自 BuildConfig），必须在创建定位客户端之前
            if (BuildConfig.AMAP_API_KEY.isNotBlank()) {
                AMapLocationClient.setApiKey(BuildConfig.AMAP_API_KEY)
            } else {
                Log.w(TAG, "未配置高德 Key（AMAP_API_KEY 或 local.properties 的 amap.api.key）")
            }

            activeOptions = options
            val initialInterval = if (options.adaptiveInterval) {
                options.staticIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
            } else {
                options.intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
            }
            currentIntervalMs = initialInterval
            lastPoint = null
            lastMovingState = false
            Log.i(TAG, "启动参数: $options, initialInterval=${initialInterval}ms")

            started = true

            if (options.singleShotPolling) {
                startSingleShotPolling(onPoint)
                Log.i(TAG, "高德轨迹采集已启动（单次定位+间隔轮询）")
                return
            }

            val client = AMapLocationClient(context.applicationContext)
            client.setLocationOption(buildLocationOption(initialInterval, options))

            client.setLocationListener { loc: AMapLocation? ->
                handleLocation(loc, onPoint)
            }

            client.startLocation()
            locationClient = client
            Log.i(TAG, "高德轨迹采集已启动")
        } catch (e: Exception) {
            started = false
            Log.e(TAG, "启动高德轨迹采集失败: ${e.message}", e)
        }
    }

    fun stop() {
        started = false
        pollingRunnable?.let { mainHandler.removeCallbacks(it) }
        pollingRunnable = null
        singleShotInFlight = false

        val client = locationClient
        try {
            client?.stopLocation()
            client?.onDestroy()
        } catch (e: Exception) {
            Log.w(TAG, "停止高德轨迹采集异常: ${e.message}")
        } finally {
            locationClient = null
            currentIntervalMs = 0L
            lastPoint = null
            lastMovingState = false
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

    private fun buildSingleShotOption(options: AMapTrackOptions): AMapLocationClientOption {
        return AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            interval = MIN_INTERVAL_MS
            isNeedAddress = options.needAddress
            isOnceLocation = true
            isMockEnable = false
            isLocationCacheEnable = false
        }
    }

    private fun startSingleShotPolling(onPoint: (AMapTrackPoint) -> Unit) {
        val runnable = object : Runnable {
            override fun run() {
                if (!started) return

                val nextInterval = resolvePollingIntervalMs()
                currentIntervalMs = nextInterval

                if (activeOptions.idleOnly && !isDeviceIdle() && !lastMovingState) {
                    Log.d(TAG, "设备当前非空闲（亮屏/解锁）且非移动状态，本轮跳过采样")
                } else {
                    requestSingleShot(onPoint)
                }

                if (started) {
                    mainHandler.postDelayed(this, nextInterval)
                }
            }
        }

        pollingRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun requestSingleShot(onPoint: (AMapTrackPoint) -> Unit) {
        if (!started || singleShotInFlight) return

        try {
            val client = AMapLocationClient(context.applicationContext)
            singleShotInFlight = true
            locationClient = client

            val timeoutRunnable = Runnable {
                if (!singleShotInFlight || locationClient !== client) return@Runnable
                Log.w(TAG, "单次定位超时，销毁本次client")
                safeDestroyClient(client)
                if (locationClient === client) {
                    locationClient = null
                }
                singleShotInFlight = false
            }

            client.setLocationOption(buildSingleShotOption(activeOptions))
            client.setLocationListener { loc ->
                mainHandler.removeCallbacks(timeoutRunnable)
                try {
                    if (!started) return@setLocationListener
                    handleLocation(loc, onPoint)
                } finally {
                    safeDestroyClient(client)
                    if (locationClient === client) {
                        locationClient = null
                    }
                    singleShotInFlight = false
                }
            }

            client.startLocation()
            mainHandler.postDelayed(timeoutRunnable, SINGLE_SHOT_TIMEOUT_MS)
        } catch (e: Exception) {
            singleShotInFlight = false
            Log.w(TAG, "触发单次定位失败: ${e.message}")
        }
    }

    private fun handleLocation(loc: AMapLocation?, onPoint: (AMapTrackPoint) -> Unit) {
        if (loc == null) {
            Log.w(TAG, "定位为空")
            return
        }

        if (loc.errorCode != 0) {
            Log.w(TAG, "定位失败: code=${loc.errorCode}, msg=${loc.errorInfo}")
            return
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
            return
        }

        val moving = isMoving(lastPoint, point, activeOptions.movementThresholdMeters)
        lastMovingState = moving

        if (activeOptions.adaptiveInterval) {
            val targetInterval = if (moving) {
                activeOptions.movingIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
            } else {
                activeOptions.staticIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
            }
            Log.d(
                TAG,
                "轨迹状态: moving=$moving, targetInterval=${targetInterval}ms, currentInterval=${currentIntervalMs}ms"
            )

            if (activeOptions.singleShotPolling) {
                currentIntervalMs = targetInterval
            } else {
                locationClient?.let { updateIntervalIfNeeded(it, targetInterval) }
            }
        }

        lastPoint = point
        Log.d(
            TAG,
            "采样点: lat=${point.latitude}, lon=${point.longitude}, acc=${point.accuracyMeters}, ts=${point.timestampMs}"
        )
        onPoint(point)
    }

    private fun resolvePollingIntervalMs(): Long {
        if (activeOptions.adaptiveInterval) {
            return currentIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        }
        return activeOptions.intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
    }

    private fun isDeviceIdle(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        val interactive = pm?.isInteractive ?: true
        val keyguardLocked = km?.isKeyguardLocked ?: false

        return !interactive || keyguardLocked
    }

    private fun safeDestroyClient(client: AMapLocationClient) {
        try {
            client.stopLocation()
            client.onDestroy()
        } catch (_: Exception) {
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
