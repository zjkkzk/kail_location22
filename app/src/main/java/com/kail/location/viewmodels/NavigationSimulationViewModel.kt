package com.kail.location.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.route.BikingRouteResult
import com.baidu.mapapi.search.route.DrivingRoutePlanOption
import com.baidu.mapapi.search.route.DrivingRouteResult
import com.baidu.mapapi.search.route.IndoorRouteResult
import com.baidu.mapapi.search.route.IntegralRouteResult
import com.baidu.mapapi.search.route.MassTransitRouteResult
import com.baidu.mapapi.search.route.OnGetRoutePlanResultListener
import com.baidu.mapapi.search.route.PlanNode
import com.baidu.mapapi.search.route.RoutePlanSearch
import com.baidu.mapapi.search.route.TransitRouteResult
import com.baidu.mapapi.search.route.WalkingRouteResult
import com.baidu.mapapi.search.sug.OnGetSuggestionResultListener
import com.baidu.mapapi.search.sug.SuggestionResult
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption
import com.kail.location.models.RouteInfo
import com.kail.location.service.ServiceGo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Intent
import androidx.core.content.ContextCompat
import android.util.Log
import com.kail.location.models.UpdateInfo
import com.kail.location.utils.UpdateChecker
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay


import com.kail.location.data.local.AppDatabase
import com.kail.location.repositories.HistoryRepository

class NavigationSimulationViewModel(application: Application) : AndroidViewModel(application) {

    private val historyRepository: HistoryRepository = HistoryRepository(
        AppDatabase.getDatabase(application).historyDao()
    )

    // --- State ---
    private val _startPoint = MutableStateFlow<String>("")
    val startPoint: StateFlow<String> = _startPoint.asStateFlow()

    private val _startLatLng = MutableStateFlow<LatLng?>(null)
    val startLatLng: StateFlow<LatLng?> = _startLatLng.asStateFlow()

    private val _endPoint = MutableStateFlow<String>("")
    val endPoint: StateFlow<String> = _endPoint.asStateFlow()

    private val _endLatLng = MutableStateFlow<LatLng?>(null)
    val endLatLng: StateFlow<LatLng?> = _endLatLng.asStateFlow()

    private val _isMultiRoute = MutableStateFlow(false)
    val isMultiRoute: StateFlow<Boolean> = _isMultiRoute.asStateFlow()

    private val _historyList = MutableStateFlow<List<RouteInfo>>(emptyList())
    val historyList: StateFlow<List<RouteInfo>> = _historyList.asStateFlow()

    // Search Suggestions
    private val _searchResults = MutableStateFlow<List<Map<String, Any>>>(emptyList())
    val searchResults: StateFlow<List<Map<String, Any>>> = _searchResults.asStateFlow()
    
    // UI State
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // Services
    private val suggestionSearch: SuggestionSearch = SuggestionSearch.newInstance()
    private val routePlanSearch: RoutePlanSearch = RoutePlanSearch.newInstance()
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    
    private val _runMode = MutableStateFlow("noroot")
    val runMode: StateFlow<String> = _runMode.asStateFlow()
    
    private val _speed = MutableStateFlow(60.0)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _candidateRoutes = MutableStateFlow<List<List<LatLng>>>(emptyList())
    val candidateRoutes: StateFlow<List<List<LatLng>>> = _candidateRoutes.asStateFlow()

    private val _currentLatLng = MutableStateFlow<LatLng?>(null)
    val currentLatLng: StateFlow<LatLng?> = _currentLatLng.asStateFlow()
    private var monitorJob: kotlinx.coroutines.Job? = null

    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ServiceGo.ACTION_STATUS_CHANGED) {
                val isSim = intent.getBooleanExtra(ServiceGo.EXTRA_IS_SIMULATING, false)
                val isPau = intent.getBooleanExtra(ServiceGo.EXTRA_IS_PAUSED, false)
                _isSimulating.value = isSim
                _isPaused.value = isPau
                if (isSim) {
                    startLocationMonitor()
                } else {
                    stopLocationMonitor()
                }
            }
        }
    }


    companion object {
        const val POI_NAME = "name"
        const val POI_ADDRESS = "address"
        const val POI_LATITUDE = "latitude"
        const val POI_LONGITUDE = "longitude"
    }

    init {
        viewModelScope.launch {
            historyRepository.recentRoutes.collect { entities ->
                _historyList.value = entities.map { entity ->
                    RouteInfo(
                        id = entity.id.toString(),
                        startName = entity.startName,
                        endName = entity.endName,
                        distance = "${entity.startLat},${entity.startLng}|${entity.endLat},${entity.endLng}" // Store coords in distance for retrieval
                    )
                }
            }
        }

        _runMode.value = sharedPreferences.getString("setting_run_mode", "noroot") ?: "noroot"
        initSearchListeners()

        // Register receiver
        val filter = android.content.IntentFilter(ServiceGo.ACTION_STATUS_CHANGED)
        ContextCompat.registerReceiver(application, statusReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun selectHistoryRoute(route: RouteInfo) {
        try {
            val parts = route.distance.split("|")
            if (parts.size == 2) {
                val startParts = parts[0].split(",")
                val endParts = parts[1].split(",")
                if (startParts.size == 2 && endParts.size == 2) {
                    val startLat = startParts[0].toDoubleOrNull()
                    val startLng = startParts[1].toDoubleOrNull()
                    val endLat = endParts[0].toDoubleOrNull()
                    val endLng = endParts[1].toDoubleOrNull()
                    
                    if (startLat != null && startLng != null && endLat != null && endLng != null) {
                        selectStartPoint(route.startName, startLat, startLng)
                        selectEndPoint(route.endName, endLat, endLng)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun checkUpdate(context: Context, isAuto: Boolean = false) {
        UpdateChecker.check(context) { info, error ->
            if (info != null) {
                _updateInfo.value = info
            } else {
                if (!isAuto) {
                    // Use MainExecutor to show toast
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (error != null) {
                            Toast.makeText(context, "检查更新失败: $error", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateInfo.value = null
    }

    private fun initSearchListeners() {
        suggestionSearch.setOnGetSuggestionResultListener(object : OnGetSuggestionResultListener {
            override fun onGetSuggestionResult(res: SuggestionResult?) {
                if (res == null || res.allSuggestions == null) {
                    _searchResults.value = emptyList()
                    return
                }
                val results = res.allSuggestions.mapNotNull { suggestion ->
                    if (suggestion.pt == null) null
                    else mapOf(
                        POI_NAME to (suggestion.key ?: ""),
                        POI_ADDRESS to (suggestion.address ?: ""),
                        POI_LATITUDE to suggestion.pt.latitude,
                        POI_LONGITUDE to suggestion.pt.longitude
                    )
                }
                _searchResults.value = results
            }
        })

        routePlanSearch.setOnGetRoutePlanResultListener(object : OnGetRoutePlanResultListener {
            override fun onGetWalkingRouteResult(p0: WalkingRouteResult?) {}
            override fun onGetTransitRouteResult(p0: TransitRouteResult?) {}
            override fun onGetMassTransitRouteResult(p0: MassTransitRouteResult?) {}
            override fun onGetDrivingRouteResult(result: DrivingRouteResult?) {
                _isLoading.value = false
                if (result == null || result.error != SearchResult.ERRORNO.NO_ERROR) {
                    Log.e("NavSimVM", "Route plan failed: ${result?.error}")
                    return
                }
                if (result.routeLines.isNotEmpty()) {
                    val allRoutes = result.routeLines.map { it.allStep.flatMap { step -> step.wayPoints } }
                    if (_isMultiRoute.value && allRoutes.size > 1) {
                        _candidateRoutes.value = allRoutes
                    } else {
                        startSimulationService(allRoutes.first())
                    }
                }
            }
            override fun onGetIndoorRouteResult(p0: IndoorRouteResult?) {}
            override fun onGetBikingRouteResult(p0: BikingRouteResult?) {}
            override fun onGetIntegralRouteResult(p0: IntegralRouteResult?) {}
        })
    }
    
    fun setRunMode(mode: String) {
        _runMode.value = mode
        sharedPreferences.edit().putString("setting_run_mode", mode).apply()
    }
    
    fun setSpeed(value: Double) {
        _speed.value = value
    }

    fun search(query: String) {
        if (query.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        suggestionSearch.requestSuggestion(
            SuggestionSearchOption()
                .city("全国")
                .keyword(query)
        )
    }

    fun selectStartPoint(name: String, lat: Double, lng: Double) {
        _startPoint.value = name
        _startLatLng.value = LatLng(lat, lng)
        _searchResults.value = emptyList()
    }

    fun selectEndPoint(name: String, lat: Double, lng: Double) {
        _endPoint.value = name
        _endLatLng.value = LatLng(lat, lng)
        _searchResults.value = emptyList()
    }

    fun setMultiRoute(enabled: Boolean) {
        _isMultiRoute.value = enabled
    }

    fun startSimulation() {
        val start = _startLatLng.value
        val end = _endLatLng.value
        if (start == null || end == null) return

        _isLoading.value = true
        val stNode = PlanNode.withLocation(start)
        val enNode = PlanNode.withLocation(end)

        // Default to Driving Route
        routePlanSearch.drivingSearch(
            DrivingRoutePlanOption()
                .from(stNode)
                .to(enNode)
        )
        
        // Save to history (Mock implementation)
        addToHistory(_startPoint.value, _endPoint.value)
    }

    private fun startSimulationService(points: List<LatLng>) {
        val app = getApplication<Application>()
        val intent = Intent(app, ServiceGo::class.java)
        
        // Flatten List<LatLng> to DoubleArray [lng1, lat1, lng2, lat2, ...]
        val pointsArray = DoubleArray(points.size * 2)
        for (i in points.indices) {
            pointsArray[i * 2] = points[i].longitude
            pointsArray[i * 2 + 1] = points[i].latitude
        }
        
        intent.putExtra(ServiceGo.EXTRA_ROUTE_POINTS, pointsArray)
        intent.putExtra(ServiceGo.EXTRA_ROUTE_LOOP, false) // Default no loop for A-B nav
        intent.putExtra(ServiceGo.EXTRA_JOYSTICK_ENABLED, true)
        intent.putExtra(ServiceGo.EXTRA_ROUTE_SPEED, _speed.value.toFloat())
        intent.putExtra(ServiceGo.EXTRA_COORD_TYPE, ServiceGo.COORD_BD09)
        intent.putExtra(ServiceGo.EXTRA_RUN_MODE, runMode.value)
        
        ContextCompat.startForegroundService(app, intent)
        _isSimulating.value = true
        _isPaused.value = false
        startLocationMonitor()
    }

    private fun addToHistory(start: String, end: String) {
        val startLat = _startLatLng.value?.latitude ?: 0.0
        val startLng = _startLatLng.value?.longitude ?: 0.0
        val endLat = _endLatLng.value?.latitude ?: 0.0
        val endLng = _endLatLng.value?.longitude ?: 0.0
        
        viewModelScope.launch {
            historyRepository.addRoute(start, end, startLat, startLng, endLat, endLng)
        }
    }
    
    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearHistory()
        }
    }

    fun chooseCandidate(index: Int) {
        val routes = _candidateRoutes.value
        if (index in routes.indices) {
            startSimulationService(routes[index])
            _candidateRoutes.value = emptyList()
        }
    }

    fun pauseSimulation() {
        val app = getApplication<Application>()
        val intent = Intent(app, ServiceGo::class.java)
        intent.putExtra(ServiceGo.EXTRA_CONTROL_ACTION, ServiceGo.CONTROL_PAUSE)
        app.startService(intent)
        _isPaused.value = true
    }

    fun resumeSimulation() {
        val app = getApplication<Application>()
        val intent = Intent(app, ServiceGo::class.java)
        intent.putExtra(ServiceGo.EXTRA_CONTROL_ACTION, ServiceGo.CONTROL_RESUME)
        app.startService(intent)
        _isPaused.value = false
    }

    fun stopSimulation() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, ServiceGo::class.java))
        _isSimulating.value = false
        _isPaused.value = false
        stopLocationMonitor()
    }

    fun seekProgress(ratio: Float) {
        val app = getApplication<Application>()
        val intent = Intent(app, ServiceGo::class.java)
        intent.putExtra(ServiceGo.EXTRA_CONTROL_ACTION, ServiceGo.CONTROL_SEEK)
        intent.putExtra(ServiceGo.EXTRA_SEEK_RATIO, ratio)
        app.startService(intent)
    }

    private fun startLocationMonitor() {
        stopLocationMonitor()
        val app = getApplication<Application>()
        val lm = app.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        monitorJob = viewModelScope.launch {
            while (_isSimulating.value) {
                try {
                    if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        val gps = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        val net = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        val loc = gps ?: net
                        if (loc != null) {
                            _currentLatLng.value = LatLng(loc.latitude, loc.longitude)
                        }
                    }
                } catch (_: Exception) {}
                delay(1000)
            }
        }
    }

    private fun stopLocationMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        suggestionSearch.destroy()
        routePlanSearch.destroy()
    }
}
