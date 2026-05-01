package com.example.experience66hello

import android.Manifest
import android.location.Location
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.Image
import com.mapbox.maps.ImageHolder
import com.mapbox.bindgen.DataRef
import com.mapbox.common.Cancelable
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.net.Uri
import android.speech.tts.TextToSpeech
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayerAbove
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.style
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.extension.style.layers.properties.generated.TextJustify
import android.text.Editable
import android.text.TextWatcher
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.gestures
import coil.Coil
import coil.ImageLoader
import coil.load
import coil.size.Scale
import okhttp3.OkHttpClient
import java.nio.ByteBuffer
import android.content.res.ColorStateList
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.animation.AccelerateDecelerateInterpolator
import com.mapbox.maps.plugin.scalebar.scalebar

/**
 * Main activity for Route 66 Experience app
 * Handles map display, POI search, geofencing, and archive integration
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        @Volatile
        private var coilImageLoaderInstalled = false
        private val contentDmOkHttpClient: OkHttpClient by lazy {
            // OCLC CONTENTdm often returns 403/5xx for default OkHttp User-Agent.
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
                        )
                        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Referer", "https://cdm16748.contentdm.oclc.org/")
                        .header("Connection", "keep-alive")
                        .build()
                    chain.proceed(req)
                }
                .build()
        }
        private const val ACTION_SHOW = GeofenceBroadcastReceiver.ACTION_SHOW
        private const val ACTION_LISTEN = GeofenceBroadcastReceiver.ACTION_LISTEN
        private const val ACTION_MORE = GeofenceBroadcastReceiver.ACTION_MORE
        private const val ACTION_NAVIGATE = GeofenceBroadcastReceiver.ACTION_NAVIGATE

        private const val POI_GEOJSON_SOURCE_ID = "route66-poi-points"
        /** Pin bitmap for [POI_SYMBOL_LAYER_ID] (same asset feel as the old point annotations). */
        private const val POI_PIN_IMAGE_ID = "route66-poi-pin"
        private const val POI_SYMBOL_LAYER_ID = "route66-poi-symbols"
    }
    private lateinit var mapView: MapView
    private lateinit var geofenceManager: GeofenceManager
    private lateinit var offlineMapManager: OfflineMapManager
    private lateinit var archiveRepository: ArchiveRepository
    private lateinit var route66DatabaseRepository: Route66DatabaseRepository
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var hasCenteredOnUserLocation = false
    /** After CSV POIs load, camera fits all markers; block auto re-center on GPS so all stay in view. */
    private var poiOverviewCameraApplied = false
    //
    private var pendingGeofenceInit = false
    private var isStyleLoaded = false
    private var pendingMarkers = false
    private var pendingNotificationLandmarkId: String? = null
    private var pendingNotificationAction: String? = null
    private var pendingNotificationTriggerToPoiMeters: Float? = null

    private var circleAnnotationManager: CircleAnnotationManager? = null
    /** Bound when [ensurePoiLayersAdded] creates the POI GeoJSON source; used to push FeatureCollections without Style.getSource. */
    private var poiGeoJsonSource: GeoJsonSource? = null
    private var cachedPoiPinBitmap: Bitmap? = null
    private var poiPinStyleImageInjected = false
    private var mapPoiClickListenerRegistered = false
    //menu
    private lateinit var sideMenuPanel: LinearLayout
    private lateinit var sideMenuOverlay: View
    private var isSideMenuOpen = false
    /** True when circles were drawn for all landmarks (e.g. after geofence highlight); false = subset only. */
    private var geofenceCirclesUseFullLandmarkSet = false
    private var geofenceCameraCancelable: Cancelable? = null
    private val geofenceCircleRedrawHandler = Handler(Looper.getMainLooper())
    private val geofenceCircleRedrawRunnable = Runnable {
        redrawGeofenceCirclesForCurrentCamera()
    }

    // Track which landmarks the user is currently inside
    private val activeLandmarks = mutableSetOf<String>()
    
    // Log of geofence events (enter/exit/dwell)
    private val geofenceEventLog = mutableListOf<GeofenceEvent>()
    
    // UI components for the geofence monitor panel
    private lateinit var monitorPanel: LinearLayout
    private lateinit var geofenceListTextView: TextView
    private lateinit var eventLogTextView: TextView
    private var isMonitorVisible = false
    private var isOnline = true
    
    // Network monitoring to detect when device goes offline
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    // Text-to-Speech for reading landmark descriptions
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    
    // Current navigation destination point
    private var currentDestinationPoint: Point? = null
    private var latestUserPoint: Point? = null

    // ID of the landmark currently being displayed
    private var currentLandmarkId: String? = null
    // Last geofence trigger point per landmark (used to report distance from trigger)
    private val lastTriggerPointByLandmark = mutableMapOf<String, Point>()
    /** When true, live location updates must not overwrite trigger-to-POI distance on the card. */
    private var distanceModeTriggerToPoi = false
    
    // Archive items matched to the current landmark (used by About button)
    private var currentLandmarkArchiveItems: List<Route66ArchiveItem> = emptyList()

    /** When the user opens a POI from the map search panel, archive / CONTENTdm matches are narrowed using this text. */
    private var contentDmSearchFilterKeyword: String = ""

    // POI detail card UI components
    private lateinit var detailCard: LinearLayout
    private lateinit var detailTitleText: TextView
    private lateinit var detailImageView: ImageView
    private lateinit var detailDescriptionText: TextView
    private lateinit var detailDistanceText: TextView
    private lateinit var detailExtraText: TextView
    private lateinit var topContainer: LinearLayout
    private lateinit var searchClearBtn: TextView
    private val liveDistanceListener = OnIndicatorPositionChangedListener { point ->
        latestUserPoint = point
        updateDistanceForCurrentLandmark(point)
    }

    // Search UI Components
    private lateinit var searchBar: EditText
    private lateinit var searchResultsScrollView: ScrollView
    private lateinit var searchResultsContainer: LinearLayout
    private lateinit var searchPanel: LinearLayout
    private var isSearchVisible = false

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    
    // Archive Item Detail Card
    private lateinit var archiveDetailCard: LinearLayout
    private lateinit var archiveDetailTitle: TextView
    private lateinit var archiveDetailCallNumber: TextView
    private lateinit var archiveDetailContentDm: TextView
    private lateinit var archiveDetailItemNumber: TextView
    private lateinit var archiveDetailUrl: TextView
    private lateinit var archiveOpenButton: Button
    private lateinit var archiveCloseButton: Button
    //colors helper
    private fun color(id: Int): Int {
        return androidx.core.content.ContextCompat.getColor(this, id)
    }

    private fun createPillButton(
        text: String,
        iconRes: Int,
        bgColor: Int,
        contentColor: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val height = 38.dp()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            layoutParams = LinearLayout.LayoutParams(
                0,
                height,
                1f
            ).apply {
                marginStart = 4.dp()
                marginEnd = 4.dp()
            }

            setPadding(8.dp(), 0, 8.dp(), 0)
            elevation = 4f

            background = androidx.core.content.ContextCompat.getDrawable(
                this@MainActivity,
                R.drawable.btn_pill
            )
            backgroundTintList = ColorStateList.valueOf(bgColor)

            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(contentColor)

                layoutParams = LinearLayout.LayoutParams(
                    16.dp(),
                    16.dp()
                ).apply {
                    marginEnd = 6.dp()
                }
            })

            addView(TextView(this@MainActivity).apply {
                this.text = text
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(contentColor)
                maxLines = 1
            })
        }
    }

    data class GeofenceEvent(
        val landmarkId: String,
        val landmarkName: String,
        val transitionType: String,
        val timestamp: Long,
        val triggerLat: Double? = null,
        val triggerLng: Double? = null,
        val triggerToPoiMeters: Float? = null
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
                // Still enable location; register geofences with fine-only (best-effort while app may be killed).
                enableUserLocation()
                tryRegisterGeofencesIfReady()
            }
        }

    private val requestPostNotificationsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS denied; background POI notifications may be hidden")
            }
        }
    //POI List
    private val poiListLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val id = data.getStringExtra("landmark_id") ?: return@registerForActivityResult
                val action = data.getStringExtra("action") ?: "show"

                when (action) {
                    "show" -> {
                        showLandmarkCard(id)
                    }
                    "navigate" -> {
                        val lm = route66DatabaseRepository.findLandmarkById(id)
                            ?: ArizonaLandmarks.findById(id)

                        if (lm != null) {
                            showLandmarkCard(id)
                            startNavigationTo(lm.toPoint())
                        } else {
                            Toast.makeText(this, "Landmark not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "about" -> {
                        showLandmarkCard(id)
                        openAboutForCurrentLandmark()
                    }
                    "listen" -> {
                        showLandmarkCard(id)
                        readCurrentLandmark()
                    }
                }
            }
        }

    // Receiver for geofence events
    private val geofenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val landmarkId = intent.getStringExtra(GeofenceBroadcastReceiver.EXTRA_LANDMARK_ID) ?: return
            val landmarkName = intent.getStringExtra(GeofenceBroadcastReceiver.EXTRA_LANDMARK_NAME) ?: "Unknown"
            val transitionType = intent.getStringExtra(GeofenceBroadcastReceiver.EXTRA_TRANSITION_TYPE) ?: return
            val timestamp = intent.getLongExtra(GeofenceBroadcastReceiver.EXTRA_TIMESTAMP, System.currentTimeMillis())
            val trigLat = if (intent.hasExtra(GeofenceBroadcastReceiver.EXTRA_TRIGGER_LAT)) {
                intent.getDoubleExtra(GeofenceBroadcastReceiver.EXTRA_TRIGGER_LAT, Double.NaN)
            } else null
            val trigLng = if (intent.hasExtra(GeofenceBroadcastReceiver.EXTRA_TRIGGER_LNG)) {
                intent.getDoubleExtra(GeofenceBroadcastReceiver.EXTRA_TRIGGER_LNG, Double.NaN)
            } else null
            val triggerLat = trigLat?.takeIf { !it.isNaN() }
            val triggerLng = trigLng?.takeIf { !it.isNaN() }
            
            handleGeofenceEvent(landmarkId, landmarkName, transitionType, timestamp, triggerLat, triggerLng)
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

    /** Coil defaults to an OkHttp User-Agent many CDNs block; set a browser-like UA once for POI images. */
    private fun installCoilImageLoaderIfNeeded() {
        if (coilImageLoaderInstalled) return
        synchronized(MainActivity::class.java) {
            if (coilImageLoaderInstalled) return
            val loader = ImageLoader.Builder(applicationContext)
                .okHttpClient { contentDmOkHttpClient }
                .build()
            Coil.setImageLoader(loader)
            coilImageLoaderInstalled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        val darkModeEnabled = prefs.getBoolean(AppSettings.KEY_DARK_MODE, false)

        AppCompatDelegate.setDefaultNightMode(
            if (darkModeEnabled) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        installCoilImageLoaderIfNeeded()
        ensureNotificationPermissionIfNeeded()

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
        archiveRepository = ArchiveRepository(this)
        route66DatabaseRepository = Route66DatabaseRepository(this)
        
        // Load POIs from CUpdated.csv (see Route66DatabaseParser.POI_DATASET_ASSET_NAME) and initialize landmarks
        loadRoute66Database()
        
        // Initialize network monitoring
        setupNetworkMonitoring()
        
        // Create root layout
        val rootLayout = FrameLayout(this)
        
        // Create and add MapView
        mapView = MapView(this)
        mapView.scalebar.enabled = false
        rootLayout.addView(mapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Create and add Geofence Monitor UI
        createGeofenceMonitorUI(rootLayout)

        // Create and add Search UI
        createTopSearchAndButtons(rootLayout)

        createSideMenu(rootLayout)
        createBottomMapButtons(rootLayout)
        // Create and add POI detail card
        createLandmarkDetailCard(rootLayout)

        createArchiveDetailCard(rootLayout)

        setContentView(rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            insets
        }
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

        val mapStyle = if (darkModeEnabled) Style.DARK else Style.MAPBOX_STREETS

        mapView.mapboxMap.loadStyle(mapStyle) { style ->
            poiGeoJsonSource = null
            poiPinStyleImageInjected = false
            isStyleLoaded = true
            setupAnnotationManagers()
            registerGeofenceCircleCameraListener()
            //compass positioning
            rootLayout.post {
                mapView.compass.apply {
                    enabled = false
                    fadeWhenFacingNorth = false
                    position = Gravity.END or Gravity.BOTTOM
                    marginRight = 16.dp().toFloat()
                    marginBottom = 260.dp().toFloat()
                }
            }
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

            ensurePoiLayersAdded(style)
            registerMapPoiTapIfNeeded()

            // ==========================

            if (pendingMarkers) {
                pendingMarkers = false
                addAllLandmarkMarkers()
                drawAllGeofenceCircles()
                updateGeofenceListDisplay()
                tryRegisterGeofencesIfReady()
                scheduleFitCameraToAllPois()
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
        
        // Preload archive data in background
        Thread {
            archiveRepository.loadArchiveData()
            Log.d(TAG, "Archive data preloaded")
        }.start()

        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }
    /**
     * New user onboarding overlay
     */
    private fun createOnboardingOverlay(rootLayout: FrameLayout) {
        onboardingOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#E6000000")) // darker shadowed background
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(
                this@MainActivity,
                R.drawable.bg_onboarding_card
            )
            setPadding(24.dp(), 28.dp(), 24.dp(), 24.dp())
            elevation = 32f
        }

        val title = TextView(this).apply {
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.text_primary))
            gravity = Gravity.CENTER_HORIZONTAL
            text = "Welcome to Experience66"
        }

        val body = TextView(this).apply {
            textSize = 14.5f
            setTextColor(color(R.color.text_secondary))
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(8f, 1.05f)
            setPadding(8.dp(), 14.dp(), 8.dp(), 0)
        }

        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 18.dp(), 0, 0)
        }

        fun dot(isActive: Boolean): View {
            return View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    if (isActive) 22.dp() else 8.dp(),
                    8.dp()
                ).apply {
                    marginStart = 6.dp()
                    marginEnd = 6.dp()
                }

                background = androidx.core.content.ContextCompat.getDrawable(
                    this@MainActivity,
                    R.drawable.bg_onboarding_dot
                )

                backgroundTintList = ColorStateList.valueOf(
                    if (isActive) color(R.color.accent_blue)
                    else color(R.color.text_muted)
                )
            }
        }

        val d1 = dot(true)
        val d2 = dot(false)
        val d3 = dot(false)

        dotsRow.addView(d1)
        dotsRow.addView(d2)
        dotsRow.addView(d3)

        fun setDots(step: Int) {
            fun tintDot(v: View, active: Boolean) {
                v.backgroundTintList = ColorStateList.valueOf(
                    if (active) color(R.color.accent_blue)
                    else color(R.color.text_muted)
                )

                v.layoutParams = LinearLayout.LayoutParams(
                    if (active) 22.dp() else 8.dp(),
                    8.dp()
                ).apply {
                    marginStart = 6.dp()
                    marginEnd = 6.dp()
                }
            }

            tintDot(d1, step == 0)
            tintDot(d2, step == 1)
            tintDot(d3, step == 2)
        }

        val pages = listOf(
            "Welcome to Experience66.\n\nExplore Route 66 through interactive landmarks, stories, and historic photos. Use the map to discover places around you.",

            "POI Landmark Cards.\n\nTap any map marker to open a POI card. Use:\n\n• Listen to hear the story\n• More to view archive details\n• Navigate to preview and start directions",

            "POI List.\n\nOpen the menu to access the POI List. Browse all landmarks, select one to view details, or quickly navigate to any location."
        )

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 20.dp(), 0, 0)
        }

        val skipBtn = TextView(this).apply {
            text = "Skip"
            textSize = 14f
            setTextColor(color(R.color.text_muted))
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            setOnClickListener { finishOnboarding() }
        }

        val nextBtn = Button(this).apply {
            text = "Next"
            background = androidx.core.content.ContextCompat.getDrawable(
                this@MainActivity,
                R.drawable.btn_pill
            )
            backgroundTintList = ColorStateList.valueOf(color(R.color.accent_blue))
            setTextColor(color(R.color.white))
            isAllCaps = false
            setPadding(24.dp(), 10.dp(), 24.dp(), 10.dp())
        }

        var step = 0

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
        buttonsRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(12.dp(), 1)
        })
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

        updateStepUI()
    }
    private fun showOnboarding() {
        onboardingOverlay.alpha = 0f
        onboardingOverlay.visibility = View.VISIBLE

        onboardingOverlay.animate()
            .alpha(1f)
            .setDuration(250)
            .start()
    }

    private fun finishOnboarding() {
        setSeenOnboarding()
        onboardingOverlay.visibility = View.GONE
    }

    /**
     * Load POIs from CUpdated.csv and initialize landmarks
     */
    private fun loadRoute66Database() {
        runOnUiThread {
            Toast.makeText(this, "Loading POIs (CUpdated.csv)…", Toast.LENGTH_SHORT).show()
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
                        circleAnnotationManager?.deleteAll()

                        addAllLandmarkMarkers()
                        drawAllGeofenceCircles()
                        updateGeofenceListDisplay()
                        tryRegisterGeofencesIfReady()
                        scheduleFitCameraToAllPois()
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

                    tryProcessPendingNotificationAction()
                    Log.d(TAG, "Loaded ${landmarks.size} POIs from ${Route66DatabaseParser.POI_DATASET_ASSET_NAME}")
                    
                    Toast.makeText(
                        this,
                        "Loaded ${landmarks.size} POIs",
                        Toast.LENGTH_SHORT
                    ).show()

                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading ${Route66DatabaseParser.POI_DATASET_ASSET_NAME}: ${e.message}", e)
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
                    Log.d(TAG, "Network available - Online mode")
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    isOnline = false
                    Log.d(TAG, "Network lost - Offline mode")

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

    private fun createTopSearchAndButtons(rootLayout: FrameLayout) {
        topContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 12f
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val menuBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_menu)
            layoutParams = LinearLayout.LayoutParams(44.dp(), 44.dp()).apply {
                marginEnd = 8.dp()
            }

            background = androidx.core.content.ContextCompat.getDrawable(
                this@MainActivity,
                R.drawable.btn_circle
            )

            backgroundTintList = android.content.res.ColorStateList.valueOf(
                color(R.color.card_background)
            )

            elevation = 8f
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            setColorFilter(color(R.color.text_primary))

            setOnClickListener {
                closeAllOverlays()
                toggleSideMenu()
            }
        }

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.card_background))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setPadding(10.dp(), 0, 10.dp(), 0)
        }

        searchBar = EditText(this).apply {
            hint = "Search locations..."
            textSize = 14f
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_muted))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setSingleLine(true)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString().orEmpty().trim()
                    searchClearBtn.visibility = if (query.isBlank()) View.GONE else View.VISIBLE

                    if (query.isBlank()) {
                        searchPanel.visibility = View.GONE
                        isSearchVisible = false
                        searchResultsContainer.removeAllViews()
                    } else {
                        performSearch()
                    }
                }

                override fun afterTextChanged(s: Editable?) {}
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    closeAllOverlays()
                }
            }
        }

        searchClearBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(color(R.color.text_muted))
            visibility = View.GONE
            setPadding(12.dp(), 8.dp(), 4.dp(), 8.dp())
            setOnClickListener {
                searchBar.setText("")
            }
        }

        searchBox.addView(searchBar)
        searchBox.addView(searchClearBtn)

        topRow.addView(menuBtn)
        topRow.addView(searchBox)

        searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.surface))
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            elevation = 14f
            visibility = View.GONE
        }

        searchResultsScrollView = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        searchResultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        searchResultsScrollView.addView(searchResultsContainer)
        searchPanel.addView(searchResultsScrollView)

        topContainer.addView(topRow)
        topContainer.addView(searchPanel)

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

        rootLayout.post {
            params.topMargin = 8.dp()
            topContainer.layoutParams = params
        }
    }
    private fun createSideMenu(rootLayout: FrameLayout) {
        sideMenuOverlay = View(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            visibility = View.GONE
            setOnClickListener { closeAllOverlays() }
        }

        rootLayout.addView(
            sideMenuOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val menuWidth = (resources.displayMetrics.widthPixels * 0.35f).toInt()

        sideMenuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.card_background))
            elevation = 18f
            visibility = View.GONE
            setPadding(16.dp(), 24.dp(), 16.dp(), 24.dp())
        }

        val title = TextView(this).apply {
            text = "Menu"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.text_primary))
            setPadding(0, 0, 0, 20.dp())
        }

        fun makeMenuItem(textValue: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = textValue
                textSize = 16f
                setTextColor(color(R.color.text_primary))
                setPadding(0, 14.dp(), 0, 14.dp())
                setOnClickListener {
                    hideSideMenu()
                    onClick()
                }
            }
        }

        val poiItem = makeMenuItem("POI List") {
            poiListLauncher.launch(Intent(this@MainActivity, PoiListActivity::class.java))
        }

        val settingsItem = makeMenuItem("Settings") {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }

        val aboutItem = makeMenuItem("About") {
            startActivity(Intent(this@MainActivity, AboutActivity::class.java))
        }

        sideMenuPanel.addView(title)
        sideMenuPanel.addView(poiItem)
        sideMenuPanel.addView(settingsItem)
        sideMenuPanel.addView(aboutItem)

        val params = FrameLayout.LayoutParams(
            menuWidth,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.START
        }

        rootLayout.addView(sideMenuPanel, params)
    }
    private fun toggleSideMenu() {
        if (isSideMenuOpen) hideSideMenu() else showSideMenu()
    }

    private fun showSideMenu() {
        sideMenuOverlay.visibility = View.VISIBLE
        sideMenuPanel.visibility = View.VISIBLE
        isSideMenuOpen = true
    }

    private fun hideSideMenu() {
        sideMenuOverlay.visibility = View.GONE
        sideMenuPanel.visibility = View.GONE
        isSideMenuOpen = false
    }
    private fun zoomToRoute66Overview() {
        mapView.mapboxMap.setCamera(
            CameraOptions.Builder()
                .center(Point.fromLngLat(-111.5, 35.1))
                .zoom(7.0)
                .bearing(0.0)
                .pitch(0.0)
                .build()
        )
    }

    private fun centerOnUserLocationButton() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestFineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    mapView.mapboxMap.setCamera(
                        CameraOptions.Builder()
                            .center(Point.fromLngLat(location.longitude, location.latitude))
                            .zoom(14.0)
                            .build()
                    )
                } else {
                    Toast.makeText(
                        this,
                        "Current location not available yet",
                        Toast.LENGTH_SHORT
                    ).show()
                    centerWhenFirstLocationIndicatorArrives()
                }
            }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    "Could not get current location",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }


    private fun createCircleMapButton(
        iconRes: Int? = null,
        text: String? = null,
        iconTint: Int = color(R.color.text_primary),
        textColor: Int = color(R.color.text_primary),
        onClick: () -> Unit
    ): FrameLayout {
        val size = 48.dp()

        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                bottomMargin = 10.dp()
            }

            background = androidx.core.content.ContextCompat.getDrawable(
                this@MainActivity,
                R.drawable.btn_circle
            )

            backgroundTintList = android.content.res.ColorStateList.valueOf(
                color(R.color.card_background)
            )

            elevation = 10f
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            if (iconRes != null) {
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(iconRes)

                        val iconSize = when (iconRes) {
                            R.drawable.route66_icon -> 28.dp()
                            R.drawable.user_location_dot -> 20.dp()
                            R.drawable.ic_compass -> 26.dp()
                            else -> 20.dp()
                        }

                        layoutParams = FrameLayout.LayoutParams(
                            iconSize,
                            iconSize,
                            Gravity.CENTER
                        )

                        if (iconRes != R.drawable.route66_icon && iconRes != R.drawable.ic_compass) {
                            imageTintList = ColorStateList.valueOf(iconTint)
                        }
                    }
                )
            }

            if (text != null) {
                addView(
                    TextView(this@MainActivity).apply {
                        this.text = text
                        textSize = 16f
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setTextColor(textColor)
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    }
                )
            }
        }
    }

    private fun createBottomMapButtons(rootLayout: FrameLayout) {
        val controlsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val route66Btn = createCircleMapButton(
            iconRes = R.drawable.route66_icon
        ) {
            zoomToRoute66Overview()
        }

        val locationBtn = createCircleMapButton(
            iconRes = R.drawable.user_location_dot,
            iconTint = color(R.color.accent_blue)
        ) {
            centerOnUserLocationButton()
        }
        val compassBtn = createCircleMapButton(
            iconRes = R.drawable.ic_compass,
            iconTint = color(R.color.text_primary)
        ) {
            mapView.mapboxMap.setCamera(CameraOptions.Builder().bearing(0.0).build())
        }

        controlsColumn.addView(compassBtn)
        controlsColumn.addView(locationBtn)
        controlsColumn.addView(route66Btn)


        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            rightMargin = 16.dp()
            bottomMargin = 120.dp()
        }

        rootLayout.addView(controlsColumn, params)
    }

    /**
     * Perform search for POI or call number
     */
    private fun performSearch() {
        val query = searchBar.text.toString().trim()

        if (query.isBlank()) {
            contentDmSearchFilterKeyword = ""
            searchPanel.visibility = View.GONE
            isSearchVisible = false
            searchResultsContainer.removeAllViews()
            return
        }

        val results = route66DatabaseRepository.searchLandmarks(query).ifEmpty {
            ArizonaLandmarks.landmarks.filter { lm ->
                route66DatabaseRepository.landmarkMatchesSearchQuery(lm, query)
            }
        }

        displayPoiSearchResults(query, results)
    }

    private fun displayPoiSearchResults(query: String, results: List<Route66Landmark>) {
        searchResultsContainer.removeAllViews()
        searchPanel.visibility = View.VISIBLE
        isSearchVisible = true

        if (results.isEmpty()) {
            val noResultsText = TextView(this).apply {
                text = "No locations found for \"$query\""
                setTextColor(color(R.color.accent_red))
                textSize = 13f
                setPadding(16, 16, 16, 16)
            }
            searchResultsContainer.addView(noResultsText)
            return
        }

        results.take(20).forEach { landmark ->
            searchResultsContainer.addView(createPoiLocationResultView(landmark))
        }
    }
    private fun createPoiLocationResultView(landmark: Route66Landmark): LinearLayout {
        val itemLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.card_background))
            setPadding(16, 12, 16, 12)
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
            setOnClickListener {
                contentDmSearchFilterKeyword = searchBar.text.toString().trim()
                mapView.mapboxMap.setCamera(
                    CameraOptions.Builder()
                        .center(Point.fromLngLat(landmark.longitude, landmark.latitude))
                        .zoom(14.0)
                        .build()
                )

                highlightLandmark(landmark.id, true)
                showLandmarkCard(landmark.id)

                searchPanel.visibility = View.GONE
                isSearchVisible = false
            }
        }

        val titleText = TextView(this).apply {
            text = landmark.name
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.accent_blue))
        }

        val descriptionText = TextView(this).apply {
            text = if (landmark.description.length > 120) {
                landmark.description.take(120) + "..."
            } else {
                landmark.description
            }
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, 6, 0, 0)
        }

        itemLayout.addView(titleText)
        itemLayout.addView(descriptionText)

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
            setBackgroundColor(color(R.color.card_background))
            setPadding(24, 24, 24, 24)
            elevation = 16f
            visibility = View.GONE
        }
        
        // Title
        archiveDetailTitle = TextView(this).apply {
            text = "Archive Item Details"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.text_primary))
            setPadding(0, 0, 0, 16)
        }
        archiveDetailCard.addView(archiveDetailTitle)
        
        // Call Number
        archiveDetailCallNumber = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, 4, 0, 4)
        }
        archiveDetailCard.addView(archiveDetailCallNumber)
        
        // CONTENTdm Number
        archiveDetailContentDm = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, 4, 0, 4)
        }
        archiveDetailCard.addView(archiveDetailContentDm)
        
        // Item Number
        archiveDetailItemNumber = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, 4, 0, 4)
        }
        archiveDetailCard.addView(archiveDetailItemNumber)
        
        // URL
        archiveDetailUrl = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.accent_blue))
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
            setBackgroundColor(color(R.color.poi_green))
            setTextColor(color(R.color.white))
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                openArchiveItem()
            }
        }
        buttonRow.addView(archiveOpenButton)
        
        // Close button
        archiveCloseButton = Button(this).apply {
            text = "✕ Close"
            setBackgroundColor(color(R.color.accent_red))
            setTextColor(color(R.color.white))
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
            setBackgroundColor(color(R.color.surface))
            setPadding(16, 16, 16, 16)
            elevation = 8f
            visibility = View.GONE
        }
        
        // Title
        val titleView = TextView(this).apply {
            text = "🗺️ GEOFENCE MONITOR"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.accent_blue))
            setPadding(0, 0, 0, 16)
        }
        monitorPanel.addView(titleView)
        
        // Registered Geofences Section
        val geofenceHeader = TextView(this).apply {
            text = "📋 REGISTERED GEOFENCES (${ArizonaLandmarks.landmarks.size})"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.text_secondary))
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
            setTextColor(color(R.color.text_muted))
            setBackgroundColor(color(R.color.card_background))
            setPadding(12, 12, 12, 12)
        }
        geofenceScrollView.addView(geofenceListTextView)
        monitorPanel.addView(geofenceScrollView)
        
        // Divider
        val divider = View(this).apply {
            setBackgroundColor(color(R.color.text_muted))
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
            setTextColor(color(R.color.text_secondary))
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
            setTextColor(color(R.color.text_muted))
            setBackgroundColor(color(R.color.card_background))
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
            sb.append("  ID: ${event.landmarkId}\n")
            if (event.triggerToPoiMeters != null) {
                sb.append("  📐 Trigger → POI: ${formatDistance(event.triggerToPoiMeters)}\n")
            }
            sb.append("\n")
        }
        
        eventLogTextView.text = sb.toString()
        eventLogTextView.setTextColor(color(R.color.text_primary))
    }

    private fun setupAnnotationManagers() {
        val annotationPlugin = mapView.annotations
        // Geofence circles (POIs use GeoJSON symbol/circle style layers — see [ensurePoiLayersAdded].)
        circleAnnotationManager = annotationPlugin.createCircleAnnotationManager()
    }

    private fun registerMapPoiTapIfNeeded() {
        if (mapPoiClickListenerRegistered) return
        mapPoiClickListenerRegistered = true
        mapView.gestures.addOnMapClickListener { point ->
            val landmark = nearestPoiLandmarkWithinTapRadius(point, maxDistanceMeters = 9_000f)

            if (landmark != null) {
                contentDmSearchFilterKeyword = ""

                mapView.mapboxMap.setCamera(
                    CameraOptions.Builder()
                        .center(landmark.toPoint())
                        .zoom(12.0)
                        .build()
                )

                showLandmarkCard(landmark.id)
                true
            } else {
                closeAllOverlays()
                true
            }
        }
    }

    private fun nearestPoiLandmarkWithinTapRadius(click: Point, maxDistanceMeters: Float): Route66Landmark? {
        val landmarksToShow = landmarksForMapPoiDisplay()
        val groups = landmarksToShow.groupBy { coordinateGroupKey(it.latitude, it.longitude) }
        val row = FloatArray(1)
        var best: Route66Landmark? = null
        var bestM = maxDistanceMeters
        for (lm in landmarksToShow) {
            val key = coordinateGroupKey(lm.latitude, lm.longitude)
            val group = groups[key].orEmpty()
            val idx = group.indexOfFirst { it.id == lm.id }.coerceAtLeast(0)
            val markerPoint = displayPointForPoiMarker(lm, idx, group.size)
            Location.distanceBetween(
                click.latitude(), click.longitude(),
                markerPoint.latitude(), markerPoint.longitude(),
                row
            )
            if (row[0] < bestM) {
                bestM = row[0]
                best = lm
            }
        }
        return best
    }

    /**
     * Same POI rows as [PoiListActivity] ([Route66DatabaseRepository.getAllLandmarks] → [ArizonaLandmarks.initialize]).
     */
    private fun landmarksForMapPoiDisplay(): List<Route66Landmark> = ArizonaLandmarks.landmarks

    private fun getPoiPinBitmap(): Bitmap {
        cachedPoiPinBitmap?.let { return it }
        val drawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.red_marker)
        val bitmap = if (drawable != null) {
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 72
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val canvas = Canvas(b)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        } else {
            Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        }
        val normalized = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        cachedPoiPinBitmap = normalized
        return normalized
    }

    private fun ensurePoiPinStyleImage(style: Style) {
        if (poiPinStyleImageInjected) return
        val bitmap = getPoiPinBitmap()
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        val pixelRatio = resources.displayMetrics.density
        val mbxImage = Image(bitmap.width, bitmap.height, DataRef(buffer))
        val result = style.addStyleImage(
            POI_PIN_IMAGE_ID,
            pixelRatio,
            mbxImage,
            false,
            emptyList(),
            emptyList(),
            null
        )
        if (result.isError) {
            Log.w(TAG, "addStyleImage($POI_PIN_IMAGE_ID): ${result.error}")
        } else {
            poiPinStyleImageInjected = true
        }
    }

    /**
     * POIs use a GeoJSON source + symbol layer (pin + labels), same data as the POI list screen.
     */
    private fun ensurePoiLayersAdded(style: Style) {
        ensurePoiPinStyleImage(style)
        if (!style.styleSourceExists(POI_GEOJSON_SOURCE_ID)) {
            val src = geoJsonSource(POI_GEOJSON_SOURCE_ID) {
                featureCollection(FeatureCollection.fromFeatures(emptyList()))
            }
            style.addSource(src)
            poiGeoJsonSource = src
        }
        if (!style.styleLayerExists(POI_SYMBOL_LAYER_ID)) {
            style.addLayerAbove(
                symbolLayer(POI_SYMBOL_LAYER_ID, POI_GEOJSON_SOURCE_ID) {
                    iconImage(POI_PIN_IMAGE_ID)
                    iconAnchor(IconAnchor.BOTTOM)
                    iconAllowOverlap(true)
                    iconIgnorePlacement(true)
                    textField(Expression.get("name"))
                    textAnchor(TextAnchor.TOP)
                    textOffset(listOf(0.0, 0.35))
                    textSize(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.literal(6.5),
                            Expression.literal(10.5),
                            Expression.literal(9.0),
                            Expression.literal(12.5),
                            Expression.literal(11.0),
                            Expression.literal(14.5),
                            Expression.literal(13.5),
                            Expression.literal(17.5),
                            Expression.literal(16.5),
                            Expression.literal(21.0)
                        )
                    )
                    textColor("#FFFFFF")
                    textHaloColor("rgba(0,0,0,0.82)")
                    textHaloWidth(1.9)
                    textHaloBlur(0.35)
                    textMaxWidth(14.0)
                    textLineHeight(1.2)
                    textJustify(TextJustify.CENTER)
                    textOptional(true)
                    textAllowOverlap(false)
                    textIgnorePlacement(false)
                },
                "route66-layer"
            )
        }
    }

    /** Same rounding as the repo [distinctBy] key so stacked CSV rows fan out slightly on the map only. */
    private fun coordinateGroupKey(latitude: Double, longitude: Double): String =
        "${String.format(Locale.US, "%.5f", latitude)},${String.format(Locale.US, "%.5f", longitude)}"

    private fun displayPointForPoiMarker(landmark: Route66Landmark, indexInGroup: Int, groupSize: Int): Point {
        if (groupSize <= 1) return landmark.toPoint()
        val metersPerDegLat = 111_320.0
        val latRad = landmark.latitude * kotlin.math.PI / 180.0
        val metersPerDegLng = 111_320.0 * kotlin.math.cos(latRad)
        val angle = 2.0 * kotlin.math.PI * indexInGroup / groupSize
        val radiusMeters = 44.0
        val dx = radiusMeters * kotlin.math.cos(angle)
        val dy = radiusMeters * kotlin.math.sin(angle)
        val dLat = dy / metersPerDegLat
        val dLng = dx / metersPerDegLng
        return Point.fromLngLat(landmark.longitude + dLng, landmark.latitude + dLat)
    }

    private fun registerGeofenceCircleCameraListener() {
        geofenceCameraCancelable?.cancel()
        geofenceCameraCancelable = mapView.mapboxMap.subscribeCameraChanged {
            scheduleGeofenceCircleRedraw()
        }
    }

    private fun scheduleGeofenceCircleRedraw() {
        geofenceCircleRedrawHandler.removeCallbacks(geofenceCircleRedrawRunnable)
        geofenceCircleRedrawHandler.postDelayed(geofenceCircleRedrawRunnable, 100L)
    }

    /**
     * Mapbox circle annotations use screen pixels; convert geofence meters using current zoom and latitude
     * so the disk matches real-world radius on the map.
     */
    private fun geofenceRadiusToScreenPixels(latitude: Double, radiusMeters: Float): Double {
        val map = mapView.mapboxMap
        val zoom = map.cameraState.zoom
        val metersPerPixel = map.getMetersPerPixelAtLatitude(latitude, zoom)
        if (metersPerPixel <= 1e-9) return 2.0
        return (radiusMeters / metersPerPixel).toDouble().coerceAtLeast(2.0)
    }

    private fun landmarksForGeofenceSubset(): List<Route66Landmark> {
        val arizonaLandmarks = ArizonaLandmarks.landmarks.filter { landmark ->
            // Eastern AZ Route 66 reaches ~-109.05 (Lupton); keep a small margin past -109.
            landmark.latitude in 31.0..37.0 && landmark.longitude in -115.0..-108.85
        }
        return if (arizonaLandmarks.size > 200) arizonaLandmarks.take(200) else arizonaLandmarks
    }

    private fun drawGeofenceCircleAnnotation(landmark: Route66Landmark, applyActiveHighlight: Boolean) {
        val isActive = applyActiveHighlight && activeLandmarks.contains(landmark.id)
        val color = if (isActive) "#4CAF50" else "#4A90D9"
        val opacity = if (isActive) 0.4 else 0.25
        val strokeW = if (isActive) 3.0 else 2.0
        val strokeC = if (isActive) "#2E7D32" else "#2E5A8B"
        val radiusPx = geofenceRadiusToScreenPixels(landmark.latitude, landmark.radiusMeters)
        val circleOptions = CircleAnnotationOptions()
            .withPoint(landmark.toPoint())
            .withCircleRadius(radiusPx)
            .withCircleColor(color)
            .withCircleOpacity(opacity)
            .withCircleStrokeWidth(strokeW)
            .withCircleStrokeColor(strokeC)
        circleAnnotationManager?.create(circleOptions)
    }

    private fun redrawGeofenceCirclesWithHighlightState() {
        circleAnnotationManager?.deleteAll()
        ArizonaLandmarks.landmarks.forEach { lm ->
            drawGeofenceCircleAnnotation(lm, applyActiveHighlight = true)
        }
    }

    private fun redrawGeofenceCirclesForCurrentCamera() {
        if (!isStyleLoaded || circleAnnotationManager == null) return
        if (ArizonaLandmarks.landmarks.isEmpty()) return
        if (geofenceCirclesUseFullLandmarkSet) {
            redrawGeofenceCirclesWithHighlightState()
        } else {
            circleAnnotationManager?.deleteAll()
            landmarksForGeofenceSubset().forEach { lm ->
                drawGeofenceCircleAnnotation(lm, applyActiveHighlight = false)
            }
        }
    }

    /** Fits the map to every POI shown on the map / list ([landmarksForMapPoiDisplay]). */
    private fun fitCameraToAllArizonaPois() {
        if (!::mapView.isInitialized || !isStyleLoaded) return
        val lms = landmarksForMapPoiDisplay()
        if (lms.isEmpty()) return
        try {
            // cameraForCoordinates() with many duplicate CSV coordinates can yield a broken zoom;
            // fit a padded lat/lng bounding box using corner points instead.
            var minLat = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var minLng = Double.POSITIVE_INFINITY
            var maxLng = Double.NEGATIVE_INFINITY
            for (lm in lms) {
                minLat = minOf(minLat, lm.latitude)
                maxLat = maxOf(maxLat, lm.latitude)
                minLng = minOf(minLng, lm.longitude)
                maxLng = maxOf(maxLng, lm.longitude)
            }
            val latSpan = (maxLat - minLat).coerceAtLeast(0.42)
            val lngSpan = (maxLng - minLng).coerceAtLeast(1.05)
            val padLat = latSpan * 0.14 + 0.02
            val padLng = lngSpan * 0.14 + 0.02
            val sw = Point.fromLngLat(minLng - padLng, minLat - padLat)
            val se = Point.fromLngLat(maxLng + padLng, minLat - padLat)
            val ne = Point.fromLngLat(maxLng + padLng, maxLat + padLat)
            val nw = Point.fromLngLat(minLng - padLng, maxLat + padLat)
            val cam = mapView.mapboxMap.cameraForCoordinates(
                listOf(sw, se, ne, nw),
                EdgeInsets(96.0, 44.0, 240.0, 44.0),
                null,
                null
            )
            mapView.mapboxMap.setCamera(cam)
            poiOverviewCameraApplied = true
            Log.d(TAG, "fitCameraToAllPois: framed ${lms.size} POIs (bbox)")
        } catch (e: Exception) {
            Log.w(TAG, "fitCameraToAllArizonaPois: ${e.message}")
        }
    }

    /** Runs now and delayed so POI framing wins over GPS re-center and late layout. */
    private fun scheduleFitCameraToAllPois() {
        mapView.post { fitCameraToAllArizonaPois() }
        mapView.postDelayed({ fitCameraToAllArizonaPois() }, 900L)
        mapView.postDelayed({ fitCameraToAllArizonaPois() }, 2400L)
    }

    /**
     * Pushes every list POI into the GeoJSON style source (one feature per landmark).
     * Rows that share the same rounded coordinates get a small ring offset on the map only; geofences stay on exact coords.
     */
    private fun syncPoiGeoJsonLayer() {
        if (!isStyleLoaded) return
        val style = mapView.mapboxMap.style ?: return
        ensurePoiLayersAdded(style)
        val landmarksToShow = landmarksForMapPoiDisplay()
        val groups = landmarksToShow.groupBy { coordinateGroupKey(it.latitude, it.longitude) }
        val features = landmarksToShow.map { lm ->
            val key = coordinateGroupKey(lm.latitude, lm.longitude)
            val group = groups[key].orEmpty()
            val idx = group.indexOfFirst { it.id == lm.id }.coerceAtLeast(0)
            val point = displayPointForPoiMarker(lm, idx, group.size)
            Feature.fromGeometry(point).also { f ->
                f.addStringProperty("id", lm.id)
                f.addStringProperty("name", lm.name)
            }
        }
        val src = poiGeoJsonSource
        if (src == null) {
            Log.e(TAG, "POI GeoJSON source not bound; cannot sync features")
            return
        }
        src.featureCollection(FeatureCollection.fromFeatures(features))
        Log.d(TAG, "POI GeoJSON: ${features.size} features (${ArizonaLandmarks.landmarks.size} landmarks in memory)")
    }

    /**
     * Draws every POI from the same list as the POI list screen ([landmarksForMapPoiDisplay]) using GeoJSON + symbols.
     */
    private fun addAllLandmarkMarkers() {
        if (ArizonaLandmarks.landmarks.isEmpty()) {
            Log.w(TAG, "No landmarks to display")
            return
        }
        syncPoiGeoJsonLayer()
    }

    /**
     * Draw geofence radius circles around all landmarks (limited to prevent UI freeze).
     * Radius in pixels follows map zoom so on-screen size matches [Route66Landmark.radiusMeters].
     */
    private fun drawAllGeofenceCircles() {
        if (ArizonaLandmarks.landmarks.isEmpty()) return
        geofenceCirclesUseFullLandmarkSet = false
        circleAnnotationManager?.deleteAll()
        landmarksForGeofenceSubset().forEach { landmark ->
            drawGeofenceCircleAnnotation(landmark, applyActiveHighlight = false)
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

        locationComponent.removeOnIndicatorPositionChangedListener(liveDistanceListener)
        locationComponent.addOnIndicatorPositionChangedListener(liveDistanceListener)

        centerMapOnUserLocationIfAvailable()
    }

    private fun centerMapOnUserLocationIfAvailable() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        if (poiOverviewCameraApplied && ArizonaLandmarks.landmarks.isNotEmpty()) {
            return
        }

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
                if (poiOverviewCameraApplied && ArizonaLandmarks.landmarks.isNotEmpty()) {
                    locationComponent.removeOnIndicatorPositionChangedListener(this)
                    return
                }
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

    private fun updateDistanceForCurrentLandmark(userPoint: Point) {
        if (distanceModeTriggerToPoi) return

        val landmarkId = currentLandmarkId ?: return
        val landmark = route66DatabaseRepository.findLandmarkById(landmarkId)
            ?: ArizonaLandmarks.findById(landmarkId)
            ?: return

        val destination = Point.fromLngLat(landmark.longitude, landmark.latitude)

        val straight = FloatArray(1)
        Location.distanceBetween(
            userPoint.latitude(), userPoint.longitude(),
            landmark.latitude, landmark.longitude,
            straight
        )
        detailDistanceText.text =
            "Distance from POI: ${formatDistance(straight[0])} (road route loading…)"
        detailDistanceText.visibility = View.VISIBLE

        Thread {
            val routeData = RouteDirectionsHelper.fetchRouteData(
                accessToken = getString(R.string.mapbox_access_token),
                origin = userPoint,
                destination = destination
            )

            runOnUiThread {
                if (currentLandmarkId != landmarkId || distanceModeTriggerToPoi) {
                    return@runOnUiThread
                }

                if (routeData != null) {
                    detailDistanceText.text =
                        "Distance from POI: ${RouteDirectionsHelper.formatMiles(routeData.distanceMeters)}"
                } else {
                    detailDistanceText.text =
                        "Distance from POI: ${formatDistance(straight[0])} (straight line; driving route unavailable)"
                }

                detailDistanceText.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun updateDistanceFromLastKnownLocation(landmarkId: String, landmark: Route66Landmark) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            detailDistanceText.text = "Distance unavailable. Location permission is required."
            detailDistanceText.visibility = View.VISIBLE
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    val origin = Point.fromLngLat(location.longitude, location.latitude)
                    val destination = Point.fromLngLat(landmark.longitude, landmark.latitude)

                    val straight = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude, location.longitude,
                        landmark.latitude, landmark.longitude,
                        straight
                    )
                    detailDistanceText.text =
                        "Distance from POI: ${formatDistance(straight[0])} (road route loading…)"
                    detailDistanceText.visibility = View.VISIBLE

                    Thread {
                        val routeData = RouteDirectionsHelper.fetchRouteData(
                            accessToken = getString(R.string.mapbox_access_token),
                            origin = origin,
                            destination = destination
                        )

                        runOnUiThread {
                            if (currentLandmarkId != landmarkId || distanceModeTriggerToPoi) {
                                return@runOnUiThread
                            }

                            if (routeData != null) {
                                detailDistanceText.text =
                                    "Distance from POI: ${RouteDirectionsHelper.formatMiles(routeData.distanceMeters)}"
                            } else {
                                detailDistanceText.text =
                                    "Distance from POI: ${formatDistance(straight[0])} (straight line; driving route unavailable)"
                            }

                            detailDistanceText.visibility = View.VISIBLE
                        }
                    }.start()
                } else {
                    detailDistanceText.text = "Distance unavailable. Waiting for your current location."
                    detailDistanceText.visibility = View.VISIBLE
                }
            }
            .addOnFailureListener {
                detailDistanceText.text = "Distance unavailable right now."
                detailDistanceText.visibility = View.VISIBLE
            }
    }

    private fun formatDistance(distanceMeters: Float): String {
        val feet = distanceMeters * 3.28084f
        val miles = distanceMeters / 1609.344f

        return if (distanceMeters < 160.9344f) {
            "${feet.toInt()} ft"
        } else {
            String.format(Locale.US, "%.2f mi", miles)
        }
    }

    /**
     * Handle geofence ENTER/EXIT events
     */
    private fun handleGeofenceEvent(
        landmarkId: String,
        landmarkName: String,
        transitionType: String,
        timestamp: Long,
        triggerLat: Double?,
        triggerLng: Double?
    ) {
        val lmForDistance = route66DatabaseRepository.findLandmarkById(landmarkId)
            ?: ArizonaLandmarks.findById(landmarkId)
        val triggerToPoiMeters = if (
            transitionType == "ENTER" &&
            lmForDistance != null &&
            triggerLat != null &&
            triggerLng != null
        ) {
            val results = FloatArray(1)
            Location.distanceBetween(
                triggerLat,
                triggerLng,
                lmForDistance.latitude,
                lmForDistance.longitude,
                results
            )
            results[0]
        } else null

        val event = GeofenceEvent(
            landmarkId,
            landmarkName,
            transitionType,
            timestamp,
            triggerLat,
            triggerLng,
            triggerToPoiMeters
        )
        geofenceEventLog.add(event)
        
        when (transitionType) {
            "ENTER" -> {
                activeLandmarks.add(landmarkId)
                if (triggerLat != null && triggerLng != null) {
                    lastTriggerPointByLandmark[landmarkId] = Point.fromLngLat(triggerLng, triggerLat)
                }
                highlightLandmark(landmarkId, true)
                showEntryNotification(landmarkName)
                showLandmarkCard(landmarkId, triggerToPoiMeters)
                if (triggerToPoiMeters != null) {
                    readCurrentLandmark()
                }
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
        geofenceCirclesUseFullLandmarkSet = true
        redrawGeofenceCirclesWithHighlightState()
    }

    private fun showEntryNotification(landmarkName: String) {
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        val notificationsEnabled = prefs.getBoolean(AppSettings.KEY_NOTIFICATIONS_ENABLED, true)

        if (!notificationsEnabled) return

        Toast.makeText(
            this,
            "📍 ENTERED: $landmarkName",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showExitNotification(landmarkName: String) {
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        val notificationsEnabled = prefs.getBoolean(AppSettings.KEY_NOTIFICATIONS_ENABLED, true)

        if (!notificationsEnabled) return

        Toast.makeText(
            this,
            "👋 EXITED: $landmarkName",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showDwellNotification(landmarkName: String) {
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        val notificationsEnabled = prefs.getBoolean(AppSettings.KEY_NOTIFICATIONS_ENABLED, true)

        if (!notificationsEnabled) return

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
        val destinationName = detailTitleText.text?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: "Destination"

        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        val mode = prefs.getString(
            AppSettings.KEY_NAVIGATION_MODE,
            AppSettings.VALUE_NAVIGATION_IN_APP
        )

        if (mode == AppSettings.VALUE_NAVIGATION_GOOGLE_MAPS) {
            NavigationHelper.openGoogleMaps(this, destination)
            return
        }

        val intent = Intent(this, RoutePreviewActivity::class.java).apply {
            putExtra(RoutePreviewActivity.EXTRA_DESTINATION_LAT, destination.latitude())
            putExtra(RoutePreviewActivity.EXTRA_DESTINATION_LON, destination.longitude())
            putExtra(RoutePreviewActivity.EXTRA_DESTINATION_NAME, destinationName)

            latestUserPoint?.let { origin ->
                putExtra(RoutePreviewActivity.EXTRA_ORIGIN_LAT, origin.latitude())
                putExtra(RoutePreviewActivity.EXTRA_ORIGIN_LON, origin.longitude())
            }
        }

        startActivity(intent)
    }

    private fun ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPostNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val landmarkId = intent.getStringExtra(GeofenceBroadcastReceiver.EXTRA_LANDMARK_ID) ?: return
        val action = intent.getStringExtra(GeofenceBroadcastReceiver.EXTRA_NOTIFICATION_ACTION) ?: ACTION_SHOW
        val triggerToPoiMeters = if (intent.hasExtra(GeofenceBroadcastReceiver.EXTRA_TRIGGER_TO_POI_METERS)) {
            intent.getFloatExtra(GeofenceBroadcastReceiver.EXTRA_TRIGGER_TO_POI_METERS, Float.NaN)
                .takeIf { !it.isNaN() }
        } else null

        pendingNotificationLandmarkId = landmarkId
        pendingNotificationAction = action
        pendingNotificationTriggerToPoiMeters = triggerToPoiMeters
        tryProcessPendingNotificationAction()
    }

    private fun tryProcessPendingNotificationAction() {
        val landmarkId = pendingNotificationLandmarkId ?: return
        val action = pendingNotificationAction ?: ACTION_SHOW

        val landmark = route66DatabaseRepository.findLandmarkById(landmarkId)
            ?: ArizonaLandmarks.findById(landmarkId)
            ?: return // Landmarks not loaded yet; retry after DB init.

        pendingNotificationLandmarkId = null
        pendingNotificationAction = null
        val triggerMeters = pendingNotificationTriggerToPoiMeters
        pendingNotificationTriggerToPoiMeters = null

        showLandmarkCard(landmarkId, triggerMeters)

        if (::mapView.isInitialized) {
            mapView.mapboxMap.setCamera(
                CameraOptions.Builder()
                    .center(Point.fromLngLat(landmark.longitude, landmark.latitude))
                    .zoom(13.0)
                    .build()
            )
        }

        when (action) {
            ACTION_LISTEN -> {
                // TTS is created in onCreate; a short delay improves reliability when cold-starting from the notification.
                detailCard.postDelayed({ readCurrentLandmark() }, 500L)
            }
            ACTION_MORE -> {
                // Allow the archive match thread from showLandmarkCard to finish so CONTENTdm opens when items exist.
                detailCard.postDelayed({ openAboutForCurrentLandmark() }, 450L)
            }
            ACTION_NAVIGATE -> startNavigationTo(landmark.toPoint())
            else -> Unit
        }
    }
    /**
     * Bottom card that shows POI details and has a "Listen" button
     */
    private fun createLandmarkDetailCard(rootLayout: FrameLayout) {
        detailCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.card_background))
            elevation = 18f
            clipToPadding = false
            visibility = View.GONE
            setPadding(0, 0, 0, 12.dp())
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(color(R.color.accent_orange))
            setPadding(18, 14, 18, 14)
            gravity = Gravity.CENTER_VERTICAL
        }

        detailTitleText = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.white))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeX = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(color(R.color.white))
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
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = false
            setImageResource(R.mipmap.ic_launcher)
        }
        detailCard.addView(detailImageView)

        detailDescriptionText = TextView(this).apply {
            textSize = 14.5f
            setTextColor(color(R.color.text_primary))
            setLineSpacing(6f, 1.05f)
            setPadding(16.dp(), 6.dp(), 16.dp(), 6.dp())
        }
        detailCard.addView(detailDescriptionText)

        detailDistanceText = TextView(this).apply {
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.poi_green))
            setPadding(16.dp(), 0, 16.dp(), 8.dp())
            visibility = View.GONE
        }
        detailCard.addView(detailDistanceText)

        detailExtraText = TextView(this).apply {
            textSize = 12.5f
            setTextColor(color(R.color.text_muted))
            setPadding(16.dp(), 0, 16.dp(), 10.dp())
        }
        detailCard.addView(detailExtraText)

        val listenBtn = createPillButton(
            text = "Listen",
            iconRes = R.drawable.volume_up,
            bgColor = color(R.color.blue_tonal),
            contentColor = color(R.color.poi_blue)
        ) {
            readCurrentLandmark()
        }

        val moreBtn = createPillButton(
            text = "More",
            iconRes = R.drawable.more_info,
            bgColor = color(R.color.purple_tonal),
            contentColor = color(R.color.poi_purple)
        ) {
            openAboutForCurrentLandmark()
        }

        val navigateBtn = createPillButton(
            text = "Navigate",
            iconRes = R.drawable.navigation,
            bgColor = color(R.color.green_tonal),
            contentColor = color(R.color.poi_green)
        ) {
            val dest = currentDestinationPoint
            if (dest != null) {
                startNavigationTo(dest)
            }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8.dp(), 6.dp(), 8.dp(), 0)
        }

        buttonRow.addView(listenBtn)
        buttonRow.addView(moreBtn)
        buttonRow.addView(navigateBtn)

        detailCard.addView(buttonRow)

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
    private fun showLandmarkCard(landmarkId: String, triggerToPoiDistanceMeters: Float? = null) {
        currentLandmarkId = landmarkId
        distanceModeTriggerToPoi = triggerToPoiDistanceMeters != null

        val lm = route66DatabaseRepository.findLandmarkById(landmarkId)
            ?: ArizonaLandmarks.findById(landmarkId)

        // Prefer CUpdated.csv row for title, long description, and image URL (accurate per LocationID).
        val dbEntry = route66DatabaseRepository.findDatabaseEntryByLandmarkId(landmarkId)
            ?: lm?.let { route66DatabaseRepository.findDatabaseEntryForLandmark(it) }

        val title = dbEntry?.name?.trim()?.takeIf { it.isNotBlank() }
            ?: lm?.name?.takeIf { it.isNotBlank() }
            ?: "Unknown Landmark"

        val description = dbEntry?.description?.trim()?.takeIf { it.isNotBlank() }
            ?: lm?.description?.takeIf { it.isNotBlank() }
            ?: ""

        val extra = buildString {
            dbEntry?.let { e ->
                val place = listOfNotNull(e.city, e.county, e.state).filter { it.isNotBlank() }.joinToString(", ")
                if (place.isNotBlank()) append(place)
                e.imageTitle?.trim()?.takeIf { it.isNotBlank() }?.let { t ->
                    if (isNotEmpty()) append("\n")
                    append(t)
                }
                e.source?.trim()?.takeIf { it.isNotBlank() }?.let { s ->
                    if (isNotEmpty()) append("\n")
                    append("Source: ").append(s)
                }
            }
        }

        detailTitleText.text = title
        bindLandmarkCardImageFromCsv(landmarkId, lm, title)
        detailDescriptionText.text = description
        detailExtraText.text = extra

        currentDestinationPoint = lm?.toPoint()

        if (triggerToPoiDistanceMeters != null) {
            detailDistanceText.text =
                "Distance from trigger point to POI: ${formatDistance(triggerToPoiDistanceMeters)}"
            detailDistanceText.visibility = View.VISIBLE
        } else {
            detailDistanceText.text = "Distance unavailable. Waiting for your current location."
            detailDistanceText.visibility = View.VISIBLE
            if (lm != null) {
                updateDistanceFromLastKnownLocation(landmarkId, lm)
            }
        }

        detailCard.alpha = 0f
        detailCard.translationY = 80.dp().toFloat()
        detailCard.visibility = View.VISIBLE

        detailCard.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        // Find and store archive items for this landmark (for About button)
        if (lm != null) {
            Thread {
                val matchedItems = findArchiveItemsForLandmark(lm, contentDmSearchFilterKeyword)
                runOnUiThread {
                    currentLandmarkArchiveItems = matchedItems
                    if (matchedItems.isNotEmpty()) {
                        Log.d(TAG, "Found ${matchedItems.size} archive items for ${lm.name}")
                    }
                }
            }.start()
        }
    }

    /**
     * Hide the card and stop any speech.
     */
    private fun hideLandmarkCard() {
        if (!::detailCard.isInitialized || detailCard.visibility != View.VISIBLE) return

        tts?.stop()

        detailCard.animate()
            .translationY(detailCard.height.toFloat() + 80.dp())
            .alpha(0f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                detailCard.visibility = View.GONE
                detailCard.translationY = 0f
                detailCard.alpha = 1f

                detailDistanceText.visibility = View.GONE
                currentLandmarkId = null
                distanceModeTriggerToPoi = false
            }
            .start()
    }
    private fun closeAllOverlays() {
        if (::detailCard.isInitialized && detailCard.visibility == View.VISIBLE) {
            hideLandmarkCard()
        }

        if (::archiveDetailCard.isInitialized && archiveDetailCard.visibility == View.VISIBLE) {
            hideArchiveDetailCard()
        }

        if (isSearchVisible) {
            searchPanel.visibility = View.GONE
            isSearchVisible = false
        }

        if (isSideMenuOpen) {
            hideSideMenu()
        }
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
            val distanceLine = detailDistanceText.text?.toString().orEmpty()
            if (distanceLine.isNotBlank()) {
                // Speak the distance after the description
                val clean = distanceLine
                    .replace("Distance from POI:", "Distance from point of interest is")
                    .replace("Distance from trigger:", "Distance from trigger point is")
                    .replace(
                        "Distance from trigger point to POI:",
                        "Distance from trigger point to the point of interest is"
                    )
                    .trim()
                append(". ").append(clean)
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
                        // Fallback: open CONTENTdm search page with keyword overrides
                        val contentDmBaseUrl = "http://cdm16748.contentdm.oclc.org"
                        val collectionUrl = "$contentDmBaseUrl/digital/collection/cpa"
                        val override = SearchKeywordOverrides.forPoiName(landmark.name)
                        val baseTerm = (override?.useTerm ?: landmark.name).trim()
                        // Bias images-only requests by appending "photograph"
                        val effectiveTerm = if (override?.imagesOnly == true) {
                            "$baseTerm photograph"
                        } else baseTerm
                        val searchQuery = effectiveTerm.replace(" ", "+").replace("'", "%27")
                        val searchUrl = "$collectionUrl/search/searchterm/$searchQuery"
                        
                        val disabled = override?.disableFallback == true
                        if (disabled) {
                            Toast.makeText(
                                this,
                                "No relevant CPA results configured for this entry.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl))
                            try {
                                startActivity(intent)
                                Toast.makeText(
                                    this,
                                    "Opening CPA search for $baseTerm",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(this, "Cannot open CONTENTdm: ${e.message}", Toast.LENGTH_LONG).show()
                            }
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
    private fun findArchiveItemsForLandmark(
        landmark: Route66Landmark,
        mapSearchKeyword: String = ""
    ): List<Route66ArchiveItem> {
        // Load archive data if needed
        if (!archiveRepository.isLoaded) {
            archiveRepository.loadArchiveData()
        }
        
        // Get all archive items
        val allItems = archiveRepository.getAllItems()
        val dbEntry = route66DatabaseRepository.findDatabaseEntryForLandmark(landmark)
        val looseMatches = route66DatabaseRepository.matchArchiveItemsToLandmark(landmark, allItems)
        val strictMatches = looseMatches.filter {
            isArchiveItemStrictlyLinkedToPoi(it, landmark, dbEntry, cdmFromPoi = null, poiPrimary = null)
        }

        val matchedItems = mutableListOf<Route66ArchiveItem>()
        when {
            strictMatches.isNotEmpty() -> strictMatches.forEach { addDistinctArchiveItem(matchedItems, it) }
            else -> Unit
        }

        val primaryUrl: String? = null
        val keyword = mapSearchKeyword.trim()
        if (keyword.isNotEmpty()) {
            val term = keyword.lowercase()
            val terms = term.split(Regex("\\s+")).filter { it.length >= 2 }
            val contextHay = "${landmark.name} ${landmark.id} ${landmark.description} " +
                "${dbEntry?.imageTitle.orEmpty()} ${dbEntry?.description.orEmpty()} " +
                "${dbEntry?.city.orEmpty()} ${dbEntry?.county.orEmpty()}"
                .lowercase()
            val filtered = matchedItems.filter { item ->
                if (primaryUrl != null && item.referenceUrl == primaryUrl) return@filter true
                val hay = "${item.callNumber} ${item.referenceUrl} ${item.contentDmNumber} $contextHay".lowercase()
                hay.contains(term) || terms.any { hay.contains(it) }
            }.distinct()
            Log.d(TAG, "Archive filter for '${landmark.name}' keyword '$keyword' -> ${filtered.size} items")
            return filtered
        }
        
        Log.d(TAG, "Found ${matchedItems.size} archive items for ${landmark.name}")
        return matchedItems.distinct()
    }

    private fun addDistinctArchiveItem(list: MutableList<Route66ArchiveItem>, item: Route66ArchiveItem) {
        if (!list.contains(item)) list.add(item)
    }

    /**
     * Keeps CONTENTdm hits tied to this POI: same CPA id as the CSV image, CSV image title tokens in URL/call,
     * or strong landmark name ↔ call number links (avoids unrelated Route 66 archive rows).
     */
    private fun isArchiveItemStrictlyLinkedToPoi(
        item: Route66ArchiveItem,
        landmark: Route66Landmark,
        dbEntry: Route66DatabaseEntry?,
        cdmFromPoi: String?,
        poiPrimary: Route66ArchiveItem?
    ): Boolean {
        if (poiPrimary != null && item.referenceUrl == poiPrimary.referenceUrl) return true
        if (cdmFromPoi != null && (item.contentDmNumber == cdmFromPoi || item.itemNumber == cdmFromPoi ||
                item.referenceUrl.contains("/cpa/$cdmFromPoi", ignoreCase = true))) return true
        dbEntry?.imageTitle?.let { title ->
            val chunks = title.lowercase().split(Regex("\\W+")).filter { it.length >= 4 }
            val ref = item.referenceUrl.lowercase()
            val call = item.callNumber.lowercase()
            if (chunks.any { chunk -> ref.contains(chunk) || call.contains(chunk) }) return true
        }
        val nameChunks = landmark.name.lowercase().split(Regex("\\W+")).filter { it.length >= 4 }
        val callLower = item.callNumber.lowercase()
        if (nameChunks.any { callLower.contains(it) }) return true
        return false
    }

    /** Resolves `R.drawable.poi_<landmarkId>` (e.g. AZ011 → `poi_az011`) when a bundled hero image should override the CSV URL. */
    private fun bundledPoiHeroImageResId(landmarkId: String): Int {
        val resourceName = "poi_${landmarkId.lowercase().replace("-", "_")}"
        return resources.getIdentifier(resourceName, "drawable", packageName)
    }

    /** Loads the POI header image from CSV `Image_URL` for this [landmarkId]; [cardTitle] is used to validate row keywords. */
    private fun bindLandmarkCardImageFromCsv(landmarkId: String, lm: Route66Landmark?, cardTitle: String) {
        val keyword = listOf(cardTitle, lm?.name.orEmpty()).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val url = route66DatabaseRepository.resolveCsvImageUrlForPoi(landmarkId, lm, cardTitle)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "No CSV Image_URL for keyword='$keyword' landmarkId=$landmarkId (CUpdated.csv Name / Image_URL)")
            // Requirement: when CSV image is pending/missing, use route66_icon fallback.
            detailImageView.setImageResource(R.drawable.route66_icon)
            return
        }

        applyPoiCardImagePlaceholder(landmarkId)

        fun loadUrl(targetUrl: String, hasRetriedHttp: Boolean) {
            // Coil: match CSV row by POI name keyword, then load that row's URL; FIT keeps image inside placeholder.
            detailImageView.load(targetUrl) {
                scale(Scale.FIT)
                crossfade(true)
                allowHardware(false)
                listener(
                    onSuccess = { _, _ ->
                        Log.d(TAG, "POI image loaded for $landmarkId")
                    },
                    onError = { _, result ->
                        Log.e(TAG, "POI image failed for $landmarkId url=$targetUrl", result.throwable)
                        val httpFallback = if (!hasRetriedHttp && targetUrl.startsWith("https://", ignoreCase = true)) {
                            targetUrl.replaceFirst("https://", "http://")
                        } else null
                        if (httpFallback != null) {
                            Log.w(TAG, "Retrying POI image with HTTP fallback for $landmarkId")
                            loadUrl(httpFallback, hasRetriedHttp = true)
                        } else if (currentLandmarkId == landmarkId) {
                            // URL existed but failed to load; keep neutral placeholder (not route66 fallback).
                            applyPoiCardImagePlaceholder(landmarkId)
                        }
                    }
                )
            }
        }

        loadUrl(url, hasRetriedHttp = false)
    }

    private fun applyPoiCardImagePlaceholder(landmarkId: String) {
        val resourceName = "poi_${landmarkId.lowercase().replace("-", "_")}"
        val resourceId = resources.getIdentifier(resourceName, "drawable", packageName)
        if (resourceId != 0) {
            detailImageView.setImageResource(resourceId)
        } else {
            detailImageView.setImageDrawable(ColorDrawable(Color.parseColor("#E8E8E8")))
        }
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
            ║ Trigger→POI: ${event.triggerToPoiMeters?.let { formatDistance(it) } ?: "—"}
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

        geofenceCameraCancelable?.cancel()
        geofenceCameraCancelable = null
        geofenceCircleRedrawHandler.removeCallbacks(geofenceCircleRedrawRunnable)

        try { unregisterReceiver(geofenceReceiver) } catch (_: Exception) {}
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        try { mapView.location.removeOnIndicatorPositionChangedListener(liveDistanceListener) } catch (_: Exception) {}
        geofenceManager.removeAllGeofences()

        //TTS cleanup
        tts?.stop()
        tts?.shutdown()
        tts = null

        if (::mapView.isInitialized) mapView.onDestroy()
    }
  }

