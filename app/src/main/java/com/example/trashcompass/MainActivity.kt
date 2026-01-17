package com.example.trashcompass

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

// UPDATED: Added 'id' to track unique objects and prevent flickering
data class Amenity(
    val id: Long,
    val location: Location,
    val tags: JSONObject?
)

class MainActivity : AppCompatActivity(), SensorEventListener {

    // --- MAPILLARY TOKEN ---
    // Get one free at https://www.mapillary.com/dashboard/developers
    // If blank, mapillary tags will be ignored, but regular images will still work.
    private val MAPILLARY_ACCESS_TOKEN = "MLY|26782956327960665|7ea4bb0428dc48fe0089e13b8f2b0617"

    // UI
    private lateinit var tvTitle: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvMetadata: TextView
    private lateinit var tvHint: TextView
    private lateinit var ivArrow: ImageView
    private lateinit var ivSettings: ImageView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvMapButton: TextView
    private lateinit var loadingSpinner: ProgressBar

    // Image Views
    private lateinit var ivAmenityImage: ImageView
    private lateinit var ivFullScreen: ImageView
    private lateinit var viewDimmer: View

    // Preferences
    private lateinit var prefs: SharedPreferences
    private var searchRadiusMeters = 2000
    private var useMetric = true

    // Sensors
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var magneticSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var currentArrowRotation = 0f
    private var lastMagAccuracy = -1

    // State
    private var currentLocation: Location? = null
    private var destinationAmenity: Amenity? = null
    private var foundAmenities = ArrayList<Amenity>()

    // Logic
    private var lastFetchLocation: Location? = null
    private val REFETCH_DISTANCE_THRESHOLD = 150f
    private val ERROR_RETRY_DISTANCE = 100f
    private var initialSearchDone = false

    // Jobs
    private var driveAnimJob: Job? = null
    private var searchJob: Job? = null
    private var imageLoadingJob: Job? = null // To cancel old image loads

    private var lastFixTime: Long = 0L
    private val SPEED_THRESHOLD_MPS = 6.7f

    private var currentAmenityName = "Trash Can"
    private var isSearching = false
    private var isErrorState = false
    private var lastFriendlyError = ""

    private val hardcodedOptions = listOf(
        "Trash Can", "Public Toilet", "Defibrillator (AED)",
        "Water Fountain", "Recycling Bin", "ATM", "Post Box", "Bench"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
        ivSettings = findViewById(R.id.ivSettings)
        tvAccuracy = findViewById(R.id.tvAccuracy)
        tvMapButton = findViewById(R.id.tvMapButton)
        loadingSpinner = findViewById(R.id.loadingSpinner)

        ivAmenityImage = findViewById(R.id.ivAmenityImage)
        ivFullScreen = findViewById(R.id.ivFullScreen)
        viewDimmer = findViewById(R.id.viewDimmer)

        val tvLegal = findViewById<TextView>(R.id.tvLegal)

        tvLegal.setOnClickListener { showLegalDialog() }
        ivSettings.setOnClickListener { showSettingsDialog() }

        // Setup Fullscreen Close Listeners
        val closeFullscreen = View.OnClickListener {
            ivFullScreen.visibility = View.GONE
            viewDimmer.visibility = View.GONE
        }
        ivFullScreen.setOnClickListener(closeFullscreen)
        viewDimmer.setOnClickListener(closeFullscreen)

        // Handle Back Button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ivFullScreen.visibility == View.VISIBLE) {
                    closeFullscreen.onClick(null)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        tvMetadata.gravity = Gravity.CENTER
        val padding = (20 * resources.displayMetrics.density).toInt()
        tvMetadata.setPadding(padding, 0, padding, 0)

        setArrowActive(false)

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
                if (currentLocation != null) {
                    fetchAmenitiesAggressively(currentLocation!!.latitude, currentLocation!!.longitude, currentAmenityName, isSilent = false)
                }
            } else {
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
            findViewById<View>(R.id.viewRing).alpha = 1.0f
        } else {
            ivArrow.alpha = 0.3f
            ivArrow.setColorFilter(Color.parseColor("#555555"), PorterDuff.Mode.SRC_IN)
            findViewById<View>(R.id.viewRing).alpha = 0.3f
        }
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Settings")
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        layout.setPadding(padding, padding, padding, padding)

        val lblRadius = TextView(this)
        lblRadius.text = "Search Radius: ${searchRadiusMeters}m"
        lblRadius.textSize = 16f
        lblRadius.setTextColor(Color.BLACK)
        layout.addView(lblRadius)

        val lblWarning = TextView(this)
        lblWarning.text = "⚠️ Large radius may cause timeouts."
        lblWarning.setTextColor(Color.RED)
        lblWarning.textSize = 12f
        lblWarning.setPadding(0, 10, 0, 10)
        lblWarning.visibility = if (searchRadiusMeters > 3000) View.VISIBLE else View.GONE
        layout.addView(lblWarning)

        val seekBar = SeekBar(this)
        seekBar.max = 95
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
            if (currentLocation != null) setNewSearchTarget(currentAmenityName)
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showCustomSearchDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Search Features")
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)

        val input = AutoCompleteTextView(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.hint = "e.g. bicycle repair"
        input.setTextColor(Color.BLACK)

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
        ivAmenityImage.visibility = View.GONE
        ivFullScreen.visibility = View.GONE
        viewDimmer.visibility = View.GONE
        tvMapButton.visibility = View.GONE
        setArrowActive(false)
        if (currentLocation != null) fetchAmenitiesAggressively(currentLocation!!.latitude, currentLocation!!.longitude, currentAmenityName, isSilent = false)
    }

    private fun showLegalDialog() {
        val message = "DATA DISCLAIMER:\nUses OpenStreetMap data.\n\nSAFETY:\nDo not use for emergencies.\n\nLICENSE:\nODbL License."
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
                            tvAccuracy.setTextColor(Color.parseColor("#32CD32"))
                        } else {
                            stopDrivingAnimation()
                        }

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

        // FIXED: Check ID equality instead of object reference to prevent flickering
        if (bestTarget?.id != destinationAmenity?.id) {
            destinationAmenity = bestTarget
            parseMetadata(bestTarget)
            updateUI()
        }
    }

    private fun parseMetadata(item: Amenity?) {
        // Cancel any pending image loads to prevent "wrong image" race conditions
        imageLoadingJob?.cancel()

        // Reset Image Views
        ivAmenityImage.setImageDrawable(null)
        ivAmenityImage.visibility = View.GONE
        ivAmenityImage.setOnClickListener(null)

        // Close Fullscreen if target changed
        ivFullScreen.visibility = View.GONE
        viewDimmer.visibility = View.GONE

        if (item?.tags == null) {
            tvMetadata.visibility = View.GONE
            return
        }
        val tags = item.tags

        // --- IMPROVED IMAGE LOADING LOGIC ---
        val mapillaryId = tags.optString("mapillary")
        val imageUrl = tags.optString("image")

        var validSource = ""
        var isMapillary = false

        // Only try Mapillary if we have an ID AND a token
        if (mapillaryId.isNotEmpty() && MAPILLARY_ACCESS_TOKEN.isNotEmpty()) {
            validSource = mapillaryId
            isMapillary = true
        } else if (imageUrl.isNotEmpty()) {
            // Fallback to regular image if Mapillary is missing or token is blank
            if (imageUrl.startsWith("http")) {
                validSource = imageUrl
                isMapillary = false
            }
        }

        if (validSource.isNotEmpty()) {
            imageLoadingJob = loadAmenityImage(validSource, isMapillary)
        }
        // ------------------------------------

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

    private fun loadAmenityImage(source: String, isMapillaryId: Boolean): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            try {
                var finalUrl = source

                if (isMapillaryId) {
                    val apiUrl = "https://graph.mapillary.com/$source?fields=thumb_1024_url&access_token=$MAPILLARY_ACCESS_TOKEN"
                    val request = Request.Builder().url(apiUrl).build()
                    val response = httpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "{}")
                        if (json.has("thumb_1024_url")) {
                            finalUrl = json.getString("thumb_1024_url")
                        } else {
                            return@launch
                        }
                    } else {
                        return@launch
                    }
                }

                val imageRequest = Request.Builder().url(finalUrl).build()
                val imageResponse = httpClient.newCall(imageRequest).execute()

                if (imageResponse.isSuccessful) {
                    val inputStream = imageResponse.body?.byteStream()
                    val bitmap = BitmapFactory.decodeStream(inputStream)

                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            ivAmenityImage.setImageBitmap(bitmap)
                            ivAmenityImage.visibility = View.VISIBLE

                            ivAmenityImage.setOnClickListener {
                                ivFullScreen.setImageBitmap(bitmap)
                                ivFullScreen.visibility = View.VISIBLE
                                viewDimmer.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
            } else {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
            }

            val azimuth = (Math.toDegrees(orientationAngles[0].toDouble()) + 360).toFloat() % 360
            updateArrowWithHeading(azimuth)

            var statusText = "Compass: Good"
            var statusColor = Color.parseColor("#32CD32")
            if (event.values.size > 4 && event.values[4] != -1f) {
                val accuracyRad = event.values[4]
                if (accuracyRad < 0.35) {
                    statusText = "Compass: Good"
                    statusColor = Color.parseColor("#32CD32")
                } else if (accuracyRad < 0.8) {
                    statusText = "Compass: Fair"
                    statusColor = Color.parseColor("#FFD700")
                } else {
                    statusText = "Compass: Poor"
                    statusColor = Color.parseColor("#FF4444")
                }
            } else {
                when (lastMagAccuracy) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> { statusText = "Compass: Good"; statusColor = Color.parseColor("#32CD32") }
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> { statusText = "Compass: Fair"; statusColor = Color.parseColor("#FFD700") }
                    else -> { statusText = "Compass: Poor"; statusColor = Color.parseColor("#FF4444") }
                }
            }
            tvAccuracy.text = statusText
            tvAccuracy.setTextColor(statusColor)
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
    }

    private fun updateUI() {
        if (isSearching) return
        if (isErrorState) {
            tvDistance.textSize = 24f
            tvDistance.text = lastFriendlyError
            setArrowActive(false)
            return
        }
        if (currentLocation != null && destinationAmenity != null) {
            tvDistance.textSize = 64f
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
            ivAmenityImage.visibility = View.GONE
            setArrowActive(false)
            if (foundAmenities.isEmpty() && initialSearchDone) {
                tvDistance.textSize = 24f
                val km = searchRadiusMeters / 1000.0
                val msg = if (km >= 1.0) "None within %.1f km".format(km) else "None within ${searchRadiusMeters}m"
                if (TagRepository.mapping.containsKey(currentAmenityName)) {
                    tvDistance.text = msg
                } else {
                    tvDistance.text = "No '$currentAmenityName' found"
                }
            }
        }
    }

    private fun getQueryString(type: String, lat: Double, lon: Double): String {
        val radius = searchRadiusMeters
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

                                        // NEW: Capture OSM ID for stable tracking
                                        val id = item.optLong("id", -1L)

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
                                        tempFoundList.add(Amenity(id, locObj, tags))
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
        "Defibrillator (AED)" to "emergency=defibrillator",
        "Public Toilet" to "amenity=toilets",
        "Water Fountain" to "amenity=drinking_water",
        "Recycling Bin" to "amenity=recycling",
        "Trash Bin" to "amenity=waste_basket",
        // --- SUSTENANCE ---
        "Bar" to "amenity=bar",
        "BBQ / Grill" to "amenity=bbq",
        "Biergarten" to "amenity=biergarten",
        "Cafe" to "amenity=cafe",
        "Drinking Water" to "amenity=drinking_water",
        "Fast Food" to "amenity=fast_food",
        "Food Court" to "amenity=food_court",
        "Ice Cream" to "amenity=ice_cream",
        "Pub" to "amenity=pub",
        "Restaurant" to "amenity=restaurant",
        "Canteen" to "amenity=canteen",
        "Water Point" to "amenity=water_point",

        // --- EDUCATION ---
        "College" to "amenity=college",
        "Driving School" to "amenity=driving_school",
        "Kindergarten" to "amenity=kindergarten",
        "Library" to "amenity=library",
        "Music School" to "amenity=music_school",
        "School" to "amenity=school",
        "Language School" to "amenity=language_school",
        "Toy Library" to "amenity=toy_library",
        "Research Institute" to "amenity=research_institute",
        "Training Centre" to "amenity=training",
        "University" to "amenity=university",

        // --- TRANSPORTATION (ROAD & GENERAL) ---
        "Bicycle Parking" to "amenity=bicycle_parking",
        "Bicycle Rental" to "amenity=bicycle_rental",
        "Bicycle Repair Station" to "amenity=bicycle_repair_station",
        "Boat Rental" to "amenity=boat_rental",
        "Boat Sharing" to "amenity=boat_sharing",
        "Bus Station" to "amenity=bus_station",
        "Car Rental" to "amenity=car_rental",
        "Car Sharing" to "amenity=car_sharing",
        "Car Wash" to "amenity=car_wash",
        "EV Charging Station" to "amenity=charging_station",
        "Ferry Terminal" to "amenity=ferry_terminal",
        "Fuel Station" to "amenity=fuel",
        "Grit Bin" to "amenity=grit_bin",
        "Motorcycle Parking" to "amenity=motorcycle_parking",
        "Parking" to "amenity=parking",
        "Parking Entrance" to "amenity=parking_entrance",
        "Parking Space" to "amenity=parking_space",
        "Taxi Stand" to "amenity=taxi",
        "Ticket Validator" to "amenity=ticket_validator",
        "Weighbridge" to "amenity=weighbridge",

        // --- TRANSPORTATION (RAILWAY) ---
        "Train Station" to "railway=station",
        "Subway Entrance" to "railway=subway_entrance",
        "Tram Stop" to "railway=tram_stop",
        "Railway Halt" to "railway=halt",
        "Railway Platform" to "railway=platform",
        "Level Crossing" to "railway=level_crossing",
        "Railway Buffer Stop" to "railway=buffer_stop",
        "Railway Signal" to "railway=signal",
        "Railway Switch" to "railway=switch",
        "Railway Turntable" to "railway=turntable",
        "Subway Station" to "station=subway",
        "Light Rail Station" to "station=light_rail",
        "Monorail Station" to "station=monorail",
        "Funicular Station" to "railway=station", // often tagged same as general station

        // --- TRANSPORTATION (AIR) ---
        "Airport" to "aeroway=aerodrome",
        "Helipad" to "aeroway=helipad",
        "Airport Terminal" to "aeroway=terminal",
        "Airport Gate" to "aeroway=gate",
        "Hangar" to "aeroway=hangar",
        "Windsock" to "aeroway=windsock",
        "Runway" to "aeroway=runway",
        "Taxiway" to "aeroway=taxiway",
        "Helicopter Parking" to "aeroway=helipad",

        // --- TRANSPORTATION (WATER) ---
        "Dock" to "waterway=dock",
        "Boatyard" to "waterway=boatyard",
        "Lock Gate" to "waterway=lock_gate",
        "Weir" to "waterway=weir",
        "Dam" to "waterway=dam",
        "Pier" to "man_made=pier",
        "Marina" to "leisure=marina",
        "Slipway" to "leisure=slipway",
        "Lighthouse" to "man_made=lighthouse",
        "Beacon" to "man_made=beacon",

        // --- FINANCIAL ---
        "ATM" to "amenity=atm",
        "Bank" to "amenity=bank",
        "Bureau de Change" to "amenity=bureau_de_change",
        "Money Transfer" to "amenity=money_transfer",
        "Payment Terminal" to "amenity=payment_terminal",

        // --- HEALTHCARE ---
        "Baby Hatch" to "amenity=baby_hatch",
        "Clinic" to "amenity=clinic",
        "Dentist" to "amenity=dentist",
        "Doctor" to "amenity=doctors",
        "Hospital" to "amenity=hospital",
        "Nursing Home" to "amenity=nursing_home",
        "Pharmacy" to "amenity=pharmacy",
        "Social Facility" to "amenity=social_facility",
        "Veterinary" to "amenity=veterinary",
        "Blood Donation" to "amenity=blood_donation",

        // --- ENTERTAINMENT, ARTS & CULTURE ---
        "Arts Centre" to "amenity=arts_centre",
        "Brothel" to "amenity=brothel",
        "Casino" to "amenity=casino",
        "Cinema" to "amenity=cinema",
        "Community Centre" to "amenity=community_centre",
        "Conference Centre" to "amenity=conference_centre",
        "Events Venue" to "amenity=events_venue",
        "Exhibition Centre" to "amenity=exhibition_centre",
        "Fountain" to "amenity=fountain",
        "Gambling" to "amenity=gambling",
        "Love Hotel" to "amenity=love_hotel",
        "Music Venue" to "amenity=music_venue",
        "Nightclub" to "amenity=nightclub",
        "Planetarium" to "amenity=planetarium",
        "Public Bookcase" to "amenity=public_bookcase",
        "Social Centre" to "amenity=social_centre",
        "Stage" to "amenity=stage",
        "Stripclub" to "amenity=stripclub",
        "Studio" to "amenity=studio",
        "Swingerclub" to "amenity=swingerclub",
        "Theatre" to "amenity=theatre",

        // --- PUBLIC SERVICE ---
        "Courthouse" to "amenity=courthouse",
        "Embassy" to "amenity=embassy",
        "Fire Station" to "amenity=fire_station",
        "Police" to "amenity=police",
        "Post Box" to "amenity=post_box",
        "Post Depot" to "amenity=post_depot",
        "Post Office" to "amenity=post_office",
        "Prison" to "amenity=prison",
        "Ranger Station" to "amenity=ranger_station",
        "Town Hall" to "amenity=townhall",
        "Register Office" to "office=register",

        // --- FACILITIES ---
        "Bench" to "amenity=bench",
        "Check-in" to "amenity=check_in",
        "Clock" to "amenity=clock",
        "Dog Toilet" to "amenity=dog_toilet",
        "Dressing Room" to "amenity=dressing_room",
        "Give Box" to "amenity=give_box",
        "Internet Cafe" to "amenity=internet_cafe",
        "Kitchen" to "amenity=kitchen",
        "Kneipp Water Cure" to "amenity=kneipp_water_cure",
        "Lounger" to "amenity=lounger",
        "Marketplace" to "amenity=marketplace",
        "Monastery" to "amenity=monastery",
        "Photo Booth" to "amenity=photo_booth",
        "Place of Mourning" to "amenity=place_of_mourning",
        "Place of Worship" to "amenity=place_of_worship",
        "Public Bath" to "amenity=public_bath",
        "Refugee Site" to "amenity=refugee_site",
        "Shelter" to "amenity=shelter",
        "Shower" to "amenity=shower",
        "Telephone" to "amenity=telephone",
        "Toilets" to "amenity=toilets",
        "Vending Machine" to "amenity=vending_machine",
        "Watering Place" to "amenity=watering_place",
        "Luggage Locker" to "amenity=locker",
        "Parcel Locker" to "amenity=parcel_locker",

        // --- WASTE MANAGEMENT ---
        "Recycling" to "amenity=recycling",
        "Sanitary Dump Station" to "amenity=sanitary_dump_station",
        "Trash Can" to "amenity=waste_basket",
        "Waste Disposal" to "amenity=waste_disposal",
        "Waste Transfer Station" to "amenity=waste_transfer_station",

        // --- OTHER AMENITIES ---
        "Animal Boarding" to "amenity=animal_boarding",
        "Animal Breeding" to "amenity=animal_breeding",
        "Animal Shelter" to "amenity=animal_shelter",
        "Animal Training" to "amenity=animal_training",
        "Baking Oven" to "amenity=baking_oven",
        "Childcare" to "amenity=childcare",
        "Crematorium" to "amenity=crematorium",
        "Dive Centre" to "amenity=dive_centre",
        "Funeral Hall" to "amenity=funeral_hall",
        "Graveyard" to "amenity=graveyard",
        "Hunting Stand" to "amenity=hunting_stand",
        "Letter Box" to "amenity=letter_box",
        "Loading Dock" to "amenity=loading_dock",
        "Mortuary" to "amenity=mortuary",
        "Public Building" to "amenity=public_building", // discourage use, but exists

        // --- SHOP: FOOD & BEVERAGES ---
        "Alcohol Shop" to "shop=alcohol",
        "Bakery" to "shop=bakery",
        "Beverage Shop" to "shop=beverages",
        "Brewing Supplies" to "shop=brewing_supplies",
        "Butcher" to "shop=butcher",
        "Cheese Shop" to "shop=cheese",
        "Chocolate Shop" to "shop=chocolate",
        "Coffee Shop" to "shop=coffee",
        "Confectionery" to "shop=confectionery",
        "Convenience Store" to "shop=convenience",
        "Dairy" to "shop=dairy",
        "Deli" to "shop=deli",
        "Farm Shop" to "shop=farm",
        "Food Shop" to "shop=food",
        "Frozen Food" to "shop=frozen_food",
        "Greengrocer" to "shop=greengrocer",
        "Health Food" to "shop=health_food",
        "Ice Cream Shop" to "shop=ice_cream",
        "Nut Shop" to "shop=nuts",
        "Pasta Shop" to "shop=pasta",
        "Pastry Shop" to "shop=pastry",
        "Seafood Shop" to "shop=seafood",
        "Spices Shop" to "shop=spices",
        "Supermarket" to "shop=supermarket",
        "Tea Shop" to "shop=tea",
        "Water Shop" to "shop=water",
        "Wine Shop" to "shop=wine",

        // --- SHOP: GENERAL & DEPARTMENT ---
        "Department Store" to "shop=department_store",
        "General Store" to "shop=general",
        "Kiosk" to "shop=kiosk",
        "Mall" to "shop=mall",
        "Wholesale" to "shop=wholesale",

        // --- SHOP: CLOTHING & SHOES ---
        "Baby Goods" to "shop=baby_goods",
        "Bag Shop" to "shop=bag",
        "Boutique" to "shop=boutique",
        "Clothes Shop" to "shop=clothes",
        "Fabric Shop" to "shop=fabric",
        "Fashion Accessories" to "shop=fashion_accessories",
        "Jewelry" to "shop=jewelry",
        "Leather Shop" to "shop=leather",
        "Sewing Shop" to "shop=sewing",
        "Shoe Repair" to "shop=shoe_repair",
        "Shoe Shop" to "shop=shoes",
        "Tailor" to "shop=tailor",
        "Watches" to "shop=watches",
        "Wool" to "shop=wool",

        // --- SHOP: DISCOUNT & CHARITY ---
        "Charity Shop" to "shop=charity",
        "Pawnbroker" to "shop=pawnbroker",
        "Second Hand" to "shop=second_hand",
        "Variety Store" to "shop=variety_store",

        // --- SHOP: HEALTH & BEAUTY ---
        "Beauty Shop" to "shop=beauty",
        "Chemist" to "shop=chemist",
        "Cosmetics" to "shop=cosmetics",
        "Drugstore" to "shop=drugstore",
        "Erotic Shop" to "shop=erotic",
        "Hairdresser" to "shop=hairdresser",
        "Hairdresser Supply" to "shop=hairdresser_supply",
        "Hearing Aids" to "shop=hearing_aids",
        "Herbalist" to "shop=herbalist",
        "Massage Shop" to "shop=massage",
        "Medical Supply" to "shop=medical_supply",
        "Nutrition Supplements" to "shop=nutrition_supplements",
        "Optician" to "shop=optician",
        "Perfumery" to "shop=perfumery",
        "Piercing" to "shop=piercing",
        "Tattoo" to "shop=tattoo",

        // --- SHOP: DIY & HOUSEHOLD ---
        "Agrarian" to "shop=agrarian",
        "Appliance" to "shop=appliance",
        "Bathroom Furnishing" to "shop=bathroom_furnishing",
        "Country Store" to "shop=country_store",
        "Do It Yourself (DIY)" to "shop=doityourself",
        "Electrical" to "shop=electrical",
        "Energy" to "shop=energy",
        "Fireplace" to "shop=fireplace",
        "Florist" to "shop=florist",
        "Garden Centre" to "shop=garden_centre",
        "Garden Furniture" to "shop=garden_furniture",
        "Gas" to "shop=gas",
        "Glaziery" to "shop=glaziery",
        "Hardware" to "shop=hardware",
        "Houseware" to "shop=houseware",
        "Locksmith" to "shop=locksmith",
        "Paint" to "shop=paint",
        "Pottery Shop" to "shop=pottery",
        "Security" to "shop=security",
        "Tool Hire" to "shop=tool_hire",
        "Trade" to "shop=trade",

        // --- SHOP: FURNITURE & INTERIOR ---
        "Antiques" to "shop=antiques",
        "Bed Shop" to "shop=bed",
        "Candles" to "shop=candles",
        "Carpet" to "shop=carpet",
        "Curtain" to "shop=curtain",
        "Doors" to "shop=doors",
        "Flooring" to "shop=flooring",
        "Furniture" to "shop=furniture",
        "Household Linen" to "shop=household_linen",
        "Interior Decoration" to "shop=interior_decoration",
        "Kitchen" to "shop=kitchen",
        "Lighting" to "shop=lighting",
        "Tiles" to "shop=tiles",
        "Window Blind" to "shop=window_blind",

        // --- SHOP: ELECTRONICS ---
        "Computer" to "shop=computer",
        "Electronics" to "shop=electronics",
        "Hifi" to "shop=hifi",
        "Mobile Phone" to "shop=mobile_phone",
        "Printer Ink" to "shop=printer_ink",
        "Radiotechnics" to "shop=radiotechnics",
        "Telecommunication" to "shop=telecommunication",
        "Vacuum Cleaner" to "shop=vacuum_cleaner",
        "Video Games" to "shop=video_games",

        // --- SHOP: OUTDOORS & VEHICLES ---
        "ATV" to "shop=atv",
        "Bicycle Shop" to "shop=bicycle",
        "Boat Shop" to "shop=boat",
        "Car Parts" to "shop=car_parts",
        "Car Repair" to "shop=car_repair",
        "Car Shop" to "shop=car",
        "Caravan" to "shop=caravan",
        "Fishing Shop" to "shop=fishing",
        "Fuel Shop" to "shop=fuel",
        "Golf Shop" to "shop=golf",
        "Hunting Shop" to "shop=hunting",
        "Military Surplus" to "shop=military_surplus",
        "Motorcycle Repair" to "shop=motorcycle_repair",
        "Motorcycle Shop" to "shop=motorcycle",
        "Outdoor" to "shop=outdoor",
        "Scuba Diving" to "shop=scuba_diving",
        "Ski" to "shop=ski",
        "Snowmobile" to "shop=snowmobile",
        "Sports" to "shop=sports",
        "Surf" to "shop=surf",
        "Swimming Pool Shop" to "shop=swimming_pool",
        "Trailer" to "shop=trailer",
        "Truck" to "shop=truck",
        "Tyres" to "shop=tyres",

        // --- SHOP: HOBBIES, ARTS, BOOKS ---
        "Anime" to "shop=anime",
        "Art" to "shop=art",
        "Bookmaker" to "shop=bookmaker",
        "Books" to "shop=books",
        "Camera" to "shop=camera",
        "Collector" to "shop=collector",
        "Copy Shop" to "shop=copyshop",
        "Craft Shop" to "shop=craft",
        "Frame" to "shop=frame",
        "Games" to "shop=games",
        "Gift" to "shop=gift",
        "Lottery" to "shop=lottery",
        "Model" to "shop=model",
        "Music" to "shop=music",
        "Musical Instrument" to "shop=musical_instrument",
        "Newsagent" to "shop=newsagent",
        "Photo" to "shop=photo",
        "Pyrotechnics" to "shop=pyrotechnics",
        "Stationery" to "shop=stationery",
        "Ticket" to "shop=ticket",
        "Tobacco" to "shop=tobacco",
        "Toys" to "shop=toys",
        "Trophy" to "shop=trophy",
        "Video" to "shop=video",

        // --- SHOP: OTHER ---
        "Cannabis" to "shop=cannabis",
        "Dry Cleaning" to "shop=dry_cleaning",
        "E-Cigarette" to "shop=e-cigarette",
        "Funeral Directors" to "shop=funeral_directors",
        "Laundry" to "shop=laundry",
        "Money Lender" to "shop=money_lender",
        "Outpost" to "shop=outpost",
        "Party" to "shop=party",
        "Pest Control" to "shop=pest_control",
        "Pet" to "shop=pet",
        "Pet Grooming" to "shop=pet_grooming",
        "Religion" to "shop=religion",
        "Rental" to "shop=rental",
        "Storage Rental" to "shop=storage_rental",
        "Travel Agency" to "shop=travel_agency",
        "Vacant Shop" to "shop=vacant",
        "Weapons" to "shop=weapons",
        "Wholesale" to "shop=wholesale",

        // --- OFFICE ---
        "Office (General)" to "office=yes",
        "Accountant" to "office=accountant",
        "Adoption Agency" to "office=adoption_agency",
        "Advertising Agency" to "office=advertising_agency",
        "Architect" to "office=architect",
        "Association" to "office=association",
        "Charity Office" to "office=charity",
        "Company" to "office=company",
        "Consulting" to "office=consulting",
        "Courier" to "office=courier",
        "Coworking" to "office=coworking",
        "Diplomatic" to "office=diplomatic",
        "Educational" to "office=educational",
        "Employment Agency" to "office=employment_agency",
        "Energy Supplier" to "office=energy_supplier",
        "Engineer" to "office=engineer",
        "Estate Agent" to "office=estate_agent",
        "Financial" to "office=financial",
        "Forestry" to "office=forestry",
        "Foundation" to "office=foundation",
        "Government" to "office=government",
        "Graphic Design" to "office=graphic_design",
        "Guide" to "office=guide",
        "Harbour Master" to "office=harbour_master",
        "Insurance" to "office=insurance",
        "IT" to "office=it",
        "Lawyer" to "office=lawyer",
        "Logistics" to "office=logistics",
        "Moving Company" to "office=moving_company",
        "Newspaper" to "office=newspaper",
        "NGO" to "office=ngo",
        "Notary" to "office=notary",
        "Political Party" to "office=political_party",
        "Private Investigator" to "office=private_investigator",
        "Property Management" to "office=property_management",
        "Quango" to "office=quango",
        "Religion Office" to "office=religion",
        "Research Office" to "office=research",
        "Security Office" to "office=security",
        "Surveyor" to "office=surveyor",
        "Tax Advisor" to "office=tax_advisor",
        "Telecommunication Office" to "office=telecommunication",
        "Travel Agent" to "office=travel_agent",
        "Union" to "office=union",
        "Water Utility" to "office=water_utility",

        // --- CRAFT ---
        "Craft (General)" to "craft=yes",
        "Agricultural Engines" to "craft=agricultural_engines",
        "Basket Maker" to "craft=basket_maker",
        "Beekeeper" to "craft=beekeeper",
        "Blacksmith" to "craft=blacksmith",
        "Boatbuilder" to "craft=boatbuilder",
        "Bookbinder" to "craft=bookbinder",
        "Brewery" to "craft=brewery",
        "Builder" to "craft=builder",
        "Cabinet Maker" to "craft=cabinet_maker",
        "Carpenter" to "craft=carpenter",
        "Carpet Layer" to "craft=carpet_layer",
        "Caterer" to "craft=caterer",
        "Chimney Sweeper" to "craft=chimney_sweeper",
        "Clockmaker" to "craft=clockmaker",
        "Confectionery (Craft)" to "craft=confectionery",
        "Dental Technician" to "craft=dental_technician",
        "Distillery" to "craft=distillery",
        "Door Construction" to "craft=door_construction",
        "Dressmaker" to "craft=dressmaker",
        "Electrician" to "craft=electrician",
        "Embroiderer" to "craft=embroiderer",
        "Engraver" to "craft=engraver",
        "Floorer" to "craft=floorer",
        "Gardener" to "craft=gardener",
        "Glazier" to "craft=glazier",
        "Goldsmith" to "craft=goldsmith",
        "Grinding Mill" to "craft=grinding_mill",
        "Handicraft" to "craft=handicraft",
        "Hvac" to "craft=hvac",
        "Insulation" to "craft=insulation",
        "Jeweller" to "craft=jeweller",
        "Joiner" to "craft=joiner",
        "Key Cutter" to "craft=key_cutter",
        "Locksmith" to "craft=locksmith",
        "Metal Construction" to "craft=metal_construction",
        "Mint" to "craft=mint",
        "Musical Instrument" to "craft=musical_instrument",
        "Oil Mill" to "craft=oil_mill",
        "Optician (Craft)" to "craft=optician",
        "Organ Builder" to "craft=organ_builder",
        "Painter" to "craft=painter",
        "Parquet Layer" to "craft=parquet_layer",
        "Photographer" to "craft=photographer",
        "Photographic Laboratory" to "craft=photographic_laboratory",
        "Piano Tuner" to "craft=piano_tuner",
        "Plasterer" to "craft=plasterer",
        "Plumber" to "craft=plumber",
        "Pottery (Craft)" to "craft=pottery",
        "Printer" to "craft=printer",
        "Print Shop" to "craft=print_shop",
        "Rigger" to "craft=rigger",
        "Roofer" to "craft=roofer",
        "Saddler" to "craft=saddler",
        "Sailmaker" to "craft=sailmaker",
        "Sawmill" to "craft=sawmill",
        "Scaffolder" to "craft=scaffolder",
        "Sculptor" to "craft=sculptor",
        "Shoemaker" to "craft=shoemaker",
        "Sign Maker" to "craft=sign_maker",
        "Stand Builder" to "craft=stand_builder",
        "Stonemason" to "craft=stonemason",
        "Stove Fitter" to "craft=stove_fitter",
        "Tailor (Craft)" to "craft=tailor",
        "Tiler" to "craft=tiler",
        "Tinsmith" to "craft=tinsmith",
        "Toolmaker" to "craft=toolmaker",
        "Turner" to "craft=turner",
        "Upholsterer" to "craft=upholsterer",
        "Watchmaker" to "craft=watchmaker",
        "Water Well Drilling" to "craft=water_well_drilling",
        "Window Construction" to "craft=window_construction",
        "Winery" to "craft=winery",

        // --- EMERGENCY ---
        "Ambulance Station" to "emergency=ambulance_station",
        "Assembly Point" to "emergency=assembly_point",
        "Defibrillator" to "emergency=defibrillator",
        "Emergency Access Point" to "emergency=access_point",
        "Fire Alarm Box" to "emergency=fire_alarm_box",
        "Fire Extinguisher" to "emergency=fire_extinguisher",
        "Fire Hose" to "emergency=fire_hose",
        "Fire Hydrant" to "emergency=fire_hydrant",
        "First Aid Kit" to "emergency=first_aid_kit",
        "Landing Site" to "emergency=landing_site",
        "Life Ring" to "emergency=life_ring",
        "Lifeguard Base" to "emergency=lifeguard_base",
        "Lifeguard Platform" to "emergency=lifeguard_platform",
        "Lifeguard Tower" to "emergency=lifeguard_tower",
        "Emergency Phone" to "emergency=phone",
        "Siren" to "emergency=siren",
        "Water Tank (Emergency)" to "emergency=water_tank",

        // --- TOURISM ---
        "Alpine Hut" to "tourism=alpine_hut",
        "Apartment (Tourism)" to "tourism=apartment",
        "Aquarium" to "tourism=aquarium",
        "Artwork" to "tourism=artwork",
        "Attraction" to "tourism=attraction",
        "Camp Site" to "tourism=camp_site",
        "Caravan Site" to "tourism=caravan_site",
        "Chalet" to "tourism=chalet",
        "Gallery" to "tourism=gallery",
        "Guest House" to "tourism=guest_house",
        "Hostel" to "tourism=hostel",
        "Hotel" to "tourism=hotel",
        "Information" to "tourism=information",
        "Motel" to "tourism=motel",
        "Museum" to "tourism=museum",
        "Picnic Site" to "tourism=picnic_site",
        "Theme Park" to "tourism=theme_park",
        "Viewpoint" to "tourism=viewpoint",
        "Wilderness Hut" to "tourism=wilderness_hut",
        "Zoo" to "tourism=zoo",

        // --- HISTORIC ---
        "Aircraft (Historic)" to "historic=aircraft",
        "Archaeological Site" to "historic=archaeological_site",
        "Battlefield" to "historic=battlefield",
        "Bomb Crater" to "historic=bomb_crater",
        "Boundary Stone" to "historic=boundary_stone",
        "Building (Historic)" to "historic=building",
        "Cannon" to "historic=cannon",
        "Castle" to "historic=castle",
        "Castle Wall" to "historic=castle_wall",
        "Charcoal Pile" to "historic=charcoal_pile",
        "Church (Historic)" to "historic=church",
        "City Gate" to "historic=city_gate",
        "Citywalls" to "historic=citywalls",
        "Farm" to "historic=farm",
        "Fort" to "historic=fort",
        "Gallows" to "historic=gallows",
        "Highwater Mark" to "historic=highwater_mark",
        "Locomotive" to "historic=locomotive",
        "Manor" to "historic=manor",
        "Memorial" to "historic=memorial",
        "Milestone" to "historic=milestone",
        "Mine" to "historic=mine",
        "Mine Shaft" to "historic=mine_shaft",
        "Monument" to "historic=monument",
        "Pillory" to "historic=pillory",
        "Railway Car" to "historic=railway_car",
        "Ruins" to "historic=ruins",
        "Rune Stone" to "historic=rune_stone",
        "Ship" to "historic=ship",
        "Shipwreck" to "historic=wreck",
        "Tank (Historic)" to "historic=tank",
        "Tomb" to "historic=tomb",
        "Tower (Historic)" to "historic=tower",
        "Wayside Cross" to "historic=wayside_cross",
        "Wayside Shrine" to "historic=wayside_shrine",

        // --- SPORTS (Specific Courts & Fields) ---
        "Soccer Field" to "sport=soccer",
        "Tennis Court" to "sport=tennis",
        "Basketball Court" to "sport=basketball",
        "Baseball Diamond" to "sport=baseball",
        "Volleyball Court" to "sport=volleyball",
        "Skate Park" to "sport=skateboard",
        "Badminton Court" to "sport=badminton",
        "Table Tennis (Ping Pong)" to "sport=table_tennis",
        "Golf Course" to "sport=golf",
        "Climbing" to "sport=climbing",
        "Cricket Field" to "sport=cricket",
        "Rugby Pitch" to "sport=rugby",
        "American Football" to "sport=american_football",
        "Equestrian" to "sport=equestrian",
        "Gymnastics" to "sport=gymnastics",
        "Hockey Pitch" to "sport=hockey",
        "Ice Hockey" to "sport=ice_hockey",
        "Scuba Diving" to "sport=scuba_diving",
        "Skiing" to "sport=skiing",
        "Surfing" to "sport=surfing",
        "Swimming" to "sport=swimming",
        "Yoga" to "sport=yoga",

        // --- SPECIFIC RECYCLING (TrashCompass Specials) ---
        "Glass Recycling" to "recycling:glass=yes",
        "Paper Recycling" to "recycling:paper=yes",
        "Clothes Recycling" to "recycling:clothes=yes",
        "Metal Recycling" to "recycling:cans=yes",
        "Plastic Recycling" to "recycling:plastic=yes",
        "Battery Recycling" to "recycling:batteries=yes",
        "Dog Poop Bags" to "vending=excrement_bags",

        // --- HIKING & NAVIGATION ---
        "Hiking Sign (Guidepost)" to "information=guidepost",
        "Map Board" to "information=map",
        "Trail Blaze" to "information=route_marker",
        "Cairn (Rock Pile)" to "man_made=cairn",

        // --- SPECIFIC VENDING MACHINES ---
        "Ticket Machine" to "vending=public_transport_tickets",
        "Parking Ticket Machine" to "vending=parking_tickets",
        "Drinks Machine" to "vending=drinks",
        "Snack Machine" to "vending=food",
        "Cigarette Machine" to "vending=cigarettes",
        "Condom Machine" to "vending=condoms",
        "Newspaper Box" to "vending=newspapers",
        "Parcel Locker" to "amenity=parcel_locker",

        // --- PLAYGROUND EQUIPMENT (For Parents) ---
        "Slide" to "playground=slide",
        "Swing" to "playground=swing",
        "Sandpit" to "playground=sandpit",
        "Seesaw" to "playground=seesaw",
        "Climbing Frame" to "playground=structure",
        "Zipwire" to "playground=zipwire",

        // --- WINTER SPORTS ---
        "Ski Piste Map" to "information=piste_map",
        "Ski Rental" to "shop=ski",
        "Snow Cannon" to "man_made=snow_cannon",
        "Sled Rental" to "shop=sled",

        // --- WATER INFRASTRUCTURE ---
        "Water Well" to "man_made=water_well",
        "Water Pump" to "man_made=water_pump",
        "Water Tower" to "man_made=water_tower",
        "Watermill" to "man_made=water_mill",

        // --- RURAL & BARRIERS ---
        "Stile (Fence Step)" to "barrier=stile",
        "Kissing Gate" to "barrier=kissing_gate",
        "Cattle Grid" to "barrier=cattle_grid",
        "Toll Booth" to "barrier=toll_booth",
        "Grit Bin (Salt)" to "amenity=grit_bin",
        "Hunting Stand" to "amenity=hunting_stand",
        "Feeding Place (Animals)" to "amenity=feeding_place"

        // --- ADVERTISING & MEDIA ---
        "Billboard" to "advertising=billboard",
        "Advertising Column" to "advertising=column",
        "Flagpole" to "man_made=flagpole",

        // --- NAVIGATION & STREETS ---
        "Street Lamp" to "highway=street_lamp",
        "Stairs" to "highway=steps",
        "Entrance" to "entrance=yes",
        "Main Entrance" to "entrance=main",
        "Service Entrance" to "entrance=service",
        "Elevator" to "highway=elevator",
        "Traffic Mirror" to "highway=traffic_mirror",
        "Milestone" to "highway=milestone",
        "Summit" to "natural=peak",
        "Tree" to "natural=tree"

        // --- MAN MADE ---
        "Adit" to "man_made=adit",
        "Antenna" to "man_made=antenna",
        "Beacon" to "man_made=beacon",
        "Breakwater" to "man_made=breakwater",
        "Bridge" to "bridge=yes",
        "Tunnel" to "tunnel=yes",
        "Bunker_silo" to "man_made=bunker_silo",
        "Cairn" to "man_made=cairn",
        "Cellar Entrance" to "man_made=cellar_entrance",
        "Chimney" to "man_made=chimney",
        "Clearcut" to "man_made=clearcut",
        "Communications Tower" to "man_made=communications_tower",
        "Crane" to "man_made=crane",
        "Cross" to "man_made=cross",
        "Cutline" to "man_made=cutline",
        "Dovecote" to "man_made=dovecote",
        "Dyke" to "man_made=dyke",
        "Embankment" to "man_made=embankment",
        "Flagpole" to "man_made=flagpole",
        "Gasometer" to "man_made=gasometer",
        "Groyne" to "man_made=groyne",
        "Kiln" to "man_made=kiln",
        "Lighthouse" to "man_made=lighthouse",
        "Manhole" to "man_made=manhole",
        "Mast" to "man_made=mast",
        "Maypole" to "man_made=maypole",
        "Mineshaft" to "man_made=mineshaft",
        "Monitoring Station" to "man_made=monitoring_station",
        "Obelisk" to "man_made=obelisk",
        "Observatory" to "man_made=observatory",
        "Offshore Platform" to "man_made=offshore_platform",
        "Oil Well" to "man_made=oil_well",
        "Petroleum Well" to "man_made=petroleum_well",
        "Pier" to "man_made=pier",
        "Pipeline" to "man_made=pipeline",
        "Pumping Station" to "man_made=pumping_station",
        "Reservoir Covered" to "man_made=reservoir_covered",
        "Silo" to "man_made=silo",
        "Snow Fence" to "man_made=snow_fence",
        "Snow Net" to "man_made=snow_net",
        "Storage Tank" to "man_made=storage_tank",
        "Street Cabinet" to "man_made=street_cabinet",
        "Surveillance" to "man_made=surveillance",
        "Survey Point" to "man_made=survey_point",
        "Telescope" to "man_made=telescope",
        "Tower" to "man_made=tower",
        "Wastewater Plant" to "man_made=wastewater_plant",
        "Water Mill" to "man_made=water_mill",
        "Water Tap" to "man_made=water_tap",
        "Water Tower" to "man_made=water_tower",
        "Water Well" to "man_made=water_well",
        "Water Works" to "man_made=water_works",
        "Wildlife Crossing" to "man_made=wildlife_crossing",
        "Windmill" to "man_made=windmill",
        "Works" to "man_made=works",

        // --- NATURAL ---
        "Bare Rock" to "natural=bare_rock",
        "Bay" to "natural=bay",
        "Beach" to "natural=beach",
        "Cape" to "natural=cape",
        "Cave Entrance" to "natural=cave_entrance",
        "Cliff" to "natural=cliff",
        "Coastline" to "natural=coastline",
        "Fell" to "natural=fell",
        "Geyser" to "natural=geyser",
        "Glacier" to "natural=glacier",
        "Grassland" to "natural=grassland",
        "Heath" to "natural=heath",
        "Hot Spring" to "natural=hot_spring",
        "Mud" to "natural=mud",
        "Peak" to "natural=peak",
        "Reef" to "natural=reef",
        "Ridge" to "natural=ridge",
        "Rock" to "natural=rock",
        "Saddle" to "natural=saddle",
        "Sand" to "natural=sand",
        "Scree" to "natural=scree",
        "Scrub" to "natural=scrub",
        "Sinkhole" to "natural=sinkhole",
        "Spring" to "natural=spring",
        "Stone" to "natural=stone",
        "Strait" to "natural=strait",
        "Tree" to "natural=tree",
        "Tree Row" to "natural=tree_row",
        "Valley" to "natural=valley",
        "Volcano" to "natural=volcano",
        "Water" to "natural=water",
        "Wetland" to "natural=wetland",
        "Wood" to "natural=wood",

        // --- POWER ---
        "Power Cable" to "power=cable",
        "Catenary Mast" to "power=catenary_mast",
        "Compensator" to "power=compensator",
        "Converter" to "power=converter",
        "Generator" to "power=generator",
        "Heliostat" to "power=heliostat",
        "Insulator" to "power=insulator",
        "Power Line" to "power=line",
        "Minor Power Line" to "power=minor_line",
        "Power Plant" to "power=plant",
        "Power Pole" to "power=pole",
        "Power Portal" to "power=portal",
        "Substation" to "power=substation",
        "Power Switch" to "power=switch",
        "Power Terminal" to "power=terminal",
        "Power Tower" to "power=tower",
        "Transformer" to "power=transformer",

        // --- LEISURE ---
        "Adult Gaming Centre" to "leisure=adult_gaming_centre",
        "Amusement Arcade" to "leisure=amusement_arcade",
        "Bandstand" to "leisure=bandstand",
        "Beach Resort" to "leisure=beach_resort",
        "Bird Hide" to "leisure=bird_hide",
        "Bowling Alley" to "leisure=bowling_alley",
        "Common" to "leisure=common",
        "Dance" to "leisure=dance",
        "Disc Golf Course" to "leisure=disc_golf_course",
        "Dog Park" to "leisure=dog_park",
        "Escape Game" to "leisure=escape_game",
        "Firepit" to "leisure=firepit",
        "Fishing" to "leisure=fishing",
        "Fitness Centre" to "leisure=fitness_centre",
        "Fitness Station" to "leisure=fitness_station",
        "Garden" to "leisure=garden",
        "Golf Course" to "leisure=golf_course",
        "Hackerspace" to "leisure=hackerspace",
        "Horse Riding" to "leisure=horse_riding",
        "Ice Rink" to "leisure=ice_rink",
        "Marina" to "leisure=marina",
        "Miniature Golf" to "leisure=miniature_golf",
        "Nature Reserve" to "leisure=nature_reserve",
        "Park" to "leisure=park",
        "Picnic Table" to "leisure=picnic_table",
        "Pitch" to "leisure=pitch",
        "Playground" to "leisure=playground",
        "Sauna" to "leisure=sauna",
        "Slipway" to "leisure=slipway",
        "Sports Centre" to "leisure=sports_centre",
        "Stadium" to "leisure=stadium",
        "Summer Camp" to "leisure=summer_camp",
        "Swimming Area" to "leisure=swimming_area",
        "Swimming Pool" to "leisure=swimming_pool",
        "Track" to "leisure=track",
        "Water Park" to "leisure=water_park",

        // --- BARRIER ---
        "Barrier (General)" to "barrier=yes",
        "Block" to "barrier=block",
        "Bollard" to "barrier=bollard",
        "Border Control" to "barrier=border_control",
        "Bump Gate" to "barrier=bump_gate",
        "Bus Trap" to "barrier=bus_trap",
        "Cable Barrier" to "barrier=cable_barrier",
        "Cattle Grid" to "barrier=cattle_grid",
        "Chain" to "barrier=chain",
        "City Wall" to "barrier=city_wall",
        "Cycle Barrier" to "barrier=cycle_barrier",
        "Debris" to "barrier=debris",
        "Ditch" to "barrier=ditch",
        "Entrance" to "barrier=entrance",
        "Fence" to "barrier=fence",
        "Full-height Turnstile" to "barrier=full-height_turnstile",
        "Gate" to "barrier=gate",
        "Guard Rail" to "barrier=guard_rail",
        "Hampshire Gate" to "barrier=hampshire_gate",
        "Handrail" to "barrier=handrail",
        "Hedge" to "barrier=hedge",
        "Height Restrictor" to "barrier=height_restrictor",
        "Horse Stile" to "barrier=horse_stile",
        "Jersey Barrier" to "barrier=jersey_barrier",
        "Kerb" to "barrier=kerb",
        "Kissing Gate" to "barrier=kissing_gate",
        "Lift Gate" to "barrier=lift_gate",
        "Log" to "barrier=log",
        "Motorcycle Barrier" to "barrier=motorcycle_barrier",
        "Retaining Wall" to "barrier=retaining_wall",
        "Rope" to "barrier=rope",
        "Sally Port" to "barrier=sally_port",
        "Sliding Gate" to "barrier=sliding_gate",
        "Spikes" to "barrier=spikes",
        "Stile" to "barrier=stile",
        "Sump Buster" to "barrier=sump_buster",
        "Swing Gate" to "barrier=swing_gate",
        "Tank Trap" to "barrier=tank_trap",
        "Toll Booth" to "barrier=toll_booth",
        "Turnstile" to "barrier=turnstile",
        "Wall" to "barrier=wall",

        // --- MILITARY ---
        "Military Airfield" to "military=airfield",
        "Ammunition" to "military=ammunition",
        "Barracks" to "military=barracks",
        "Military Base" to "military=base",
        "Bunker" to "military=bunker",
        "Checkpoint" to "military=checkpoint",
        "Danger Area" to "military=danger_area",
        "Naval Base" to "military=naval_base",
        "Nuclear Explosion Site" to "military=nuclear_explosion_site",
        "Obstacle Course" to "military=obstacle_course",
        "Military Office" to "military=office",
        "Military Range" to "military=range",
        "Training Area" to "military=training_area",
        "Trench" to "military=trench",

        // --- GEOLOGICAL ---
        "Moraine" to "geological=moraine",
        "Outcrop" to "geological=outcrop",
        "Palaeontological Site" to "geological=palaeontological_site",
        "Volcanic Caldera" to "geological=volcanic_caldera_rim",
        "Volcanic Lava Flow" to "geological=volcanic_lava_flow",
        "Volcanic Vent" to "geological=volcanic_vent",

        // --- AERIALWAY ---
        "Cable Car" to "aerialway=cable_car",
        "Chair Lift" to "aerialway=chair_lift",
        "Drag Lift" to "aerialway=drag_lift",
        "Gondola" to "aerialway=gondola",
        "Goods Ropeway" to "aerialway=goods",
        "J-bar" to "aerialway=j-bar",
        "Magic Carpet" to "aerialway=magic_carpet",
        "Mixed Lift" to "aerialway=mixed_lift",
        "Platter" to "aerialway=platter",
        "Pylon" to "aerialway=pylon",
        "Rope Tow" to "aerialway=rope_tow",
        "Station" to "aerialway=station",
        "T-bar" to "aerialway=t-bar",
        "Zip Line" to "aerialway=zip_line"
    )
}