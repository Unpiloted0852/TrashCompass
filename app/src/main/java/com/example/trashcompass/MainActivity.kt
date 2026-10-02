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
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

class MainActivity : AppCompatActivity(), SensorEventListener {

    // UI
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

    // Preferences
    private lateinit var prefs: SharedPreferences
    private var searchRadiusMeters = 2000
    private var useMetric = true
    private var hapticsEnabled = true

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
    private val refetchDistanceThreshold = 150f
    private val errorRetryDistance = 100f
    private var initialSearchDone = false
    private var isSearching = false
    private var isErrorState = false
    private var lastFriendlyError = ""

    // Jobs
    private var driveAnimJob: Job? = null
    private var searchAnimJob: Job? = null
    private var fetchJob: Job? = null
    private var imageLoadingJob: Job? = null

    private var lastFixTime = 0L
    private val speedThresholdMps = 6.7f // ~24 km/h: switch to GPS heading

    companion object {
        private const val MAX_CACHED_FIX_AGE_MS = 10 * 60 * 1000L // 10 minutes
    }

    // Haptics
    private var lastPulseTime = 0L
    private val alignmentToleranceDeg = 12f

    private var currentAmenityName = "Trash Can"

    private val quickPicks = listOf(
        "Trash Can", "Public Toilet", "Defibrillator (AED)",
        "Water Fountain", "Recycling Bin", "ATM", "Post Box", "Bench"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("TrashCompassPrefs", Context.MODE_PRIVATE)
        searchRadiusMeters = prefs.getInt("search_radius", 2000)
        useMetric = prefs.getBoolean("use_metric", true)
        hapticsEnabled = prefs.getBoolean("haptics", true)
        val savedTarget = prefs.getString("last_target", null)
        if (savedTarget != null && TagRepository.mapping.containsKey(savedTarget)) {
            currentAmenityName = savedTarget
        }

        bindViews()
        tvTitle.text = "Nearest ${displayNameFor(currentAmenityName)}"

        tvMetadata.gravity = Gravity.CENTER
        val padding = (20 * resources.displayMetrics.density).toInt()
        tvMetadata.setPadding(padding, 0, padding, 0)

        setArrowActive(false)
        tvDistance.text = "Waiting for GPS..."

        setUpClickListeners()
        setUpBackHandling()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkPermissions()
    }

    private fun bindViews() {
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
    }

    private fun setUpClickListeners() {
        findViewById<TextView>(R.id.tvLegal).setOnClickListener { showLegalDialog() }
        ivSettings.setOnClickListener { showSettingsDialog() }
        tvSearchBar.setOnClickListener { showFindAnythingDialog() }

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

        tvTitle.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            quickPicks.forEach { popup.menu.add(it) }
            popup.menu.add("🔍 Find Anything...")
            popup.menu.add("📂 Browse Categories...")
            popup.menu.add("🛠 Advanced: Tag Database...")
            popup.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "🔍 Find Anything..." -> showFindAnythingDialog()
                    "📂 Browse Categories..." -> showCategoryBrowser()
                    "🛠 Advanced: Tag Database..." -> showTaginfoKeySearchDialog()
                    else -> setNewSearchTarget(item.title.toString())
                }
                true
            }
            popup.show()
        }
    }

    private fun setUpBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ivFullScreen.visibility == View.VISIBLE) {
                    ivFullScreen.visibility = View.GONE
                    viewDimmer.visibility = View.GONE
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
        input.setTextColor(Color.BLACK)
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
                            setNewSearchTarget("$key=${accumulated[which].value}")
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
        input.setTextColor(Color.BLACK)
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

    /** "waste_basket" -> "Waste Basket" */
    private fun prettyTagName(raw: String): String =
        raw.replace('_', ' ').split(' ').joinToString(" ") { word ->
            word.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
        }

    /**
     * Human-readable name for whatever we're hunting. Catalog entries
     * already have friendly names; raw "key=value" targets get their
     * value (or key, when the value is just yes) prettified.
     */
    private fun displayNameFor(target: String): String {
        if (TagRepository.mapping.containsKey(target)) return target
        if (target.contains("=")) {
            val key = target.substringBefore("=").trim()
            val value = target.substringAfter("=").trim()
            return if (value.isNotEmpty() && value != "yes" && value != "*") prettyTagName(value)
            else prettyTagName(key)
        }
        return target
    }

    /**
     * One search box for everyone. Type plain words ("trash can",
     * "water fountain", "pharmacy") and get, in one list:
     *   1. matching entries from the built-in catalog (instant),
     *   2. matching tags from the live OSM wiki word index (taginfo),
     *      shown by their human name,
     *   3. a fallback that searches the actual names of places nearby.
     * Typing "key=value" directly still works for people who know OSM.
     */
    private fun showFindAnythingDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(padding, padding, padding, 0)

        val input = AutoCompleteTextView(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.hint = "toilet, playground, ice cream..."
        input.setTextColor(Color.BLACK)
        input.setSingleLine(true)
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line,
            TagRepository.mapping.keys.toList().sorted()
        )
        input.setAdapter(adapter)
        input.threshold = 1
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Find Anything")
            .setView(container)
            .setPositiveButton("Search", null) // handled below so we control dismissal
            .setNegativeButton("Cancel", null)
            .create()

        // One code path no matter how the person triggers the search:
        // the Search button, the keyboard's search key, or tapping an
        // autocomplete suggestion (that last one navigates instantly:
        // type "toi", tap "Toilets", the arrow starts pointing).
        fun go() {
            val query = input.text.toString().trim()
            if (query.isEmpty()) return
            dialog.dismiss()
            if (query.contains("=")) { // power-user shortcut, never required
                setNewSearchTarget(query)
                return
            }
            // Exactly matches a catalog entry? No more dialogs -- just go.
            val exact = TagRepository.mapping.keys.firstOrNull { it.equals(query, ignoreCase = true) }
            if (exact != null) setNewSearchTarget(exact) else runFindAnything(query)
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                go()
                true
            } else {
                false
            }
        }
        input.setOnItemClickListener { _, _, _, _ -> go() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { go() }
            input.requestFocus()
        }
        // Pop the keyboard immediately -- typing is the only step.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun runFindAnything(query: String) {
        val queryLower = query.lowercase(Locale.getDefault())
        val queryWords = queryLower.split(Regex("\\s+")).filter { it.isNotEmpty() }

        // Catalog matches: every word the person typed must appear in
        // the entry name (any order), so "water drink" finds
        // "Drinking Water".
        val localMatches = TagRepository.mapping.keys.filter { name ->
            val n = name.lowercase(Locale.getDefault())
            queryWords.all { n.contains(it) }
        }.take(25)

        lifecycleScope.launch {
            // The taginfo keyword search is a substring match against
            // each tag's wiki words, so a multi-word phrase can miss
            // even when every individual word would hit. Search the
            // whole phrase AND each word (plus naive singulars for
            // plural words), then merge; tags matched by more terms
            // rank higher.
            val searchTerms = LinkedHashSet<String>()
            searchTerms.add(queryLower)
            if (queryWords.size > 1) searchTerms.addAll(queryWords)
            for (w in queryWords) {
                if (w.length > 3 && w.endsWith("s")) searchTerms.add(w.dropLast(1))
            }

            val hits = LinkedHashMap<String, TaginfoClient.TagHit>()
            val termMatches = HashMap<String, Int>()
            for (term in searchTerms.take(4)) { // keep the request count polite
                val result: List<TaginfoClient.TagHit> = try {
                    TaginfoClient.searchByKeyword(term)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList() // offline: catalog + name fallback still work
                }
                for (h in result) {
                    val id = "${h.key}=${h.value}"
                    if (!hits.containsKey(id)) hits[id] = h
                    termMatches[id] = (termMatches[id] ?: 0) + 1
                }
            }

            val ranked = hits.values.sortedBy { hit ->
                findScore(hit, queryLower, queryWords, termMatches["${hit.key}=${hit.value}"] ?: 1)
            }

            if (localMatches.isEmpty() && ranked.isEmpty()) {
                // Nothing recognized: fall straight through to hunting
                // places whose NAME matches (works for brand names like
                // "Aldi" or "Starbucks" too).
                Toast.makeText(
                    this@MainActivity,
                    "Searching places named \"$query\" nearby...",
                    Toast.LENGTH_SHORT
                ).show()
                setNewSearchTarget(query)
            } else {
                showFindResults(query, localMatches, ranked)
            }
        }
    }

    /** Lower is better. Exact-name matches beat partial ones; tags hit by more search terms beat one-offs. */
    private fun findScore(
        hit: TaginfoClient.TagHit,
        queryLower: String,
        queryWords: List<String>,
        termMatchCount: Int
    ): Int {
        val name = prettyTagName(if (hit.value.isNotEmpty()) hit.value else hit.key)
            .lowercase(Locale.getDefault())
        val base = when {
            name == queryLower -> 0
            name.contains(queryLower) -> 1
            queryWords.all { name.contains(it) } -> 2
            else -> 3
        }
        return base * 100 - termMatchCount
    }

    private fun showFindResults(
        query: String,
        local: List<String>,
        remote: List<TaginfoClient.TagHit>
    ) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        for (name in local) {
            labels.add(name)
            actions.add { setNewSearchTarget(name) }
        }

        // Same friendly name can come from different contexts (a
        // bakery shop vs. a baker's workshop). Disambiguate duplicates
        // with a human word in parentheses -- never raw notation.
        val localLower = local.map { it.lowercase(Locale.getDefault()) }.toHashSet()
        val nameCounts = HashMap<String, Int>()
        for (hit in remote) {
            if (hit.value.isEmpty()) continue
            val p = prettyTagName(hit.value).lowercase(Locale.getDefault())
            nameCounts[p] = (nameCounts[p] ?: 0) + 1
        }

        var added = 0
        for (hit in remote) {
            if (added >= 30) break
            if (hit.value.isNotEmpty()) {
                val pretty = prettyTagName(hit.value)
                val prettyLower = pretty.lowercase(Locale.getDefault())
                if (prettyLower in localLower) continue // already listed above
                val label = if ((nameCounts[prettyLower] ?: 0) > 1) {
                    "$pretty  (${prettyTagName(hit.key)})"
                } else {
                    pretty
                }
                labels.add(label)
                actions.add { setNewSearchTarget("${hit.key}=${hit.value}") }
            } else {
                labels.add(prettyTagName(hit.key) + "  (see all types...)")
                actions.add { showTaginfoValuesDialog(hit.key, null, 1, ArrayList()) }
            }
            added++
        }

        labels.add("📍 Places NAMED \"$query\" near me")
        actions.add { setNewSearchTarget(query) }

        val items = labels.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Results for \"$query\"")
            .setItems(items) { _, which -> actions[which].invoke() }
            .setNegativeButton("Back") { _, _ -> showFindAnythingDialog() }
            .show()
    }

    private fun setNewSearchTarget(targetName: String) {
        currentAmenityName = targetName
        if (TagRepository.mapping.containsKey(targetName)) {
            prefs.edit().putString("last_target", targetName).apply()
        }
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
    // Dialogs
    // ------------------------------------------------------------------

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
        lblWarning.text = "⚠️ Large radius may be slower."
        lblWarning.setTextColor(Color.RED)
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
        hapticsSwitch.setTextColor(Color.BLACK)
        hapticsSwitch.setPadding(0, padding / 2, 0, 0)
        layout.addView(hapticsSwitch)

        builder.setView(layout)
        builder.setPositiveButton("Save") { _, _ ->
            searchRadiusMeters = 500 + seekBar.progress * 100
            hapticsEnabled = hapticsSwitch.isChecked
            prefs.edit()
                .putInt("search_radius", searchRadiusMeters)
                .putBoolean("haptics", hapticsEnabled)
                .apply()
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
            val dist = loc.distanceTo(lastFetchLocation!!)
            if (dist > refetchDistanceThreshold || (isErrorState && dist > errorRetryDistance)) {
                lastFetchLocation = loc
                fetchAmenities(loc.latitude, loc.longitude, currentAmenityName, silent = true)
            }
        }

        if (foundAmenities.isNotEmpty()) recalculateNearest()
        if (!isSearching) updateUI()
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

        val hasImageTag = tags.optString("image").startsWith("http") ||
                tags.optString("mapillary").isNotEmpty() ||
                tags.optString("wikimedia_commons").isNotEmpty() ||
                tags.optString("wikidata").isNotEmpty()
        if (hasImageTag) {
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
        if (tags.has("toilets") && tags.optString("amenity") != "toilets") {
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

    private fun fetchAmenities(lat: Double, lon: Double, target: String, silent: Boolean) {
        fetchJob?.cancel()
        if (!silent) {
            startSearchingAnimation()
            isErrorState = false
        }
        fetchJob = lifecycleScope.launch {
            val query = OverpassClient.buildQuery(target, lat, lon, searchRadiusMeters)
            val result: List<Amenity>? = try {
                OverpassClient.fetch(query)
            } catch (e: CancellationException) {
                throw e // don't treat our own cancellation as a network error
            } catch (e: Exception) {
                null
            }
            if (target != currentAmenityName) return@launch // stale response
            if (result != null) {
                if (!silent) stopSearchingAnimation()
                isErrorState = false
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
                lastFriendlyError = "Connection Failed.\nTap to retry."
                updateUI()
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
            tvDistance.text = if (useMetric) {
                if (dist >= 1000) String.format(Locale.getDefault(), "%.1f km", dist / 1000)
                else "${dist.toInt()} m"
            } else {
                val feet = dist * 3.28084
                if (feet >= 1000) String.format(Locale.getDefault(), "%.2f mi", feet / 5280)
                else "${feet.toInt()} ft"
            }

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
                val km = searchRadiusMeters / 1000.0
                val msg = if (km >= 1.0) String.format(Locale.getDefault(), "None within %.1f km", km)
                else "None within ${searchRadiusMeters}m"
                tvDistance.text = if (TagRepository.mapping.containsKey(currentAmenityName)) msg
                else "No ${displayNameFor(currentAmenityName)} found"
            }
        }
    }
}
