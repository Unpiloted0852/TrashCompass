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
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
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

    // Sensors
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var currentArrowRotation = 0f

    // State
    private var currentLocation: Location? = null
    private var destinationAmenity: Amenity? = null
    private var foundAmenities = ArrayList<Amenity>()

    // Logic
    private var lastFetchLocation: Location? = null
    private val REFETCH_DISTANCE_THRESHOLD = 150f
    private var initialSearchDone = false

    // SPEED THRESHOLD: 15 MPH is approx 6.7 Meters per Second
    private val SPEED_THRESHOLD_MPS = 6.7f

    private var useMetric = true
    private var currentAmenityName = "Trash Can"
    private var isSearching = false
    private var isErrorState = false
    private var searchJob: Job? = null
    private var lastFriendlyError = ""

    // SPEED TUNING: 6 second timeout
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    // Server Priority List
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
                // Retry
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
            val opts = listOf(
                "Trash Can",
                "Public Toilet",
                "Defibrillator (AED)",
                "Water Fountain",
                "Recycling Bin",
                "ATM",
                "Post Box",
                "Bench"
            )
            opts.forEach { popup.menu.add(it) }

            popup.setOnMenuItemClickListener { item ->
                currentAmenityName = item.title.toString()
                tvTitle.text = "Nearest $currentAmenityName"

                // Reset State
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
                true
            }
            popup.show()
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkPermissions()
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

                        // --- CAR MODE LOGIC ---
                        // If we are moving faster than 15mph (6.7 m/s) AND we have a bearing, use GPS bearing
                        val speed = loc.speed // in meters/second
                        if (speed > SPEED_THRESHOLD_MPS && loc.hasBearing()) {
                            // Moving fast: Use GPS Bearing (ignore magnets)
                            updateArrowWithHeading(loc.bearing)
                            tvAccuracy.text = "GPS Heading"
                            tvAccuracy.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#32CD32"))
                        }
                        // ----------------------

                        // 1. Initial Load - FAST
                        if (!initialSearchDone && !isSearching) {
                            initialSearchDone = true
                            lastFetchLocation = loc
                            // No delay. Fire immediately.
                            fetchAmenitiesFast(loc.latitude, loc.longitude, currentAmenityName, isSilent = false)
                        }
                        // 2. Refetch
                        else if (lastFetchLocation != null && !isSearching) {
                            if (loc.distanceTo(lastFetchLocation!!) > REFETCH_DISTANCE_THRESHOLD) {
                                lastFetchLocation = loc
                                fetchAmenitiesFast(loc.latitude, loc.longitude, currentAmenityName, isSilent = true)
                            }
                        }

                        if (foundAmenities.isNotEmpty()) {
                            recalculateNearest()
                        }

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

        // 1. Name & Context
        // If we found a business with a toilet (toilets=yes), say "Inside..."
        if (tags.has("name")) {
            val name = tags.getString("name")
            if (currentAmenityName == "Public Toilet" && tags.optString("toilets") == "yes") {
                infoList.add("Inside $name")
            } else {
                infoList.add(name)
            }
        }

        // 2. Access
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

        // 3. Fees
        // Check for explicit price tags first
        var price = tags.optString("charge")
        if (price.isEmpty()) price = tags.optString("toilets:charge")

        if (price.isNotEmpty()) {
            infoList.add("Fee: $price")
        } else {
            // Check generic fee status
            var fee = tags.optString("toilets:fee")
            if (fee.isEmpty()) fee = tags.optString("fee")

            if (fee == "no") {
                infoList.add("Free")
            } else if (fee == "yes") {
                infoList.add("Fee Required")
            } else if (fee.isNotEmpty()) {
                infoList.add("Fee: $fee")
            }
        }

        // 4. Specific details
        when (currentAmenityName) {
            "Recycling Bin" -> {
                if (tags.has("recycling_type")) infoList.add("Type: " + tags.getString("recycling_type").replace("_", " ").capitalize())
            }
            "Water Fountain" -> {
                if (tags.has("drinking_water")) {
                    val dw = tags.getString("drinking_water")
                    if (dw == "yes") infoList.add("Water: Drinkable")
                    else if (dw == "no") infoList.add("Water: Not Drinkable")
                }
            }
            "Defibrillator (AED)" -> {
                // Location Notes
                var loc = tags.optString("defibrillator:location")
                if (loc.isEmpty()) loc = tags.optString("location")

                if (loc.isNotEmpty()) infoList.add("Location: $loc")

                // General Descriptions
                if (tags.has("description")) infoList.add("Note: " + tags.getString("description"))

                // Indoor Check
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
    }
    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        // 1. SPEED CHECK: If driving fast (> 15mph), ignore the compass (GPS handles it)
        val currentSpeed = currentLocation?.speed ?: 0f
        if (currentSpeed > SPEED_THRESHOLD_MPS) {
            return
        }

        // 2. GET ROTATION MATRIX
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // 3. FIX FOR SCREEN ROTATION (Portrait vs Landscape)
        // This fixes the "Compass is less accurate" issue when holding the phone sideways
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
            // Fallback if remap fails
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
        }

        val azimuth = (Math.toDegrees(orientationAngles[0].toDouble()) + 360).toFloat() % 360

        // 4. UPDATE ARROW
        updateArrowWithHeading(azimuth)

        // 5. UPDATE ACCURACY STATUS
        val statusText: String
        val statusColor: Int

        when (event.accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> {
                statusText = "Compass: Good"
                statusColor = Color.parseColor("#32CD32") // Green
            }
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> {
                statusText = "Compass: Fair"
                statusColor = Color.parseColor("#FFD700") // Yellow
            }
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> {
                statusText = "Compass: Weak"
                statusColor = Color.RED
            }
            else -> {
                statusText = "Compass: Poor" // Unreliable
                statusColor = Color.RED
            }
        }

        tvAccuracy.text = statusText
        tvAccuracy.backgroundTintList = ColorStateList.valueOf(statusColor)
    }

    // --- NEW HELPER FUNCTION FOR ROTATION ---
    private fun updateArrowWithHeading(userHeading: Float) {
        if (currentLocation != null && destinationAmenity != null) {
            val bearingToTarget = currentLocation!!.bearingTo(destinationAmenity!!.location)
            val normalizedBearing = (bearingToTarget + 360) % 360
            val targetRot = (normalizedBearing - userHeading + 360) % 360

            var diff = targetRot - currentArrowRotation
            while (diff < -180) diff += 360
            while (diff > 180) diff -= 360
            currentArrowRotation += diff * 0.15f // Smoothing
            ivArrow.rotation = currentArrowRotation
        }
    }

    private fun showCalibrationDialog() {
        AlertDialog.Builder(this).setTitle("Compass Status").setMessage("Wave phone in Figure-8.").setPositiveButton("OK", null).show()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- ANIMATION ---
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
                delay(250) // Faster animation
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
                tvDistance.text = "None found within 1km"
                tvHint.text = "(Tap to retry)"
            }
        }
    }

    private fun getQueryString(type: String, lat: Double, lon: Double): String {
        val bbox = String.format(Locale.US, "(around:1000, %f, %f)", lat, lon)
        return when (type) {
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
            else -> """[out:json];(node["amenity"="waste_basket"]$bbox;node["amenity"="waste_disposal"]$bbox;node["bin"="yes"]$bbox;way["amenity"="waste_basket"]$bbox;way["bin"="yes"]$bbox;);out center;"""
        }
    }

    // --- HIGH SPEED FETCH ---
    private fun fetchAmenitiesFast(lat: Double, lon: Double, amenityType: String, isSilent: Boolean) {
        if (!isSilent) {
            isSearching = true // Mark searching immediately
            startSearchingAnimation()
            isErrorState = false
        }

        CoroutineScope(Dispatchers.IO).launch {
            var attempts = 0
            var success = false

            // Loop through servers until one works
            while (attempts < servers.size && !success) {
                try {
                    val query = getQueryString(amenityType, lat, lon)
                    val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

                    val currentServer = servers[attempts]
                    val url = "$currentServer?data=$encodedQuery"

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "TrashCompass/2.0")
                        .build()

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
                                    } else {
                                        continue
                                    }
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
                    } else {
                        throw Exception("HTTP:${response.code}")
                    }

                } catch (e: Exception) {
                    attempts++
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