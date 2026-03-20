package com.kail.location.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.baidu.mapapi.model.LatLng
import android.util.Log
import com.kail.location.views.locationpicker.LocationPickerActivity
import com.kail.location.R
import com.kail.location.utils.GoUtils
import com.kail.location.utils.KailLog
import com.kail.location.views.joystick.JoyStick
import kotlin.math.abs
import kotlin.math.cos
import com.kail.location.utils.MapUtils
import com.kail.location.geo.GeoMath
import com.kail.location.geo.GeoPredict

/**
 * 前台定位模拟服务。
 * 管理模拟位置提供者、摇杆悬浮窗以及后台线程执行。
 */
class ServiceGo : Service() {
    // 定位相关变量
    private var mCurLat = DEFAULT_LAT
    private var mCurLng = DEFAULT_LNG
    private var mCurAlt = DEFAULT_ALT
    private var mCurBea = DEFAULT_BEA
    private var mSpeed = 1.2        /* 默认的速度，单位 m/s */

    private lateinit var mLocManager: LocationManager
    private lateinit var mLocHandlerThread: HandlerThread
    private lateinit var mLocHandler: Handler
    private var isStop = false

    private var mActReceiver: NoteActionReceiver? = null
    // Notification object
    private var mNotification: Notification? = null

    // 摇杆相关
    private lateinit var mJoyStick: JoyStick

    private val mBinder = ServiceGoBinder()
    private var mRoutePoints: MutableList<Pair<Double, Double>> = mutableListOf()
    private var mRouteCumulativeDistances: MutableList<Double> = mutableListOf()
    private var mTotalDistance: Double = 0.0
    private var mRouteIndex = 0
    private var mRouteLoop = false
    private var mSegmentProgressMeters = 0.0

    private var mRunMode: String = "noroot"
    private var portalRandomKey: String? = null
    private var portalStarted: Boolean = false
    private var locationLoopStarted: Boolean = false
    private var stepEnabledCache: Boolean = false
    private var stepFreqCache: Double = 0.0
    private var speedFluctuation: Boolean = false

    companion object {
        const val DEFAULT_LAT = 36.667662
        const val DEFAULT_LNG = 117.027707
        const val DEFAULT_ALT = 55.0
        const val DEFAULT_BEA = 0.0f

        private const val HANDLER_MSG_ID = 0
        private const val SERVICE_GO_HANDLER_NAME = "ServiceGoLocation"

        // 通知栏消息
        private const val SERVICE_GO_NOTE_ID = 1
        const val SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick"
        const val SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick"
        private const val SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE"
        private const val SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE"
        const val EXTRA_ROUTE_POINTS = "EXTRA_ROUTE_POINTS"
        const val EXTRA_ROUTE_LOOP = "EXTRA_ROUTE_LOOP"
        const val EXTRA_JOYSTICK_ENABLED = "EXTRA_JOYSTICK_ENABLED"
        const val EXTRA_ROUTE_SPEED = "EXTRA_ROUTE_SPEED"
        const val EXTRA_COORD_TYPE = "EXTRA_COORD_TYPE"
        const val EXTRA_RUN_MODE = "EXTRA_RUN_MODE"
        const val EXTRA_CONTROL_ACTION = "EXTRA_CONTROL_ACTION"
        const val EXTRA_SPEED_FLUCTUATION = "EXTRA_SPEED_FLUCTUATION"
        const val EXTRA_SEEK_RATIO = "EXTRA_SEEK_RATIO"
        const val EXTRA_STEP_ENABLED = "EXTRA_STEP_ENABLED"
        const val EXTRA_STEP_FREQ = "EXTRA_STEP_FREQ"
        const val EXTRA_NATIVE_SENSOR_HOOK = "EXTRA_NATIVE_SENSOR_HOOK"
        const val CONTROL_PAUSE = "pause"
        const val CONTROL_RESUME = "resume"
        const val CONTROL_STOP = "stop"
        const val CONTROL_SEEK = "seek"
        const val CONTROL_SET_SPEED = "set_speed"
        const val CONTROL_SET_SPEED_FLUCTUATION = "set_speed_fluctuation"
        const val CONTROL_SET_STEP = "set_step"
        const val CONTROL_SET_NATIVE_HOOK = "set_native_hook"
        const val COORD_WGS84 = "WGS84"
        const val COORD_BD09 = "BD09"
        const val COORD_GCJ02 = "GCJ02"

        const val ACTION_STATUS_CHANGED = "com.kail.location.service.STATUS_CHANGED"
        const val EXTRA_IS_SIMULATING = "is_simulating"
        const val EXTRA_IS_PAUSED = "is_paused"
        private const val PORTAL_PROVIDER = "portal"
    }

    /**
     * 广播当前模拟状态（是否正在模拟、是否暂停）到应用内，供界面刷新。
     */
    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_CHANGED)
        intent.putExtra(EXTRA_IS_SIMULATING, locationLoopStarted && !isStop)
        intent.putExtra(EXTRA_IS_PAUSED, isStop)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    /**
     * 绑定服务到 Activity。
     *
     * @param intent 绑定意图。
     * @return 服务的 Binder 实例。
     */
    override fun onBind(intent: Intent): IBinder {
        return mBinder
    }

    /**
     * 服务创建时的初始化流程：
     * - 初始化并启动前台通知
     * - 初始化 LocationManager
     * - 初始化定位线程与 Handler
     * - 初始化摇杆并根据权限显示/隐藏
     * 最后广播初始状态供界面监听。
     */
    override fun onCreate() {
        super.onCreate()
        KailLog.i(this, "ServiceGo", "onCreate started")
        
        // 1. Init Notification & Foreground Service
        try {
            KailLog.i(this, "ServiceGo", "1. initNotification")
            // Must call startForeground immediately
            initNotification()
        } catch (e: Throwable) {
            KailLog.e(this, "ServiceGo", "Error in initNotification: ${e.message}")
            // Continue execution, don't stopSelf yet, maybe we can survive or at least log more
        }

        // 2. Init Location Manager & Providers
        try {
            KailLog.i(this, "ServiceGo", "2. init LocationManager")
            mLocManager = this.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        } catch (e: Throwable) {
            KailLog.e(this, "ServiceGo", "Error in LocationManager init: ${e.message}")
        }

        // 3. Init Location Handler
        try {
            KailLog.i(this, "ServiceGo", "3. initGoLocation")
            initGoLocation()
        } catch (e: Throwable) {
            KailLog.e(this, "ServiceGo", "Error in initGoLocation: ${e.message}")
        }
            
        // 4. Init JoyStick
        try {
            KailLog.i(this, "ServiceGo", "4. initJoyStick")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                GoUtils.DisplayToast(applicationContext, "请授予悬浮窗权限")
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val joystickEnabledPref = prefs.getBoolean("setting_joystick_enabled", false)
            initJoyStick()
            if (joystickEnabledPref) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                    mJoyStick.show()
                }
            } else {
                mJoyStick.hide()
            }
        } catch (e: Throwable) {
            KailLog.e(this, "ServiceGo", "Error initializing JoyStick: ${e.message}")
            GoUtils.DisplayToast(applicationContext, "悬浮窗初始化失败: ${e.message}")
        }

        broadcastStatus()
        KailLog.i(this, "ServiceGo", "onCreate finished")
    }

    /**
     * 服务启动回调。
     * 处理位置、路线与摇杆设置相关的启动参数。
     *
     * @param intent 启动服务时传入的意图。
     * @param flags 启动标志。
     * @param startId 启动 ID。
     * @return 返回服务的启动语义。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle control actions (pause/resume/stop) early
        if (intent != null) {
            val ctrl = intent.getStringExtra(EXTRA_CONTROL_ACTION)
            if (!ctrl.isNullOrBlank()) {
                when (ctrl) {
                    CONTROL_PAUSE -> {
                        try {
                            isStop = true
                            if (this::mJoyStick.isInitialized) {
                                mJoyStick.setRoutePauseState(true)
                            }
                            broadcastStatus()
                            KailLog.log(this, "ServiceGo", "Paused simulation (isStop=true)", isHighFrequency = false)
                        } catch (e: Exception) {
                            KailLog.log(this, "ServiceGo", "Pause error: ${e.message}", isHighFrequency = false)
                        }
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_RESUME -> {
                        try {
                            isStop = false
                            if (this::mJoyStick.isInitialized) {
                                mJoyStick.setRoutePauseState(false)
                            }
                            broadcastStatus()
                            KailLog.log(this, "ServiceGo", "Resumed simulation (isStop=false)", isHighFrequency = false)
                        } catch (e: Exception) {
                            KailLog.log(this, "ServiceGo", "Resume error: ${e.message}", isHighFrequency = false)
                        }
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_STOP -> {
                        try {
                            stopSelf()
                            broadcastStatus() // Technically stopSelf calls onDestroy, but explicit broadcast helps
                            KailLog.i(this, "ServiceGo", "stopSelf via control action")
                        } catch (e: Exception) {
                            KailLog.e(this, "ServiceGo", "stop error: ${e.message}")
                        }
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_SEEK -> {
                        try {
                            val ratio = intent.getFloatExtra(EXTRA_SEEK_RATIO, 0f).coerceIn(0f, 1f)
                            if (mRoutePoints.size >= 2 && mRouteCumulativeDistances.isNotEmpty()) {
                                val targetDist = mTotalDistance * ratio
                                var idx = 0
                                for (i in 0 until mRouteCumulativeDistances.size - 1) {
                                    if (targetDist >= mRouteCumulativeDistances[i] && targetDist < mRouteCumulativeDistances[i + 1]) {
                                        idx = i
                                        break
                                    }
                                }
                                if (targetDist >= mTotalDistance) {
                                    idx = mRoutePoints.size - 2
                                }

                                mRouteIndex = idx
                                mSegmentProgressMeters = targetDist - mRouteCumulativeDistances[idx]

                                val a = mRoutePoints[mRouteIndex]
                                val b = mRoutePoints[(mRouteIndex + 1).coerceAtMost(mRoutePoints.size - 1)]
                                val midLat = (a.second + b.second) / 2.0
                                val metersPerDegLat = GeoMath.metersPerDegLat(midLat)
                                val metersPerDegLng = GeoMath.metersPerDegLng(midLat)
                                val dLatDeg2 = b.second - a.second
                                val dLngDeg2 = b.first - a.first
                                val segLenMeters = kotlin.math.sqrt((dLatDeg2 * metersPerDegLat) * (dLatDeg2 * metersPerDegLat) + (dLngDeg2 * metersPerDegLng) * (dLngDeg2 * metersPerDegLng))
                                val f = if (segLenMeters > 0) (mSegmentProgressMeters / segLenMeters) else 0.0
                                val dLngDeg = b.first - a.first
                                val dLatDeg = b.second - a.second
                                mCurLng = a.first + dLngDeg * f
                                mCurLat = a.second + dLatDeg * f
                                mCurBea = GeoMath.bearingDegrees(a.first, a.second, b.first, b.second)
                                updateJoystickStatus()
                                KailLog.i(this, "ServiceGo", "seek to ratio=$ratio index=$mRouteIndex progress=$mSegmentProgressMeters")
                            }
                        } catch (e: Exception) {
                            KailLog.e(this, "ServiceGo", "seek error: ${e.message}")
                        }
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_SET_SPEED -> {
                        try {
                            val kmh = intent.getFloatExtra(EXTRA_ROUTE_SPEED, (mSpeed * 3.6).toFloat())
                            mSpeed = kmh.toDouble() / 3.6
                            if (mRunMode == "root") {
                                portalStartIfNeeded()
                                portalSend("set_speed") { putFloat("speed", mSpeed.toFloat()) }
                            }
                            KailLog.i(this, "ServiceGo", "speed updated to km/h=$kmh m/s=$mSpeed")
                        } catch (e: Exception) {
                            KailLog.e(this, "ServiceGo", "set_speed error: ${e.message}")
                        }
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_SET_SPEED_FLUCTUATION -> {
                        try {
                            speedFluctuation = intent.getBooleanExtra(EXTRA_SPEED_FLUCTUATION, speedFluctuation)
                            KailLog.i(this, "ServiceGo", "speedFluctuation updated to $speedFluctuation")
                        } catch (e: Exception) {
                            KailLog.e(this, "ServiceGo", "set_speed_fluctuation error: ${e.message}")
                        }
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_SET_STEP -> {
                        try {
                            stepEnabledCache = intent.getBooleanExtra(EXTRA_STEP_ENABLED, stepEnabledCache)
                            stepFreqCache = intent.getFloatExtra(EXTRA_STEP_FREQ, stepFreqCache.toFloat()).toDouble()
                            if (mRunMode == "root") {
                                portalStartIfNeeded()
                                portalSend("set_step_enabled") { putBoolean("enabled", stepEnabledCache) }
                                portalSend("set_step_cadence") { putFloat("cadence", stepFreqCache.toFloat()) }
                            }
                            com.kail.location.xposed.NativeHook.setStepConfigSafe(stepEnabledCache, stepFreqCache.toFloat())
                            KailLog.i(this, "ServiceGo", "step simulation updated: enabled=$stepEnabledCache, freq=$stepFreqCache")
                        } catch (e: Exception) {
                            KailLog.e(this, "ServiceGo", "set_step error: ${e.message}")
                        }
                        return super.onStartCommand(intent, flags, startId)
                    }
                    CONTROL_SET_NATIVE_HOOK -> {
                        try {
                            val enabled = intent.getBooleanExtra(EXTRA_NATIVE_SENSOR_HOOK, false)
                            com.kail.location.xposed.NativeHook.setStepConfigSafe(enabled, stepFreqCache.toFloat())
                            KailLog.i(this, "ServiceGo", "native hook status updated to $enabled")
                        } catch (e: Exception) {
                            KailLog.e(this, "ServiceGo", "set_native_hook error: ${e.message}")
                        }
                        return super.onStartCommand(intent, flags, startId)
                    }
                }
            }
            stepEnabledCache = intent.getBooleanExtra(EXTRA_STEP_ENABLED, false)
            stepFreqCache = intent.getFloatExtra(EXTRA_STEP_FREQ, 0f).toDouble()
            speedFluctuation = intent.getBooleanExtra(EXTRA_SPEED_FLUCTUATION, false)
            val nativeHookEnabled = intent.getBooleanExtra(EXTRA_NATIVE_SENSOR_HOOK, false)
            com.kail.location.xposed.NativeHook.setStepConfigSafe(nativeHookEnabled, stepFreqCache.toFloat())
            if (nativeHookEnabled) {
                com.kail.location.xposed.NativeHook.startHook()
            }
        }
        // Ensure startForeground is called to prevent crash (ForegroundServiceDidNotStartInTimeException)
        // even if onCreate was skipped (service already running)
        if (mNotification != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(SERVICE_GO_NOTE_ID, mNotification!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(SERVICE_GO_NOTE_ID, mNotification!!)
            }
        } else {
            // If notification is missing, try to init it again
            try {
                initNotification()
            } catch (e: Exception) {
                KailLog.e(this, "ServiceGo", "Error in onStartCommand initNotification: ${e.message}")
            }
        }

        if (intent != null) {
            mRunMode = intent.getStringExtra(EXTRA_RUN_MODE) ?: "noroot"
            val coordType = intent.getStringExtra(EXTRA_COORD_TYPE) ?: COORD_BD09
            mCurLng = intent.getDoubleExtra(LocationPickerActivity.LNG_MSG_ID, DEFAULT_LNG)
            mCurLat = intent.getDoubleExtra(LocationPickerActivity.LAT_MSG_ID, DEFAULT_LAT)
            try {
                when (coordType) {
                    COORD_WGS84 -> { /* keep */ }
                    COORD_GCJ02 -> {
                        val wgs = MapUtils.gcj02towgs84(mCurLng, mCurLat)
                        mCurLng = wgs[0]
                        mCurLat = wgs[1]
                    }
                    else -> {
                        val wgs = MapUtils.bd2wgs(mCurLng, mCurLat)
                        mCurLng = wgs[0]
                        mCurLat = wgs[1]
                    }
                }
            } catch (_: Exception) {}
            mCurAlt = intent.getDoubleExtra(LocationPickerActivity.ALT_MSG_ID, DEFAULT_ALT)
            val joystickEnabled = intent.getBooleanExtra(EXTRA_JOYSTICK_ENABLED, false)
            mSpeed = intent.getFloatExtra(EXTRA_ROUTE_SPEED, mSpeed.toFloat()).toDouble() / 3.6
            val routeArray = intent.getDoubleArrayExtra(EXTRA_ROUTE_POINTS)
            if (routeArray != null && routeArray.size >= 2) {
                mRoutePoints.clear()
                var i = 0
                while (i + 1 < routeArray.size) {
                    val bdLng = routeArray[i]
                    val bdLat = routeArray[i + 1]
                    when (coordType) {
                        COORD_WGS84 -> mRoutePoints.add(Pair(bdLng, bdLat))
                        COORD_GCJ02 -> {
                            val wgs = MapUtils.gcj02towgs84(bdLng, bdLat)
                            mRoutePoints.add(Pair(wgs[0], wgs[1]))
                        }
                        else -> {
                            val wgs = MapUtils.bd2wgs(bdLng, bdLat)
                            mRoutePoints.add(Pair(wgs[0], wgs[1]))
                        }
                    }
                    i += 2
                }
                mRouteIndex = 0
                mRouteLoop = intent.getBooleanExtra(EXTRA_ROUTE_LOOP, false)
                mSegmentProgressMeters = 0.0
                calculateRouteDistances()
            }
            
            KailLog.i(this, "ServiceGo", "onStartCommand received lat=$mCurLat, lng=$mCurLng, runMode=$mRunMode")

            // Always ensure providers so third-party SDKs (e.g., Baidu) can receive GPS/Network updates
            if (mRunMode != "root") {
                ensureNorootProviders()
            }
            if (mRunMode == "root") {
                // Move portal init/update to background thread to avoid ANR
                if (this::mLocHandler.isInitialized) {
                    mLocHandler.post {
                        portalInitIfNeeded()
                        portalStartIfNeeded()
                        portalUpdateOnce()
                    }
                }
            }

            startLocationLoop()

            if (this::mJoyStick.isInitialized) {
                try {
                    mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt)
                    if (joystickEnabled) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                            if (mRoutePoints.isNotEmpty()) {
                                mJoyStick.showRouteControl(mSpeed * 3.6) // Show Route Control if route exists
                            } else {
                                mJoyStick.show() // Show Manual Joystick otherwise
                            }
                        } else {
                            GoUtils.DisplayToast(applicationContext, "请授予悬浮窗权限")
                        }
                    } else {
                        mJoyStick.hide()
                    }
                } catch (e: Exception) {
                    KailLog.e(this, "ServiceGo", "Error setting current position or showing joystick: ${e.message}")
                }
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 服务销毁回调。
     * 清理资源、广播接收器并停止前台服务。
     */
    override fun onDestroy() {
        KailLog.i(this, "ServiceGo", "onDestroy started")
        try {
            val intent = Intent(ACTION_STATUS_CHANGED)
            intent.putExtra(EXTRA_IS_SIMULATING, false)
            intent.putExtra(EXTRA_IS_PAUSED, false)
            intent.setPackage(packageName)
            sendBroadcast(intent)

            isStop = true
            if (this::mLocHandler.isInitialized) {
                mLocHandler.removeMessages(HANDLER_MSG_ID)
            }
            if (this::mLocHandlerThread.isInitialized) {
                mLocHandlerThread.quit()
            }

            if (this::mJoyStick.isInitialized) {
                mJoyStick.destroy()
            }

            if (mRunMode != "root") {
                removeTestProviderNetwork()
                removeTestProviderGPS()
            } else {
                portalStopSafe()
            }

            mActReceiver?.let { unregisterReceiver(it) }
            mActReceiver = null
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            KailLog.e(this, "ServiceGo", "Error in onDestroy: ${e.message}")
        }

        super.onDestroy()
        KailLog.i(this, "ServiceGo", "onDestroy finished")
    }

    /**
     * 初始化前台服务通知。
     * 同时注册通知栏操作的广播接收器。
     */
    private fun initNotification() {
        if (mActReceiver == null) {
            mActReceiver = NoteActionReceiver()
            val filter = IntentFilter()
            filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)
            filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)
            ContextCompat.registerReceiver(
                this,
                mActReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        val mChannel = NotificationChannel(
            SERVICE_GO_NOTE_CHANNEL_ID,
            SERVICE_GO_NOTE_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        
        notificationManager?.createNotificationChannel(mChannel)
        
        //准备intent
        val clickIntent = Intent(this, LocationPickerActivity::class.java)
        val clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE)
        val showIntent = Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)
        val showPendingPI =
            PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE)
        val hideIntent = Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)
        val hidePendingPI =
            PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
            .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
            .setContentTitle(resources.getString(R.string.app_name))
            .setContentText(resources.getString(R.string.app_service_tips))
            .setContentIntent(clickPI)
            .addAction(
                NotificationCompat.Action(
                    null,
                    resources.getString(R.string.note_show),
                    showPendingPI
                )
            )
            .addAction(
                NotificationCompat.Action(
                    null,
                    resources.getString(R.string.note_hide),
                    hidePendingPI
                )
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        
        mNotification = notification

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_GO_NOTE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(SERVICE_GO_NOTE_ID, notification)
        }
    }

    /**
     * 初始化摇杆并设置监听器。
     */
    private fun initJoyStick() {
        mJoyStick = JoyStick(this)
        mJoyStick.setListener(object : JoyStick.JoyStickClickListener {
            override fun onMoveInfo(speed: Double, disLng: Double, disLat: Double, angle: Double) {
                mSpeed = speed
                val next = GeoPredict.nextByDisplacementKm(mCurLng, mCurLat, disLng, disLat)
                mCurLng = next.first
                mCurLat = next.second
                mCurBea = angle.toFloat()
            }

            override fun onPositionInfo(lng: Double, lat: Double, alt: Double) {
                mCurLng = lng
                mCurLat = lat
                mCurAlt = alt
            }

            override fun onRouteControl(action: String) {
                val intent = Intent(this@ServiceGo, ServiceGo::class.java)
                intent.putExtra(EXTRA_CONTROL_ACTION, action)
                startService(intent)
            }

            override fun onRouteSeek(progress: Float) {
                val intent = Intent(this@ServiceGo, ServiceGo::class.java)
                intent.putExtra(EXTRA_CONTROL_ACTION, CONTROL_SEEK)
                intent.putExtra(EXTRA_SEEK_RATIO, progress)
                startService(intent)
            }
            
            override fun onRouteSpeedChange(speed: Double) {
                mSpeed = speed / 3.6 // km/h to m/s
            }
        })
        // mJoyStick.show() // Removed to avoid unconditional show on init
    }

    /**
     * 初始化定位更新的后台线程与 Handler。
     */
    private fun initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND)
        // 启动 HandlerThread 线程
        mLocHandlerThread.start()
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = object : Handler(mLocHandlerThread.looper) {
            override fun handleMessage(msg: Message) {
                try {
                    Thread.sleep(100)

                    // If not paused, advance the position along the route
                    if (!isStop) {
                        if (mRoutePoints.size >= 2) {
                            val speedForStep = if (speedFluctuation) {
                                GeoPredict.randomInRangeWithMean(mSpeed * 0.5, mSpeed * 1.5, mSpeed)
                            } else {
                                mSpeed
                            }
                            advanceAlongRoute(speedForStep * 0.1)
                            updateJoystickStatus()
                        }
                    }

                    // Always push mock location, even when paused (isStop=true).
                    // This prevents the system from reverting to real location while paused.
                    if (mRunMode == "root") {
                        portalTick()
                    } else {
                        setLocationNetwork()
                        setLocationGPS()
                    }

                    // Always schedule next update as long as the service is alive.
                    // The loop is only truly stopped in onDestroy.
                    sendEmptyMessage(HANDLER_MSG_ID)
                } catch (e: InterruptedException) {
                    KailLog.e(this@ServiceGo, "ServiceGo", "handleMessage interrupted: ${e.message}")
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    KailLog.e(this@ServiceGo, "ServiceGo", "handleMessage exception: ${e.message}")
                    // 防止死循环崩溃，稍微延迟后再发送消息
                    if (!isStop) {
                         sendEmptyMessageDelayed(HANDLER_MSG_ID, 1000)
                    }
                }
            }
        }
    }

    /**
     * 启动定位循环：
     * - 将 isStop 置为 false
     * - 发送首个 Handler 消息，进入 100ms 周期的更新循环
     */
    private fun startLocationLoop() {
        if (!this::mLocHandler.isInitialized) return
        isStop = false
        if (locationLoopStarted) return // Already running the loop
        locationLoopStarted = true
        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID)
    }

    /**
     * 确保系统 GPS/Network TestProvider 已添加并启用（先移除再添加）。
     * 第三方定位 SDK（如百度）依赖系统 Provider 才能接收位置更新。
     */
    private fun ensureNorootProviders() {
        try {
            removeTestProviderNetwork()
            addTestProviderNetwork()
            removeTestProviderGPS()
            addTestProviderGPS()
        } catch (e: Throwable) {
            KailLog.e(this, "ServiceGo", "Error ensuring providers: ${e.message}")
        }
    }

    /**
     * 初始化 portal 命令通道：
     * - 通过 sendExtraCommand("portal", "exchange_key") 交换随机 key
     * - 成功后缓存 key 供后续命令使用
     */
    private fun portalInitIfNeeded(): Boolean {
        if (portalRandomKey != null) return true
        val rely = Bundle()
        KailLog.i(this, "ServiceGo", "sending exchange_key...")
        val ok = kotlin.runCatching {
            mLocManager.sendExtraCommand(PORTAL_PROVIDER, "exchange_key", rely)
        }.onFailure {
            KailLog.e(this, "ServiceGo", "sendExtraCommand exception: ${it.message}")
        }.getOrDefault(false)
        if (!ok) {
            KailLog.e(this, "ServiceGo", "exchange_key failed (sendExtraCommand returned false)")
            return false
        }
        val key = rely.getString("key")
        if (key.isNullOrBlank()) {
            KailLog.e(this, "ServiceGo", "exchange_key failed (key is null/blank)")
            return false
        }
        KailLog.i(this, "ServiceGo", "exchange_key success, key=$key")
        portalRandomKey = key
        return true
    }

    /**
     * 通过 portal 通道发送命令：
     * - 使用交换得到的 key 作为 command
     * - 写入 command_id 与参数到 Bundle
     */
    private fun portalSend(commandId: String, block: Bundle.() -> Unit = {}): Boolean {
        val key = portalRandomKey ?: return false
        val rely = Bundle()
        rely.putString("command_id", commandId)
        rely.block()
        KailLog.i(this, "ServiceGo", "PORTAL发送：cmd=$commandId，内容=$rely",isHighFrequency = true)
        val ok = kotlin.runCatching {
            mLocManager.sendExtraCommand(PORTAL_PROVIDER, key, rely)
        }.onFailure {
             KailLog.e(this, "ServiceGo", "portalSend exception command=$commandId: ${it.message}")
             // If we get an exception, something is wrong with the connection, reset key
             portalRandomKey = null
             portalStarted = false
        }.getOrDefault(false)
        
        if (!ok) {
            KailLog.e(this, "ServiceGo", "PORTAL结果失败：cmd=$commandId，可能密钥失效",isHighFrequency = true)
            // If the command failed, maybe the key is invalid (system_server restarted)
            // We should reset and try to re-init next time
            portalRandomKey = null
            portalStarted = false
        } else {
            KailLog.i(this, "ServiceGo", "PORTAL结果成功：cmd=$commandId",isHighFrequency = true)
        }
        return ok
    }

    /**
     * 启动 Xposed 侧模拟引擎（仅一次）：
     * - 若未初始化先交换 key
     * - 发送 start 命令（速度/海拔/精度）
     * - 同步步频设置
     */
    private fun portalStartIfNeeded(): Boolean {
        if (portalStarted) return true
        if (!portalInitIfNeeded()) {
            KailLog.e(this, "ServiceGo", "portalStartIfNeeded failed because init failed")
            return false
        }
        val ok = portalSend("start") {
            putDouble("speed", mSpeed)
            putDouble("altitude", mCurAlt)
            putFloat("accuracy", 1.0f)
        }
        if (ok) {
            KailLog.i(this, "ServiceGo", "portal start command success")
            portalStarted = true
            portalSend("set_step_enabled") { putBoolean("enabled", stepEnabledCache) }
            portalSend("set_step_cadence") { putFloat("cadence", stepFreqCache.toFloat()) }
        } else {
            KailLog.e(this, "ServiceGo", "portal start command failed")
        }
        return ok
    }

    /**
     * 启动后立即同步一次状态到 Xposed：
     * - set_altitude / set_speed / set_bearing / update_location / broadcast_location
     */
    private fun portalUpdateOnce() {
        if (!portalStartIfNeeded()) {
            KailLog.e(this, "ServiceGo", "portalUpdateOnce failed because start failed")
            return
        }
        portalSend("set_altitude") { putDouble("altitude", mCurAlt) }
        portalSend("set_speed") { putFloat("speed", mSpeed.toFloat()) }
        portalSend("set_bearing") { putDouble("bearing", mCurBea.toDouble()) }
        portalSend("update_location") {
            putDouble("lat", mCurLat)
            putDouble("lon", mCurLng)
            putString("mode", "=")
        }
        portalSend("broadcast_location")
    }

    /**
     * 循环周期内向 Xposed 下发最新速度、朝向与位置并广播。
     */
    private fun portalTick() {
        if (!portalStartIfNeeded()) return
        val speedToSet = if (isStop) 0.0f else mSpeed.toFloat()
        
        KailLog.log(this, "ServiceGo", "Portal Tick: lat=$mCurLat, lng=$mCurLng, speed=$speedToSet", isHighFrequency = true)

        portalSend("set_speed") { putFloat("speed", speedToSet) }
        portalSend("set_bearing") { putDouble("bearing", mCurBea.toDouble()) }
        portalSend("update_location") {
            putDouble("lat", mCurLat)
            putDouble("lon", mCurLng)
            putString("mode", "=")
        }
        portalSend("broadcast_location")
    }

    

    /**
     * 安全停止 Xposed 侧模拟引擎并清理状态。
     */
    private fun portalStopSafe() {
        kotlin.runCatching {
            portalSend("stop")
        }
        portalStarted = false
        portalRandomKey = null
    }

    /**
     * 按给定距离推进路线：
     * - 在当前路段上累积进度，满段切换下一段
     * - 线性插值当前位置与方位角
     * - 处理循环/非循环的结束逻辑
     */
    private fun advanceAlongRoute(distanceMeters: Double) {
        var remaining = distanceMeters
        while (remaining > 0 && mRoutePoints.size >= 2) {
            val startIdx = mRouteIndex
            val endIdx = if (startIdx + 1 < mRoutePoints.size) startIdx + 1 else -1
            if (endIdx == -1) {
                if (mRouteLoop) {
                    mRouteIndex = 0
                    mSegmentProgressMeters = 0.0
                    continue
                } else {
                    mRoutePoints.clear()
                    mRouteIndex = 0
                    mSegmentProgressMeters = 0.0
                    break
                }
            }
            val a = mRoutePoints[startIdx]
            val b = mRoutePoints[endIdx]
            val midLat = (a.second + b.second) / 2.0
            val metersPerDegLat = GeoMath.metersPerDegLat(midLat)
            val metersPerDegLng = GeoMath.metersPerDegLng(midLat)
            val dLatDeg = b.second - a.second
            val dLngDeg = b.first - a.first
            val segLenMeters = kotlin.math.sqrt((dLatDeg * metersPerDegLat) * (dLatDeg * metersPerDegLat) + (dLngDeg * metersPerDegLng) * (dLngDeg * metersPerDegLng))
            if (segLenMeters <= 0.0) {
                mRouteIndex++
                mSegmentProgressMeters = 0.0
                if (mRouteIndex >= mRoutePoints.size - 1) {
                    if (mRouteLoop) {
                        mRouteIndex = 0
                    } else {
                        mRoutePoints.clear()
                        mRouteIndex = 0
                        break
                    }
                }
                continue
            }
            val available = segLenMeters - mSegmentProgressMeters
            if (remaining >= available) {
                mCurLng = b.first
                mCurLat = b.second
                mCurBea = GeoMath.bearingDegrees(a.first, a.second, b.first, b.second)
                remaining -= available
                mRouteIndex++
                mSegmentProgressMeters = 0.0
                if (mRouteIndex >= mRoutePoints.size - 1) {
                    if (mRouteLoop) {
                        mRouteIndex = 0
                    } else {
                        mRoutePoints.clear()
                        mRouteIndex = 0
                        break
                    }
                }
            } else {
                mSegmentProgressMeters += remaining
                val f = mSegmentProgressMeters / segLenMeters
                mCurLng = a.first + dLngDeg * f
                mCurLat = a.second + dLatDeg * f
                mCurBea = GeoMath.bearingDegrees(a.first, a.second, b.first, b.second)
                remaining = 0.0
            }
        }
    }

    /**
     * 预计算路线累计距离数组与总距离，供进度与 Seek 使用。
     */
    private fun calculateRouteDistances() {
        mRouteCumulativeDistances.clear()
        mRouteCumulativeDistances.add(0.0)
        var total = 0.0
        for (i in 0 until mRoutePoints.size - 1) {
            val a = mRoutePoints[i]
            val b = mRoutePoints[i + 1]
            val midLat = (a.second + b.second) / 2.0
            val metersPerDegLat = 110.574 * 1000.0
            val metersPerDegLng = 111.320 * 1000.0 * kotlin.math.cos(kotlin.math.abs(midLat) * Math.PI / 180.0)
            val dLatDeg = b.second - a.second
            val dLngDeg = b.first - a.first
            val seg = kotlin.math.sqrt((dLatDeg * metersPerDegLat) * (dLatDeg * metersPerDegLat) + (dLngDeg * metersPerDegLng) * (dLngDeg * metersPerDegLng))
            total += seg
            mRouteCumulativeDistances.add(total)
        }
        mTotalDistance = total
    }

    /**
     * 更新摇杆悬浮窗的路线进度与当前位置展示。
     */
    private fun updateJoystickStatus() {
        if (this::mJoyStick.isInitialized && mRoutePoints.isNotEmpty()) {
            val currentDist = if (mRouteIndex < mRouteCumulativeDistances.size)
                mRouteCumulativeDistances[mRouteIndex] + mSegmentProgressMeters
            else mTotalDistance

            val progress = if (mTotalDistance > 0) (currentDist / mTotalDistance).toFloat() else 0f
            val distStr = if (currentDist > 1000) String.format("%.2fkm", currentDist / 1000) else String.format("%.0fm", currentDist)
            val totalDistStr = if (mTotalDistance > 1000) String.format("%.2fkm", mTotalDistance / 1000) else String.format("%.0fm", mTotalDistance)

            val displayStr = "$distStr / $totalDistStr"

            val bd = MapUtils.wgs2bd(mCurLng, mCurLat)
            val latLng = LatLng(bd[1], bd[0])

            mJoyStick.updateRouteStatus(progress, displayStr, latLng)
        }
    }

    /**
     * 计算两点之间的方位角（单位：度）。
     */
    // bearingDegrees moved to GeoMath

    /**
     * 移除 GPS 测试提供者。
     */
    private fun removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            }
        } catch (e: Exception) {
            KailLog.e(this, "ServiceGo", "removeTestProviderGPS error: ${e.message}")
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("WrongConstant")
    /**
     * 添加并启用 GPS 测试提供者（Android S 及以上使用 ProviderProperties）。
     */
    private fun addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    mLocManager.addTestProvider(
                        LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    mLocManager.addTestProvider(
                        LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, 3 /* POWER_HIGH */, 1 /* ACCURACY_FINE */
                    )
                }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            }
        } catch (e: Exception) {
            KailLog.e(this, "ServiceGo", "addTestProviderGPS error: ${e.message}")
        }
    }

    /**
     * 为 GPS 提供者设置模拟位置（精度、海拔、方向、速度等属性）。
     */
    private fun setLocationGPS() {
        try {
            // 尽可能模拟真实的 GPS 数据
            val loc = Location(LocationManager.GPS_PROVIDER)
            loc.accuracy = 1.0f // ACCURACY_FINE
            loc.altitude = mCurAlt                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.bearing = mCurBea                       // 方向（度）
            loc.latitude = mCurLat                   // 纬度（度）
            loc.longitude = mCurLng                  // 经度（度）
            loc.time = System.currentTimeMillis()    // 本地时间
            val speedToSet = if (isStop) 0.0f else mSpeed.toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                loc.speed = speedToSet
                loc.speedAccuracyMetersPerSecond = 0.1f
                loc.verticalAccuracyMeters = 0.1f
                loc.bearingAccuracyDegrees = 0.1f
            } else {
                loc.speed = speedToSet
            }
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            val bundle = Bundle()
            bundle.putInt("satellites", 7)
            loc.extras = bundle

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (e: Exception) {
            KailLog.e(this, "ServiceGo", "setLocationGPS error: ${e.message}")
        }
    }

    /**
     * 移除 Network 测试提供者。
     */
    private fun removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            KailLog.e(this, "ServiceGo", "removeTestProviderNetwork error: ${e.message}")
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("WrongConstant")
    /**
     * Adds the Network test provider with appropriate settings.
     * Uses ProviderProperties on Android S and above; falls back to
     * deprecated integer constants on older versions. Ensures the
     * provider is enabled after addition.
     */
    private fun addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(
                    LocationManager.NETWORK_PROVIDER, true, false,
                    true, true, true, true,
                    true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE
                )
            } else {
                @Suppress("DEPRECATION")
                mLocManager.addTestProvider(
                    LocationManager.NETWORK_PROVIDER, true, false,
                    true, true, true, true,
                    true, 1 /* POWER_LOW */, 2 /* ACCURACY_COARSE */
                )
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            }
        } catch (e: SecurityException) {
            KailLog.e(this, "ServiceGo", "addTestProviderNetwork error: ${e.message}")
        }
    }

    /**
     * 为 Network 提供者设置模拟位置（精度、海拔、方向、速度等属性）。
     */
    private fun setLocationNetwork() {
        try {
            // 尽可能模拟真实的 GPS 数据
            val loc = Location(LocationManager.NETWORK_PROVIDER)
            loc.accuracy = 1.0f // ACCURACY_FINE
            loc.altitude = mCurAlt                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.bearing = mCurBea                       // 方向（度）
            loc.latitude = mCurLat                   // 纬度（度）
            loc.longitude = mCurLng                  // 经度（度）
            loc.time = System.currentTimeMillis()    // 本地时间
            val speedToSet = if (isStop) 0.0f else mSpeed.toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                loc.speed = speedToSet
                loc.speedAccuracyMetersPerSecond = 0.1f
                loc.verticalAccuracyMeters = 0.1f
                loc.bearingAccuracyDegrees = 0.1f
            } else {
                loc.speed = speedToSet
            }
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            val bundle = Bundle()
            bundle.putInt("satellites", 7)
            loc.extras = bundle

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc)
        } catch (e: Exception) {
            KailLog.e(this, "ServiceGo", "setLocationNetwork error: ${e.message}")
        }
    }

    /**
     * 通知栏操作（显示/隐藏摇杆）的广播接收器。
     */
    inner class NoteActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action != null) {
                if (action == SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW) {
                    mJoyStick.show()
                }
                if (action == SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE) {
                    mJoyStick.hide()
                }
            }
        }
    }

    /**
     * ServiceGo 的 Binder。
     */
    inner class ServiceGoBinder : Binder() {
        fun getService(): ServiceGo {
            return this@ServiceGo
        }
    }
}
