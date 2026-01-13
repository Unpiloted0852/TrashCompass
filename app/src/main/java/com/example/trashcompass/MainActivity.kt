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
import android.widget.Toast
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

    // Stores the complex filter string determined by the Smart Search
    private var currentAiFilter = ""

    // --- 1. THE FAST BRAIN (Local Dictionary) ---
    // Kept small for speed.
    private val searchDictionary = mapOf(
        "Pharmacy" to "amenity=pharmacy",
        "Hospital" to "amenity=hospital",
        "Police" to "amenity=police",
        "Fire Station" to "amenity=fire_station",
        "Hydrant" to "emergency=fire_hydrant",
        "Gas Station" to "amenity=fuel",
        "EV Charging" to "amenity=charging_station",
        "Parking" to "amenity=parking",
        "Supermarket" to "shop=supermarket",
        "Convenience Store" to "shop=convenience",
        "Bakery" to "shop=bakery",
        "Cafe" to "amenity=cafe",
        "Restaurant" to "amenity=restaurant",
        "Fast Food" to "amenity=fast_food",
        "Pub" to "amenity=pub",
        "Park" to "leisure=park",
        "Playground" to "leisure=playground",
        "Hotel" to "tourism=hotel"
    )

    private val hardcodedOptions = listOf(
        "Trash Can", "Public Toilet", "Defibrillator (AED)",
        "Water Fountain", "Recycling Bin", "ATM", "Post Box", "Bench"
    )

    // HTTP Client
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS) // Increased for smarter AI response time
        .readTimeout(30, TimeUnit.SECONDS)
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

        // UI Setup
        tvMetadata.gravity = Gravity.CENTER
        val padding = (20 * resources.displayMetrics.density).toInt()
        tvMetadata.setPadding(padding, 0, padding, 0)

        addLegalFooter()
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
                // RETRY LOGIC
                tvDistance.text = "Retrying..."
                tvHint.text = "Please wait..."
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
            popup.menu.add("🔍 Custom Search")

            popup.setOnMenuItemClickListener { item ->
                if (item.title == "🔍 Custom Search") {
                    showCustomSearchDialog()
                } else {
                    currentAiFilter = "" // Reset Smart Search logic
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

    private fun showCustomSearchDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Smart Search")

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, padding)

        val input = AutoCompleteTextView(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.hint = "e.g. USPS Mailbox, Bus stop with shelter..."

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, searchDictionary.keys.toList())
        input.setAdapter(adapter)

        container.addView(input)
        builder.setView(container)

        builder.setPositiveButton("Search") { _, _ ->
            val query = input.text.toString().trim()
            if (query.isNotEmpty()) {
                currentAiFilter = "" // Reset
                setNewSearchTarget(query)
            }
        }
        builder.setNegativeButton("Cancel", null)

        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
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

        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        params.gravity = Gravity.TOP
        params.topMargin = (80 * resources.displayMetrics.density).toInt()
        tvInterference.layoutParams = params
        rootLayout.addView(tvInterference)
    }

    private fun addLegalFooter() {
        val rootLayout = findViewById<ViewGroup>(android.R.id.content)
        tvLegal = TextView(this)
        tvLegal.text = "© OpenStreetMap contributors. Data may be incomplete. Tap for info."
        tvLegal.textSize = 10f
        tvLegal.setTextColor(Color.parseColor("#808080"))
        tvLegal.gravity = Gravity.CENTER

        // Goldilocks padding: 12dp
        val bottomPad = (12 * resources.displayMetrics.density).toInt()
        tvLegal.setPadding(0, 0, 0, bottomPad)

        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        params.gravity = Gravity.BOTTOM
        tvLegal.layoutParams = params
        tvLegal.setOnClickListener { showLegalDialog() }
        rootLayout.addView(tvLegal)
    }

    private fun showLegalDialog() {
        AlertDialog.Builder(this)
            .setTitle("Legal & Safety")
            .setMessage("Data from OpenStreetMap (OSM). Use at your own risk. Not for emergency navigation.")
            .setPositiveButton("OK", null)
            .show()
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

                        val speed = loc.speed
                        if (speed > SPEED_THRESHOLD_MPS && loc.hasBearing()) {
                            updateArrowWithHeading(loc.bearing)
                            tvAccuracy.text = "GPS Heading"
                            tvAccuracy.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#32CD32"))
                            tvInterference.visibility = View.GONE
                        }

                        // 1. Initial Load
                        if (!initialSearchDone && !isSearching) {
                            initialSearchDone = true
                            lastFetchLocation = loc
                            fetchAmenitiesFast(loc.latitude, loc.longitude, currentAmenityName, isSilent = false)
                        }
                        // 2. Refetch
                        else if (lastFetchLocation != null && !isSearching) {
                            if (loc.distanceTo(lastFetchLocation!!) > REFETCH_DISTANCE_THRESHOLD) {
                                lastFetchLocation = loc
                                fetchAmenitiesFast(loc.latitude, loc.longitude, currentAmenityName, isSilent = true)
                            }
                        }

                        // 3. Recalculate
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

    // --- METADATA PARSING ---
    private fun parseMetadata(item: Amenity?) {
        if (item?.tags == null) {
            tvMetadata.visibility = View.GONE
            return
        }
        val tags = item.tags
        val infoList = ArrayList<String>()

        if (tags.has("name")) {
            val name = tags.getString("name")
            val isToilet = currentAmenityName == "Public Toilet" || tags.optString("amenity") == "toilets" || tags.optString("toilets") == "yes"
            if (isToilet && tags.optString("toilets") == "yes") infoList.add("Inside $name")
            else infoList.add(name)
        }

        // Show Operator
        if (tags.has("operator")) infoList.add(tags.getString("operator"))

        var access = tags.optString("toilets:access")
        if (access.isEmpty()) access = tags.optString("access")
        if (access == "customers") infoList.add("⚠ Customers Only")
        else if (access.isNotEmpty()) infoList.add("Access: $access")

        // Fees
        var price = tags.optString("charge")
        if (price.isEmpty()) price = tags.optString("toilets:charge")
        if (price.isNotEmpty()) infoList.add("Fee: $price")
        else {
            var fee = tags.optString("toilets:fee")
            if (fee.isEmpty()) fee = tags.optString("fee")
            if (fee == "no") infoList.add("Free")
            else if (fee == "yes") infoList.add("Fee Required")
        }

        if (tags.optString("amenity") == "recycling" || tags.has("recycling_type")) {
            if (tags.has("recycling_type")) infoList.add("Type: " + tags.getString("recycling_type").replace("_", " ").capitalize())
        }

        if (tags.optString("amenity") == "drinking_water") {
            if (tags.has("drinking_water")) {
                val dw = tags.getString("drinking_water")
                if (dw == "yes") infoList.add("Water: Drinkable")
                else if (dw == "no") infoList.add("Water: Not Drinkable")
            }
        }

        if (tags.optString("emergency") == "defibrillator") {
            var loc = tags.optString("defibrillator:location")
            if (loc.isEmpty()) loc = tags.optString("location")
            if (loc.isNotEmpty()) infoList.add("Location: $loc")
            if (tags.optString("indoor") == "yes") infoList.add("(Indoors)")
        }

        // Helpful notes
        if (tags.has("description")) infoList.add("Note: " + tags.getString("description"))
        if (tags.has("note")) infoList.add("Note: " + tags.getString("note"))
        if (tags.has("shelter")) { if(tags.getString("shelter") == "yes") infoList.add("Has Shelter") }
        if (tags.has("bin")) { if(tags.getString("bin") == "yes") infoList.add("Has Bin") }

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
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
            if ((currentLocation?.speed ?: 0f) <= SPEED_THRESHOLD_MPS) {
                if (magnitude > 75 || magnitude < 20) tvInterference.visibility = View.VISIBLE else tvInterference.visibility = View.GONE
            }
        }
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            if ((currentLocation?.speed ?: 0f) > SPEED_THRESHOLD_MPS) return
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val axisAdjusted = FloatArray(9)
            val rot = windowManager.defaultDisplay.rotation
            var ax = SensorManager.AXIS_X; var ay = SensorManager.AXIS_Y
            when(rot) {
                1 -> { ax = SensorManager.AXIS_Y; ay = SensorManager.AXIS_MINUS_X }
                2 -> { ax = SensorManager.AXIS_MINUS_X; ay = SensorManager.AXIS_MINUS_Y }
                3 -> { ax = SensorManager.AXIS_MINUS_Y; ay = SensorManager.AXIS_X }
            }
            SensorManager.remapCoordinateSystem(rotationMatrix, ax, ay, axisAdjusted)
            SensorManager.getOrientation(axisAdjusted, orientationAngles)
            val azimuth = (Math.toDegrees(orientationAngles[0].toDouble()) + 360).toFloat() % 360
            updateArrowWithHeading(azimuth)

            val statusColor = if (event.accuracy >= 3) Color.parseColor("#32CD32") else if (event.accuracy == 2) Color.parseColor("#FFD700") else Color.RED
            tvAccuracy.text = if (event.accuracy >= 3) "Compass: Good" else "Compass: Weak"
            tvAccuracy.backgroundTintList = ColorStateList.valueOf(statusColor)
        }
    }

    private fun updateArrowWithHeading(userHeading: Float) {
        if (currentLocation != null && destinationAmenity != null) {
            val bearingToTarget = currentLocation!!.bearingTo(destinationAmenity!!.location)
            val normalizedBearing = (bearingToTarget + 360) % 360
            val targetRot = (normalizedBearing - userHeading + 360) % 360

            var diff = targetRot - currentArrowRotation
            while (diff < -180) diff += 360
            while (diff > 180) diff -= 360

            // --- FIX START ---
            // Check if we are moving fast enough to be using GPS mode
            val speed = currentLocation?.speed ?: 0f

            // If driving (>15mph), snap INSTANTLY (1.0f).
            // If walking (Compass), keep smoothing (0.15f) to reduce jitter.
            val smoothingFactor = if (speed > SPEED_THRESHOLD_MPS) 1.0f else 0.15f

            currentArrowRotation += diff * smoothingFactor
            // --- FIX END ---

            ivArrow.rotation = currentArrowRotation
        }
    }

    private fun showCalibrationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Compass Status")
            .setMessage("Wave phone in Figure-8 to calibrate.")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- ANIMATION ---
    private fun startSearchingAnimation(isAi: Boolean) {
        if (isSearching) return
        isSearching = true
        searchJob?.cancel()
        searchJob = CoroutineScope(Dispatchers.Main).launch {
            tvDistance.textSize = 24f
            var dots = ""
            while (isActive) {
                if (isAi) tvDistance.text = "Analyzing$dots"
                else tvDistance.text = "Searching$dots"
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
            if (useMetric) tvDistance.text = "${dist.toInt()} m" else tvDistance.text = "${(dist * 3.28084).toInt()} ft"
            tvMapButton.visibility = View.VISIBLE
        } else {
            tvMapButton.visibility = View.GONE
            tvMetadata.visibility = View.GONE
            if (foundAmenities.isEmpty() && initialSearchDone) {
                tvDistance.textSize = 24f
                tvDistance.text = "No '$currentAmenityName' found"
                tvHint.text = "(Tap to retry)"
            }
        }
    }

    // --- 2. THE SLOW BRAIN (CLOUD AI) ---
    // UPDATED: Now requests 'openai' model from Pollinations for smarter results
    private fun resolveTagWithAI(userQuery: String): String? {
        try {
            val prompt = """
                You are an OpenStreetMap expert. Convert the user search "$userQuery" into a raw OverpassQL filter string.
                
                CRITICAL ONTOLOGY RULES:
                1. SPORTS/RECREATION (Pools, Parks, Pitches, Gyms) -> use "leisure".
                2. STORES/RETAIL (Supermarkets, Clothes, Bakeries) -> use "shop".
                3. SERVICES/FACILITIES (Banks, Toilets, Cafes, Pharmacies) -> use "amenity".
                4. TOURISM (Hotels, Museums, Zoos) -> use "tourism".
                5. EMERGENCY (Hydrants, Defibrillators) -> use "emergency".
                6. DISPENSERS (Poop bags, Tickets) -> use "vending" or "amenity=vending_machine".
                7. BRANDS/NAMES (Starbucks, USPS) -> use ["name"~"Regex",i] or ["brand"~"Regex",i].

                EXAMPLES:
                Input: "Dog poop bags"
                Output: { "filter": "[\"vending\"=\"excrement_bags\"]" }

                Input: "USPS mail"
                Output: { "filter": "[\"amenity\"=\"post_box\"][\"operator\"~\"USPS\",i]" }

                Return ONLY JSON: { "filter": "..." }
            """.trimIndent()

            val encodedPrompt = java.net.URLEncoder.encode(prompt, "UTF-8")

            // UPDATED URL: Requesting 'openai' model explicitly for better logic
            val url = "https://text.pollinations.ai/$encodedPrompt?model=openai"

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val responseText = response.body?.string() ?: return null
                val startIndex = responseText.indexOf("{")
                val endIndex = responseText.lastIndexOf("}")

                if (startIndex != -1 && endIndex != -1) {
                    val cleanJson = responseText.substring(startIndex, endIndex + 1)
                    val json = JSONObject(cleanJson)
                    if (json.has("filter")) {
                        return json.getString("filter")
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    // --- QUERY LOGIC ---
    private fun getQueryString(type: String, customFilter: String?, lat: Double, lon: Double): String {
        val bbox = String.format(Locale.US, "(around:1000, %f, %f)", lat, lon) // 1km radius

        return when (type) {
            "Trash Can" -> """[out:json];(node["amenity"="waste_basket"]$bbox;node["amenity"="waste_disposal"]$bbox;node["bin"="yes"]$bbox;way["amenity"="waste_basket"]$bbox;way["bin"="yes"]$bbox;);out center;"""
            "Defibrillator (AED)" -> """[out:json];(node["emergency"="defibrillator"]$bbox;way["emergency"="defibrillator"]$bbox;);out center;"""
            "Public Toilet" -> """[out:json];(node["amenity"="toilets"]$bbox;way["amenity"="toilets"]$bbox;node["toilets"="yes"]$bbox;way["toilets"="yes"]$bbox;);out center;"""
            "Water Fountain" -> """[out:json];(node["amenity"="drinking_water"]$bbox;way["amenity"="drinking_water"]$bbox;);out center;"""
            "Recycling Bin" -> """[out:json];(node["amenity"="recycling"]$bbox;node["recycling_type"="container"]$bbox;);out center;"""
            "ATM" -> """[out:json];node["amenity"="atm"]$bbox;out center;"""
            "Post Box" -> """[out:json];node["amenity"="post_box"]$bbox;out center;"""
            "Bench" -> """[out:json];node["amenity"="bench"]$bbox;out center;"""
            else -> {
                // HANDLE CUSTOM FILTERS
                if (customFilter != null && customFilter.isNotEmpty()) {
                    """[out:json];(node$customFilter$bbox;way$customFilter$bbox;);out center;"""
                } else { "" }
            }
        }
    }

    // --- MAIN FETCH LOGIC ---
    private fun fetchAmenitiesFast(lat: Double, lon: Double, amenityType: String, isSilent: Boolean) {
        if (!isSilent) {
            isSearching = true
            val needsAi = !hardcodedOptions.contains(amenityType) && !searchDictionary.containsKey(amenityType)
            startSearchingAnimation(isAi = needsAi)
            isErrorState = false
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                var searchFilter: String? = null
                var aiSuccess = true

                // STEP 1: RESOLVE WHAT TO LOOK FOR
                if (hardcodedOptions.contains(amenityType)) {
                    // Use hardcoded complex queries logic in getQueryString
                }
                else if (currentAiFilter.isNotEmpty()) {
                    searchFilter = currentAiFilter
                }
                else if (searchDictionary.containsKey(amenityType)) {
                    val tag = searchDictionary[amenityType]!!
                    val key = tag.substringBefore("=")
                    val value = tag.substringAfter("=")
                    searchFilter = "[\"$key\"=\"$value\"]"
                } else {
                    val aiResult = resolveTagWithAI(amenityType)
                    if (aiResult != null) {
                        searchFilter = aiResult
                        currentAiFilter = aiResult
                    } else {
                        aiSuccess = false
                    }
                }

                // STEP 2: QUERY OVERPASS
                if (aiSuccess) {
                    var attempts = 0
                    var success = false

                    while (attempts < servers.size && !success) {
                        try {
                            val query = getQueryString(amenityType, searchFilter, lat, lon)

                            if (query.isNotEmpty()) {
                                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                                val url = "${servers[attempts]}?data=$encodedQuery"

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
                                                var itemLat = 0.0; var itemLon = 0.0
                                                val tags = if (item.has("tags")) item.getJSONObject("tags") else null
                                                if (item.has("lat")) { itemLat = item.getDouble("lat"); itemLon = item.getDouble("lon") }
                                                else if (item.has("center")) { val c = item.getJSONObject("center"); itemLat = c.getDouble("lat"); itemLon = c.getDouble("lon") }
                                                else continue

                                                val locObj = Location("osm"); locObj.latitude = itemLat; locObj.longitude = itemLon
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
                                } else { throw Exception("HTTP") }
                            }
                        } catch (e: Exception) { attempts++ }
                    }
                    if (!success && !isSilent) {
                        withContext(Dispatchers.Main) {
                            stopSearchingAnimation()
                            isErrorState = true; lastFriendlyError = "Connection Failed"; updateUI()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        stopSearchingAnimation()
                        isErrorState = true; lastFriendlyError = "Search Failed"; updateUI()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    stopSearchingAnimation()
                    isErrorState = true; lastFriendlyError = "Error"; updateUI()
                }
            }
        }
    }
}