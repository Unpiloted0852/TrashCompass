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
        // --- SUSTAINABILITY & UTILITY ---
        "Trash Can" to "amenity=waste_basket",
        "Recycling Bin" to "amenity=recycling",
        "Waste Disposal" to "amenity=waste_disposal",
        "Compost" to "amenity=compost",
        "Drinking Water" to "amenity=drinking_water",
        "Water Point" to "amenity=water_point",
        "Watering Place" to "amenity=watering_place",
        "Toilets" to "amenity=toilets",
        "Bench" to "amenity=bench",
        "Shelter" to "amenity=shelter",
        "Post Box" to "amenity=post_box",
        "Telephone" to "amenity=telephone",
        "Shower" to "amenity=shower",
        "Dressing Room" to "amenity=dressing_room",
        "Luggage Locker" to "amenity=locker",

        // --- EMERGENCY ---
        "Defibrillator (AED)" to "emergency=defibrillator",
        "Fire Hydrant" to "emergency=fire_hydrant",
        "Fire Station" to "amenity=fire_station",
        "Ambulance Station" to "emergency=ambulance_station",
        "Emergency Phone" to "emergency=phone",
        "Police Station" to "amenity=police",
        "Siren" to "emergency=siren",
        "Lifeguard Tower" to "emergency=lifeguard_tower",
        "Life Ring" to "emergency=life_ring",
        "Assembly Point" to "emergency=assembly_point",
        "Fire Extinguisher" to "emergency=fire_extinguisher",
        "Fire Alarm Box" to "emergency=fire_alarm_box",
        "Landing Site" to "emergency=landing_site",
        "Mountain Rescue" to "emergency=mountain_rescue",

        // --- FOOD & DRINK ---
        "Restaurant" to "amenity=restaurant",
        "Cafe" to "amenity=cafe",
        "Fast Food" to "amenity=fast_food",
        "Bar" to "amenity=bar",
        "Pub" to "amenity=pub",
        "Biergarten" to "amenity=biergarten",
        "Food Court" to "amenity=food_court",
        "Ice Cream" to "amenity=ice_cream",
        "Canteen" to "amenity=canteen",
        "Nightclub" to "amenity=nightclub",

        // --- SHOPS (FOOD) ---
        "Supermarket" to "shop=supermarket",
        "Bakery" to "shop=bakery",
        "Convenience Store" to "shop=convenience",
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
        "Frozen Food" to "shop=frozen_food",
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

        // --- SHOPS (GENERAL) ---
        "Department Store" to "shop=department_store",
        "General Store" to "shop=general",
        "Mall" to "shop=mall",
        "Kiosk" to "shop=kiosk",
        "Marketplace" to "amenity=marketplace",
        "Wholesale" to "shop=wholesale",
        "Duty Free" to "shop=duty_free",

        // --- SHOPS (CLOTHING & FASHION) ---
        "Clothes Shop" to "shop=clothes",
        "Shoe Shop" to "shop=shoes",
        "Bag Shop" to "shop=bag",
        "Baby Goods" to "shop=baby_goods",
        "Beauty Shop" to "shop=beauty",
        "Boutique" to "shop=boutique",
        "Cosmetics" to "shop=cosmetics",
        "Fabric Shop" to "shop=fabric",
        "Fashion Accessories" to "shop=fashion_accessories",
        "Jewelry" to "shop=jewelry",
        "Leather" to "shop=leather",
        "Sewing" to "shop=sewing",
        "Tailor Shop" to "shop=tailor",
        "Watches" to "shop=watches",
        "Wool" to "shop=wool",

        // --- SHOPS (HOME & LIVING) ---
        "Furniture Store" to "shop=furniture",
        "Garden Centre" to "shop=garden_centre",
        "Hardware Store" to "shop=hardware",
        "DIY Store" to "shop=doityourself",
        "Antiques" to "shop=antiques",
        "Bedding Shop" to "shop=bed",
        "Carpet Shop" to "shop=carpet",
        "Curtain Shop" to "shop=curtain",
        "Electrical" to "shop=electrical",
        "Flooring" to "shop=flooring",
        "Florist" to "shop=florist",
        "Houseware" to "shop=houseware",
        "Interior Decoration" to "shop=interior_decoration",
        "Kitchen Shop" to "shop=kitchen",
        "Lighting Shop" to "shop=lighting",
        "Paint Shop" to "shop=paint",
        "Security Shop" to "shop=security",
        "Trade Shop" to "shop=trade",

        // --- SHOPS (ELECTRONICS) ---
        "Electronics Store" to "shop=electronics",
        "Computer Shop" to "shop=computer",
        "Mobile Phone Shop" to "shop=mobile_phone",
        "Camera Shop" to "shop=camera",
        "Hifi Shop" to "shop=hifi",
        "Video Games" to "shop=video_games",
        "Robot Shop" to "shop=robot",

        // --- SHOPS (HOBBY & SPORT) ---
        "Bicycle Shop" to "shop=bicycle",
        "Book Store" to "shop=books",
        "Gift Shop" to "shop=gift",
        "Music Shop" to "shop=music",
        "Musical Instrument" to "shop=musical_instrument",
        "Newsagent" to "shop=newsagent",
        "Outdoor Shop" to "shop=outdoor",
        "Pet Shop" to "shop=pet",
        "Photo Shop" to "shop=photo",
        "Sports Shop" to "shop=sports",
        "Stationery" to "shop=stationery",
        "Ticket Shop" to "shop=ticket",
        "Tobacco Shop" to "shop=tobacco",
        "Toy Shop" to "shop=toys",
        "Trophy Shop" to "shop=trophy",
        "Weapons" to "shop=weapons",
        "Fishing Shop" to "shop=fishing",
        "Hunting Shop" to "shop=hunting",
        "Scuba Diving Shop" to "shop=scuba_diving",
        "Art Supply" to "shop=art",
        "Craft Supply" to "shop=craft",

        // --- SHOPS (AUTOMOTIVE) ---
        "Car Shop" to "shop=car",
        "Car Parts" to "shop=car_parts",
        "Car Repair Shop" to "shop=car_repair",
        "Motorcycle Shop" to "shop=motorcycle",
        "Tyre Shop" to "shop=tyres",
        "Boat Dealer" to "shop=boat",

        // --- SHOPS (HEALTH & OTHERS) ---
        "Chemist" to "shop=chemist",
        "Drugstore" to "shop=drugstore",
        "Optician" to "shop=optician",
        "Hearing Aids" to "shop=hearing_aids",
        "Herbalist" to "shop=herbalist",
        "Medical Supply" to "shop=medical_supply",
        "Nutrition Supplements" to "shop=nutrition_supplements",
        "Charity Shop" to "shop=charity",
        "Pawnbroker" to "shop=pawnbroker",
        "Second Hand" to "shop=second_hand",
        "Variety Store" to "shop=variety_store",
        "Adult Shop" to "shop=erotic",
        "Betting Shop" to "shop=betting",
        "Lottery" to "shop=lottery",
        "Cannabis" to "shop=cannabis",
        "E-Cigarettes" to "shop=e-cigarette",
        "Funeral Directors" to "shop=funeral_directors",
        "Laundry" to "shop=laundry",
        "Dry Cleaning" to "shop=dry_cleaning",
        "Money Lender" to "shop=money_lender",
        "Party Shop" to "shop=party",
        "Travel Agency" to "shop=travel_agency",
        "Vacant Shop" to "shop=vacant",

        // --- HEALTHCARE ---
        "Hospital" to "amenity=hospital",
        "Clinic" to "amenity=clinic",
        "Doctors" to "amenity=doctors",
        "Dentist" to "amenity=dentist",
        "Pharmacy" to "amenity=pharmacy",
        "Veterinary" to "amenity=veterinary",
        "Nursing Home" to "amenity=nursing_home",
        "Social Facility" to "amenity=social_facility",
        "Blood Donation" to "amenity=blood_donation",

        // --- TRANSPORT (ROAD) ---
        "Fuel Station" to "amenity=fuel",
        "EV Charging" to "amenity=charging_station",
        "Parking" to "amenity=parking",
        "Parking Entrance" to "amenity=parking_entrance",
        "Parking Space" to "amenity=parking_space",
        "Motorcycle Parking" to "amenity=motorcycle_parking",
        "Bicycle Parking" to "amenity=bicycle_parking",
        "Bicycle Rental" to "amenity=bicycle_rental",
        "Bicycle Repair Station" to "amenity=bicycle_repair_station",
        "Bus Stop" to "highway=bus_stop",
        "Bus Station" to "amenity=bus_station",
        "Taxi Stand" to "amenity=taxi",
        "Car Rental" to "amenity=car_rental",
        "Car Wash" to "amenity=car_wash",
        "Car Sharing" to "amenity=car_sharing",
        "Traffic Signals" to "highway=traffic_signals",
        "Crosswalk" to "highway=crossing",
        "Street Lamp" to "highway=street_lamp",
        "Stop Sign" to "highway=stop",
        "Speed Camera" to "highway=speed_camera",
        "Toll Booth" to "barrier=toll_booth",
        "Turning Circle" to "highway=turning_circle",
        "Mini Roundabout" to "highway=mini_roundabout",

        // --- TRANSPORT (RAIL & WATER) ---
        "Train Station" to "railway=station",
        "Train Halt" to "railway=halt",
        "Subway Entrance" to "railway=subway_entrance",
        "Tram Stop" to "railway=tram_stop",
        "Platform" to "railway=platform",
        "Ferry Terminal" to "amenity=ferry_terminal",
        "Boat Rental" to "amenity=boat_rental",
        "Pier" to "man_made=pier",
        "Marina" to "leisure=marina",
        "Slipway" to "leisure=slipway",
        "Dock" to "waterway=dock",
        "Lock Gate" to "waterway=lock_gate",

        // --- TRANSPORT (AIR) ---
        "Airport" to "aeroway=aerodrome",
        "Helipad" to "aeroway=helipad",
        "Terminal" to "aeroway=terminal",
        "Hangar" to "aeroway=hangar",
        "Gate (Airport)" to "aeroway=gate",
        "Windsock" to "aeroway=windsock",

        // --- TOURISM ---
        "Hotel" to "tourism=hotel",
        "Motel" to "tourism=motel",
        "Hostel" to "tourism=hostel",
        "Guest House" to "tourism=guest_house",
        "Camp Site" to "tourism=camp_site",
        "Caravan Site" to "tourism=caravan_site",
        "Chalet" to "tourism=chalet",
        "Apartment" to "tourism=apartment",
        "Museum" to "tourism=museum",
        "Art Gallery" to "tourism=gallery",
        "Attraction" to "tourism=attraction",
        "Information" to "tourism=information",
        "Information Board" to "tourism=information_board",
        "Information Map" to "tourism=information_map",
        "Picnic Site" to "tourism=picnic_site",
        "Viewpoint" to "tourism=viewpoint",
        "Zoo" to "tourism=zoo",
        "Theme Park" to "tourism=theme_park",
        "Aquarium" to "tourism=aquarium",
        "Artwork" to "tourism=artwork",
        "Wilderness Hut" to "tourism=alpine_hut",

        // --- LEISURE ---
        "Park" to "leisure=park",
        "Playground" to "leisure=playground",
        "Dog Park" to "leisure=dog_park",
        "Golf Course" to "leisure=golf_course",
        "Swimming Pool" to "leisure=swimming_pool",
        "Water Park" to "leisure=water_park",
        "Stadium" to "leisure=stadium",
        "Pitch" to "leisure=pitch",
        "Track" to "leisure=track",
        "Fitness Centre" to "leisure=fitness_centre",
        "Fitness Station" to "leisure=fitness_station",
        "Sports Centre" to "leisure=sports_centre",
        "Garden" to "leisure=garden",
        "Nature Reserve" to "leisure=nature_reserve",
        "Fishing" to "leisure=fishing",
        "Bird Hide" to "leisure=bird_hide",
        "Beach Resort" to "leisure=beach_resort",
        "Dance Floor" to "leisure=dance",
        "Bowling Alley" to "leisure=bowling_alley",
        "Fire Pit" to "leisure=firepit",
        "Hackerspace" to "leisure=hackerspace",
        "Sauna" to "leisure=sauna",
        "Trampoline Park" to "leisure=trampoline_park",
        "Escape Game" to "leisure=escape_game",
        "Miniature Golf" to "leisure=miniature_golf",

        // --- OFFICE ---
        "Office" to "office=yes",
        "Government Office" to "office=government",
        "Insurance" to "office=insurance",
        "Lawyer" to "office=lawyer",
        "IT Office" to "office=it",
        "Estate Agent" to "office=estate_agent",
        "Employment Agency" to "office=employment_agency",
        "NGO" to "office=ngo",
        "Coworking Space" to "office=coworking",
        "Accountant" to "office=accountant",
        "Architect" to "office=architect",
        "Association" to "office=association",
        "Company" to "office=company",
        "Courier" to "office=courier",
        "Educational" to "office=educational",
        "Notary" to "office=notary",
        "Political Party" to "office=political_party",
        "Newspaper" to "office=newspaper",
        "Research" to "office=research",
        "Tax Advisor" to "office=tax_advisor",
        "Telecommunication" to "office=telecommunication",

        // --- CRAFT (Makers) ---
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
        "Beekeeper" to "craft=beekeeper",
        "Bookbinder" to "craft=bookbinder",
        "Caterer" to "craft=caterer",
        "Confectionery (Craft)" to "craft=confectionery",
        "Dressmaker" to "craft=dressmaker",
        "Gardener" to "craft=gardener",
        "Glazier" to "craft=glazier",
        "Handicraft" to "craft=handicraft",
        "Jeweler" to "craft=jeweller",
        "Metal Construction" to "craft=metal_construction",
        "Painter" to "craft=painter",
        "Pottery" to "craft=pottery",
        "Printer" to "craft=printer",
        "Roofer" to "craft=roofer",
        "Sculptor" to "craft=sculptor",
        "Tinsmith" to "craft=tinsmith",
        "Upholsterer" to "craft=upholsterer",
        "Watchmaker" to "craft=watchmaker",

        // --- HISTORIC ---
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
        "Aircraft" to "historic=aircraft",
        "Tank" to "historic=tank",
        "Locomotive" to "historic=locomotive",
        "Ship" to "historic=ship",
        "Boundary Stone" to "historic=boundary_stone",
        "Highwater Mark" to "historic=highwater_mark",
        "Pillory" to "historic=pillory",
        "Tomb" to "historic=tomb",

        // --- MAN MADE ---
        "Surveillance Camera" to "man_made=surveillance",
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
        "Water Tap" to "man_made=water_tap",
        "Gasometer" to "man_made=gasometer",
        "Oil Well" to "man_made=oil_well",
        "Pumping Station" to "man_made=pumping_station",
        "Wastewater Plant" to "man_made=wastewater_plant",
        "Water Works" to "man_made=water_works",
        "Bridge" to "man_made=bridge",
        "Tunnel" to "man_made=tunnel",

        // --- NATURAL ---
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
        "Waterfall" to "waterway=waterfall",
        "Stream" to "waterway=stream",
        "River" to "waterway=river",
        "Canal" to "waterway=canal",
        "Drain" to "waterway=drain",
        "Ditch" to "waterway=ditch",
        "Weir" to "waterway=weir",
        "Dam" to "waterway=dam",
        "Saddle" to "natural=saddle",
        "Ridge" to "natural=ridge",
        "Valley" to "natural=valley",
        "Stone" to "natural=stone",
        "Sinkhole" to "natural=sinkhole",

        // --- POWER ---
        "Generator" to "power=generator",
        "Power Line" to "power=line",
        "Power Pole" to "power=pole",
        "Power Tower" to "power=tower",
        "Transformer" to "power=transformer",
        "Substation" to "power=substation",
        "Nuclear Power Plant" to "power=plant",
        "Solar Panel" to "power=solar_panel",
        "Wind Turbine" to "power=generator", // Note: often tagged with generator:source=wind

        // --- PUBLIC & EDUCATION ---
        "Town Hall" to "amenity=townhall",
        "Courthouse" to "amenity=courthouse",
        "Prison" to "amenity=prison",
        "Embassy" to "amenity=embassy",
        "Post Office" to "amenity=post_office",
        "Community Centre" to "amenity=community_centre",
        "Library" to "amenity=library",
        "Crematorium" to "amenity=crematorium",
        "Graveyard" to "amenity=graveyard",
        "Cemetery" to "landuse=cemetery",
        "College" to "amenity=college",
        "Kindergarten" to "amenity=kindergarten",
        "School" to "amenity=school",
        "University" to "amenity=university",
        "Driving School" to "amenity=driving_school",
        "Music School" to "amenity=music_school",
        "Language School" to "amenity=language_school",

        // --- BARRIERS ---
        "Gate" to "barrier=gate",
        "Bollard" to "barrier=bollard",
        "Border Control" to "barrier=border_control",
        "Cattle Grid" to "barrier=cattle_grid",
        "Stile" to "barrier=stile",
        "Kissing Gate" to "barrier=kissing_gate",
        "Fence" to "barrier=fence",
        "Wall" to "barrier=wall",
        "Block" to "barrier=block",
        "Chain" to "barrier=chain",
        "Turnstile" to "barrier=turnstile",
        "Swing Gate" to "barrier=swing_gate",
        "Lift Gate" to "barrier=lift_gate",

        // --- AERIAL ---
        "Cable Car" to "aerialway=cable_car",
        "Gondola" to "aerialway=gondola",
        "Chair Lift" to "aerialway=chair_lift",
        "Drag Lift" to "aerialway=drag_lift",
        "Zip Line" to "aerialway=zip_line",
        "Pylon" to "aerialway=pylon",

        // --- RELIGION ---
        "Place of Worship" to "amenity=place_of_worship",
        "Cathedral" to "building=cathedral",
        "Chapel" to "building=chapel",
        "Mosque" to "building=mosque",
        "Synagogue" to "building=synagogue",
        "Temple" to "building=temple",
        "Shrine" to "amenity=shrine",
        "Monastery" to "amenity=monastery",

        // --- MILITARY ---
        "Bunker" to "military=bunker",
        "Barracks" to "military=barracks",
        "Danger Area" to "military=danger_area",
        "Range" to "military=range",
        "Naval Base" to "military=naval_base",
        "Airfield (Military)" to "military=airfield",

        // --- FINANCIAL ---
        "ATM" to "amenity=atm",
        "Bank" to "amenity=bank",
        "Bureau de Change" to "amenity=bureau_de_change",

        // --- OTHER ---
        "Vending Machine" to "amenity=vending_machine",
        "Parcel Locker" to "amenity=parcel_locker",
        "Ticket Validator" to "amenity=ticket_validator",
        "Public Bookcase" to "amenity=public_bookcase",
        "Casino" to "amenity=casino",
        "Cinema" to "amenity=cinema",
        "Theatre" to "amenity=theatre",
        "Arts Centre" to "amenity=arts_centre",
        "Planetarium" to "amenity=planetarium",
        "Studio" to "amenity=studio",
        "Childcare" to "amenity=childcare",
        "Conference Centre" to "amenity=conference_centre",
        "Events Venue" to "amenity=events_venue",
        "Exhibition Centre" to "amenity=exhibition_centre",
        "Gambling" to "amenity=gambling",
        "Love Hotel" to "amenity=love_hotel",
        "Stripclub" to "amenity=stripclub",
        "Swingerclub" to "amenity=swingerclub",
        "Internet Cafe" to "amenity=internet_cafe",
        "Kitchen" to "amenity=kitchen",
        "Give Box" to "amenity=give_box",
        "Loading Dock" to "amenity=loading_dock",
        "Misty Spray" to "amenity=mist_spraying_cooler",
        "Stage" to "amenity=stage"
    )
}