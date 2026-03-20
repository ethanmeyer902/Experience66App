package com.example.experience66hello

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.net.Uri
import android.speech.tts.TextToSpeech
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.style
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.layers.addLayer


/**
 * Main activity for Route 66 Experience app
 * Handles map display, POI search, geofencing, and archive integration
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "MainActivity"
    }
    private var topUpdateButton: Button? = null
    private lateinit var mapView: MapView
    private lateinit var geofenceManager: GeofenceManager
    private lateinit var offlineMapManager: OfflineMapManager
    private lateinit var offlineDataCache: OfflineDataCache
    private lateinit var archiveRepository: ArchiveRepository
    private lateinit var route66DatabaseRepository: Route66DatabaseRepository
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var hasCenteredOnUserLocation = false
    //
    private var pendingGeofenceInit = false
    private var isStyleLoaded = false
    private var pendingMarkers = false

    private var pointAnnotationManager: PointAnnotationManager? = null
    private var circleAnnotationManager: CircleAnnotationManager? = null
    
    // Track which landmarks the user is currently inside
    private val activeLandmarks = mutableSetOf<String>()
    
    // Log of geofence events (enter/exit/dwell)
    private val geofenceEventLog = mutableListOf<GeofenceEvent>()
    
    // UI components for the geofence monitor panel
    private lateinit var monitorPanel: LinearLayout
    private lateinit var geofenceListTextView: TextView
    private lateinit var eventLogTextView: TextView
    private var isMonitorVisible = false
    
    // UI components for offline status bar
    private lateinit var offlineStatusBar: LinearLayout
    private lateinit var offlineStatusText: TextView
    private lateinit var offlineStatusIcon: TextView
    private lateinit var downloadProgress: ProgressBar
    private var isOnline = true
    
    // Network monitoring to detect when device goes offline
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    // Text-to-Speech for reading landmark descriptions
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    
    // Current navigation destination point
    private var currentDestinationPoint: Point? = null

    // ID of the landmark currently being displayed
    private var currentLandmarkId: String? = null
    
    // Archive items matched to the current landmark (used by About button)
    private var currentLandmarkArchiveItems: List<Route66ArchiveItem> = emptyList()

    // POI detail card UI components
    private lateinit var detailCard: LinearLayout
    private lateinit var detailTitleText: TextView
    private lateinit var detailImageView: ImageView
    private lateinit var detailDescriptionText: TextView
    private lateinit var detailExtraText: TextView
    private lateinit var detailListenButton: Button
    private lateinit var detailAboutButton: Button

    // Map annotation → landmark mapping for marker clicks
    private val landmarkByAnnotationId = mutableMapOf<String, Route66Landmark>()
    
    // Search UI Components
    private lateinit var searchBar: EditText
    private lateinit var searchResultsScrollView: ScrollView
    private lateinit var searchResultsContainer: LinearLayout
    private lateinit var searchPanel: LinearLayout
    private var isSearchVisible = false
    private var currentSearchResults: List<Route66ArchiveItem> = emptyList()
    
    // Archive Item Detail Card
    private lateinit var archiveDetailCard: LinearLayout
    private lateinit var archiveDetailTitle: TextView
    private lateinit var archiveDetailCallNumber: TextView
    private lateinit var archiveDetailContentDm: TextView
    private lateinit var archiveDetailItemNumber: TextView
    private lateinit var archiveDetailUrl: TextView
    private lateinit var archiveOpenButton: Button
    private lateinit var archiveCloseButton: Button

    data class GeofenceEvent(
        val landmarkId: String,
        val landmarkName: String,
        val transitionType: String,
        val timestamp: Long
    )

    // Permission launcher for fine location
    private val requestFineLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                checkBackgroundLocationPermission()
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_LONG).show()
            }
        }
    
    // Permission launcher for background location (required for geofencing)
    private val requestBackgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                initializeGeofencing()
            } else {
                Toast.makeText(
                    this,
                    "Background location needed for geofence detection",
                    Toast.LENGTH_LONG
                ).show()
                // Still enable location but geofencing won't work in background
                enableUserLocation()
            }
        }
    //POI List
    private val poiListLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val id = result.data?.getStringExtra("landmark_id") ?: return@registerForActivityResult
                showLandmarkCard(id)
            }
        }

    // Receiver for geofence events
    private val geofenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val landmarkId = intent.getStringExtra("landmark_id") ?: return
            val landmarkName = intent.getStringExtra("landmark_name") ?: "Unknown"
            val transitionType = intent.getStringExtra("transition_type") ?: return
            val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
            
            handleGeofenceEvent(landmarkId, landmarkName, transitionType, timestamp)
        }
    }

    //onboarding
    private lateinit var onboardingOverlay: FrameLayout
    private val prefs by lazy { getSharedPreferences("experience66_prefs", MODE_PRIVATE) }
    private fun hasSeenOnboarding(): Boolean =
        prefs.getBoolean("seen_onboarding", false)
    private fun setSeenOnboarding() {
        prefs.edit().putBoolean("seen_onboarding", true).apply()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //initialize TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                isTtsReady = false
            }
        }


        // Initialize managers
        geofenceManager = GeofenceManager(this)
        offlineMapManager = OfflineMapManager(this)
        offlineDataCache = OfflineDataCache(this)
        archiveRepository = ArchiveRepository(this)
        route66DatabaseRepository = Route66DatabaseRepository(this)
        
        // Load Route 66 Database and initialize landmarks
        loadRoute66Database()
        
        // Initialize network monitoring
        setupNetworkMonitoring()
        
        // Create root layout
        val rootLayout = FrameLayout(this)
        
        // Create and add MapView
        mapView = MapView(this)
        rootLayout.addView(mapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        // C1: Create offline status bar at top
        createOfflineStatusBar(rootLayout)

        // Create and add Geofence Monitor UI
        createGeofenceMonitorUI(rootLayout)

        // Create and add Search UI
        createTopSearchAndButtons(rootLayout)
        createSearchResultsPanel(rootLayout)
        // Create and add POI detail card
        createLandmarkDetailCard(rootLayout)

        createArchiveDetailCard(rootLayout)

        setContentView(rootLayout)
        //onboarding
        if (!hasSeenOnboarding()) {
            createOnboardingOverlay(rootLayout)
            showOnboarding()
        }

        // Set camera to show Arizona Route 66 corridor
        mapView.mapboxMap.setCamera(
            CameraOptions.Builder()
                .center(Point.fromLngLat(-111.5, 35.1)) // Central Arizona
                .zoom(7.0) // See full Arizona Route 66
                .build()
        )

        mapView.mapboxMap.loadStyle(Style.MAPBOX_STREETS) { style ->
            isStyleLoaded = true
            setupAnnotationManagers()

            // ===== ROUTE 66 LINE =====

            val routeSource = geoJsonSource("route66-source") {
                url("asset://route66.geojson")
            }

            style.addSource(routeSource)

            val routeLayer = lineLayer("route66-layer", "route66-source") {
                lineColor("#FFC107")      // amber highlight
                lineWidth(6.0)
                lineOpacity(0.9)
            }

            style.addLayer(routeLayer)

            // ==========================

            if (pendingMarkers) {
                pendingMarkers = false
                addAllLandmarkMarkers()
                drawAllGeofenceCircles()
                updateGeofenceListDisplay()
                tryRegisterGeofencesIfReady()
            }

            requestLocationPermissions()
        }


        // Register broadcast receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                geofenceReceiver,
                IntentFilter(GeofenceBroadcastReceiver.ACTION_GEOFENCE_EVENT),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                geofenceReceiver,
                IntentFilter(GeofenceBroadcastReceiver.ACTION_GEOFENCE_EVENT)
            )
        }
        
        // C1: Initialize offline cache on startup
        initializeOfflineCache()
        
        // Preload archive data in background
        Thread {
            archiveRepository.loadArchiveData()
            Log.d(TAG, "Archive data preloaded")
        }.start()
    }
    /**
     * New user onboarding overlay
     */
    private fun createOnboardingOverlay(rootLayout: FrameLayout) {
        onboardingOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000")) // translucent black
            visibility = View.GONE
            isClickable = true // blocks touches to map underneath
            isFocusable = true
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
            elevation = 20f
        }

        val title = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            text = "Welcome to Experience66"
        }

        val body = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#424242"))
            setLineSpacing(6f, 1.05f)
            setPadding(0, 12.dp(), 0, 0)
        }

        // Progress dots
        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 16.dp(), 0, 0)
        }

        fun dot(isActive: Boolean): View {
            return View(this).apply {
                val size = 8.dp()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = 6.dp()
                    marginEnd = 6.dp()
                }
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@MainActivity,
                    android.R.drawable.presence_online
                )
                // presence_online is a circle-ish; we just tint it
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(if (isActive) "#1976D2" else "#BDBDBD")
                )
            }
        }

        val d1 = dot(true)
        val d2 = dot(false)
        val d3 = dot(false)
        val d4 = dot(false)
        dotsRow.addView(d1); dotsRow.addView(d2); dotsRow.addView(d3); dotsRow.addView(d4)

        // Buttons
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 18.dp(), 0, 0)
        }

        val skipBtn = Button(this).apply {
            text = "Skip"
            setOnClickListener { finishOnboarding() }
        }

        val nextBtn = Button(this).apply {
            text = "Next"
        }

        fun setDots(step: Int) {
            fun tintDot(v: View, active: Boolean) {
                v.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(if (active) "#1976D2" else "#BDBDBD")
                )
            }
            tintDot(d1, step == 0)
            tintDot(d2, step == 1)
            tintDot(d3, step == 2)
            tintDot(d4, step == 3)
        }

        val pages = listOf(
            "Explore historic Route 66 landmarks across Arizona.\n\nTap markers on the map to discover stories, photos, and archive materials.",
            "Use the search bar to find a POI or archive item.\n\nTap any marker on the map to open its detail card. \n\nOr use the POIs button to see a list.",
            "Tap MONITOR to see active geofences and entry/exit events.\n\n Tap UPDATE to cache landmarks and map data for offline use.\n\nWhen offline, the app will use cached data automatically.",
            "The ABOUT button inside a POI card will take you straight to the archive.\n\nTap NAVIGATE inside a POI card to get directions.\n\nTap LISTEN to hear the landmark description."
        )

        var step = 0
        body.text = pages[step]
        setDots(step)

        fun updateStepUI() {
            body.text = pages[step]
            setDots(step)
            nextBtn.text = if (step == pages.lastIndex) "Get Started" else "Next"
        }

        nextBtn.setOnClickListener {
            if (step < pages.lastIndex) {
                step++
                updateStepUI()
            } else {
                finishOnboarding()
            }
        }

        buttonsRow.addView(skipBtn)
        buttonsRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(12.dp(), 1) })
        buttonsRow.addView(nextBtn)

        card.addView(title)
        card.addView(body)
        card.addView(dotsRow)
        card.addView(buttonsRow)

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = 20.dp()
            rightMargin = 20.dp()
        }

        onboardingOverlay.addView(card, cardParams)

        rootLayout.addView(
            onboardingOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun showOnboarding() {
        onboardingOverlay.visibility = View.VISIBLE
    }

    private fun finishOnboarding() {
        setSeenOnboarding()
        onboardingOverlay.visibility = View.GONE
    }

    /**
     * Load Route 66 Database from CSV and initialize landmarks
     */
    private fun loadRoute66Database() {
        runOnUiThread {
            Toast.makeText(this, "Loading Route 66 Database...", Toast.LENGTH_SHORT).show()
        }
        
        Thread {
            try {
                val startTime = System.currentTimeMillis()
                Log.d(TAG, "Starting database load in background thread...")
                
                route66DatabaseRepository.loadDatabase()
                val landmarks = route66DatabaseRepository.getAllLandmarks()
                
                val loadTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Database load completed in ${loadTime}ms, loaded ${landmarks.size} landmarks")
                
                if (landmarks.isEmpty()) {
                    Log.w(TAG, "No landmarks loaded from database!")
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Warning: No POIs loaded. Check CSV file.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@Thread
                }
                
                runOnUiThread {
                    // Initialize ArizonaLandmarks with loaded data
                    ArizonaLandmarks.initialize(landmarks)

                    if (isStyleLoaded) {
                        // safe to draw now
                        pointAnnotationManager?.deleteAll()
                        circleAnnotationManager?.deleteAll()
                        landmarkByAnnotationId.clear()

                        addAllLandmarkMarkers()
                        drawAllGeofenceCircles()
                        updateGeofenceListDisplay()
                        tryRegisterGeofencesIfReady()
                    } else {
                        // style not ready yet; draw later
                        pendingMarkers = true
                    }

                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        tryRegisterGeofencesIfReady()
                    }

                    // If permissions were granted earlier, finish geofence setup now
                    val hasFine = ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true

                    if (pendingGeofenceInit && hasFine && hasBg) {
                        pendingGeofenceInit = false
                        initializeGeofencing()
                    }
                    Log.d(TAG, "Loaded ${landmarks.size} landmarks from Route 66 Database")
                    
                    Toast.makeText(
                        this,
                        "Loaded ${landmarks.size} POIs",
                        Toast.LENGTH_SHORT
                    ).show()

                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading Route 66 Database: ${e.message}", e)
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Error loading database: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
    
    /**
     * C1: Setup network connectivity monitoring
     */
    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    isOnline = true
                    updateOfflineStatusUI()
                    Log.d(TAG, "Network available - Online mode")
                }
            }
            
            override fun onLost(network: Network) {
                runOnUiThread {
                    isOnline = false
                    updateOfflineStatusUI()
                    Log.d(TAG, "Network lost - Offline mode")
                    
                    // Show toast about offline mode
                    if (offlineDataCache.isCacheValid()) {
                        Toast.makeText(
                            this@MainActivity,
                            "📴 Offline Mode - Using cached data",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        
        // Check initial state
        isOnline = isNetworkAvailable()
    }
    
    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    
    /**
     * C1: Create offline status bar UI
     */
    private fun createOfflineStatusBar(rootLayout: FrameLayout) {
        offlineStatusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#4CAF50")) // Green for online
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER_VERTICAL
        }
        
        // Status icon
        offlineStatusIcon = TextView(this).apply {
            text = "🟢"
            textSize = 16f
            setPadding(0, 0, 12, 0)
        }
        offlineStatusBar.addView(offlineStatusIcon)
        
        // Status text
        offlineStatusText = TextView(this).apply {
            text = "Online"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        offlineStatusBar.addView(offlineStatusText)
        
        // Progress bar (hidden by default)
        downloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(100, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 12
            }
            visibility = View.GONE
            max = 100
        }
        offlineStatusBar.addView(downloadProgress)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        }
        rootLayout.addView(offlineStatusBar, params)
        
        // Initial UI update
        updateOfflineStatusUI()
    }
    
    /**
     * C1: Update offline status UI based on connectivity and cache state
     */
    private fun updateOfflineStatusUI() {
        val cacheInfo = offlineDataCache.getCacheInfo()
        
        if (isOnline) {
            offlineStatusBar.setBackgroundColor(Color.parseColor("#4CAF50"))
            offlineStatusIcon.text = "🟢"
            offlineStatusText.text = if (cacheInfo.isValid) {
                "Online | Cache: ${cacheInfo.itemCount} items"
            } else {
                "Online | No cache"
            }
        } else {
            offlineStatusBar.setBackgroundColor(Color.parseColor("#FF9800"))
            offlineStatusIcon.text = "📴"
            offlineStatusText.text = if (cacheInfo.isValid) {
                "Offline | Using cache (${cacheInfo.itemCount} items)"
            } else {
                "Offline | No cache available!"
            }

            if (!cacheInfo.isValid) {
                offlineStatusBar.setBackgroundColor(Color.parseColor("#F44336"))
            }
        }
    }
    
    /**
     * C1: Handle offline download button click
     */
    private fun handleOfflineDownload() {
        Toast.makeText(this, "Starting offline cache...", Toast.LENGTH_SHORT).show()
        
        // Show progress
        if (::downloadProgress.isInitialized) {
            downloadProgress.visibility = View.VISIBLE
            downloadProgress.progress = 0
        }
        // Disable BOTH buttons if they exist
        topUpdateButton?.isEnabled = false
        topUpdateButton?.text = "⏳"

        // Cache landmark metadata first
        val metadataCached = offlineDataCache.cacheLandmarksData()
        
        if (metadataCached) {
            downloadProgress.progress = 30
            
            // Check if map tiles already downloaded
            offlineMapManager.checkOfflineRegionExists { exists, size ->
                runOnUiThread {
                    if (exists) {
                        // Already have offline maps
                        downloadProgress.progress = 100
                        completeOfflineDownload(true, "Cache updated! Maps: ${size/1024/1024}MB")
                    } else {
                        // Download map tiles
                        downloadProgress.progress = 40
                        offlineMapManager.downloadArizonaRoute66Region(
                            onProgress = { progress ->
                                runOnUiThread {
                                    downloadProgress.progress = (40 + progress * 0.6).toInt()
                                }
                            },
                            onComplete = { success, message ->
                                runOnUiThread {
                                    completeOfflineDownload(success, message)
                                }
                            }
                        )
                    }
                }
            }
        } else {
            completeOfflineDownload(false, "Failed to cache metadata")
        }
    }
    
    /**
     * C1: Complete offline download process
     */
    private fun completeOfflineDownload(success: Boolean, message: String) {
        if (::downloadProgress.isInitialized) downloadProgress.visibility = View.GONE

        // Re-enable BOTH buttons
        topUpdateButton?.isEnabled = true
        topUpdateButton?.text = "UPDATE"

        updateOfflineStatusUI()
        
        if (success) {
            Toast.makeText(this, "✅ $message", Toast.LENGTH_LONG).show()
            Log.d(TAG, "Offline cache complete: $message")
        } else {
            Toast.makeText(this, "❌ $message", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Offline cache failed: $message")
        }
    }
    
    /**
     * C1: Initialize offline cache on startup
     */
    private fun initializeOfflineCache() {
        if (!offlineDataCache.isCacheValid()) {
            // Auto-cache on first launch if online
            if (isOnline) {
                offlineDataCache.cacheLandmarksData()
                Log.d(TAG, "Initial offline cache created")
            }
        } else {
            val cacheInfo = offlineDataCache.getCacheInfo()
            Log.d(TAG, "Offline cache loaded: ${cacheInfo.itemCount} items, last updated: ${cacheInfo.lastUpdated}")
        }
    }
    private fun createTopSearchAndButtons(rootLayout: FrameLayout) {
        // A vertical container pinned to the top (UNDER the offlineStatusBar)
        val topContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 12f
        }

        // --- Buttons row (ON TOP now) ---
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 0, 0, 10.dp())
        }

        fun addGap() {
            buttonRow.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(10.dp(), 1)
            })
        }

        // UPDATE (reuse your cache/update action)
        val updateBtn = Button(this).apply {
            text = "UPDATE"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#1976D2"))
            setTextColor(Color.WHITE)
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            setOnClickListener { handleOfflineDownload() }
        }
        topUpdateButton = updateBtn

        // MONITOR (reuse your toggle)
        val monitorBtn = Button(this).apply {
            text = "MONITOR"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            setOnClickListener { toggleMonitorPanel() }
        }

        // POIs
        val poisBtn = Button(this).apply {
            text = "POIs"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#424242"))
            setTextColor(Color.WHITE)
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            setOnClickListener {
                poiListLauncher.launch(Intent(this@MainActivity, PoiListActivity::class.java))
            }
        }

        buttonRow.addView(updateBtn); addGap()
        buttonRow.addView(monitorBtn); addGap()
        buttonRow.addView(poisBtn)

        topContainer.addView(buttonRow)

        // --- Search row (UNDER buttons now) ---
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        searchBar = EditText(this).apply {
            hint = "Search POI or Call Number..."
            textSize = 14f
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 10.dp()
            }
            setSingleLine(true)
            setOnEditorActionListener { _, _, _ ->
                performSearch()
                true
            }
        }

        val searchIconBtn = android.widget.ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setBackgroundColor(Color.parseColor("#FF9800"))
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(44.dp(), 44.dp())
            setOnClickListener { performSearch() }
        }

        searchRow.addView(searchBar)
        searchRow.addView(searchIconBtn)
        topContainer.addView(searchRow)

        // Add container to rootLayout pinned to TOP
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            leftMargin = 8.dp()
            rightMargin = 8.dp()
            topMargin = 0
        }

        rootLayout.addView(topContainer, params)

        // After offlineStatusBar is measured, move this container below it
        rootLayout.post {
            params.topMargin = offlineStatusBar.height
            topContainer.layoutParams = params
        }
    }

    private fun createSearchResultsPanel(rootLayout: FrameLayout) {
        // Panel that holds the results (hidden until you search)
        searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            elevation = 14f
            visibility = View.GONE
        }

        // Scroll area
        searchResultsScrollView = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Container inside scroll
        searchResultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        searchResultsScrollView.addView(searchResultsContainer)
        searchPanel.addView(searchResultsScrollView)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            leftMargin = 8.dp()
            rightMargin = 8.dp()
            // push below the offline bar + your top search/buttons container (we set after layout)
            topMargin = 0
        }

        rootLayout.addView(searchPanel, params)

        // Move panel below the offlineStatusBar + top container height
        rootLayout.post {
            val topOffset = offlineStatusBar.height
            params.topMargin = topOffset + 110.dp() // simple safe offset; adjust if needed
            searchPanel.layoutParams = params
        }
    }

    /**
     * Perform search for POI or call number
     */
    private fun performSearch() {
        searchPanel.visibility = View.VISIBLE
        isSearchVisible = true

        val query = searchBar.text.toString().trim()
        
        if (query.isBlank()) {
            Toast.makeText(this, "Please enter a search term", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Show loading
        searchResultsContainer.removeAllViews()
        val loadingText = TextView(this).apply {
            text = "🔍 Searching..."
            textSize = 14f
            setTextColor(Color.parseColor("#616161"))
            setPadding(16, 16, 16, 16)
            gravity = Gravity.CENTER
        }
        searchResultsContainer.addView(loadingText)
        
        // Perform search on background thread
        Thread {
            try {
                Log.d(TAG, "Starting search for: '$query'")
                val results = archiveRepository.searchByPoi(query)
                Log.d(TAG, "Search completed, found ${results.size} results")
                
                runOnUiThread {
                    if (results.isEmpty()) {
                        Log.w(TAG, "No results found, checking if archive is loaded...")
                        val itemCount = archiveRepository.getItemCount()
                        Log.d(TAG, "Total archive items available: $itemCount")
                    }
                    displaySearchResults(query, results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during search: ${e.message}", e)
                runOnUiThread {
                    val errorText = TextView(this@MainActivity).apply {
                        text = "❌ Error searching: ${e.message}\n\nPlease try again or check logs."
                        setTextColor(Color.parseColor("#F44336"))
                        textSize = 12f
                        setPadding(16, 16, 16, 16)
                    }
                    searchResultsContainer.removeAllViews()
                    searchResultsContainer.addView(errorText)
                }
            }
        }.start()
    }
    
    /**
     * Display search results as clickable items
     */
    private fun displaySearchResults(query: String, results: List<Route66ArchiveItem>) {
        currentSearchResults = results
        searchResultsContainer.removeAllViews()
        
        if (results.isEmpty()) {
            val noResultsText = TextView(this).apply {
                text = "❌ No results found for '$query'\n\n" +
                        "💡 Try searching for:\n" +
                        "• POI names: 'Oatman', 'Kingman', 'Flagstaff', 'Winslow'\n" +
                        "• Call numbers: 'NAU.PH.2004', 'NAU.PH.2010'\n" +
                        "• Landmark IDs: 'oatman', 'kingman', 'flagstaff'\n\n" +
                        "📊 Total archive items: ${archiveRepository.getItemCount()}"
                setTextColor(Color.parseColor("#F44336"))
                textSize = 12f
                setPadding(16, 16, 16, 16)
            }
            searchResultsContainer.addView(noResultsText)
            return
        }
        
        // Check if this is a POI search and show landmark info
        val searchTerm = query.lowercase().trim()
        val matchingLandmark = route66DatabaseRepository.searchLandmarks(query).firstOrNull()
            ?: ArizonaLandmarks.landmarks.find { landmark ->
                landmark.name.lowercase().contains(searchTerm) ||
                landmark.id.lowercase().contains(searchTerm) ||
                searchTerm.contains(landmark.name.lowercase()) ||
                searchTerm.contains(landmark.id.lowercase())
            }
        
        // If POI found, navigate map to that location and find matching archive items
        if (matchingLandmark != null) {
            // Navigate map camera to the POI location
            mapView.mapboxMap.setCamera(
                CameraOptions.Builder()
                    .center(Point.fromLngLat(matchingLandmark.longitude, matchingLandmark.latitude))
                    .zoom(14.0) // Zoom in close to the POI
                    .build()
            )
            
            // Highlight the landmark on the map
            highlightLandmark(matchingLandmark.id, true)
            
            // Find archive items that match this POI using call number matching
            Thread {
                val matchedItems = findArchiveItemsForLandmark(matchingLandmark)
                runOnUiThread {
                    currentLandmarkArchiveItems = matchedItems
                    if (matchedItems.isNotEmpty()) {
                        Log.d(TAG, "Found ${matchedItems.size} archive items for ${matchingLandmark.name}")
                    }
                }
            }.start()
            
            Log.d(TAG, "Navigated map to ${matchingLandmark.name} at (${matchingLandmark.latitude}, ${matchingLandmark.longitude})")
        }
        
        // Header with landmark info if found
        val headerText = if (matchingLandmark != null) {
            TextView(this).apply {
                text = "📍 ${matchingLandmark.name}\n" +
                       "${matchingLandmark.description}\n" +
                       "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                       "📚 CONTENTdm Archive Database (${results.size} items):\n" +
                       "🔗 All items linked to Call Numbers"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1976D2"))
                setPadding(12, 12, 12, 12)
                setBackgroundColor(Color.parseColor("#E3F2FD"))
            }
        } else {
            TextView(this).apply {
                text = "✅ Found ${results.size} result(s) for '$query'\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1976D2"))
                setPadding(12, 8, 12, 12)
            }
        }
        searchResultsContainer.addView(headerText)
        
        // Always show all archive items when POI is found - this is the "database"
        if (results.isNotEmpty()) {
            // Create clickable result items for all results
            results.take(50).forEachIndexed { index, item ->
                val resultItem = createResultItemView(index + 1, item)
                searchResultsContainer.addView(resultItem)
            }
            
            // If POI found, automatically scroll to show items
            if (matchingLandmark != null) {
                searchResultsScrollView.post {
                    searchResultsScrollView.smoothScrollTo(0, 0)
                }
            }
        }
        
        if (results.size > 50) {
            val moreText = TextView(this).apply {
                text = "... and ${results.size - 50} more result(s)"
                textSize = 12f
                setTextColor(Color.parseColor("#757575"))
                setPadding(16, 8, 16, 8)
                gravity = Gravity.CENTER
            }
            searchResultsContainer.addView(moreText)
        }
        
        if (matchingLandmark != null) {
            Toast.makeText(this, "📍 ${matchingLandmark.name} - ${results.size} archive items loaded", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "✅ Found ${results.size} result(s) - Tap '🌐 Open' to view archive", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Create a clickable result item view
     */
    private fun createResultItemView(index: Int, item: Route66ArchiveItem): LinearLayout {
        val itemLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(16, 12, 16, 12)
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
        }
        
        // Content row (title and info)
        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        
        // Title with Call Number
        val titleText = TextView(this).apply {
            text = "📄 Call Number: ${item.callNumber}"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, 0, 0, 4)
        }
        contentRow.addView(titleText)
        
        // CONTENTdm info
        val contentDmText = TextView(this).apply {
            text = "   🆔 CONTENTdm Number: ${item.contentDmNumber}"
            textSize = 12f
            setTextColor(Color.parseColor("#1976D2"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 2, 0, 2)
        }
        contentRow.addView(contentDmText)
        
        // Item Number
        val itemNumberText = TextView(this).apply {
            text = "   🔢 Item Number: ${item.itemNumber}"
            textSize = 11f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 2, 0, 4)
        }
        contentRow.addView(itemNumberText)
        
        // CONTENTdm URL (clickable)
        val urlText = TextView(this).apply {
            text = "   🔗 ${item.referenceUrl}"
            textSize = 10f
            setTextColor(Color.parseColor("#4CAF50"))
            setTypeface(null, Typeface.ITALIC)
            setPadding(0, 2, 0, 4)
            setOnClickListener {
                openArchiveItemUrl(item)
            }
        }
        contentRow.addView(urlText)
        
        itemLayout.addView(contentRow)
        
        // Buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 8, 0, 0)
        }
        
        // "View Details" button
        val viewDetailsButton = Button(this).apply {
            text = "📋 Details"
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                showArchiveDetailCard(item)
            }
        }
        buttonRow.addView(viewDetailsButton)
        
        // "Open Archive" button - directly opens the URL
        val openButton = Button(this).apply {
            text = "🌐 Open"
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                openArchiveItemUrl(item)
            }
        }
        buttonRow.addView(openButton)
        
        itemLayout.addView(buttonRow)
        
        return itemLayout
    }
    
    /**
     * Open archive item URL directly
     */
    private fun openArchiveItemUrl(item: Route66ArchiveItem) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.referenceUrl))
        try {
            startActivity(intent)
            Toast.makeText(this, "Opening archive item...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open URL: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Error opening URL: ${item.referenceUrl}", e)
        }
    }
    
    /**
     * Create archive item detail card
     */
    private fun createArchiveDetailCard(rootLayout: FrameLayout) {
        archiveDetailCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
            elevation = 16f
            visibility = View.GONE
        }
        
        // Title
        archiveDetailTitle = TextView(this).apply {
            text = "Archive Item Details"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, 0, 0, 16)
        }
        archiveDetailCard.addView(archiveDetailTitle)
        
        // Call Number
        archiveDetailCallNumber = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 4, 0, 4)
        }
        archiveDetailCard.addView(archiveDetailCallNumber)
        
        // CONTENTdm Number
        archiveDetailContentDm = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 4, 0, 4)
        }
        archiveDetailCard.addView(archiveDetailContentDm)
        
        // Item Number
        archiveDetailItemNumber = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 4, 0, 4)
        }
        archiveDetailCard.addView(archiveDetailItemNumber)
        
        // URL
        archiveDetailUrl = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#2196F3"))
            setPadding(0, 8, 0, 16)
            setTypeface(null, Typeface.ITALIC)
        }
        archiveDetailCard.addView(archiveDetailUrl)
        
        // Buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        
        // Open Archive Item button
        archiveOpenButton = Button(this).apply {
            text = "🌐 Open Archive Item"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                openArchiveItem()
            }
        }
        buttonRow.addView(archiveOpenButton)
        
        // Close button
        archiveCloseButton = Button(this).apply {
            text = "✕ Close"
            setBackgroundColor(Color.parseColor("#F44336"))
            setTextColor(Color.WHITE)
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                hideArchiveDetailCard()
            }
        }
        buttonRow.addView(archiveCloseButton)
        
        archiveDetailCard.addView(buttonRow)
        
        // Add to root layout
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = 16
            rightMargin = 16
            bottomMargin = 32
        }
        rootLayout.addView(archiveDetailCard, params)
    }
    
    private var currentArchiveItem: Route66ArchiveItem? = null
    
    /**
     * Show archive item detail card
     */
    private fun showArchiveDetailCard(item: Route66ArchiveItem) {
        currentArchiveItem = item
        
        archiveDetailTitle.text = "📄 Archive Item Details"
        archiveDetailCallNumber.text = "📋 Call Number: ${item.callNumber}"
        archiveDetailContentDm.text = "🆔 CONTENTdm Number: ${item.contentDmNumber}"
        archiveDetailItemNumber.text = "🔢 Item Number: ${item.itemNumber}"
        archiveDetailUrl.text = "🔗 ${item.referenceUrl}"
        
        archiveDetailCard.visibility = View.VISIBLE
        
        // Hide search panel to show detail card better
        searchPanel.visibility = View.GONE
        isSearchVisible = false
    }
    
    /**
     * Hide archive detail card
     */
    private fun hideArchiveDetailCard() {
        archiveDetailCard.visibility = View.GONE
        currentArchiveItem = null
    }
    
    /**
     * Open archive item URL in browser
     */
    private fun openArchiveItem() {
        val item = currentArchiveItem
        if (item != null) {
            openArchiveItemUrl(item)
        } else {
            Toast.makeText(this, "No archive item selected", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Create the Geofence Monitor UI overlay
     */
    private fun createGeofenceMonitorUI(rootLayout: FrameLayout) {
        
        // Monitor panel (initially hidden)
        monitorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(16, 16, 16, 16)
            elevation = 8f
            visibility = View.GONE
        }
        
        // Title
        val titleView = TextView(this).apply {
            text = "🗺️ GEOFENCE MONITOR"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1976D2"))
            setPadding(0, 0, 0, 16)
        }
        monitorPanel.addView(titleView)
        
        // Registered Geofences Section
        val geofenceHeader = TextView(this).apply {
            text = "📋 REGISTERED GEOFENCES (${ArizonaLandmarks.landmarks.size})"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 8, 0, 8)
        }
        monitorPanel.addView(geofenceHeader)
        
        // Scrollable geofence list
        val geofenceScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                200
            )
        }
        
        geofenceListTextView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#616161"))
            setBackgroundColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
        }
        geofenceScrollView.addView(geofenceListTextView)
        monitorPanel.addView(geofenceScrollView)
        
        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#BDBDBD"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply { 
                topMargin = 16
                bottomMargin = 16
            }
        }
        monitorPanel.addView(divider)
        
        // Event Log Section
        val eventHeader = TextView(this).apply {
            text = "📜 EVENT LOG"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 0, 0, 8)
        }
        monitorPanel.addView(eventHeader)
        
        // Scrollable event log
        val eventScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150
            )
        }
        
        eventLogTextView = TextView(this).apply {
            text = "Waiting for geofence events...\n(Use mock location to trigger ENTER/EXIT)"
            textSize = 11f
            setTextColor(Color.parseColor("#757575"))
            setBackgroundColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
        }
        eventScrollView.addView(eventLogTextView)
        monitorPanel.addView(eventScrollView)
        
        // Add panel to root layout
        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = 16
            rightMargin = 16
            bottomMargin = 32
        }
        rootLayout.addView(monitorPanel, panelParams)
        
        // Populate geofence list
        updateGeofenceListDisplay()
    }
    
    /**
     * Toggle the monitor panel visibility
     */
    private fun toggleMonitorPanel() {
        isMonitorVisible = !isMonitorVisible
        if (isMonitorVisible) {
            monitorPanel.visibility = View.VISIBLE
        } else {
            monitorPanel.visibility = View.GONE
        }
    }
    
    /**
     * Update the geofence list display with all landmarks
     */
    private fun updateGeofenceListDisplay() {
        val sb = StringBuilder()
        ArizonaLandmarks.landmarks.forEachIndexed { index, landmark ->
            val status = if (activeLandmarks.contains(landmark.id)) "🟢 INSIDE" else "⚪ Outside"
            sb.append("${index + 1}. ${landmark.name}\n")
            sb.append("   ID: ${landmark.id}\n")
            sb.append("   📍 (${landmark.latitude}, ${landmark.longitude})\n")
            sb.append("   📏 Radius: ${landmark.radiusMeters.toInt()}m\n")
            sb.append("   Status: $status\n")
            if (index < ArizonaLandmarks.landmarks.size - 1) {
                sb.append("   ─────────────────────\n")
            }
        }
        geofenceListTextView.text = sb.toString()
    }
    
    /**
     * Update the event log display
     */
    private fun updateEventLogDisplay() {
        if (geofenceEventLog.isEmpty()) {
            eventLogTextView.text = "Waiting for geofence events...\n(Use mock location to trigger ENTER/EXIT)"
            return
        }
        
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        
        // Show most recent events first (last 20)
        geofenceEventLog.takeLast(20).reversed().forEach { event ->
            val timeStr = timeFormat.format(Date(event.timestamp))
            val emoji = when (event.transitionType) {
                "ENTER" -> "🟢 ENTER"
                "EXIT" -> "🔴 EXIT"
                "DWELL" -> "🟡 DWELL"
                else -> "⚪ ${event.transitionType}"
            }
            sb.append("[$timeStr] $emoji\n")
            sb.append("  → ${event.landmarkName}\n")
            sb.append("  ID: ${event.landmarkId}\n\n")
        }
        
        eventLogTextView.text = sb.toString()
        eventLogTextView.setTextColor(Color.parseColor("#212121"))
    }

    private fun setupAnnotationManagers() {
        val annotationPlugin = mapView.annotations

        // Point markers (Route 66 landmarks)
        pointAnnotationManager = annotationPlugin.createPointAnnotationManager().apply {
            // When the user taps a marker, start navigation to that point
            addClickListener { annotation ->
                val landmark = landmarkByAnnotationId[annotation.id]
                if (landmark != null) {
                    // Optional: move camera closer
                    mapView.mapboxMap.setCamera(
                        CameraOptions.Builder()
                            .center(annotation.point)
                            .zoom(12.0)
                            .build()
                    )
                    showLandmarkCard(landmark.id)
                }
                true

            //    startNavigationTo(annotation.point)
            //    true // tell Mapbox we handled the click
            }
        }

        // Geofence circles
        circleAnnotationManager = annotationPlugin.createCircleAnnotationManager()
    }


    /**
     * Add markers for all Route 66 landmarks (limit to prevent UI freeze)
     */
    private fun addAllLandmarkMarkers() {
        if (ArizonaLandmarks.landmarks.isEmpty()) {
            Log.w(TAG, "No landmarks to display")
            return
        }
        
        // Limit markers to prevent UI freeze with thousands of POIs
        // Filter to Arizona landmarks first, then limit total
        val arizonaLandmarks = ArizonaLandmarks.landmarks.filter { landmark ->
            // Filter to Arizona (latitude 31-37, longitude -115 to -109)
            landmark.latitude in 31.0..37.0 && landmark.longitude in -115.0..-109.0
        }
        
        val maxMarkers = 200 // Limit to 200 markers to prevent UI freeze
        val landmarksToShow = if (arizonaLandmarks.size > maxMarkers) {
            Log.w(TAG, "Too many landmarks (${arizonaLandmarks.size}), showing first $maxMarkers")
            arizonaLandmarks.take(maxMarkers)
        } else {
            arizonaLandmarks
        }
        
        // Convert vector drawable to bitmap
        val drawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.red_marker)
        val markerBitmap = if (drawable != null) {
            val bitmap = android.graphics.Bitmap.createBitmap(
                drawable.intrinsicWidth.takeIf { it > 0 } ?: 48,
                drawable.intrinsicHeight.takeIf { it > 0 } ?: 72,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } else {
            // Fallback: create a simple colored bitmap
            android.graphics.Bitmap.createBitmap(24, 36, android.graphics.Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
            }
        }
        
        landmarksToShow.forEach { landmark ->
            val markerOptions = PointAnnotationOptions()
                .withPoint(landmark.toPoint())
                .withIconImage(markerBitmap)
                .withTextField(landmark.name)
                .withTextOffset(listOf(0.0, 2.0))
                .withTextSize(10.0)

            val annotation = pointAnnotationManager?.create(markerOptions)
            if (annotation != null) {
                landmarkByAnnotationId[annotation.id] = landmark
            }
        }
        
        Log.d(TAG, "Added ${landmarksToShow.size} landmark markers (out of ${ArizonaLandmarks.landmarks.size} total)")
    }

    /**
     * Draw geofence radius circles around all landmarks (limited to prevent UI freeze)
     */
    private fun drawAllGeofenceCircles() {
        if (ArizonaLandmarks.landmarks.isEmpty()) return
        
        // Limit circles to same landmarks as markers
        val arizonaLandmarks = ArizonaLandmarks.landmarks.filter { landmark ->
            landmark.latitude in 31.0..37.0 && landmark.longitude in -115.0..-109.0
        }
        val landmarksToShow = if (arizonaLandmarks.size > 200) {
            arizonaLandmarks.take(200)
        } else {
            arizonaLandmarks
        }
        
        landmarksToShow.forEach { landmark ->
            val circleOptions = CircleAnnotationOptions()
                .withPoint(landmark.toPoint())
                .withCircleRadius((landmark.radiusMeters / 10).toDouble()) // Visual scaling
                .withCircleColor("#4A90D9") // Blue
                .withCircleOpacity(0.25)
                .withCircleStrokeWidth(2.0)
                .withCircleStrokeColor("#2E5A8B")
            
            circleAnnotationManager?.create(circleOptions)
        }
    }

    /**
     * Request location permissions step by step
     */
    private fun requestLocationPermissions() {
        when {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                checkBackgroundLocationPermission()
            }
            else -> {
                requestFineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                enableUserLocation()
                // DON'T call initializeGeofencing() here
                // Wait until landmarks are loaded
                tryRegisterGeofencesIfReady()
            } else {
                Toast.makeText(this, "Background location allows geofence detection while traveling", Toast.LENGTH_LONG).show()
                requestBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            enableUserLocation()
            tryRegisterGeofencesIfReady()
        }
    }

    private fun tryRegisterGeofencesIfReady() {
        if (ArizonaLandmarks.landmarks.isNotEmpty()) {
            initializeGeofencing()
        } else {
            Log.d(TAG, "Landmarks not loaded yet; will register geofences after DB load.")
        }
    }
    /**
     * Initialize geofencing after permissions granted
     */
    private fun initializeGeofencing() {
        // Prevent crash: no geofences to register yet
        if (ArizonaLandmarks.landmarks.isEmpty()) {
            Log.w(TAG, "initializeGeofencing(): landmarks not loaded yet. Will retry after DB load.")
            pendingGeofenceInit = true
            return
        }

        enableUserLocation()
        
        geofenceManager.registerAllGeofences(
            onSuccess = {
                Toast.makeText(
                    this,
                    "✓ Monitoring ${ArizonaLandmarks.landmarks.size} Route 66 landmarks",
                    Toast.LENGTH_SHORT
                ).show()
                Log.d(TAG, "Geofences registered successfully")
                printGeofenceList()
            },
            onFailure = { e ->
                Toast.makeText(
                    this,
                    "Geofence registration failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                Log.e(TAG, "Geofence registration failed", e)
            }
        )
    }
    
    /**
     * Print registered geofences for demo/debug
     */
    private fun printGeofenceList() {
        Log.d(TAG, "=== REGISTERED GEOFENCES ===")
        ArizonaLandmarks.landmarks.forEach { landmark ->
            Log.d(TAG, """
                Landmark: ${landmark.name}
                ID: ${landmark.id}
                Coordinates: (${landmark.latitude}, ${landmark.longitude})
                Radius: ${landmark.radiusMeters}m
                ---
            """.trimIndent())
        }
    }

    /**
     * Enable user location display on map
     */
    private fun enableUserLocation() {
        val locationComponent = mapView.location
        locationComponent.updateSettings {
            enabled = true
            locationPuck = LocationPuck2D(
                topImage = ImageHolder.from(R.drawable.user_location_dot),
                bearingImage = null,
                shadowImage = null
            )
            pulsingEnabled = false
        }
        centerMapOnUserLocationIfAvailable()
    }

    private fun centerMapOnUserLocationIfAvailable() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    mapView.mapboxMap.setCamera(
                        CameraOptions.Builder()
                            .center(Point.fromLngLat(location.longitude, location.latitude))
                            .zoom(7.0)
                            .build()
                    )
                    hasCenteredOnUserLocation = true
                } else {
                    centerWhenFirstLocationIndicatorArrives()
                }
            }
            .addOnFailureListener {
                Log.w(TAG, "Could not center map on user location: ${it.message}")
                centerWhenFirstLocationIndicatorArrives()
            }
    }

    private fun centerWhenFirstLocationIndicatorArrives() {
        if (hasCenteredOnUserLocation) return
        val locationComponent = mapView.location
        val oneTimeListener = object : OnIndicatorPositionChangedListener {
            override fun onIndicatorPositionChanged(point: Point) {
                if (hasCenteredOnUserLocation) return
                hasCenteredOnUserLocation = true
                mapView.mapboxMap.setCamera(
                    CameraOptions.Builder()
                        .center(point)
                        .zoom(7.0)
                        .build()
                )
                locationComponent.removeOnIndicatorPositionChangedListener(this)
            }
        }
        locationComponent.addOnIndicatorPositionChangedListener(oneTimeListener)
    }

    /**
     * Handle geofence ENTER/EXIT events
     */
    private fun handleGeofenceEvent(
        landmarkId: String,
        landmarkName: String,
        transitionType: String,
        timestamp: Long
    ) {
        val event = GeofenceEvent(landmarkId, landmarkName, transitionType, timestamp)
        geofenceEventLog.add(event)
        
        when (transitionType) {
            "ENTER" -> {
                activeLandmarks.add(landmarkId)
                highlightLandmark(landmarkId, true)
                showEntryNotification(landmarkName)
                showLandmarkCard(landmarkId)
            }
            "EXIT" -> {
                activeLandmarks.remove(landmarkId)
                highlightLandmark(landmarkId, false)
                showExitNotification(landmarkName)
            }
            "DWELL" -> {
                showDwellNotification(landmarkName)

            }
        }
        
        // Update UI displays
        updateGeofenceListDisplay()
        updateEventLogDisplay()
        
        logGeofenceEvent(event)
    }
    
    /**
     * Highlight or unhighlight a landmark's geofence circle
     */
    private fun highlightLandmark(landmarkId: String, highlight: Boolean) {
        // Remove existing circles and redraw with new colors
        circleAnnotationManager?.deleteAll()
        
        ArizonaLandmarks.landmarks.forEach { lm ->
            val isActive = activeLandmarks.contains(lm.id)
            val color = if (isActive) "#4CAF50" else "#4A90D9" // Green if active, blue otherwise
            val opacity = if (isActive) 0.4 else 0.25
            
            val circleOptions = CircleAnnotationOptions()
                .withPoint(lm.toPoint())
                .withCircleRadius((lm.radiusMeters / 10).toDouble())
                .withCircleColor(color)
                .withCircleOpacity(opacity)
                .withCircleStrokeWidth(if (isActive) 3.0 else 2.0)
                .withCircleStrokeColor(if (isActive) "#2E7D32" else "#2E5A8B")
            
            circleAnnotationManager?.create(circleOptions)
        }
    }
    
    private fun showEntryNotification(landmarkName: String) {
        Toast.makeText(
            this,
            "📍 ENTERED: $landmarkName",
            Toast.LENGTH_LONG
        ).show()
    }
    
    private fun showExitNotification(landmarkName: String) {
        Toast.makeText(
            this,
            "👋 EXITED: $landmarkName",
            Toast.LENGTH_SHORT
        ).show()
    }
    
    private fun showDwellNotification(landmarkName: String) {
        Toast.makeText(
            this,
            "⏱️ DWELLING at: $landmarkName",
            Toast.LENGTH_SHORT
        ).show()
    }
    /**
     * Start navigation to a landmark
     */
    private fun startNavigationTo(destination: Point) {
        val lat = destination.latitude()
        val lon = destination.longitude()

        // Try Google Maps navigation app first
        val gmmIntentUri = Uri.parse("google.navigation:q=$lat,$lon&mode=d")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
            setPackage("com.google.android.apps.maps")
        }

        if (mapIntent.resolveActivity(packageManager) != null) {
            // Google Maps app is installed
            startActivity(mapIntent)
        } else {
            // Fallback to browser directions if app is not installed
            val browserUri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving"
            )
            val browserIntent = Intent(Intent.ACTION_VIEW, browserUri)
            startActivity(browserIntent)
        }
    }
    /**
     * Bottom card that shows POI details and has a "Listen" button
     */
    private fun createLandmarkDetailCard(rootLayout: FrameLayout) {
        detailCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.poi_card_bg)
            elevation = 18f
            clipToPadding = false
            visibility = View.GONE
        }
        //header/Title
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#FF7A00"))
            setPadding(18, 14, 18, 14)
            gravity = Gravity.CENTER_VERTICAL
        }

        detailTitleText = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeX = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(16, 0, 0, 0)
            setOnClickListener { hideLandmarkCard() }
        }

        headerRow.addView(detailTitleText)
        headerRow.addView(closeX)

        detailCard.addView(headerRow)

        detailCard.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 12
            )
        })

        detailImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                220.dp()
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.mipmap.ic_launcher)
        }
        detailCard.addView(detailImageView)

        // Description
        detailDescriptionText = TextView(this).apply {
            textSize = 14.5f
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(6f, 1.05f)
            setPadding(4, 6, 4, 6)
        }
        detailCard.addView(detailDescriptionText)

        // Extra / historical notes
        detailExtraText = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#666666"))
            setPadding(4, 0, 4, 10)
        }

        detailCard.addView(detailExtraText)

        // Buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        fun styleButton(b: Button, color: String) {
            b.background = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.btn_pill)
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
            b.setTextColor(Color.WHITE)
            b.textSize = 12f
            b.isAllCaps = false
        }

        // "Listen" button
        detailListenButton = Button(this).apply {
            text = "🔊 Listen"
            setOnClickListener {
                readCurrentLandmark()
            }
        }

        // "About" button - opens CONTENTdm for this POI
        detailAboutButton = Button(this).apply {
            text = "ℹ️ About"
            setBackgroundColor(Color.parseColor("#9C27B0"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                openAboutForCurrentLandmark()
            }
        }

        // Navigate button
        val navigateButton = Button(this).apply {
            text = "Navigate"
                setOnClickListener {
                    val dest = currentDestinationPoint
                    if (dest != null) {
                        startNavigationTo(dest)   // uses your existing function
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "No navigation target available",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
        }

        //button style
        styleButton(detailListenButton, "#1976D2")   // blue
        styleButton(detailAboutButton, "#7B1FA2")    // purple
        styleButton(navigateButton, "#2E7D32")       // green

        fun addSpacer() {
            buttonRow.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(12, 1)
            })
        }

        buttonRow.addView(detailListenButton); addSpacer()
        buttonRow.addView(detailAboutButton); addSpacer()
        buttonRow.addView(navigateButton); addSpacer()

        detailCard.addView(buttonRow)

        // Attach to bottom of rootLayout
        val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                leftMargin = 16
                rightMargin = 16
                bottomMargin = 32
        }

        rootLayout.addView(detailCard, params)
        }


    /**
     * Show details for the given landmark ID and read it aloud.
     */
    private fun showLandmarkCard(landmarkId: String) {
        currentLandmarkId = landmarkId

        // Try to get cached offline data first
        val cached = offlineDataCache
            .getCachedLandmarks()
            .firstOrNull { it.id == landmarkId }

        val title: String
        val description: String
        val extra: String

        if (cached != null) {
            title = cached.name
            description = cached.description
            extra = "${cached.historicalNotes}\nEstablished: ${cached.yearEstablished}"
        } else {
            // Fallback to in-memory landmarks
            val lm = ArizonaLandmarks.landmarks.firstOrNull { it.id == landmarkId }
            title = lm?.name ?: "Unknown Landmark"
            description = lm?.description ?: ""
            extra = ""
        }

        detailTitleText.text = title
        detailImageView.setImageResource(resolveLandmarkImageRes(landmarkId))
        detailDescriptionText.text = description
        detailExtraText.text = extra

        detailCard.visibility = View.VISIBLE

        //save where to navigate
        val lm = route66DatabaseRepository.findLandmarkById(landmarkId)
            ?: ArizonaLandmarks.findById(landmarkId)
        currentDestinationPoint = lm?.toPoint()
        
        // Find and store archive items for this landmark (for About button)
        if (lm != null) {
            Thread {
                val matchedItems = findArchiveItemsForLandmark(lm)
                runOnUiThread {
                    currentLandmarkArchiveItems = matchedItems
                    if (matchedItems.isNotEmpty()) {
                        Log.d(TAG, "Found ${matchedItems.size} archive items for ${lm.name}")
                    }
                }
            }.start()
        }

        // Auto read when opened (if you want; you can remove this line to only read on button press)
        readTextForLandmark(title, description, extra)
    }

    /**
     * Hide the card and stop any speech.
     */
    private fun hideLandmarkCard() {
        detailCard.visibility = View.GONE
        tts?.stop()
    }

    /**
     * Build full narration text and send it to Text-to-Speech.
     */
    private fun readTextForLandmark(
        title: String,
        description: String,
        extra: String
    ) {
        if (!isTtsReady || tts == null) {
            Toast.makeText(this, "Voice not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        val textToSpeak = buildString {
            append(title).append(". ")
            append(description)
            if (extra.isNotBlank()) {
                append(". ").append(extra)
            }
        }

        tts?.stop()
        tts?.speak(
            textToSpeak,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "LANDMARK_TTS"
        )
    }

    /**
     * Called when the user taps the "🔊 Read this" button.
     */
    private fun readCurrentLandmark() {
        val title = detailTitleText.text?.toString().orEmpty()
        val description = detailDescriptionText.text?.toString().orEmpty()
        val extra = detailExtraText.text?.toString().orEmpty()

        if (title.isNotBlank() || description.isNotBlank()) {
            readTextForLandmark(title, description, extra)
        }
    }

    /**
     * Open About page (CONTENTdm) for the current landmark
     * Uses call number from CSV to match CONTENTdm and item number, then opens reference URL
     */
    private fun openAboutForCurrentLandmark() {
        val landmarkId = currentLandmarkId
        if (landmarkId == null) {
            Toast.makeText(this, "No landmark selected", Toast.LENGTH_SHORT).show()
            return
        }
        
        val landmark = route66DatabaseRepository.findLandmarkById(landmarkId)
            ?: ArizonaLandmarks.findById(landmarkId)
        if (landmark == null) {
            Toast.makeText(this, "Landmark not found", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Use pre-matched archive items if available, otherwise find them
        if (currentLandmarkArchiveItems.isNotEmpty()) {
            // Open the first matching archive item's reference URL from CSV
            val firstItem = currentLandmarkArchiveItems.first()
            openArchiveItemUrl(firstItem)
            
            if (currentLandmarkArchiveItems.size > 1) {
                Toast.makeText(
                    this,
                    "Opening archive item 1 of ${currentLandmarkArchiveItems.size} for ${landmark.name}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Opening archive for ${landmark.name}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            // Find archive items that match this POI using call number matching
            Thread {
                val archiveItems = findArchiveItemsForLandmark(landmark)
                
                runOnUiThread {
                    if (archiveItems.isEmpty()) {
                        // Fallback: open CONTENTdm search page
                        val contentDmBaseUrl = "http://cdm16748.contentdm.oclc.org"
                        val collectionUrl = "$contentDmBaseUrl/digital/collection/cpa"
                        val searchQuery = landmark.name.replace(" ", "+").replace("'", "%27")
                        val searchUrl = "$collectionUrl/search/searchterm/$searchQuery"
                        
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl))
                        try {
                            startActivity(intent)
                            Toast.makeText(
                                this,
                                "Opening CONTENTdm search for ${landmark.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Cannot open CONTENTdm: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // Store matched items
                        currentLandmarkArchiveItems = archiveItems
                        
                        // Open the first matching archive item's reference URL from CSV
                        val firstItem = archiveItems.first()
                        openArchiveItemUrl(firstItem)
                        
                        if (archiveItems.size > 1) {
                            Toast.makeText(
                                this,
                                "Opening archive item 1 of ${archiveItems.size} for ${landmark.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this,
                                "Opening archive for ${landmark.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }.start()
        }
    }
    
    /**
     * Find archive items for a landmark by matching call numbers
     * Uses Route66DatabaseRepository to match POIs with CONTENTdm archive items
     */
    private fun findArchiveItemsForLandmark(landmark: Route66Landmark): List<Route66ArchiveItem> {
        // Load archive data if needed
        if (!archiveRepository.isLoaded) {
            archiveRepository.loadArchiveData()
        }
        
        // Get all archive items
        val allItems = archiveRepository.getAllItems()
        
        // Use repository's matching logic
        val matchedItems = route66DatabaseRepository.matchArchiveItemsToLandmark(landmark, allItems).toMutableList()
        
        // If no matches, try fallback matching
        if (matchedItems.isEmpty()) {
            val landmarkNameLower = landmark.name.lowercase()
            val landmarkIdLower = landmark.id.lowercase()
            val landmarkWords = landmarkNameLower.split(" ", "-", "_", "'", "Ghost", "Town")
                .filter { it.length > 3 }
            
            allItems.forEach { item ->
                val callLower = item.callNumber.lowercase()
                val matches = landmarkWords.any { word ->
                    callLower.contains(word)
                } || callLower.contains(landmarkIdLower)
                
                if (matches && !matchedItems.contains(item)) {
                    matchedItems.add(item)
                }
            }
        }
        
        Log.d(TAG, "Found ${matchedItems.size} archive items for ${landmark.name}")
        return matchedItems
    }
    
    /**
     * Log geofence events for demo inspection
     */
    private fun logGeofenceEvent(event: GeofenceEvent) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = timeFormat.format(Date(event.timestamp))
        
        Log.d(TAG, """
            ╔══════════════════════════════════════
            ║ GEOFENCE EVENT
            ╠══════════════════════════════════════
            ║ Type: ${event.transitionType}
            ║ Landmark: ${event.landmarkName}
            ║ ID: ${event.landmarkId}
            ║ Time: $timeStr
            ╚══════════════════════════════════════
        """.trimIndent())
    }

    /**
     * Get event log for demo purposes
     */
    fun getGeofenceEventLog(): List<GeofenceEvent> = geofenceEventLog.toList()
    
    /**
     * Get currently active landmarks
     */
    fun getActiveLandmarks(): Set<String> = activeLandmarks.toSet()

    /**
     * Resolves the image reference for a POI card.
     * Tries a POI-specific drawable first, then falls back to the app icon.
     */
    private fun resolveLandmarkImageRes(landmarkId: String): Int {
        val resourceName = "poi_${landmarkId.lowercase().replace("-", "_")}"
        val resourceId = resources.getIdentifier(resourceName, "drawable", packageName)
        return if (resourceId != 0) resourceId else R.mipmap.ic_launcher
    }

    // Lifecycle management
    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        if (::mapView.isInitialized) mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()

        try { unregisterReceiver(geofenceReceiver) } catch (_: Exception) {}
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        geofenceManager.removeAllGeofences()

        //TTS cleanup
        tts?.stop()
        tts?.shutdown()
        tts = null

        if (::mapView.isInitialized) mapView.onDestroy()
    }
  }

