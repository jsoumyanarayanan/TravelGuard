package com.travelguard.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import android.widget.ArrayAdapter
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.travelguard.app.R
import com.travelguard.app.data.JourneyStateStore
import com.travelguard.app.data.local.TravelGuardPreferences
import com.travelguard.app.data.remote.NominatimGeocodingClient
import com.travelguard.app.data.remote.OsrmRoutingClient
import com.travelguard.app.databinding.ActivityMainBinding
import com.travelguard.app.domain.geo.GeoMath
import com.travelguard.app.domain.model.Destination
import com.travelguard.app.domain.model.JourneyStatus
import com.travelguard.app.domain.model.JourneyUiState
import com.travelguard.app.domain.model.ProximityAlarmState
import com.travelguard.app.domain.model.RouteMonitoringState
import com.travelguard.app.navigation.GoogleMapsDirectionsLauncher
import com.travelguard.app.service.LocationTrackingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: TravelGuardPreferences
    private val geocodingClient = NominatimGeocodingClient()
    private val routingClient = OsrmRoutingClient()

    private var userMarker: Marker? = null
    private var destMarker: Marker? = null
    private var radiusPolygon: Polygon? = null
    private var routePolyline: Polyline? = null
    private var isUpdatingControlsProgrammatically = false
    private var isApplyingDestinationText = false
    private var suggestionJob: Job? = null
    private var suggestionRequestId = 0L
    private var suggestionDestinations: List<Destination> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            startJourneyService()
        } else {
            JourneyStateStore.update {
                it.copy(warningMessage = "Location permission is required to monitor your journey.")
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Notification permission denied. Wake Me Up alerts may not appear visibly.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = TravelGuardPreferences(this)
        JourneyStateStore.initFromPrefs(this)

        setupMap()
        setupControls()
        observeState()
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(13.0)
            controller.setCenter(GeoPoint(13.0827, 80.2707))

            val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    selectMapDestination(p)
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean {
                    selectMapDestination(p)
                    return true
                }
            })
            overlays.add(0, eventsOverlay)
        }
    }

    private fun selectMapDestination(point: GeoPoint) {
        val currentState = JourneyStateStore.uiState.value
        if (currentState.journeyStatus == JourneyStatus.ACTIVE) {
            Toast.makeText(this, "End the active journey before changing destination.", Toast.LENGTH_SHORT).show()
            return
        }
        val initialDest = Destination(
            name = String.format(Locale.US, "Pinned (%.4f, %.4f)", point.latitude, point.longitude),
            address = "Resolving address...",
            latitude = point.latitude,
            longitude = point.longitude
        )
        JourneyStateStore.setDestination(this, initialDest)

        lifecycleScope.launch {
            val label = geocodingClient.reverseGeocode(point.latitude, point.longitude)
            if (label != null) {
                JourneyStateStore.setDestination(
                    this@MainActivity,
                    initialDest.copy(name = label, address = "Map pin • ${String.format(Locale.US, "%.4f, %.4f", point.latitude, point.longitude)}")
                )
            } else {
                JourneyStateStore.setDestination(
                    this@MainActivity,
                    initialDest.copy(address = "Map pin (offline)")
                )
            }
            fetchPreviewRouteIfPossible()
        }
    }

    private fun setupControls() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnSearchDestination.setOnClickListener { performDestinationSearch() }
        binding.etSearchDestination.threshold = 3
        binding.etSearchDestination.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())
        )
        binding.etSearchDestination.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isApplyingDestinationText) return
                if (JourneyStateStore.uiState.value.journeyStatus == JourneyStatus.ACTIVE) return

                val query = s?.toString()?.trim().orEmpty()
                suggestionJob?.cancel()
                suggestionRequestId++
                val requestId = suggestionRequestId
                suggestionDestinations = emptyList()
                (binding.etSearchDestination.adapter as? ArrayAdapter<String>)?.clear()

                // A typed query is not a confirmed destination. Remove stale coordinates immediately.
                if (JourneyStateStore.uiState.value.destination != null) {
                    JourneyStateStore.setDestination(this@MainActivity, null)
                }
                if (query.length < 3) {
                    binding.etSearchDestination.dismissDropDown()
                    return
                }

                suggestionJob = lifecycleScope.launch {
                    // Nominatim's public service asks clients to stay at or below one request/second.
                    delay(1_100)
                    when (val result = geocodingClient.search(query)) {
                        is NominatimGeocodingClient.SearchResult.Success -> {
                            if (requestId != suggestionRequestId ||
                                binding.etSearchDestination.text?.toString()?.trim() != query
                            ) return@launch
                            suggestionDestinations = result.destinations
                            val labels = result.destinations.map {
                                if (it.address.isBlank() || it.address == it.name) it.name
                                else "${it.name} — ${it.address}"
                            }
                            val adapter = binding.etSearchDestination.adapter as ArrayAdapter<String>
                            adapter.clear()
                            adapter.addAll(labels)
                            adapter.notifyDataSetChanged()
                            if (labels.isNotEmpty()) binding.etSearchDestination.showDropDown()
                            else binding.etSearchDestination.dismissDropDown()
                        }
                        is NominatimGeocodingClient.SearchResult.Error -> {
                            if (requestId == suggestionRequestId) {
                                JourneyStateStore.update { it.copy(warningMessage = result.message) }
                            }
                        }
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.etSearchDestination.setOnItemClickListener { _, _, position, _ ->
            val selected = suggestionDestinations.getOrNull(position) ?: return@setOnItemClickListener
            isApplyingDestinationText = true
            binding.etSearchDestination.setText(selected.name, false)
            isApplyingDestinationText = false
            suggestionJob?.cancel()
            suggestionRequestId++
            applySelectedDestination(selected)
        }
        binding.etSearchDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performDestinationSearch()
                true
            } else {
                false
            }
        }

        binding.btnClearDestination.setOnClickListener {
            if (JourneyStateStore.uiState.value.journeyStatus == JourneyStatus.ACTIVE) {
                Toast.makeText(this, "End the active journey first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            suggestionJob?.cancel()
            suggestionRequestId++
            JourneyStateStore.setDestination(this, null)
            isApplyingDestinationText = true
            binding.etSearchDestination.setText("", false)
            isApplyingDestinationText = false
        }

        binding.btnRecenterMap.setOnClickListener {
            val loc = JourneyStateStore.uiState.value.currentLocation
            val dest = JourneyStateStore.uiState.value.destination
            when {
                loc != null -> binding.mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                dest != null -> binding.mapView.controller.animateTo(GeoPoint(dest.latitude, dest.longitude))
                else -> Toast.makeText(this, "No GPS fix or destination yet.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnFitRoute.setOnClickListener {
            fitRouteOrPoints()
        }

        binding.switchWakeMeUp.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingControlsProgrammatically) return@setOnCheckedChangeListener
            val state = JourneyStateStore.uiState.value
            prefs.saveAlarmConfig(
                wakeMeUpEnabled = isChecked,
                wakeRadiusKm = state.wakeRadiusKm,
                routeGuardEnabled = state.routeGuardEnabled,
                deviationThresholdKm = state.deviationThresholdKm
            )
            JourneyStateStore.update {
                it.copy(
                    wakeMeUpEnabled = isChecked,
                    proximityAlarmState = if (isChecked) ProximityAlarmState.ARMED else ProximityAlarmState.DISABLED
                )
            }
            notifyServiceConfigChanged()
        }

        binding.sliderWakeRadius.addOnChangeListener { _, value, fromUser ->
            val rounded = (Math.round(value * 10f) / 10f).coerceIn(0.1f, 10.0f)
            binding.tvWakeRadiusValue.text = String.format(Locale.US, "%.1f km", rounded)
            if (!fromUser) return@addOnChangeListener
            val state = JourneyStateStore.uiState.value
            prefs.saveAlarmConfig(
                wakeMeUpEnabled = state.wakeMeUpEnabled,
                wakeRadiusKm = rounded,
                routeGuardEnabled = state.routeGuardEnabled,
                deviationThresholdKm = state.deviationThresholdKm
            )
            JourneyStateStore.update { it.copy(wakeRadiusKm = rounded) }
            notifyServiceConfigChanged()
        }

        binding.switchRouteGuard.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingControlsProgrammatically) return@setOnCheckedChangeListener
            val state = JourneyStateStore.uiState.value
            prefs.saveAlarmConfig(
                wakeMeUpEnabled = state.wakeMeUpEnabled,
                wakeRadiusKm = state.wakeRadiusKm,
                routeGuardEnabled = isChecked,
                deviationThresholdKm = state.deviationThresholdKm
            )
            JourneyStateStore.update {
                it.copy(
                    routeGuardEnabled = isChecked,
                    routeMonitoringState = when {
                        !isChecked -> RouteMonitoringState.DISABLED
                        it.routeData != null -> RouteMonitoringState.MONITORING
                        else -> RouteMonitoringState.WAITING_FOR_ROUTE
                    }
                )
            }
            notifyServiceConfigChanged()
        }

        binding.sliderDeviationThreshold.addOnChangeListener { _, value, fromUser ->
            val rounded = (Math.round(value * 10f) / 10f).coerceIn(0.2f, 5.0f)
            binding.tvDeviationValue.text = String.format(Locale.US, "%.1f km", rounded)
            if (!fromUser) return@addOnChangeListener
            val state = JourneyStateStore.uiState.value
            prefs.saveAlarmConfig(
                wakeMeUpEnabled = state.wakeMeUpEnabled,
                wakeRadiusKm = state.wakeRadiusKm,
                routeGuardEnabled = state.routeGuardEnabled,
                deviationThresholdKm = rounded
            )
            JourneyStateStore.update { it.copy(deviationThresholdKm = rounded) }
            notifyServiceConfigChanged()
        }

        binding.btnImAwake.setOnClickListener {
            startService(
                Intent(this, LocationTrackingService::class.java).apply {
                    action = LocationTrackingService.ACTION_ACKNOWLEDGE_ALARM
                }
            )
        }

        binding.btnStartEndJourney.setOnClickListener {
            val state = JourneyStateStore.uiState.value
            if (state.journeyStatus == JourneyStatus.ACTIVE) {
                startService(
                    Intent(this, LocationTrackingService::class.java).apply {
                        action = LocationTrackingService.ACTION_STOP_JOURNEY
                    }
                )
            } else {
                if (state.destination == null || !state.destination.isValid()) {
                    Toast.makeText(this, "Please select a destination first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ensurePermissionsAndStart()
            }
        }

        binding.btnOpenGoogleMaps.setOnClickListener {
            val state = JourneyStateStore.uiState.value
            when (val res = GoogleMapsDirectionsLauncher.openDirections(this, state.currentLocation, state.destination)) {
                is GoogleMapsDirectionsLauncher.LaunchResult.Failed -> {
                    Toast.makeText(this, res.reason, Toast.LENGTH_LONG).show()
                }
                else -> Unit
            }
        }
    }

    private fun performDestinationSearch() {
        val query = binding.etSearchDestination.text?.toString().orEmpty().trim()
        if (query.isEmpty()) return

        lifecycleScope.launch {
            when (val result = geocodingClient.search(query)) {
                is NominatimGeocodingClient.SearchResult.Success -> {
                    if (result.destinations.isEmpty()) {
                        Toast.makeText(this@MainActivity, "No matching places found.", Toast.LENGTH_SHORT).show()
                    } else if (result.destinations.size == 1) {
                        applySelectedDestination(result.destinations[0])
                    } else {
                        val labels = result.destinations.map { "${it.name}\n${it.address}" }.toTypedArray()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Select destination")
                            .setItems(labels) { _, which ->
                                applySelectedDestination(result.destinations[which])
                            }
                            .show()
                    }
                }
                is NominatimGeocodingClient.SearchResult.Error -> {
                    JourneyStateStore.update { it.copy(warningMessage = result.message) }
                }
            }
        }
    }

    private fun applySelectedDestination(dest: Destination) {
        suggestionJob?.cancel()
        suggestionRequestId++
        isApplyingDestinationText = true
        binding.etSearchDestination.setText(dest.name, false)
        isApplyingDestinationText = false
        JourneyStateStore.setDestination(this, dest)
        binding.mapView.controller.animateTo(GeoPoint(dest.latitude, dest.longitude))
        fetchPreviewRouteIfPossible()
    }

    private fun fetchPreviewRouteIfPossible() {
        val state = JourneyStateStore.uiState.value
        val loc = state.currentLocation ?: return
        val dest = state.destination ?: return
        lifecycleScope.launch {
            when (val res = routingClient.fetchRoute(loc.latitude, loc.longitude, dest.latitude, dest.longitude)) {
                is OsrmRoutingClient.RouteResult.Success -> {
                    JourneyStateStore.setRouteData(this@MainActivity, res.route)
                }
                is OsrmRoutingClient.RouteResult.Unavailable -> {
                    JourneyStateStore.update { it.copy(warningMessage = res.reason) }
                }
            }
        }
    }

    private fun ensurePermissionsAndStart() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            startJourneyService()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Location permission needed")
                .setMessage("TravelGuard uses your GPS position in the background to trigger Wake Me Up and Route Guard even when your screen is off or you switch to Google Maps.")
                .setPositiveButton("Continue") { _, _ ->
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startJourneyService() {
        val intent = Intent(this, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START_JOURNEY
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun notifyServiceConfigChanged() {
        if (JourneyStateStore.uiState.value.journeyStatus == JourneyStatus.ACTIVE) {
            startService(
                Intent(this, LocationTrackingService::class.java).apply {
                    action = LocationTrackingService.ACTION_REFRESH_CONFIG
                }
            )
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                JourneyStateStore.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: JourneyUiState) {
        isUpdatingControlsProgrammatically = true

        binding.tvJourneyStatusChip.text = when (state.journeyStatus) {
            JourneyStatus.IDLE -> "Idle"
            JourneyStatus.PREPARING -> "Preparing"
            JourneyStatus.ACTIVE -> "Journey Active"
            JourneyStatus.COMPLETED -> "Completed"
            JourneyStatus.ERROR -> "Attention"
        }

        if (state.destination != null) {
            binding.tvSelectedDestName.text = state.destination.name
            binding.tvSelectedDestAddress.text = state.destination.address.ifBlank {
                String.format(Locale.US, "%.4f, %.4f", state.destination.latitude, state.destination.longitude)
            }
        } else {
            binding.tvSelectedDestName.text = "No destination selected"
            binding.tvSelectedDestAddress.text = getString(R.string.pick_on_map)
        }

        binding.switchWakeMeUp.isChecked = state.wakeMeUpEnabled
        binding.sliderWakeRadius.value = state.wakeRadiusKm.coerceIn(0.1f, 10.0f)
        binding.tvWakeRadiusValue.text = String.format(Locale.US, "%.1f km", state.wakeRadiusKm)
        binding.tvWakeStatus.text = when {
            !state.wakeMeUpEnabled -> "Status: OFF"
            state.proximityAlarmState == ProximityAlarmState.TRIGGERED -> "Status: ON • ALARM RINGING"
            state.proximityAlarmState == ProximityAlarmState.ACKNOWLEDGED -> "Status: ON • Acknowledged"
            else -> "Status: ON • Armed"
        }
        binding.btnImAwake.visibility =
            if (state.proximityAlarmState == ProximityAlarmState.TRIGGERED) View.VISIBLE else View.GONE

        binding.switchRouteGuard.isChecked = state.routeGuardEnabled
        binding.sliderDeviationThreshold.value = state.deviationThresholdKm.coerceIn(0.2f, 5.0f)
        binding.tvDeviationValue.text = String.format(Locale.US, "%.1f km", state.deviationThresholdKm)
        binding.tvRouteGuardStatus.text = when (state.routeMonitoringState) {
            RouteMonitoringState.DISABLED -> "Status: OFF"
            RouteMonitoringState.WAITING_FOR_ROUTE -> "Status: ON • Waiting for route"
            RouteMonitoringState.MONITORING -> "Status: ON • Monitoring route"
            RouteMonitoringState.DEVIATION_PENDING -> "Status: ON • Verifying deviation..."
            RouteMonitoringState.DEVIATED -> {
                val devKm = (state.routeDeviationMeters ?: 0.0) / 1000.0
                String.format(Locale.US, "Status: DEVIATED (%.2f km off route)", devKm)
            }
            RouteMonitoringState.UNAVAILABLE -> "Route monitoring unavailable — no route loaded"
        }

        binding.btnStartEndJourney.text =
            if (state.journeyStatus == JourneyStatus.ACTIVE) getString(R.string.end_journey)
            else getString(R.string.start_journey)

        binding.btnOpenGoogleMaps.isEnabled = state.destination != null && state.destination.isValid()

        // Metrics
        val distMeters = state.remainingDistanceMeters
        binding.tvMetricDistance.text = if (distMeters != null) {
            val label = if (state.isRemainingDistanceDirect) "Direct" else "Route"
            String.format(Locale.US, "%s: %.2f km", label, distMeters / 1000.0)
        } else {
            "Distance: --"
        }

        val etaSec = state.estimatedRemainingSeconds
        binding.tvMetricEta.text = if (etaSec != null) {
            val mins = (etaSec / 60).coerceAtLeast(1)
            if (mins >= 60) "ETA: ${mins / 60}h ${mins % 60}m" else "ETA: ${mins}m"
        } else {
            "ETA: --"
        }

        val arrivalMs = state.estimatedArrivalMillis
        binding.tvMetricArrival.text = if (arrivalMs != null) {
            val formatted = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(arrivalMs))
            "Arrival: $formatted"
        } else {
            "Arrival: --"
        }

        binding.tvMetricProgress.text = state.progressPercent?.let { "Progress: $it%" } ?: "Progress: --"
        binding.tvCurrentLocality.text = "Location: ${state.currentLocality ?: "Waiting for locality / offline"}"

        val loc = state.currentLocation
        binding.tvGpsAge.text = if (loc != null) {
            val ageSec = ((System.currentTimeMillis() - loc.timestampMillis) / 1000L).coerceAtLeast(0L)
            val staleNote = if (ageSec > 60) " (Stale)" else ""
            "GPS: ±${loc.accuracyMeters.toInt()}m • ${ageSec}s ago$staleNote"
        } else {
            "GPS status: No fix yet"
        }

        if (!state.warningMessage.isNullOrBlank()) {
            binding.cardWarningBanner.visibility = View.VISIBLE
            binding.tvWarningBanner.text = state.warningMessage
        } else {
            binding.cardWarningBanner.visibility = View.GONE
        }

        binding.tvRecentAlerts.text = if (state.recentAlerts.isEmpty()) {
            "No alerts recorded yet."
        } else {
            val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
            state.recentAlerts.joinToString("\n") { alert ->
                "• [${tf.format(Date(alert.timestampMillis))}] ${alert.type}: ${alert.message}"
            }
        }

        renderMapOverlays(state)
        isUpdatingControlsProgrammatically = false
    }

    private fun renderMapOverlays(state: JourneyUiState) {
        val map = binding.mapView

        // Route polyline
        routePolyline?.let { map.overlays.remove(it) }
        val routePts = state.routeData?.points
        if (!routePts.isNullOrEmpty()) {
            routePolyline = Polyline().apply {
                setPoints(routePts.map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.tg_map_route_stroke)
                outlinePaint.strokeWidth = 9f
            }
            map.overlays.add(routePolyline)
        }

        // Destination marker + proximity circle
        destMarker?.let { map.overlays.remove(it) }
        radiusPolygon?.let { map.overlays.remove(it) }
        val dest = state.destination
        if (dest != null && dest.isValid()) {
            val destGeo = GeoPoint(dest.latitude, dest.longitude)
            radiusPolygon = Polygon().apply {
                points = Polygon.pointsAsCircle(destGeo, GeoMath.kmToMeters(state.wakeRadiusKm))
                fillPaint.color = ContextCompat.getColor(this@MainActivity, R.color.tg_map_radius_fill)
                outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.tg_map_radius_stroke)
                outlinePaint.strokeWidth = 3f
            }
            map.overlays.add(radiusPolygon)

            destMarker = Marker(map).apply {
                position = destGeo
                title = dest.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(destMarker)
        }

        // User marker
        userMarker?.let { map.overlays.remove(it) }
        val loc = state.currentLocation
        if (loc != null) {
            userMarker = Marker(map).apply {
                position = GeoPoint(loc.latitude, loc.longitude)
                title = "Current location"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            map.overlays.add(userMarker)
        }

        map.invalidate()
    }

    private fun fitRouteOrPoints() {
        val pts = mutableListOf<GeoPoint>()
        JourneyStateStore.uiState.value.currentLocation?.let { pts.add(GeoPoint(it.latitude, it.longitude)) }
        JourneyStateStore.uiState.value.destination?.let { pts.add(GeoPoint(it.latitude, it.longitude)) }
        JourneyStateStore.uiState.value.routeData?.points?.forEach { pts.add(GeoPoint(it.latitude, it.longitude)) }

        if (pts.size >= 2) {
            val box = BoundingBox.fromGeoPoints(pts)
            binding.mapView.zoomToBoundingBox(box, true, 80)
        } else if (pts.size == 1) {
            binding.mapView.controller.animateTo(pts[0])
        } else {
            Toast.makeText(this, "Select a destination or wait for GPS fix.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }
}
