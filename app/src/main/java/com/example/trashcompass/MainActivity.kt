package com.example.trashcompass

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.widget.TextView
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
    private lateinit var tvAccuracy: TextView
    private lateinit var tvMapButton: TextView
    private lateinit var tvLegal: TextView
    private lateinit var tvInterference: TextView

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
    private var initialSearchDone = false

    // --- DRIVING ANIMATION STATE ---
    private var driveAnimJob: Job? = null
    private var lastFixTime: Long = 0L
    // -------------------------------

    // SPEED THRESHOLD: 15 MPH is approx 6.7 Meters per Second
    private val SPEED_THRESHOLD_MPS = 6.7f

    private var useMetric = true
    private var currentAmenityName = "Trash Can"
    private var isSearching = false
    private var isErrorState = false
    private var searchJob: Job? = null
    private var lastFriendlyError = ""

    // --- DICTIONARY FOR "ADVANCED" SEARCH ---
    private val searchDictionary = mapOf(
        "Pharmacy" to "amenity=pharmacy",
        "Hospital" to "amenity=hospital",
        "Police" to "amenity=police",
        "Fire Station" to "amenity=fire_station",
        "Gas Station" to "amenity=fuel",
        "EV Charging" to "amenity=charging_station",
        "Parking" to "amenity=parking",
        "Bus Stop" to "highway=bus_stop",
        "Taxi Stand" to "amenity=taxi",
        "Supermarket" to "shop=supermarket",
        "Convenience Store" to "shop=convenience",
        "Bakery" to "shop=bakery",
        "Cafe" to "amenity=cafe",
        "Restaurant" to "amenity=restaurant",
        "Fast Food" to "amenity=fast_food",
        "Bar" to "amenity=bar",
        "Pub" to "amenity=pub",
        "Library" to "amenity=library",
        "Cinema" to "amenity=cinema",
        "Park" to "leisure=park",
        "Playground" to "leisure=playground",
        "Dog Park" to "leisure=dog_park",
        "Picnic Table" to "leisure=picnic_table",
        "Picnic Site" to "tourism=picnic_site",
        "Hotel" to "tourism=hotel",
        "Motel" to "tourism=motel",
        "Camp Site" to "tourism=camp_site",
        "Hostel" to "tourism=hostel",
        "Museum" to "tourism=museum",
        "Zoo" to "tourism=zoo",
        "Vending Machine" to "amenity=vending_machine",
        "Ice Cream" to "amenity=ice_cream",
        "Dentist" to "amenity=dentist",
        "Veterinarian" to "amenity=veterinary",
        "Bank" to "amenity=bank",
        "Clothes Shop" to "shop=clothes",
        "Electronics Store" to "shop=electronics",
        "Hardware Store" to "shop=hardware",
        "Bicycle Shop" to "shop=bicycle",
        "Car Repair" to "shop=car_repair"
    )

    private val hardcodedOptions = listOf(
        "Trash Can", "Public Toilet", "Defibrillator (AED)",
        "Water Fountain", "Recycling Bin", "ATM", "Post Box", "Bench"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val servers = listOf(
        "https://overpass.kumi.systems/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://overpass-api.de/api/interpreter"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvTitle = findViewById(R.id.tvTitle)
        tvDistance = findViewById(R.id.tvDistance)
        tvMetadata = findViewById(R.id.tvMetadata)
        tvHint = findViewById(R.id.tvHint)
        ivArrow = findViewById(R.id.ivArrow)
        tvAccuracy = findViewById(R.id.tvAccuracy)
        tvMapButton = findViewById(R.id.tvMapButton)
        tvLegal = findViewById(R.id.tvLegal)

        tvLegal.setOnClickListener { showLegalDialog() }

        // --- FIXED: Removed the negative top margin hack that was cutting off text ---
        // (The problematic code block is completely deleted here)

        // Center metadata text
        tvMetadata.gravity = Gravity.CENTER
        val padding = (20 * resources.displayMetrics.density).toInt()
        tvMetadata.setPadding(padding, 0, padding, 0)

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
                if (currentLocation != null) {
                    fetchAmenitiesFast(currentLocation!!.latitude, currentLocation!!.longitude, currentAmenityName, isSilent = false)
                }
            } else {
                useMetric = !useMetric
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

    // --- SEARCH LOGIC ---
    private fun showCustomSearchDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("What are you looking for?")
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)

        val input = AutoCompleteTextView(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.hint = "Type here (e.g. Pharmacy, Gas...)"
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, searchDictionary.keys.toList())
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

        if (currentLocation != null) {
            fetchAmenitiesFast(currentLocation!!.latitude, currentLocation!!.longitude, currentAmenityName, isSilent = false)
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
            This app uses data from OpenStreetMap (OSM). Data may be inaccurate, outdated, or incomplete.
            
            SAFETY WARNING:
            Never rely solely on this app for emergency navigation. AED locations may be incorrect.
            
            LICENSE:
            Data is available under the Open Database License (ODbL).
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

                        if (!initialSearchDone && !isSearching) {
                            initialSearchDone = true
                            lastFetchLocation = loc
                            fetchAmenitiesFast(loc.latitude, loc.longitude, currentAmenityName, isSilent = false)
                        } else if (lastFetchLocation != null && !isSearching) {
                            if (loc.distanceTo(lastFetchLocation!!) > REFETCH_DISTANCE_THRESHOLD) {
                                lastFetchLocation = loc
                                fetchAmenitiesFast(loc.latitude, loc.longitude, currentAmenityName, isSilent = true)
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

        if (tags.has("name")) {
            val name = tags.getString("name")
            if (currentAmenityName == "Public Toilet" && tags.optString("toilets") == "yes") {
                infoList.add("Inside $name")
            } else {
                infoList.add(name)
            }
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
        if (price.isNotEmpty()) {
            infoList.add("Fee: $price")
        } else {
            var fee = tags.optString("toilets:fee")
            if (fee.isEmpty()) fee = tags.optString("fee")
            if (fee == "no") infoList.add("Free")
            else if (fee == "yes") infoList.add("Fee Required")
            else if (fee.isNotEmpty()) infoList.add("Fee: $fee")
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
                if (tags.has("description")) infoList.add("Note: " + tags.getString("description"))
                if (tags.optString("indoor") == "yes") infoList.add("(Indoors)")
            }
        }

        if (infoList.isNotEmpty()) {
            tvMetadata.text = infoList.joinToString("\n")
            tvMetadata.visibility = View.VISIBLE
        } else {
            tvMetadata.visibility = View.GONE
        }
    }

    private fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

    // --- SENSOR LOGIC ---
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

        // 1. MAGNETIC FIELD (Interference + Fallback Accuracy)
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            val magX = event.values[0]
            val magY = event.values[1]
            val magZ = event.values[2]
            val magnitude = sqrt(magX * magX + magY * magY + magZ * magZ)

            // Interference Warning
            if ((currentLocation?.speed ?: 0f) <= SPEED_THRESHOLD_MPS) {
                if (magnitude > 75 || magnitude < 20) tvInterference.visibility = View.VISIBLE
                else tvInterference.visibility = View.GONE
            }

            // Store legacy accuracy in case Rotation Vector doesn't support it
            lastMagAccuracy = event.accuracy
        }

        // 2. ROTATION VECTOR (Heading + Smart Status)
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val currentSpeed = currentLocation?.speed ?: 0f
            if (currentSpeed > SPEED_THRESHOLD_MPS) return

            // --- HEADING CALCULATION ---
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val axisAdjustedMatrix = FloatArray(9)
            val displayRotation = windowManager.defaultDisplay.rotation
            var axisX = SensorManager.AXIS_X
            var axisY = SensorManager.AXIS_Y

            when (displayRotation) {
                android.view.Surface.ROTATION_90 -> {
                    axisX = SensorManager.AXIS_Y
                    axisY = SensorManager.AXIS_MINUS_X
                }
                android.view.Surface.ROTATION_180 -> {
                    axisX = SensorManager.AXIS_MINUS_X
                    axisY = SensorManager.AXIS_MINUS_Y
                }
                android.view.Surface.ROTATION_270 -> {
                    axisX = SensorManager.AXIS_MINUS_Y
                    axisY = SensorManager.AXIS_X
                }
            }

            if (SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, axisAdjustedMatrix)) {
                SensorManager.getOrientation(axisAdjustedMatrix, orientationAngles)
            } else {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
            }

            val azimuth = (Math.toDegrees(orientationAngles[0].toDouble()) + 360).toFloat() % 360
            updateArrowWithHeading(azimuth)

            // --- NEW: SMART COMPASS STATUS ---
            // Priority 1: Check 'values[4]' (Estimated Heading Accuracy in Radians)
            // Available on Android 4.3 (API 18) and newer
            var statusText = "Compass: Good"
            var statusColor = Color.parseColor("#32CD32") // Green

            if (event.values.size > 4 && event.values[4] != -1f) {
                val accuracyRad = event.values[4]
                // 0.35 rad ≈ 20 degrees, 0.8 rad ≈ 45 degrees
                if (accuracyRad < 0.35) {
                    statusText = "Compass: Good"
                    statusColor = Color.parseColor("#32CD32") // Green
                } else if (accuracyRad < 0.8) {
                    statusText = "Compass: Fair"
                    statusColor = Color.parseColor("#FFD700") // Gold
                } else {
                    statusText = "Compass: Poor"
                    statusColor = Color.RED
                }
            } else {
                // Priority 2: Fallback to Magnetometer status (if values[4] unavailable)
                when (lastMagAccuracy) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> {
                        statusText = "Compass: Good"
                        statusColor = Color.parseColor("#32CD32")
                    }
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> {
                        statusText = "Compass: Fair"
                        statusColor = Color.parseColor("#FFD700")
                    }
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW, SensorManager.SENSOR_STATUS_UNRELIABLE -> {
                        statusText = "Compass: Poor"
                        statusColor = Color.RED
                    }
                }
            }

            tvAccuracy.text = statusText
            tvAccuracy.backgroundTintList = ColorStateList.valueOf(statusColor)
        }
    }

    // --- REFACTORED ARROW LOGIC ---
    private fun updateArrowWithHeading(userHeading: Float) {
        currentLocation?.let { updateArrowUI(it, userHeading) }
    }

    private fun updateArrowUI(userLoc: Location, userHeading: Float) {
        if (destinationAmenity != null) {
            val bearingToTarget = userLoc.bearingTo(destinationAmenity!!.location)
            val normalizedBearing = (bearingToTarget + 360) % 360
            val targetRot = (normalizedBearing - userHeading + 360) % 360

            var diff = targetRot - currentArrowRotation
            while (diff < -180) diff += 360
            while (diff > 180) diff -= 360

            currentArrowRotation += diff * 0.15f
            ivArrow.rotation = currentArrowRotation
        }
    }

    // --- SMOOTH DRIVING ANIMATION ---
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

    // --- LOADING ANIMATION ---
    private fun startSearchingAnimation() {
        if (isSearching) return
        isSearching = true
        tvHint.text = "Please wait..."
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
        searchJob?.cancel()
        tvHint.text = "(Tap to change units)"
    }

    private fun updateUI() {
        if (isSearching) return

        if (isErrorState) {
            tvDistance.textSize = 24f
            tvDistance.text = lastFriendlyError
            tvHint.text = "(Tap to retry)"
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
        } else {
            tvMapButton.visibility = View.GONE
            tvMetadata.visibility = View.GONE
            if (foundAmenities.isEmpty() && initialSearchDone) {
                tvDistance.textSize = 24f
                if (hardcodedOptions.contains(currentAmenityName) || searchDictionary.containsKey(currentAmenityName)) {
                    tvDistance.text = "None found within 3km"
                } else {
                    tvDistance.text = "No '$currentAmenityName' found"
                }
                tvHint.text = "(Tap to retry)"
            }
        }
    }

    private fun getQueryString(type: String, lat: Double, lon: Double): String {
        val bbox = String.format(Locale.US, "(around:3000, %f, %f)", lat, lon)

        if (searchDictionary.containsKey(type)) {
            val tag = searchDictionary[type]!!
            val key = tag.substringBefore("=")
            val value = tag.substringAfter("=")
            return """[out:json];(node["$key"="$value"]$bbox;way["$key"="$value"]$bbox;);out center;"""
        }

        return when (type) {
            "Trash Can" -> """[out:json];(node["amenity"="waste_basket"]$bbox;node["amenity"="waste_disposal"]$bbox;node["bin"="yes"]$bbox;way["amenity"="waste_basket"]$bbox;way["bin"="yes"]$bbox;);out center;"""
            "Defibrillator (AED)" -> """[out:json];(node["emergency"="defibrillator"]$bbox;way["emergency"="defibrillator"]$bbox;);out center;"""
            "Public Toilet" -> """
                [out:json];
                (
                  node["amenity"="toilets"]$bbox;
                  way["amenity"="toilets"]$bbox;
                  node["toilets"="yes"]$bbox;
                  way["toilets"="yes"]$bbox;
                );
                out center;
            """.trimIndent()
            "Water Fountain" -> """[out:json];(node["amenity"="drinking_water"]$bbox;way["amenity"="drinking_water"]$bbox;);out center;"""
            "Recycling Bin" -> """[out:json];(node["amenity"="recycling"]$bbox;node["recycling_type"="container"]$bbox;);out center;"""
            "ATM" -> """[out:json];node["amenity"="atm"]$bbox;out center;"""
            "Post Box" -> """[out:json];node["amenity"="post_box"]$bbox;out center;"""
            "Bench" -> """[out:json];node["amenity"="bench"]$bbox;out center;"""
            else -> {
                val rawInput = type
                val snakeCase = type.lowercase().replace(" ", "_")
                """
                [out:json];
                (
                  node["name"~"$rawInput",i]$bbox;
                  way["name"~"$rawInput",i]$bbox;
                  node["amenity"="$snakeCase"]$bbox;
                  way["amenity"="$snakeCase"]$bbox;
                  node["leisure"="$snakeCase"]$bbox;
                  way["leisure"="$snakeCase"]$bbox;
                  node["tourism"="$snakeCase"]$bbox;
                  way["tourism"="$snakeCase"]$bbox;
                  node["shop"="$snakeCase"]$bbox;
                  way["shop"="$snakeCase"]$bbox;
                  node["man_made"="$snakeCase"]$bbox;
                  way["man_made"="$snakeCase"]$bbox;
                );
                out center;
                """.trimIndent()
            }
        }
    }

    private fun fetchAmenitiesFast(lat: Double, lon: Double, amenityType: String, isSilent: Boolean) {
        if (!isSilent) {
            isSearching = true
            startSearchingAnimation()
            isErrorState = false
        }
        CoroutineScope(Dispatchers.IO).launch {
            var attempts = 0
            var success = false
            while (attempts < servers.size && !success) {
                try {
                    val query = getQueryString(amenityType, lat, lon)
                    val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                    val currentServer = servers[attempts]
                    val url = "$currentServer?data=$encodedQuery"
                    val request = Request.Builder().url(url).header("User-Agent", "TrashCompass/2.0").build()
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
                            foundAmenities = tempFoundList
                            if (foundAmenities.isEmpty()) destinationAmenity = null
                            else recalculateNearest()
                            updateUI()
                        }
                    } else throw Exception("HTTP:${response.code}")
                } catch (e: Exception) { attempts++ }
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