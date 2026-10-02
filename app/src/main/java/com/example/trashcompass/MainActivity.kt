package com.example.trashcompass

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.util.Log
import android.os.Vibrator
import android.text.InputType
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.material.color.MaterialColors
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

class MainActivity : AppCompatActivity(), SensorEventListener {

    // UI
    private lateinit var tvUpdate: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSearchBar: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvMetadata: TextView
    private lateinit var tvHint: TextView
    private lateinit var ivArrow: ImageView
    private lateinit var ivSettings: ImageView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvMapButton: TextView
    private lateinit var tvSkipButton: TextView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var ivAmenityImage: ImageView
    private lateinit var ivFullScreen: ImageView
    private lateinit var viewDimmer: View
    private lateinit var searchPanel: SearchPanel
    private val updater by lazy { AppUpdater(this) }

    // Preferences
    private lateinit var prefs: SharedPreferences
    private var searchRadiusMeters = 2000
    private var useMetric = true
    private var hapticsEnabled = true
    private var keepHistory = true // recent searches + the target restored at launch

    // Sensors & location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var magneticSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var currentArrowRotation = 0f
    private var lastArrowUpdateTime = 0L
    private var lastMagAccuracy = -1

    // True-north correction. TYPE_ROTATION_VECTOR azimuth is relative
    // to MAGNETIC north, but Location.bearingTo() returns a bearing
    // relative to TRUE north (Android docs). Without adding the local
    // magnetic declination the arrow is systematically wrong -- by
    // 10-15 degrees or more in some parts of the world.
    private var declinationDeg = 0f
    private var declinationLocation: Location? = null

    // State
    private var currentLocation: Location? = null
    private var destinationAmenity: Amenity? = null
    private var foundAmenities: List<Amenity> = emptyList()
    private val skippedKeys = HashSet<String>()
    private var currentRank = 1

    // Fetch logic
    private var lastFetchLocation: Location? = null
    // Never refetch for less movement than this, whatever else is true.
    private val minRefetchMove = 150f
    private val errorRetryDistance = 100f
    private var initialSearchDone = false
    private var isSearching = false
    private var isErrorState = false
    private var lastFriendlyError = ""

    // Jobs
    private var driveAnimJob: Job? = null
    private var searchAnimJob: Job? = null
    private var fetchJob: Job? = null
    private var busyRetryJob: Job? = null
    private var busyRetriesLeft = MAX_BUSY_RETRIES

    // Recent answers, so retrying, widening again or switching back to a
    // target a minute later costs the public servers nothing.
    private class CachedFetch(val center: Location, val timeMs: Long, val results: List<Amenity>) {
        /** Same test as [needsRefetch]: the nearest known target must still be inside the fetched circle. */
        fun stillExactAt(here: Location, radius: Int): Boolean {
            val moved = center.distanceTo(here)
            if (moved > radius * 0.5f) return false
            if (results.isEmpty()) return true
            return results.minOf { here.distanceTo(it.location) } <= radius - moved
        }
    }
    private val fetchCache = object : LinkedHashMap<String, CachedFetch>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedFetch>?) =
            size > MAX_CACHED_FETCHES
    }
    private var imageLoadingJob: Job? = null

    private var lastFixTime = 0L
    private val speedThresholdMps = 6.7f // ~24 km/h: switch to GPS heading

    companion object {
        private const val MAX_CACHED_FIX_AGE_MS = 10 * 60 * 1000L // 10 minutes
        private const val MAX_RADIUS_METERS = 50_000
        private const val MAX_NAME_SEARCH_RADIUS_METERS = 20_000 // name search is a regex scan
        private const val MAX_RECENT = 6
        private const val FIRST_RADIUS_METERS = 500 // first look; small answers are fast and light
        private const val MAX_CACHED_FETCHES = 12
        private const val CACHE_MAX_AGE_MS = 10 * 60 * 1000L
        private const val MAX_BUSY_RETRIES = 2
        private const val BUSY_RETRY_SECONDS = 10
    }

    // Haptics
    private var lastPulseTime = 0L
    private val alignmentToleranceDeg = 12f

    // What we are hunting. [currentAmenityName] is the search target (a
    // catalog entry name, a tag filter, or free text for a name search);
    // [currentLabel] is its plain-language name when the target is not
    // already one.
    private var currentAmenityName = "Trash Can"
    private var currentLabel: String? = null

    // The radius the current results came from. Starts at the Settings
    // radius and widens automatically when nothing is found (see
    // fetchAmenities), so rare things are still found.
    private var activeRadiusMeters = 2000

    private val quickPicks = listOf(
        "Trash Can", "Public Toilet", "Defibrillator (AED)",
        "Water Fountain", "Recycling Bin", "ATM", "Post Box", "Bench"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark in both themes, so keep the system bar icons light.
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("TrashCompassPrefs", Context.MODE_PRIVATE)
        searchRadiusMeters = prefs.getInt("search_radius", 2000)
        useMetric = prefs.getBoolean("use_metric", true)
        hapticsEnabled = prefs.getBoolean("haptics", true)
        keepHistory = prefs.getBoolean("keep_history", true)
        activeRadiusMeters = firstRadius()
        val savedTarget = if (keepHistory) prefs.getString("last_target", null) else null
        if (!savedTarget.isNullOrBlank()) {
            currentAmenityName = savedTarget
            currentLabel = prefs.getString("last_label", null)
        }
        lifecycleScope.launch(Dispatchers.IO) { FeatureIndex.load(applicationContext) }

        bindViews()
        applyWindowInsets()
        tvTitle.text = "Nearest ${displayNameFor(currentAmenityName)}"

        setArrowActive(false)
        tvDistance.text = "Waiting for GPS..."

        setUpClickListeners()
        setUpBackHandling()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkPermissions()
        checkForUpdate()
    }

    /**
     * The window is edge-to-edge, so pad the content by the system bars (and
     * the search screen by the keyboard too) instead of guessing margins.
     */
    private fun applyWindowInsets() {
        val column = findViewById<View>(R.id.mainColumn)
        val panel = findViewById<View>(R.id.searchPanel)
        val overlay = findViewById<View>(R.id.ivFullScreen)
        val columnTop = column.paddingTop
        val columnBottom = column.paddingBottom
        val overlayPad = overlay.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            column.setPadding(
                column.paddingLeft, columnTop + bars.top, column.paddingRight, columnBottom + bars.bottom
            )
            panel.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            overlay.setPadding(overlayPad, overlayPad + bars.top, overlayPad, overlayPad + bars.bottom)
            insets
        }
    }

    /** Once per launch: if GitHub has a newer release, offer it as a tappable pill at the top. */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val release = updater.checkForUpdate() ?: return@launch
            val offer = "Update available: v${release.versionName} -- tap to install"
            var busy = false
            tvUpdate.text = offer
            tvUpdate.visibility = View.VISIBLE
            tvUpdate.setOnClickListener {
                if (busy) return@setOnClickListener
                busy = true
                lifecycleScope.launch {
                    val error = updater.downloadAndInstall(release) { pct ->
                        tvUpdate.text = if (pct < 100) "Downloading update... $pct%" else "Installing update..."
                    }
                    // Only reached if the update did not replace the running app.
                    busy = false
                    tvUpdate.text = offer
                    if (error != null && error != "cancelled") {
                        Toast.makeText(this@MainActivity, "Update failed: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun bindViews() {
        tvUpdate = findViewById(R.id.tvUpdate)
        tvTitle = findViewById(R.id.tvTitle)
        tvSearchBar = findViewById(R.id.tvSearchBar)
        tvDistance = findViewById(R.id.tvDistance)
        tvCount = findViewById(R.id.tvCount)
        tvMetadata = findViewById(R.id.tvMetadata)
        tvHint = findViewById(R.id.tvHint)
        ivArrow = findViewById(R.id.ivArrow)
        ivSettings = findViewById(R.id.ivSettings)
        tvAccuracy = findViewById(R.id.tvAccuracy)
        tvMapButton = findViewById(R.id.tvMapButton)
        tvSkipButton = findViewById(R.id.tvSkipButton)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        ivAmenityImage = findViewById(R.id.ivAmenityImage)
        ivFullScreen = findViewById(R.id.ivFullScreen)
        viewDimmer = findViewById(R.id.viewDimmer)

        searchPanel = SearchPanel(
            this,
            findViewById(R.id.searchPanel),
            findViewById<EditText>(R.id.etSearch),
            findViewById<ListView>(R.id.lvSearch),
            findViewById(R.id.tvSearchClose),
            object : SearchPanel.Host {
                override fun pick(target: String, label: String?) = setNewSearchTarget(target, label)
                override fun recentSearches() = if (keepHistory) loadRecent() else emptyList()
                override fun clearRecentSearches() = clearHistory()
                override fun quickPicks() = quickPicks
                override fun currentLocation() = currentLocation
                override fun formatDistance(meters: Float) = this@MainActivity.formatDistance(meters)
                override fun browseCategories() = showCategoryBrowser()
                override fun openTagDatabase() = showTaginfoKeySearchDialog()
            }
        )
    }

    private fun setUpClickListeners() {
        findViewById<TextView>(R.id.tvLegal).setOnClickListener { showLegalDialog() }
        ivSettings.setOnClickListener { showSettingsDialog() }
        // Both the search bar and the title open the same search screen.
        tvSearchBar.setOnClickListener { searchPanel.open() }
        tvTitle.setOnClickListener { searchPanel.open() }
        tvMetadata.setOnClickListener { showDetailsDialog() }

        val closeFullscreen = View.OnClickListener {
            ivFullScreen.visibility = View.GONE
            viewDimmer.visibility = View.GONE
        }
        ivFullScreen.setOnClickListener(closeFullscreen)
        viewDimmer.setOnClickListener(closeFullscreen)

        tvMapButton.setOnClickListener {
            destinationAmenity?.let { target ->
                val lat = target.location.latitude
                val lon = target.location.longitude
                val label = Uri.encode(displayNameFor(currentAmenityName))
                val uri = "geo:0,0?q=$lat,$lon($label)"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                intent.setPackage("com.google.android.apps.maps")
                if (intent.resolveActivity(packageManager) != null) startActivity(intent)
                else startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
                    )
                )
            }
        }

        // Skip: point at the NEXT-nearest target instead. Handy when
        // the closest one is behind a fence or across a highway.
        // Long-press resets the skips.
        tvSkipButton.setOnClickListener {
            val dest = destinationAmenity ?: return@setOnClickListener
            if (foundAmenities.size <= 1) return@setOnClickListener
            skippedKeys.add(dest.key)
            destinationAmenity = null
            recalculateNearest()
            updateUI()
        }
        tvSkipButton.setOnLongClickListener {
            if (skippedKeys.isNotEmpty()) {
                skippedKeys.clear()
                destinationAmenity = null
                recalculateNearest()
                updateUI()
            }
            true
        }

        val mainAction = {
            if (!hasLocationPermission()) {
                checkPermissions()
            } else if (isErrorState || (foundAmenities.isEmpty() && initialSearchDone && !isSearching)) {
                busyRetriesLeft = MAX_BUSY_RETRIES
                currentLocation?.let {
                    fetchAmenities(it.latitude, it.longitude, currentAmenityName, silent = false)
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

    }

    private fun setUpBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ivFullScreen.visibility == View.VISIBLE) {
                    ivFullScreen.visibility = View.GONE
                    viewDimmer.visibility = View.GONE
                } else if (searchPanel.isOpen) {
                    searchPanel.close()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // Target selection UI
    // ------------------------------------------------------------------

    private fun showCategoryBrowser() {
        val categoryNames = TagRepository.categories.keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Categories")
            .setItems(categoryNames) { _, which ->
                val category = categoryNames[which]
                val items = TagRepository.categories[category]?.keys?.toTypedArray() ?: return@setItems
                AlertDialog.Builder(this)
                    .setTitle(category)
                    .setItems(items) { _, itemIndex -> setNewSearchTarget(items[itemIndex]) }
                    .setNegativeButton("Back") { _, _ -> showCategoryBrowser() }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Live tag browser (Taginfo): every key and value in the OSM
    // database, not just the built-in catalog. Flow:
    //   key search -> pick key -> paged value list -> pick value
    // ------------------------------------------------------------------

    private fun showTaginfoKeySearchDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Tag Database (Advanced)")
        builder.setMessage("Search every tag key in the OSM database, via taginfo.openstreetmap.org.")
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, 0)
        val input = AutoCompleteTextView(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "e.g. recycling, amenity, surface"
        container.addView(input)
        builder.setView(container)
        builder.setPositiveButton("Search") { _, _ ->
            val query = input.text.toString().trim()
            if (query.isEmpty()) return@setPositiveButton
            lifecycleScope.launch {
                try {
                    val keys = TaginfoClient.searchKeys(query)
                    if (keys.isEmpty()) {
                        Toast.makeText(this@MainActivity, "No keys match \"$query\"", Toast.LENGTH_SHORT).show()
                    } else {
                        showTaginfoKeyResults(keys)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Taginfo request failed. Check connection.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showTaginfoKeyResults(keys: List<TaginfoClient.KeyInfo>) {
        val labels = keys.map { "${it.key}   (${formatCount(it.count)} uses)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pick a key")
            .setItems(labels) { _, which ->
                showTaginfoValuesDialog(keys[which].key, null, 1, ArrayList())
            }
            .setNegativeButton("Back") { _, _ -> showTaginfoKeySearchDialog() }
            .show()
    }

    /**
     * Shows one (accumulated) page of values for [key], most-used
     * first. "Load more" pages through until every value is listed --
     * [TaginfoClient.ValuePage.total] tells us when we have them all.
     */
    private fun showTaginfoValuesDialog(
        key: String,
        filter: String?,
        page: Int,
        accumulated: MutableList<TaginfoClient.ValueInfo>
    ) {
        lifecycleScope.launch {
            try {
                val result = TaginfoClient.valuesForKey(key, filter, page)
                accumulated.addAll(result.values)
                if (accumulated.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No values found", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val labels = ArrayList<String>(accumulated.size + 1)
                for (v in accumulated) {
                    var label = "${prettyTagName(v.value)}   (${formatCount(v.count)} mapped)"
                    if (v.description.isNotEmpty()) label += "\n" + v.description.take(90)
                    labels.add(label)
                }
                val hasMore = accumulated.size < result.total
                if (hasMore) labels.add("⬇ Load more  (showing ${accumulated.size} of ${result.total})")
                val items = labels.toTypedArray()
                val builder = AlertDialog.Builder(this@MainActivity)
                    .setTitle(
                        if (filter.isNullOrBlank()) "Types of ${prettyTagName(key)}"
                        else "${prettyTagName(key)}: \"$filter\""
                    )
                    .setItems(items) { _, which ->
                        if (hasMore && which == items.size - 1) {
                            showTaginfoValuesDialog(key, filter, page + 1, accumulated)
                        } else {
                            val value = accumulated[which].value
                            setNewSearchTarget("$key=$value", prettyTagName(value))
                        }
                    }
                    .setNeutralButton("Filter values...") { _, _ -> showTaginfoValueFilterDialog(key) }
                    .setNegativeButton("Cancel", null)
                builder.show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Taginfo request failed. Check connection.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTaginfoValueFilterDialog(key: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Filter values of \"$key\"")
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, 0)
        val input = AutoCompleteTextView(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "substring, e.g. glass"
        container.addView(input)
        builder.setView(container)
        builder.setPositiveButton("Filter") { _, _ ->
            val q = input.text.toString().trim()
            showTaginfoValuesDialog(key, if (q.isEmpty()) null else q, 1, ArrayList())
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun formatCount(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

    // ------------------------------------------------------------------
    // Plain-language naming: normal people should never need to know
    // that a trash can is "amenity=waste_basket".
    // ------------------------------------------------------------------

    /**
     * Human-readable name for whatever we're hunting. Catalog entries
     * already have friendly names; raw "key=value" targets get their
     * value (or key, when the value is just yes) prettified.
     */
    private fun displayNameFor(target: String): String {
        if (TagRepository.mapping.containsKey(target)) return target
        if (target == currentAmenityName) currentLabel?.let { return it }
        if (target.contains("=")) {
            val key = target.substringBefore("=").trim()
            val value = target.substringAfter("=").trim()
            return if (value.isNotEmpty() && value != "yes" && value != "*") prettyTagName(value)
            else prettyTagName(key)
        }
        return target
    }

    private fun setNewSearchTarget(targetName: String, label: String? = null) {
        // Re-running the same target (retry, changed radius) keeps its label.
        currentLabel = label ?: if (targetName == currentAmenityName) currentLabel else null
        currentAmenityName = targetName
        if (keepHistory) {
            prefs.edit()
                .putString("last_target", targetName)
                .putString("last_label", currentLabel)
                .apply()
            rememberRecent(targetName, displayNameFor(targetName))
        }
        activeRadiusMeters = firstRadius()
        busyRetryJob?.cancel()
        busyRetriesLeft = MAX_BUSY_RETRIES
        tvTitle.text = "Nearest ${displayNameFor(currentAmenityName)}"
        foundAmenities = emptyList()
        skippedKeys.clear()
        destinationAmenity = null
        lastFetchLocation = null
        initialSearchDone = false
        tvDistance.text = "Searching..."
        tvCount.visibility = View.GONE
        tvMetadata.visibility = View.GONE
        ivAmenityImage.visibility = View.GONE
        ivFullScreen.visibility = View.GONE
        viewDimmer.visibility = View.GONE
        tvMapButton.visibility = View.GONE
        tvSkipButton.visibility = View.GONE
        setArrowActive(false)
        currentLocation?.let {
            lastFetchLocation = it
            initialSearchDone = true
            fetchAmenities(it.latitude, it.longitude, currentAmenityName, silent = false)
        }
    }

    // ------------------------------------------------------------------
    // Recent searches (shown on the search screen's starting page)
    // ------------------------------------------------------------------

    private fun loadRecent(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val arr = JSONArray(prefs.getString("recent", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(o.getString("t") to o.getString("l"))
            }
        } catch (e: Exception) {
            // corrupt entry: start over with an empty list
        }
        return out
    }

    private fun rememberRecent(target: String, label: String) {
        val list = ArrayList(loadRecent().filter { it.first != target })
        list.add(0, target to label)
        val arr = JSONArray()
        for ((t, l) in list.take(MAX_RECENT)) arr.put(JSONObject().put("t", t).put("l", l))
        prefs.edit().putString("recent", arr.toString()).apply()
    }

    /** Forgets every search the app has stored: the recent list and the target restored at launch. */
    private fun clearHistory() {
        prefs.edit().remove("recent").remove("last_target").remove("last_label").apply()
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    /** The details line is capped at a few lines on the main screen; tapping it shows everything. */
    private fun showDetailsDialog() {
        val text = tvMetadata.text
        if (text.isNullOrEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(displayNameFor(currentAmenityName))
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
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
        layout.addView(lblRadius)

        val lblWarning = TextView(this)
        lblWarning.text = "⚠️ Large radius may be slower."
        // The theme's error colour stays readable on both the light and the dark dialog.
        lblWarning.setTextColor(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorError, Color.RED)
        )
        lblWarning.textSize = 12f
        lblWarning.setPadding(0, 10, 0, 10)
        lblWarning.visibility = if (searchRadiusMeters > 3000) View.VISIBLE else View.GONE
        layout.addView(lblWarning)

        // 500 m .. 10 km in 100 m steps
        val seekBar = SeekBar(this)
        seekBar.max = 95
        seekBar.progress = ((searchRadiusMeters - 500) / 100).coerceIn(0, 95)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val meters = 500 + progress * 100
                lblRadius.text = "Search Radius: ${meters}m"
                lblWarning.visibility = if (meters > 3000) View.VISIBLE else View.GONE
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        layout.addView(seekBar)

        val hapticsSwitch = Switch(this)
        hapticsSwitch.text = "Vibrate when pointing at target"
        hapticsSwitch.isChecked = hapticsEnabled
        hapticsSwitch.setPadding(0, padding / 2, 0, 0)
        layout.addView(hapticsSwitch)

        val historySwitch = Switch(this)
        historySwitch.text = "Remember my searches"
        historySwitch.isChecked = keepHistory
        historySwitch.setPadding(0, padding / 2, 0, 0)
        layout.addView(historySwitch)

        val lblHistory = TextView(this)
        lblHistory.text = "Recent searches and the last thing you looked for are kept on this " +
                "phone only. Turning this off also deletes what is stored."
        lblHistory.textSize = 12f
        lblHistory.alpha = 0.75f
        layout.addView(lblHistory)

        builder.setView(layout)
        builder.setPositiveButton("Save") { _, _ ->
            searchRadiusMeters = 500 + seekBar.progress * 100
            hapticsEnabled = hapticsSwitch.isChecked
            keepHistory = historySwitch.isChecked
            prefs.edit()
                .putInt("search_radius", searchRadiusMeters)
                .putBoolean("haptics", hapticsEnabled)
                .putBoolean("keep_history", keepHistory)
                .apply()
            if (!keepHistory) clearHistory()
            if (currentLocation != null) setNewSearchTarget(currentAmenityName)
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showLegalDialog() {
        val message = "DATA DISCLAIMER:\nUses OpenStreetMap data. Coverage depends on " +
                "what volunteers have mapped in your area.\n\nSAFETY:\nDo not use for " +
                "emergencies.\n\nLICENSE:\nMap data © OpenStreetMap contributors, ODbL."
        AlertDialog.Builder(this).setTitle("Legal & Safety")
            .setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun showCalibrationDialog() {
        AlertDialog.Builder(this).setTitle("Compass Status")
            .setMessage("To calibrate:\nWave phone in a Figure-8 motion.")
            .setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------------
    // Permissions & location
    // ------------------------------------------------------------------

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100
            )
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                tvDistance.textSize = 24f
                tvDistance.text = "Location permission needed.\nTap to grant."
            }
        }
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission() || locationCallback != null) return
        try {
            primeWithLastLocation()
            val locationRequest =
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(p0: LocationResult) {
                    p0.lastLocation?.let { onNewLocation(it) }
                }
            }
            locationCallback = callback
            fusedLocationClient.requestLocationUpdates(locationRequest, callback, mainLooper)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    /**
     * Kicks off the first search with the fused provider's cached
     * last-known location instead of waiting for a fresh GPS fix
     * (which can take several seconds, especially indoors). If the
     * first real fix lands more than 150 m away, the existing refetch
     * logic corrects the results automatically.
     *
     * Fixes older than [MAX_CACHED_FIX_AGE_MS] are ignored -- age is
     * measured with elapsedRealtimeNanos, which is monotonic and not
     * affected by wall-clock changes.
     */
    private fun primeWithLastLocation() {
        if (!hasLocationPermission()) return
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc == null || currentLocation != null) return@addOnSuccessListener
                val ageMs =
                    (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
                if (ageMs in 0 until MAX_CACHED_FIX_AGE_MS) {
                    onNewLocation(loc)
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun onNewLocation(loc: Location) {
        currentLocation = loc
        lastFixTime = System.currentTimeMillis()
        updateDeclination(loc)

        val speed = loc.speed
        if (speed > speedThresholdMps && loc.hasBearing()) {
            // Driving: the GPS track bearing IS true-north referenced,
            // so no declination correction is needed here.
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
            fetchAmenities(loc.latitude, loc.longitude, currentAmenityName, silent = false)
        } else if (lastFetchLocation != null && !isSearching) {
            if (needsRefetch(loc, loc.distanceTo(lastFetchLocation!!))) {
                lastFetchLocation = loc
                fetchAmenities(loc.latitude, loc.longitude, currentAmenityName, silent = true)
            }
        }

        if (foundAmenities.isNotEmpty()) recalculateNearest()
        if (!isSearching) updateUI()
    }

    /**
     * Whether the results we hold can no longer be trusted after moving
     * [moved] metres from where they were fetched. They are complete only
     * inside the fetched circle, so there is nothing to gain from asking
     * again until either we are close to its edge, or the nearest target we
     * know of is farther away than the edge (a nearer one could then lie
     * just outside). Until then the old answer is exact and costs nothing.
     */
    private fun needsRefetch(here: Location, moved: Float): Boolean {
        if (isErrorState) return moved > errorRetryDistance
        if (moved < minRefetchMove) return false
        val radius = activeRadiusMeters.toFloat()
        if (moved > radius * 0.8f) return true
        if (foundAmenities.isEmpty()) return moved > radius * 0.5f
        val nearest = foundAmenities.minOf { here.distanceTo(it.location) }
        return nearest > radius - moved
    }

    /** Recompute magnetic declination when we've moved far enough. */
    private fun updateDeclination(loc: Location) {
        val prev = declinationLocation
        if (prev == null || loc.distanceTo(prev) > 10000f) {
            val field = GeomagneticField(
                loc.latitude.toFloat(),
                loc.longitude.toFloat(),
                loc.altitude.toFloat(),
                System.currentTimeMillis()
            )
            declinationDeg = field.declination
            declinationLocation = loc
        }
    }

    // ------------------------------------------------------------------
    // Nearest-target logic
    // ------------------------------------------------------------------

    private fun recalculateNearest() {
        val here = currentLocation ?: return
        if (foundAmenities.isEmpty()) return

        val sorted = foundAmenities.sortedBy { here.distanceTo(it.location) }
        var best: Amenity? = null
        var rank = 1
        for ((index, item) in sorted.withIndex()) {
            if (!skippedKeys.contains(item.key)) {
                best = item
                rank = index + 1
                break
            }
        }
        if (best == null) {
            // Everything was skipped -- start over from the nearest.
            skippedKeys.clear()
            best = sorted.first()
            rank = 1
        }
        currentRank = rank
        if (best.key != destinationAmenity?.key) {
            destinationAmenity = best
            parseMetadata(best)
            updateUI()
        }
    }

    // ------------------------------------------------------------------
    // Metadata & images
    // ------------------------------------------------------------------

    private fun parseMetadata(item: Amenity?) {
        imageLoadingJob?.cancel()
        ivAmenityImage.setImageDrawable(null)
        ivAmenityImage.visibility = View.GONE
        ivAmenityImage.setOnClickListener(null)
        ivFullScreen.visibility = View.GONE
        viewDimmer.visibility = View.GONE

        val tags = item?.tags
        if (tags == null) {
            tvMetadata.visibility = View.GONE
            return
        }

        if (ImageResolver.hasPhotoTags(tags)) {
            imageLoadingJob = lifecycleScope.launch {
                val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                    ImageResolver.loadImageForTags(tags)
                }
                if (bitmap != null && destinationAmenity?.key == item.key) {
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

        val infoList = ArrayList<String>()
        val name = tags.optString("name")
        // "Inside X" only makes sense when hunting toilets and the match is a
        // building that has some (a museum is not "inside" itself).
        val huntingToilets = (TagRepository.mapping[currentAmenityName] ?: currentAmenityName).contains("toilets")
        if (huntingToilets && tags.has("toilets") && tags.optString("amenity") != "toilets") {
            val building = tags.optString("name", "Building")
            infoList.add("Inside $building")
        } else if (tags.optString("bin") == "yes" || tags.optString("rubbish") == "yes" ||
            tags.optString("waste_basket") == "yes"
        ) {
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

        // Type-specific extras
        if (tags.has("recycling_type")) {
            infoList.add("Type: " + tags.optString("recycling_type").replace("_", " ").cap())
        }
        if (tags.has("drinking_water")) {
            when (tags.optString("drinking_water")) {
                "yes" -> infoList.add("Water: Drinkable")
                "no" -> infoList.add("Water: Not Drinkable")
            }
        }
        if (tags.optString("emergency") == "defibrillator") {
            var loc = tags.optString("defibrillator:location")
            if (loc.isEmpty()) loc = tags.optString("location")
            if (loc.isNotEmpty()) infoList.add("Location: $loc")
            if (tags.optString("indoor") == "yes") infoList.add("(Indoors)")
        }
        if (tags.has("opening_hours")) infoList.add("Hours: " + tags.optString("opening_hours"))
        if (tags.has("operator")) infoList.add("Operator: " + tags.optString("operator"))
        if (tags.has("description")) infoList.add(tags.optString("description"))
        if (tags.optString("wheelchair") == "yes") infoList.add("♿ Accessible")

        if (infoList.isNotEmpty()) {
            tvMetadata.text = infoList.joinToString("\n")
            tvMetadata.visibility = View.VISIBLE
        } else {
            tvMetadata.visibility = View.GONE
        }
    }

    private fun String.cap() =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

    // ------------------------------------------------------------------
    // Sensors & arrow
    // ------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magneticSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopDrivingAnimation()
        stopLocationUpdates()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            lastMagAccuracy = event.accuracy
            return
        }
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val currentSpeed = currentLocation?.speed ?: 0f
        if (currentSpeed > speedThresholdMps) return // GPS heading in charge

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val rotation = currentDisplayRotation()
        var axisX = SensorManager.AXIS_X
        var axisY = SensorManager.AXIS_Y
        when (rotation) {
            Surface.ROTATION_90 -> { axisX = SensorManager.AXIS_Y; axisY = SensorManager.AXIS_MINUS_X }
            Surface.ROTATION_180 -> { axisX = SensorManager.AXIS_MINUS_X; axisY = SensorManager.AXIS_MINUS_Y }
            Surface.ROTATION_270 -> { axisX = SensorManager.AXIS_MINUS_Y; axisY = SensorManager.AXIS_X }
        }
        if (SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)) {
            SensorManager.getOrientation(remappedMatrix, orientationAngles)
        } else {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
        }

        val magneticAzimuth = (Math.toDegrees(orientationAngles[0].toDouble()) + 360).toFloat() % 360
        val trueAzimuth = (magneticAzimuth + declinationDeg + 360) % 360
        currentLocation?.let { updateArrowUI(it, trueAzimuth) }

        updateCompassStatus(event)
    }

    private fun currentDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    private fun updateCompassStatus(event: SensorEvent) {
        var statusText = "Compass: Good"
        var statusColor = Color.parseColor("#32CD32")
        if (event.values.size > 4 && event.values[4] != -1f) {
            val accuracyRad = event.values[4]
            when {
                accuracyRad < 0.35 -> { statusText = "Compass: Good"; statusColor = Color.parseColor("#32CD32") }
                accuracyRad < 0.8 -> { statusText = "Compass: Fair"; statusColor = Color.parseColor("#FFD700") }
                else -> { statusText = "Compass: Poor"; statusColor = Color.parseColor("#FF4444") }
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

    /**
     * Rotates the arrow toward the target with time-based exponential
     * smoothing (frame-rate independent, unlike a fixed 0.15 factor),
     * and fires a short haptic pulse when the arrow lines up.
     */
    private fun updateArrowUI(userLoc: Location, userHeading: Float) {
        val dest = destinationAmenity
        if (dest == null) {
            setArrowActive(false)
            return
        }
        setArrowActive(true)
        val bearingToTarget = (userLoc.bearingTo(dest.location) + 360) % 360
        val targetRot = (bearingToTarget - userHeading + 360) % 360

        var diff = targetRot - currentArrowRotation
        while (diff < -180) diff += 360
        while (diff > 180) diff -= 360

        val now = SystemClock.elapsedRealtime()
        val dtSeconds = if (lastArrowUpdateTime == 0L) 0.016f
        else ((now - lastArrowUpdateTime).coerceAtMost(100L)) / 1000f
        lastArrowUpdateTime = now
        val alpha = 1f - exp(-dtSeconds * 10f)
        currentArrowRotation += diff * alpha
        ivArrow.rotation = currentArrowRotation

        // Haptic "you're pointing the right way" pulse
        var alignment = targetRot
        if (alignment > 180) alignment -= 360
        if (abs(alignment) < alignmentToleranceDeg) alignmentPulse()
    }

    private fun alignmentPulse() {
        if (!hapticsEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPulseTime < 1500) return
        lastPulseTime = now
        try {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, 80))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30)
            }
        } catch (e: Exception) {
            // no vibrator -- fine
        }
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

    // ------------------------------------------------------------------
    // Driving mode: dead-reckon between 1 Hz GPS fixes for a smooth arrow
    // ------------------------------------------------------------------

    private fun startDrivingAnimation() {
        if (driveAnimJob?.isActive == true) return
        driveAnimJob = lifecycleScope.launch {
            while (isActive) {
                val loc = currentLocation
                if (loc != null && loc.speed > speedThresholdMps && loc.hasBearing() && lastFixTime > 0L) {
                    val timeDelta = System.currentTimeMillis() - lastFixTime
                    if (timeDelta < 2000) {
                        val predicted = projectLocation(loc, loc.speed, loc.bearing, timeDelta)
                        updateArrowUI(predicted, loc.bearing)
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
        val newLatRad = kotlin.math.asin(
            kotlin.math.sin(latRad) * kotlin.math.cos(angularDistance) +
                    kotlin.math.cos(latRad) * kotlin.math.sin(angularDistance) * kotlin.math.cos(bearingRad)
        )
        val newLonRad = lonRad + kotlin.math.atan2(
            kotlin.math.sin(bearingRad) * kotlin.math.sin(angularDistance) * kotlin.math.cos(latRad),
            kotlin.math.cos(angularDistance) - kotlin.math.sin(latRad) * kotlin.math.sin(newLatRad)
        )
        val newLoc = Location("predicted")
        newLoc.latitude = Math.toDegrees(newLatRad)
        newLoc.longitude = Math.toDegrees(newLonRad)
        return newLoc
    }

    // ------------------------------------------------------------------
    // Searching UI + fetch
    // ------------------------------------------------------------------

    private fun startSearchingAnimation() {
        if (isSearching) return
        isSearching = true
        loadingSpinner.visibility = View.VISIBLE
        setArrowActive(false)
        searchAnimJob?.cancel()
        searchAnimJob = lifecycleScope.launch {
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
        searchAnimJob?.cancel()
    }

    /**
     * The next, wider radius to try after [radius] came back empty, or null
     * once the limit is reached. Free-text name searches stop earlier because
     * they make the server scan every name in the area.
     */
    private fun widerRadius(radius: Int, target: String): Int? {
        if (radius < searchRadiusMeters) return searchRadiusMeters
        val isNameSearch = !TagRepository.mapping.containsKey(target) && !target.contains("=")
        val limit = if (isNameSearch) MAX_NAME_SEARCH_RADIUS_METERS else MAX_RADIUS_METERS
        return if (radius >= limit) null else (radius * 5).coerceAtMost(limit)
    }

    /**
     * Every search starts close by: in a town the nearest bin or bench is
     * almost always within a few hundred metres, and asking for 500 m
     * instead of 2 km returns a sixteenth of the data. Only if that is
     * empty does the search step out to the Settings radius and beyond.
     */
    private fun firstRadius(): Int = minOf(searchRadiusMeters, FIRST_RADIUS_METERS)

    private fun formatRadius(meters: Int): String =
        if (meters >= 1000) String.format(Locale.getDefault(), "%.0f km", meters / 1000.0)
        else "$meters m"

    private fun fetchAmenities(
        lat: Double, lon: Double, target: String, silent: Boolean,
        radius: Int = if (silent) activeRadiusMeters else firstRadius()
    ) {
        fetchJob?.cancel()
        if (!silent) {
            busyRetryJob?.cancel()
            startSearchingAnimation()
            isErrorState = false
        }
        val here = Location("fetch").apply { latitude = lat; longitude = lon }
        fetchJob = lifecycleScope.launch {
            // An answer fetched moments ago close to here is still exact for
            // the middle of its circle: reuse it instead of asking again.
            val cacheKey = "$target|$radius"
            val cached = fetchCache[cacheKey]?.takeIf {
                SystemClock.elapsedRealtime() - it.timeMs < CACHE_MAX_AGE_MS && it.stillExactAt(here, radius)
            }
            var center = here
            var busy = false
            val result: List<Amenity>? = if (cached != null) {
                Log.d("TrashCompass", "reusing cached answer for $cacheKey")
                center = cached.center
                cached.results
            } else {
                Log.d("TrashCompass", "asking the map servers for $cacheKey" + if (silent) " (refresh)" else "")
                try {
                    OverpassClient.fetch(OverpassClient.buildQuery(target, lat, lon, radius)).also {
                        fetchCache[cacheKey] = CachedFetch(here, SystemClock.elapsedRealtime(), it)
                    }
                } catch (e: CancellationException) {
                    throw e // don't treat our own cancellation as a network error
                } catch (e: OverpassClient.ServerBusyException) {
                    busy = true
                    null
                } catch (e: Exception) {
                    null
                }
            }
            if (target != currentAmenityName) return@launch // stale response
            if (result != null) {
                if (result.isEmpty()) {
                    // Nothing this close: look farther before giving up.
                    val wider = widerRadius(radius, target)
                    if (wider != null) {
                        if (!silent) {
                            tvCount.text = "None within ${formatRadius(radius)} -- looking up to ${formatRadius(wider)}"
                            tvCount.visibility = View.VISIBLE
                        }
                        fetchAmenities(lat, lon, target, silent, radius = wider)
                        return@launch
                    }
                }
                if (!silent) stopSearchingAnimation()
                isErrorState = false
                busyRetryJob?.cancel()
                busyRetriesLeft = MAX_BUSY_RETRIES
                activeRadiusMeters = radius
                lastFetchLocation = center
                foundAmenities = result
                if (foundAmenities.isEmpty()) {
                    destinationAmenity = null
                } else {
                    // Drop skips that no longer exist in the new result set
                    val validKeys = foundAmenities.map { it.key }.toHashSet()
                    skippedKeys.retainAll(validKeys)
                    recalculateNearest()
                }
                updateUI()
            } else if (!silent) {
                stopSearchingAnimation()
                isErrorState = true
                if (busy && busyRetriesLeft > 0) {
                    busyRetriesLeft--
                    retryWhenServersRecover(lat, lon, target, radius)
                } else {
                    lastFriendlyError =
                        if (busy) "Map servers are busy.\nTap to try again."
                        else "Can't reach the map servers.\nTap to retry."
                    updateUI()
                }
            }
        }
    }

    /**
     * The public map servers were reachable but overloaded or rate limiting
     * us. That usually clears within seconds, so count down and ask again
     * rather than leaving the person at a dead end.
     */
    private fun retryWhenServersRecover(lat: Double, lon: Double, target: String, radius: Int) {
        busyRetryJob?.cancel()
        busyRetryJob = lifecycleScope.launch {
            for (seconds in BUSY_RETRY_SECONDS downTo 1) {
                lastFriendlyError = "Map servers are busy.\nTrying again in $seconds s..."
                updateUI()
                delay(1000)
            }
            if (target == currentAmenityName) {
                fetchAmenities(lat, lon, target, silent = false, radius = radius)
            }
        }
    }

    // ------------------------------------------------------------------
    // Main UI refresh
    // ------------------------------------------------------------------

    private fun updateUI() {
        if (isSearching) return
        if (isErrorState) {
            tvDistance.textSize = 24f
            tvDistance.text = lastFriendlyError
            tvCount.visibility = View.GONE
            tvSkipButton.visibility = View.GONE
            setArrowActive(false)
            return
        }
        val here = currentLocation
        val dest = destinationAmenity
        if (here != null && dest != null) {
            tvDistance.textSize = 64f
            val dist = here.distanceTo(dest.location)
            tvDistance.text = formatDistance(dist)

            val total = foundAmenities.size
            if (total > 1) {
                tvCount.text = if (currentRank == 1) "nearest of $total found"
                else "#$currentRank of $total found"
                tvCount.visibility = View.VISIBLE
                tvSkipButton.visibility = View.VISIBLE
            } else {
                tvCount.visibility = View.GONE
                tvSkipButton.visibility = View.GONE
            }
            tvMapButton.visibility = View.VISIBLE
            setArrowActive(true)
        } else {
            tvMapButton.visibility = View.GONE
            tvSkipButton.visibility = View.GONE
            tvCount.visibility = View.GONE
            tvMetadata.visibility = View.GONE
            ivAmenityImage.visibility = View.GONE
            setArrowActive(false)
            if (foundAmenities.isEmpty() && initialSearchDone) {
                tvDistance.textSize = 24f
                tvDistance.text = "None within ${formatRadius(activeRadiusMeters)}.\nTap to search again."
            }
        }
    }

    private fun formatDistance(meters: Float): String =
        if (useMetric) {
            if (meters >= 1000) String.format(Locale.getDefault(), "%.1f km", meters / 1000)
            else "${meters.toInt()} m"
        } else {
            val feet = meters * 3.28084
            if (feet >= 1000) String.format(Locale.getDefault(), "%.2f mi", feet / 5280)
            else "${feet.toInt()} ft"
        }
}
