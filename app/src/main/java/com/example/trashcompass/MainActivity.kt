package com.example.trashcompass

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

// Data class
data class Amenity(
    val location: Location,
    val tags: JSONObject?
)

class MainActivity : AppCompatActivity(), SensorEventListener {

    // UI
    private lateinit var tvTitle: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvMetadata: TextView
    private lateinit var tvHint: TextView
    private lateinit var ivArrow: ImageView
    private lateinit var ivSettings: ImageView // NEW
    private lateinit var tvAccuracy: TextView
    private lateinit var tvMapButton: TextView
    private lateinit var tvLegal: TextView
    private lateinit var tvInterference: TextView
    private lateinit var loadingSpinner: ProgressBar

    // Preferences
    private lateinit var prefs: SharedPreferences
    private var searchRadiusMeters = 2000 // Default 2km
    private var useMetric = true

    // Sensors
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var magneticSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var currentArrowRotation = 0f

    // Compass Accuracy State
    private var lastMagAccuracy = -1 // Fallback accuracy

    // State
    private var currentLocation: Location? = null
    private var destinationAmenity: Amenity? = null
    private var foundAmenities = ArrayList<Amenity>()

    // Logic
    private var lastFetchLocation: Location? = null
    private val REFETCH_DISTANCE_THRESHOLD = 150f
    private val ERROR_RETRY_DISTANCE = 100f
    private var initialSearchDone = false

    // --- DRIVING ANIMATION STATE ---
    private var driveAnimJob: Job? = null
    private var lastFixTime: Long = 0L
    // -------------------------------

    // SPEED THRESHOLD: 15 MPH is approx 6.7 Meters per Second
    private val SPEED_THRESHOLD_MPS = 6.7f

    private var currentAmenityName = "Trash Can"
    private var isSearching = false
    private var isErrorState = false
    private var searchJob: Job? = null
    private var lastFriendlyError = ""

    // Quick access menu options
    private val hardcodedOptions = listOf(
        "Trash Can", "Public Toilet", "Defibrillator (AED)",
        "Water Fountain", "Recycling Bin", "ATM", "Post Box", "Bench"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Aggressive Server List
    private val servers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.openstreetmap.ru/api/interpreter"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Load Preferences
        prefs = getSharedPreferences("TrashCompassPrefs", Context.MODE_PRIVATE)
        searchRadiusMeters = prefs.getInt("search_radius", 2000)
        useMetric = prefs.getBoolean("use_metric", true)

        tvTitle = findViewById(R.id.tvTitle)
        tvDistance = findViewById(R.id.tvDistance)
        tvMetadata = findViewById(R.id.tvMetadata)
        tvHint = findViewById(R.id.tvHint)
        ivArrow = findViewById(R.id.ivArrow)
        ivSettings = findViewById(R.id.ivSettings) // Bind NEW View
        tvAccuracy = findViewById(R.id.tvAccuracy)
        tvMapButton = findViewById(R.id.tvMapButton)
        tvLegal = findViewById(R.id.tvLegal)
        loadingSpinner = findViewById(R.id.loadingSpinner)

        tvLegal.setOnClickListener { showLegalDialog() }
        ivSettings.setOnClickListener { showSettingsDialog() }

        // Center metadata text
        tvMetadata.gravity = Gravity.CENTER
        val padding = (20 * resources.displayMetrics.density).toInt()
        tvMetadata.setPadding(padding, 0, padding, 0)

        // Initialize Arrow as Inactive (Grey)
        setArrowActive(false)

        addInterferenceWarning()

        tvDistance.text = "Waiting for GPS..."

        tvMapButton.setOnClickListener {
            destinationAmenity?.let { target ->
                val lat = target.location.latitude
                val lon = target.location.longitude
                val label = Uri.encode(currentAmenityName)
                val uri = "geo:0,0?q=$lat,$lon($label)"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                intent.setPackage("com.google.android.apps.maps")
                if (intent.resolveActivity(packageManager) != null) startActivity(intent)
                else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")))
            }
        }

        val mainAction = {
            if (isErrorState || (foundAmenities.isEmpty() && initialSearchDone && !isSearching)) {
                // Retry action
                if (currentLocation != null) {
                    fetchAmenitiesAggressively(currentLocation!!.latitude, currentLocation!!.longitude, currentAmenityName, isSilent = false)
                }
            } else {
                // Toggle units action
                useMetric = !useMetric
                prefs.edit().putBoolean("use_metric", useMetric).apply()
                updateUI()
            }
        }
        tvDistance.setOnClickListener { mainAction() }
        tvHint.setOnClickListener { mainAction() }

        tvAccuracy.setOnClickListener { showCalibrationDialog() }

        tvTitle.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            hardcodedOptions.forEach { popup.menu.add(it) }
            popup.menu.add("🔍 Search Custom...")
            popup.setOnMenuItemClickListener { item ->
                if (item.title == "🔍 Search Custom...") {
                    showCustomSearchDialog()
                } else {
                    setNewSearchTarget(item.title.toString())
                }
                true
            }
            popup.show()
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkPermissions()
    }

    private fun setArrowActive(isActive: Boolean) {
        if (isActive) {
            ivArrow.alpha = 1.0f
            ivArrow.setColorFilter(Color.parseColor("#32CD32"), PorterDuff.Mode.SRC_IN)
        } else {
            ivArrow.alpha = 0.5f
            ivArrow.setColorFilter(Color.GRAY, PorterDuff.Mode.SRC_IN)
        }
    }

    // --- SETTINGS DIALOG ---
    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Settings")

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        layout.setPadding(padding, padding, padding, padding)

        // Label
        val lblRadius = TextView(this)
        lblRadius.text = "Search Radius: ${searchRadiusMeters}m"
        lblRadius.textSize = 16f
        layout.addView(lblRadius)

        // Warning Text
        val lblWarning = TextView(this)
        lblWarning.text = "⚠️ Large radius may cause timeouts."
        lblWarning.setTextColor(Color.RED)
        lblWarning.textSize = 12f
        lblWarning.setPadding(0, 10, 0, 10)
        lblWarning.visibility = if (searchRadiusMeters > 3000) View.VISIBLE else View.GONE
        layout.addView(lblWarning)

        // Slider
        val seekBar = SeekBar(this)
        seekBar.max = 95 // 0 to 95 steps (500m to 10000m)
        // Map 500..10000 to 0..95 (Step 100)
        seekBar.progress = (searchRadiusMeters - 500) / 100

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val meters = 500 + (progress * 100)
                lblRadius.text = "Search Radius: ${meters}m"
                if (meters > 3000) {
                    lblWarning.visibility = View.VISIBLE
                    if (meters > 5000) lblWarning.text = "⚠️ Warning: Very slow load times!"
                    else lblWarning.text = "⚠️ Large radius may cause timeouts."
                } else {
                    lblWarning.visibility = View.GONE
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        layout.addView(seekBar)

        builder.setView(layout)

        builder.setPositiveButton("Save") { _, _ ->
            val newRadius = 500 + (seekBar.progress * 100)
            searchRadiusMeters = newRadius
            prefs.edit().putInt("search_radius", searchRadiusMeters).apply()

            // Trigger refresh
            if (currentLocation != null) {
                setNewSearchTarget(currentAmenityName)
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    // --- SEARCH LOGIC ---
    private fun showCustomSearchDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Search Features")
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)

        val input = AutoCompleteTextView(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.hint = "e.g. Castle, Crane, Adit..."

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, TagRepository.mapping.keys.toList().sorted())
        input.setAdapter(adapter)
        input.threshold = 1
        container.addView(input)
        builder.setView(container)

        builder.setPositiveButton("Search") { _, _ ->
            val query = input.text.toString().trim()
            if (query.isNotEmpty()) setNewSearchTarget(query)
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun setNewSearchTarget(targetName: String) {
        currentAmenityName = targetName
        tvTitle.text = "Nearest $currentAmenityName"
        foundAmenities.clear()
        destinationAmenity = null
        lastFetchLocation = null
        initialSearchDone = false
        tvDistance.text = "Searching..."
        tvMetadata.visibility = View.GONE
        tvMapButton.visibility = View.GONE
        setArrowActive(false)

        if (currentLocation != null) {
            fetchAmenitiesAggressively(currentLocation!!.latitude, currentLocation!!.longitude, currentAmenityName, isSilent = false)
        }
    }

    // --- INTERFERENCE UI ---
    private fun addInterferenceWarning() {
        val rootLayout = findViewById<ViewGroup>(android.R.id.content)
        tvInterference = TextView(this)
        tvInterference.text = "🧲 High Magnetic Interference Detected"
        tvInterference.textSize = 14f
        tvInterference.setTextColor(Color.WHITE)
        tvInterference.setBackgroundColor(Color.parseColor("#CCFF0000"))
        tvInterference.gravity = Gravity.CENTER
        tvInterference.setPadding(20, 10, 20, 10)
        tvInterference.visibility = View.GONE

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.TOP
        params.topMargin = (80 * resources.displayMetrics.density).toInt()
        tvInterference.layoutParams = params
        rootLayout.addView(tvInterference)
    }

    private fun showLegalDialog() {
        val message = """
            DATA DISCLAIMER:
            This app uses data from OpenStreetMap (OSM). Data may be inaccurate.
            
            SAFETY:
            Never rely solely on this app for emergencies.
            
            LICENSE:
            Data available under the ODbL license.
        """.trimIndent()
        AlertDialog.Builder(this).setTitle("Legal & Safety").setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(p0: LocationResult) {
                    p0.lastLocation?.let { loc ->
                        currentLocation = loc
                        lastFixTime = System.currentTimeMillis()

                        val speed = loc.speed
                        if (speed > SPEED_THRESHOLD_MPS && loc.hasBearing()) {
                            updateArrowUI(loc, loc.bearing)
                            startDrivingAnimation()
                            tvAccuracy.text = "GPS Heading"
                            tvAccuracy.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#32CD32"))
                            tvInterference.visibility = View.GONE
                        } else {
                            stopDrivingAnimation()
                        }

                        // Search Triggers
                        if (!initialSearchDone && !isSearching) {
                            initialSearchDone = true
                            lastFetchLocation = loc
                            fetchAmenitiesAggressively(loc.latitude, loc.longitude, currentAmenityName, isSilent = false)
                        } else if (lastFetchLocation != null && !isSearching) {
                            val dist = loc.distanceTo(lastFetchLocation!!)
                            if (dist > REFETCH_DISTANCE_THRESHOLD || (isErrorState && dist > ERROR_RETRY_DISTANCE)) {
                                lastFetchLocation = loc
                                fetchAmenitiesAggressively(loc.latitude, loc.longitude, currentAmenityName, isSilent = true)
                            }
                        }

                        if (foundAmenities.isNotEmpty()) recalculateNearest()
                        if (!isSearching) updateUI()
                    }
                }
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        } catch (e: SecurityException) { e.printStackTrace() }
    }

    private fun recalculateNearest() {
        if (currentLocation == null || foundAmenities.isEmpty()) return
        var minDistance = Float.MAX_VALUE
        var bestTarget: Amenity? = null

        for (item in foundAmenities) {
            val dist = currentLocation!!.distanceTo(item.location)
            if (dist < minDistance) {
                minDistance = dist
                bestTarget = item
            }
        }

        if (bestTarget != destinationAmenity) {
            destinationAmenity = bestTarget
            parseMetadata(bestTarget)
            updateUI()
        }
    }

    private fun parseMetadata(item: Amenity?) {
        if (item?.tags == null) {
            tvMetadata.visibility = View.GONE
            return
        }
        val tags = item.tags
        val infoList = ArrayList<String>()

        var name = tags.optString("name")
        if (tags.has("toilets") && tags.optString("amenity") != "toilets") {
            val building = tags.optString("name", "Building")
            infoList.add("Inside $building")
        } else if (tags.optString("bin") == "yes" || tags.optString("rubbish") == "yes" || tags.optString("waste_basket") == "yes") {
            val attachedTo = when {
                tags.optString("highway") == "bus_stop" -> "Bus Stop"
                tags.optString("amenity") == "bench" -> "Bench"
                else -> tags.optString("name")
            }
            if (attachedTo.isNotEmpty() && attachedTo != "null") infoList.add("At $attachedTo")
            else if (name.isNotEmpty()) infoList.add(name)
        } else if (name.isNotEmpty()) {
            infoList.add(name)
        }

        var access = tags.optString("toilets:access")
        if (access.isEmpty()) access = tags.optString("access")
        if (access.isNotEmpty()) {
            when (access) {
                "customers" -> infoList.add("⚠ Customers Only")
                "permissive", "yes" -> infoList.add("Public Access")
                "private", "no" -> infoList.add("⚠ Private")
                else -> infoList.add("Access: $access")
            }
        }

        var price = tags.optString("charge")
        if (price.isEmpty()) price = tags.optString("toilets:charge")
        if (price.isNotEmpty()) infoList.add("Fee: $price")
        else {
            val fee = tags.optString("fee")
            if (fee == "no") infoList.add("Free")
            else if (fee == "yes") infoList.add("Fee Required")
        }

        when (currentAmenityName) {
            "Recycling Bin" -> if (tags.has("recycling_type")) infoList.add("Type: " + tags.getString("recycling_type").replace("_", " ").capitalize())
            "Water Fountain" -> if (tags.has("drinking_water")) {
                val dw = tags.getString("drinking_water")
                if (dw == "yes") infoList.add("Water: Drinkable") else if (dw == "no") infoList.add("Water: Not Drinkable")
            }
            "Defibrillator (AED)" -> {
                var loc = tags.optString("defibrillator:location")
                if (loc.isEmpty()) loc = tags.optString("location")
                if (loc.isNotEmpty()) infoList.add("Location: $loc")
                if (tags.optString("indoor") == "yes") infoList.add("(Indoors)")
            }
        }

        if (tags.has("description")) infoList.add(tags.getString("description"))
        if (tags.optString("wheelchair") == "yes") infoList.add("♿ Accessible")

        if (infoList.isNotEmpty()) {
            tvMetadata.text = infoList.joinToString("\n")
            tvMetadata.visibility = View.VISIBLE
        } else {
            tvMetadata.visibility = View.GONE
        }
    }

    private fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magneticSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopDrivingAnimation()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
            if ((currentLocation?.speed ?: 0f) <= SPEED_THRESHOLD_MPS) {
                if (magnitude > 75 || magnitude < 20) tvInterference.visibility = View.VISIBLE
                else tvInterference.visibility = View.GONE
            }
            lastMagAccuracy = event.accuracy
        }
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val currentSpeed = currentLocation?.speed ?: 0f
            if (currentSpeed > SPEED_THRESHOLD_MPS) return
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val axisAdjustedMatrix = FloatArray(9)
            val displayRotation = windowManager.defaultDisplay.rotation
            var axisX = SensorManager.AXIS_X
            var axisY = SensorManager.AXIS_Y
            when (displayRotation) {
                android.view.Surface.ROTATION_90 -> { axisX = SensorManager.AXIS_Y; axisY = SensorManager.AXIS_MINUS_X }
                android.view.Surface.ROTATION_180 -> { axisX = SensorManager.AXIS_MINUS_X; axisY = SensorManager.AXIS_MINUS_Y }
                android.view.Surface.ROTATION_270 -> { axisX = SensorManager.AXIS_MINUS_Y; axisY = SensorManager.AXIS_X }
            }
            if (SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, axisAdjustedMatrix)) {
                SensorManager.getOrientation(axisAdjustedMatrix, orientationAngles)
            } else SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuth = (Math.toDegrees(orientationAngles[0].toDouble()) + 360).toFloat() % 360
            updateArrowWithHeading(azimuth)

            var statusText = "Compass: Good"
            var statusColor = Color.parseColor("#32CD32")
            if (event.values.size > 4 && event.values[4] != -1f) {
                val accuracyRad = event.values[4]
                if (accuracyRad < 0.35) { statusText = "Compass: Good"; statusColor = Color.parseColor("#32CD32") }
                else if (accuracyRad < 0.8) { statusText = "Compass: Fair"; statusColor = Color.parseColor("#FFD700") }
                else { statusText = "Compass: Poor"; statusColor = Color.RED }
            } else {
                when (lastMagAccuracy) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> { statusText = "Compass: Good"; statusColor = Color.parseColor("#32CD32") }
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> { statusText = "Compass: Fair"; statusColor = Color.parseColor("#FFD700") }
                    else -> { statusText = "Compass: Poor"; statusColor = Color.RED }
                }
            }
            tvAccuracy.text = statusText
            tvAccuracy.backgroundTintList = ColorStateList.valueOf(statusColor)
        }
    }

    private fun updateArrowWithHeading(userHeading: Float) {
        currentLocation?.let { updateArrowUI(it, userHeading) }
    }

    private fun updateArrowUI(userLoc: Location, userHeading: Float) {
        if (destinationAmenity != null) {
            setArrowActive(true)
            val bearingToTarget = userLoc.bearingTo(destinationAmenity!!.location)
            val normalizedBearing = (bearingToTarget + 360) % 360
            val targetRot = (normalizedBearing - userHeading + 360) % 360
            var diff = targetRot - currentArrowRotation
            while (diff < -180) diff += 360
            while (diff > 180) diff -= 360
            currentArrowRotation += diff * 0.15f
            ivArrow.rotation = currentArrowRotation
        } else setArrowActive(false)
    }

    private fun startDrivingAnimation() {
        if (driveAnimJob?.isActive == true) return
        driveAnimJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val loc = currentLocation
                if (loc != null && loc.speed > SPEED_THRESHOLD_MPS && loc.hasBearing() && lastFixTime > 0L) {
                    val currentTime = System.currentTimeMillis()
                    val timeDelta = currentTime - lastFixTime
                    if (timeDelta < 2000) {
                        val predictedLoc = projectLocation(loc, loc.speed, loc.bearing, timeDelta)
                        updateArrowUI(predictedLoc, loc.bearing)
                    }
                }
                delay(33)
            }
        }
    }

    private fun stopDrivingAnimation() {
        driveAnimJob?.cancel()
        driveAnimJob = null
    }

    private fun projectLocation(startLoc: Location, speed: Float, bearing: Float, timeDiff: Long): Location {
        val seconds = timeDiff / 1000.0
        val dist = speed * seconds
        val earthRadius = 6371000.0
        val angularDistance = dist / earthRadius
        val bearingRad = Math.toRadians(bearing.toDouble())
        val latRad = Math.toRadians(startLoc.latitude)
        val lonRad = Math.toRadians(startLoc.longitude)
        val newLatRad = kotlin.math.asin(kotlin.math.sin(latRad) * kotlin.math.cos(angularDistance) +
                kotlin.math.cos(latRad) * kotlin.math.sin(angularDistance) * kotlin.math.cos(bearingRad))
        val newLonRad = lonRad + kotlin.math.atan2(kotlin.math.sin(bearingRad) * kotlin.math.sin(angularDistance) * kotlin.math.cos(latRad),
            kotlin.math.cos(angularDistance) - kotlin.math.sin(latRad) * kotlin.math.sin(newLatRad))
        val newLoc = Location("predicted")
        newLoc.latitude = Math.toDegrees(newLatRad)
        newLoc.longitude = Math.toDegrees(newLonRad)
        return newLoc
    }

    private fun showCalibrationDialog() {
        AlertDialog.Builder(this).setTitle("Compass Status").setMessage("To calibrate:\nWave phone in a Figure-8 motion.").setPositiveButton("OK", null).show()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startSearchingAnimation() {
        if (isSearching) return
        isSearching = true
        loadingSpinner.visibility = View.VISIBLE
        tvHint.text = "Please wait..."
        setArrowActive(false)
        searchJob?.cancel()
        searchJob = CoroutineScope(Dispatchers.Main).launch {
            tvDistance.textSize = 30f
            var dots = ""
            while (isActive) {
                tvDistance.text = "Searching$dots"
                dots += "."
                if (dots.length > 3) dots = ""
                delay(250)
            }
        }
    }

    private fun stopSearchingAnimation() {
        isSearching = false
        loadingSpinner.visibility = View.GONE
        searchJob?.cancel()
        tvHint.text = "(Tap to change units)"
    }

    private fun updateUI() {
        if (isSearching) return
        if (isErrorState) {
            tvDistance.textSize = 24f
            tvDistance.text = lastFriendlyError
            tvHint.text = "(Tap to retry)"
            setArrowActive(false)
            return
        }
        if (currentLocation != null && destinationAmenity != null) {
            tvDistance.textSize = 45f
            val dist = currentLocation!!.distanceTo(destinationAmenity!!.location)
            if (useMetric) {
                if (dist >= 1000) tvDistance.text = String.format("%.1f km", dist / 1000)
                else tvDistance.text = "${dist.toInt()} m"
            } else {
                val feet = dist * 3.28084
                if (feet >= 1000) tvDistance.text = String.format("%.2f mi", feet / 5280)
                else tvDistance.text = "${feet.toInt()} ft"
            }
            tvMapButton.visibility = View.VISIBLE
            setArrowActive(true)
        } else {
            tvMapButton.visibility = View.GONE
            tvMetadata.visibility = View.GONE
            setArrowActive(false)
            if (foundAmenities.isEmpty() && initialSearchDone) {
                tvDistance.textSize = 24f
                if (TagRepository.mapping.containsKey(currentAmenityName)) {
                    tvDistance.text = "None found within ${searchRadiusMeters/1000}km"
                } else {
                    tvDistance.text = "No '$currentAmenityName' found"
                }
                tvHint.text = "(Tap to retry)"
            }
        }
    }

    // --- UPDATED QUERY GENERATOR (USES searchRadiusMeters) ---
    private fun getQueryString(type: String, lat: Double, lon: Double): String {
        val radius = searchRadiusMeters // USES VARIABLE
        val bbox = String.format(Locale.US, "(around:$radius, %f, %f)", lat, lon)
        val header = "[out:json];"

        if (type == "Public Toilet") {
            return """$header(node["amenity"="toilets"]$bbox;way["amenity"="toilets"]$bbox;node["toilets"~"yes|designated|public"]$bbox;way["toilets"~"yes|designated|public"]$bbox;);out center;"""
        }

        if (type == "Trash Can" || type == "Trash Bin") {
            return """$header(node["amenity"="waste_basket"]$bbox;way["amenity"="waste_basket"]$bbox;node["bin"="yes"]$bbox;way["bin"="yes"]$bbox;node["rubbish"="yes"]$bbox;way["rubbish"="yes"]$bbox;node["amenity"="waste_disposal"]$bbox;);out center;"""
        }

        val mappedTag = TagRepository.mapping[type]
        if (mappedTag != null) {
            val key = mappedTag.substringBefore("=")
            val value = mappedTag.substringAfter("=")
            return """$header(node["$key"="$value"]$bbox;way["$key"="$value"]$bbox;);out center;"""
        }

        if (type.contains("=")) {
            val key = type.substringBefore("=")
            val value = type.substringAfter("=")
            return """$header(node["$key"="$value"]$bbox;way["$key"="$value"]$bbox;);out center;"""
        }

        val rawInput = type
        val snakeCase = type.lowercase().replace(" ", "_")
        val fallbackKeys = listOf("amenity", "shop", "leisure", "tourism", "natural", "historic", "highway", "emergency", "man_made", "craft", "office", "sport", "building")
        val queryParts = StringBuilder()
        queryParts.append("""node["name"~"$rawInput",i]$bbox;""")
        queryParts.append("""way["name"~"$rawInput",i]$bbox;""")
        for (key in fallbackKeys) {
            queryParts.append("""node["$key"="$snakeCase"]$bbox;""")
            queryParts.append("""way["$key"="$snakeCase"]$bbox;""")
        }
        return """$header($queryParts);out center;"""
    }

    private fun fetchAmenitiesAggressively(lat: Double, lon: Double, amenityType: String, isSilent: Boolean) {
        if (!isSilent) {
            isSearching = true
            startSearchingAnimation()
            isErrorState = false
        }
        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            val maxLoops = 3
            var currentLoop = 0

            while (currentLoop < maxLoops && !success) {
                var serverIndex = 0
                while (serverIndex < servers.size && !success) {
                    try {
                        val query = getQueryString(amenityType, lat, lon)
                        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                        val currentServer = servers[serverIndex]
                        val url = "$currentServer?data=$encodedQuery"

                        val request = Request.Builder().url(url).header("User-Agent", "TrashCompass/2.1").build()
                        val response = httpClient.newCall(request).execute()

                        if (response.isSuccessful) {
                            val jsonString = response.body?.string()
                            val tempFoundList = ArrayList<Amenity>()
                            if (jsonString != null) {
                                val jsonObj = JSONObject(jsonString)
                                if (jsonObj.has("elements")) {
                                    val elements = jsonObj.getJSONArray("elements")
                                    for (i in 0 until elements.length()) {
                                        val item = elements.getJSONObject(i)
                                        var itemLat = 0.0
                                        var itemLon = 0.0
                                        val tags = if (item.has("tags")) item.getJSONObject("tags") else null

                                        if (item.has("lat")) {
                                            itemLat = item.getDouble("lat")
                                            itemLon = item.getDouble("lon")
                                        } else if (item.has("center")) {
                                            val center = item.getJSONObject("center")
                                            itemLat = center.getDouble("lat")
                                            itemLon = center.getDouble("lon")
                                        } else continue

                                        val locObj = Location("osm")
                                        locObj.latitude = itemLat
                                        locObj.longitude = itemLon
                                        tempFoundList.add(Amenity(locObj, tags))
                                    }
                                }
                            }
                            success = true
                            withContext(Dispatchers.Main) {
                                if (!isSilent) stopSearchingAnimation()
                                isErrorState = false
                                foundAmenities = tempFoundList
                                if (foundAmenities.isEmpty()) destinationAmenity = null
                                else recalculateNearest()
                                updateUI()
                            }
                        } else {
                            serverIndex++
                        }
                    } catch (e: Exception) {
                        serverIndex++
                    }
                }
                if (!success) {
                    currentLoop++
                    delay(1000)
                }
            }
            if (!success && !isSilent) {
                withContext(Dispatchers.Main) {
                    stopSearchingAnimation()
                    isErrorState = true
                    lastFriendlyError = "Connection Failed"
                    updateUI()
                }
            }
        }
    }
}

object TagRepository {
    val mapping = mapOf(
        "Trash Can" to "amenity=waste_basket",
        "Public Toilet" to "amenity=toilets",
        "Defibrillator (AED)" to "emergency=defibrillator",
        "Water Fountain" to "amenity=drinking_water",
        "Recycling Bin" to "amenity=recycling",
        "ATM" to "amenity=atm",
        "Post Box" to "amenity=post_box",
        "Bench" to "amenity=bench",
        "Surveillance Camera" to "man_made=surveillance",
        "Cafe" to "amenity=cafe",
        "Restaurant" to "amenity=restaurant",
        "Fast Food" to "amenity=fast_food",
        "Bar" to "amenity=bar",
        "Pub" to "amenity=pub",
        "Biergarten" to "amenity=biergarten",
        "Food Court" to "amenity=food_court",
        "Ice Cream" to "amenity=ice_cream",
        "Vending Machine" to "amenity=vending_machine",
        "Supermarket" to "shop=supermarket",
        "Convenience Store" to "shop=convenience",
        "Bakery" to "shop=bakery",
        "Department Store" to "shop=department_store",
        "General Store" to "shop=general",
        "Mall" to "shop=mall",
        "Kiosk" to "shop=kiosk",
        "Alcohol Shop" to "shop=alcohol",
        "Beverage Shop" to "shop=beverages",
        "Butcher" to "shop=butcher",
        "Cheese Shop" to "shop=cheese",
        "Chocolate Shop" to "shop=chocolate",
        "Coffee Shop" to "shop=coffee",
        "Confectionery" to "shop=confectionery",
        "Dairy" to "shop=dairy",
        "Deli" to "shop=deli",
        "Farm Shop" to "shop=farm",
        "Greengrocer" to "shop=greengrocer",
        "Health Food" to "shop=health_food",
        "Ice Cream Shop" to "shop=ice_cream",
        "Pasta Shop" to "shop=pasta",
        "Pastry Shop" to "shop=pastry",
        "Seafood Shop" to "shop=seafood",
        "Spices Shop" to "shop=spices",
        "Tea Shop" to "shop=tea",
        "Water Shop" to "shop=water",
        "Wine Shop" to "shop=wine",
        "Antiques" to "shop=antiques",
        "Bag Shop" to "shop=bag",
        "Baby Goods" to "shop=baby_goods",
        "Beauty Shop" to "shop=beauty",
        "Bedding Shop" to "shop=bed",
        "Book Store" to "shop=books",
        "Boutique" to "shop=boutique",
        "Camera Shop" to "shop=camera",
        "Carpet Shop" to "shop=carpet",
        "Charity Shop" to "shop=charity",
        "Chemist" to "shop=chemist",
        "Clothes Shop" to "shop=clothes",
        "Computer Shop" to "shop=computer",
        "Cosmetics" to "shop=cosmetics",
        "Craft Shop" to "shop=craft",
        "Curtain Shop" to "shop=curtain",
        "Drugstore" to "shop=drugstore",
        "Electronics Store" to "shop=electronics",
        "Fabric Shop" to "shop=fabric",
        "Florist" to "shop=florist",
        "Furniture Store" to "shop=furniture",
        "Garden Centre" to "shop=garden_centre",
        "Gift Shop" to "shop=gift",
        "Hardware Store" to "shop=hardware",
        "Hearing Aids" to "shop=hearing_aids",
        "Hifi Shop" to "shop=hifi",
        "Interior Decoration" to "shop=interior_decoration",
        "Jewelry" to "shop=jewelry",
        "Kitchen Shop" to "shop=kitchen",
        "Lighting Shop" to "shop=lighting",
        "Mobile Phone Shop" to "shop=mobile_phone",
        "Music Shop" to "shop=music",
        "Musical Instrument" to "shop=musical_instrument",
        "Newsagent" to "shop=newsagent",
        "Optician" to "shop=optician",
        "Paint Shop" to "shop=paint",
        "Perfume Shop" to "shop=perfumery",
        "Pet Shop" to "shop=pet",
        "Photo Shop" to "shop=photo",
        "Second Hand" to "shop=second_hand",
        "Shoe Shop" to "shop=shoes",
        "Sports Shop" to "shop=sports",
        "Stationery" to "shop=stationery",
        "Tailor" to "shop=tailor",
        "Tattoo Parlour" to "shop=tattoo",
        "Ticket Shop" to "shop=ticket",
        "Tobacco Shop" to "shop=tobacco",
        "Toy Shop" to "shop=toys",
        "Video Games" to "shop=video_games",
        "Watches" to "shop=watches",
        "Weapons" to "shop=weapons",
        "Wholesale" to "shop=wholesale",
        "Bicycle Shop" to "shop=bicycle",
        "Car Parts" to "shop=car_parts",
        "Car Repair" to "shop=car_repair",
        "Car Shop" to "shop=car",
        "Fuel Station" to "amenity=fuel",
        "Tyre Shop" to "shop=tyres",
        "Laundry" to "shop=laundry",
        "Dry Cleaning" to "shop=dry_cleaning",
        "Funeral Directors" to "shop=funeral_directors",
        "Hairdresser" to "shop=hairdresser",
        "Massage" to "shop=massage",
        "Medical Supply" to "shop=medical_supply",
        "Money Lender" to "shop=money_lender",
        "Pawnbroker" to "shop=pawnbroker",
        "Travel Agency" to "shop=travel_agency",
        "Vacant Shop" to "shop=vacant",
        "Ambulance Station" to "emergency=ambulance_station",
        "Defibrillator" to "emergency=defibrillator",
        "Fire Hydrant" to "emergency=fire_hydrant",
        "Fire Station" to "amenity=fire_station",
        "Emergency Phone" to "emergency=phone",
        "Police" to "amenity=police",
        "Siren" to "emergency=siren",
        "Hospital" to "amenity=hospital",
        "Lifeguard" to "emergency=lifeguard",
        "Assembly Point" to "emergency=assembly_point",
        "Clinic" to "amenity=clinic",
        "Dentist" to "amenity=dentist",
        "Doctors" to "amenity=doctors",
        "Pharmacy" to "amenity=pharmacy",
        "Veterinary" to "amenity=veterinary",
        "Nursing Home" to "amenity=nursing_home",
        "Airport" to "aeroway=aerodrome",
        "Helipad" to "aeroway=helipad",
        "Bus Station" to "amenity=bus_station",
        "Bus Stop" to "highway=bus_stop",
        "Car Rental" to "amenity=car_rental",
        "Car Wash" to "amenity=car_wash",
        "EV Charging" to "amenity=charging_station",
        "Ferry Terminal" to "amenity=ferry_terminal",
        "Parking" to "amenity=parking",
        "Bicycle Parking" to "amenity=bicycle_parking",
        "Taxi Stand" to "amenity=taxi",
        "Train Station" to "railway=station",
        "Tram Stop" to "railway=tram_stop",
        "Subway Entrance" to "railway=subway_entrance",
        "Platform" to "railway=platform",
        "Hotel" to "tourism=hotel",
        "Motel" to "tourism=motel",
        "Hostel" to "tourism=hostel",
        "Guest House" to "tourism=guest_house",
        "Camp Site" to "tourism=camp_site",
        "Caravan Site" to "tourism=caravan_site",
        "Chalet" to "tourism=chalet",
        "Museum" to "tourism=museum",
        "Art Gallery" to "tourism=gallery",
        "Attraction" to "tourism=attraction",
        "Information" to "tourism=information",
        "Picnic Site" to "tourism=picnic_site",
        "Viewpoint" to "tourism=viewpoint",
        "Zoo" to "tourism=zoo",
        "Theme Park" to "tourism=theme_park",
        "Water Park" to "leisure=water_park",
        "Casino" to "amenity=casino",
        "Cinema" to "amenity=cinema",
        "Nightclub" to "amenity=nightclub",
        "Theatre" to "amenity=theatre",
        "Library" to "amenity=library",
        "Park" to "leisure=park",
        "Playground" to "leisure=playground",
        "Golf Course" to "leisure=golf_course",
        "Slipway" to "leisure=slipway",
        "Swimming Pool" to "leisure=swimming_pool",
        "Stadium" to "leisure=stadium",
        "Pitch" to "leisure=pitch",
        "Dog Park" to "leisure=dog_park",
        "Marina" to "leisure=marina",
        "Fishing" to "leisure=fishing",
        "Sauna" to "leisure=sauna",
        "Town Hall" to "amenity=townhall",
        "Courthouse" to "amenity=courthouse",
        "Prison" to "amenity=prison",
        "Embassy" to "amenity=embassy",
        "Post Office" to "amenity=post_office",
        "Community Centre" to "amenity=community_centre",
        "Social Facility" to "amenity=social_facility",
        "Marketplace" to "amenity=marketplace",
        "Crematorium" to "amenity=crematorium",
        "Graveyard" to "amenity=graveyard",
        "Cemetery" to "landuse=cemetery",
        "College" to "amenity=college",
        "Kindergarten" to "amenity=kindergarten",
        "School" to "amenity=school",
        "University" to "amenity=university",
        "Driving School" to "amenity=driving_school",
        "Archaeological Site" to "historic=archaeological_site",
        "Castle" to "historic=castle",
        "Church" to "historic=church",
        "City Gate" to "historic=city_gate",
        "Fort" to "historic=fort",
        "Manor" to "historic=manor",
        "Memorial" to "historic=memorial",
        "Monument" to "historic=monument",
        "Ruins" to "historic=ruins",
        "Battlefield" to "historic=battlefield",
        "Shipwreck" to "historic=wreck",
        "Wayside Cross" to "historic=wayside_cross",
        "Wayside Shrine" to "historic=wayside_shrine",
        "Cannon" to "historic=cannon",
        "Tree" to "natural=tree",
        "Peak" to "natural=peak",
        "Volcano" to "natural=volcano",
        "Cave Entrance" to "natural=cave_entrance",
        "Spring" to "natural=spring",
        "Beach" to "natural=beach",
        "Glacier" to "natural=glacier",
        "Cliff" to "natural=cliff",
        "Bay" to "natural=bay",
        "Wetland" to "natural=wetland",
        "Wood" to "natural=wood",
        "Scrub" to "natural=scrub",
        "Heath" to "natural=heath",
        "Grassland" to "natural=grassland",
        "Sand" to "natural=sand",
        "Rock" to "natural=bare_rock",
        "Geyser" to "natural=geyser",
        "Hot Spring" to "natural=hot_spring",
        "Tower" to "man_made=tower",
        "Water Tower" to "man_made=water_tower",
        "Lighthouse" to "man_made=lighthouse",
        "Windmill" to "man_made=windmill",
        "Crane" to "man_made=crane",
        "Chimney" to "man_made=chimney",
        "Communications Tower" to "man_made=communications_tower",
        "Mast" to "man_made=mast",
        "Flagpole" to "man_made=flagpole",
        "Silo" to "man_made=silo",
        "Storage Tank" to "man_made=storage_tank",
        "Telescope" to "man_made=telescope",
        "Water Well" to "man_made=water_well",
        "Pipeline" to "man_made=pipeline",
        "Pier" to "man_made=pier",
        "Breakwater" to "man_made=breakwater",
        "Groyne" to "man_made=groyne",
        "Dyke" to "man_made=dyke",
        "Adit (Mine Entrance)" to "man_made=adit",
        "Mineshaft" to "man_made=mineshaft",
        "Kiln" to "man_made=kiln",
        "Maypole" to "man_made=maypole",
        "Obelisk" to "man_made=obelisk",
        "Street Cabinet" to "man_made=street_cabinet",
        "Survey Point" to "man_made=survey_point",
        "Gate" to "barrier=gate",
        "Bollard" to "barrier=bollard",
        "Border Control" to "barrier=border_control",
        "Cattle Grid" to "barrier=cattle_grid",
        "Toll Booth" to "barrier=toll_booth",
        "Stile" to "barrier=stile",
        "Kissing Gate" to "barrier=kissing_gate",
        "Generator" to "power=generator",
        "Power Line" to "power=line",
        "Power Pole" to "power=pole",
        "Power Tower" to "power=tower",
        "Transformer" to "power=transformer",
        "Substation" to "power=substation",
        "Nuclear Power Plant" to "power=plant",
        "Water Fall" to "waterway=waterfall",
        "Lock Gate" to "waterway=lock_gate",
        "Weir" to "waterway=weir",
        "Dam" to "waterway=dam",
        "Cable Car" to "aerialway=cable_car",
        "Gondola" to "aerialway=gondola",
        "Chair Lift" to "aerialway=chair_lift",
        "Drag Lift" to "aerialway=drag_lift",
        "Zip Line" to "aerialway=zip_line",
        "Office" to "office=yes",
        "Estate Agent" to "office=estate_agent",
        "Insurance" to "office=insurance",
        "Lawyer" to "office=lawyer",
        "IT Office" to "office=it",
        "Government Office" to "office=government",
        "Employment Agency" to "office=employment_agency",
        "NGO" to "office=ngo",
        "Coworking Space" to "office=coworking",
        "Shoemaker" to "craft=shoemaker",
        "Carpenter" to "craft=carpenter",
        "Electrician" to "craft=electrician",
        "Plumber" to "craft=plumber",
        "Photographer" to "craft=photographer",
        "Blacksmith" to "craft=blacksmith",
        "Brewery" to "craft=brewery",
        "Distillery" to "craft=distillery",
        "Winery" to "craft=winery",
        "Sawmill" to "craft=sawmill",
        "Stonemason" to "craft=stonemason",
        "Tailor (Craft)" to "craft=tailor",
        "Clockmaker" to "craft=clockmaker",
        "Key Cutter" to "craft=key_cutter",
        "Locksmith" to "craft=locksmith",
        "Traffic Signals" to "highway=traffic_signals",
        "Crosswalk" to "highway=crossing",
        "Street Lamp" to "highway=street_lamp",
        "Stop Sign" to "highway=stop",
        "Speed Camera" to "highway=speed_camera",
        "Milestone" to "highway=milestone",
        "Bunker" to "military=bunker",
        "Barracks" to "military=barracks",
        "Place of Worship" to "amenity=place_of_worship",
        "Cathedral" to "building=cathedral",
        "Chapel" to "building=chapel",
        "Mosque" to "building=mosque",
        "Synagogue" to "building=synagogue",
        "Temple" to "building=temple"
    )
}