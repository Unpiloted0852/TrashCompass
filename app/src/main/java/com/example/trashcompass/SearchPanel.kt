package com.example.trashcompass

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The full-screen search: one box, plain words, results as you type.
 *
 * With nothing typed it is a starting page (what's around me, recent and
 * popular searches, the category browser). While typing it lists, in order:
 *   1. matches from the offline [FeatureIndex] (instant, works without signal),
 *   2. further tags from the live OSM tag database via [TaginfoClient], so
 *      anything mappers have ever tagged can be found, not just what is listed,
 *   3. a last row that searches for places NAMED what was typed (brands,
 *      shop names, streets).
 * Nothing in the normal flow shows OSM notation; typing "key=value" still
 * works for people who know it.
 */
class SearchPanel(
    private val activity: AppCompatActivity,
    private val panel: View,
    private val input: EditText,
    private val list: ListView,
    closeButton: View,
    private val host: Host
) {

    interface Host {
        /** Start hunting for [target]; [label] is its plain name if we know one. */
        fun pick(target: String, label: String?)
        /** Most recent first: target to label. */
        fun recentSearches(): List<Pair<String, String>>
        fun quickPicks(): List<String>
        fun currentLocation(): Location?
        fun formatDistance(meters: Float): String
        fun browseCategories()
        fun openTagDatabase()
    }

    private sealed class Row {
        class Header(val text: String) : Row()
        class Item(val title: String, val subtitle: String, val action: (() -> Unit)?) : Row()
    }

    private var rows: List<Row> = emptyList()
    private var remoteJob: Job? = null
    private var nearbyJob: Job? = null
    private val density = activity.resources.displayMetrics.density

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int): Boolean =
            (rows[position] as? Row.Item)?.action != null

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is Row.Header -> {
                    val tv = (convertView as? TextView) ?: TextView(activity).apply {
                        setTextColor(Color.parseColor("#8B949E"))
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        isAllCaps = true
                        letterSpacing = 0.08f
                        setPadding(dp(20), dp(18), dp(20), dp(6))
                    }
                    tv.text = row.text
                    tv
                }
                is Row.Item -> {
                    val box = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(13), dp(20), dp(13))
                        minimumHeight = dp(56)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(TextView(activity).apply {
                            setTextColor(Color.WHITE)
                            textSize = 17f
                        })
                        addView(TextView(activity).apply {
                            setTextColor(Color.parseColor("#8B949E"))
                            textSize = 13f
                            maxLines = 2
                        })
                    }
                    val title = box.getChildAt(0) as TextView
                    val subtitle = box.getChildAt(1) as TextView
                    title.text = row.title
                    title.alpha = if (row.action != null) 1f else 0.6f
                    subtitle.text = row.subtitle
                    subtitle.visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
                    box
                }
            }
    }

    val isOpen: Boolean get() = panel.visibility == View.VISIBLE

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            (rows.getOrNull(position) as? Row.Item)?.action?.invoke()
        }
        closeButton.setOnClickListener { close() }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isOpen) onQueryChanged(s?.toString().orEmpty())
            }
        })
        // The keyboard's search key takes the top result.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                rows.firstNotNullOfOrNull { (it as? Row.Item)?.action }?.invoke()
                true
            } else {
                false
            }
        }
    }

    fun open() {
        panel.visibility = View.VISIBLE
        input.setText("")
        showHome()
        input.requestFocus()
        keyboard().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    fun close() {
        remoteJob?.cancel()
        nearbyJob?.cancel()
        keyboard().hideSoftInputFromWindow(input.windowToken, 0)
        panel.visibility = View.GONE
    }

    private fun keyboard() =
        activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun dp(value: Int) = (value * density).toInt()

    private fun show(newRows: List<Row>) {
        rows = newRows
        adapter.notifyDataSetChanged()
    }

    private fun pick(target: String, label: String?) {
        close()
        host.pick(target, label)
    }

    // ------------------------------------------------------------------
    // Starting page
    // ------------------------------------------------------------------

    private fun showHome() {
        remoteJob?.cancel()
        nearbyJob?.cancel()
        val out = ArrayList<Row>()
        out.add(
            Row.Item("📍  What's around me?", "List everything mapped within ${NearbyExplorer.RADIUS_METERS} m of you") {
                showNearby()
            }
        )
        val recent = host.recentSearches()
        if (recent.isNotEmpty()) {
            out.add(Row.Header("Recent"))
            for ((target, label) in recent) out.add(Row.Item(label, "") { pick(target, label) })
        }
        out.add(Row.Header("Popular"))
        for (name in host.quickPicks()) out.add(Row.Item(name, "") { pick(name, null) })
        out.add(Row.Header("More ways to look"))
        out.add(Row.Item("📂  Browse by category", "Pick from the built-in catalog") {
            close()
            host.browseCategories()
        })
        out.add(Row.Item("🛠  Advanced: tag database", "Every tag in OpenStreetMap, for people who know OSM") {
            close()
            host.openTagDatabase()
        })
        show(out)
    }

    // ------------------------------------------------------------------
    // Results while typing
    // ------------------------------------------------------------------

    private fun onQueryChanged(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) {
            showHome()
            return
        }
        nearbyJob?.cancel()
        remoteJob?.cancel()

        val local = FeatureIndex.search(query)
        val askOsm = query.length >= 3 && !query.contains("=")
        show(buildResults(query, local, emptyList(), loadingMore = askOsm))
        if (!askOsm) return

        remoteJob = activity.lifecycleScope.launch {
            delay(450) // wait for a pause in typing; keeps the request count polite
            val remote = TaginfoClient.searchTags(query)
            if (input.text.toString().trim() == query) {
                show(buildResults(query, local, remote, loadingMore = false))
            }
        }
    }

    private fun buildResults(
        query: String,
        local: List<Feature>,
        remote: List<TaginfoClient.TagHit>,
        loadingMore: Boolean
    ): List<Row> {
        val out = ArrayList<Row>()

        if (query.contains("=")) { // power-user shortcut, never required
            out.add(Row.Item("Search tag \"$query\"", "OpenStreetMap tag notation") { pick(query, null) })
        }

        for (f in local) {
            out.add(Row.Item(f.label, f.hint) { pick(f.target, f.label) })
        }

        // Tags from the live database that the offline list does not already cover.
        val known = HashSet<String>()
        for (f in local) {
            val spec = TagRepository.mapping[f.target] ?: f.target
            for (clause in spec.split(" OR ")) known.add(clause.trim())
        }
        val extra = ArrayList<Row>()
        for (hit in remote) {
            if (extra.size >= 20) break
            val target = "${hit.key}=${hit.value}"
            if (!known.add(target)) continue
            val label = prettyTagName(hit.value)
            val uses = String.format(Locale.getDefault(), "%,d", hit.count)
            extra.add(Row.Item(label, "${prettyTagName(hit.key)} · $uses mapped worldwide") { pick(target, label) })
        }
        if (extra.isNotEmpty()) {
            out.add(Row.Header("More from OpenStreetMap"))
            out.addAll(extra)
        } else if (loadingMore) {
            out.add(Row.Item("Looking in the OpenStreetMap tag database...", "", null))
        }

        if (!query.contains("=")) {
            out.add(Row.Header("By name"))
            out.add(Row.Item("📍  Places named \"$query\"", "Shops, brands, streets -- anything with this name") {
                pick(query, null)
            })
        }
        return out
    }

    // ------------------------------------------------------------------
    // What's around me?
    // ------------------------------------------------------------------

    private fun showNearby() {
        keyboard().hideSoftInputFromWindow(input.windowToken, 0)
        val header = Row.Header("Around you (within ${NearbyExplorer.RADIUS_METERS} m)")
        val here = host.currentLocation()
        if (here == null) {
            show(listOf(header, Row.Item("Still waiting for your location.", "Tap to try again") { showNearby() }))
            return
        }
        show(listOf(header, Row.Item("Looking around...", "", null)))
        nearbyJob?.cancel()
        nearbyJob = activity.lifecycleScope.launch {
            val groups = try {
                NearbyExplorer.explore(here)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show(listOf(header, Row.Item("Couldn't reach the map servers.", "Tap to try again") { showNearby() }))
                return@launch
            }
            if (groups.isEmpty()) {
                show(listOf(header, Row.Item("Nothing is mapped this close to you.", "Try typing what you are looking for", null)))
                return@launch
            }
            val out = ArrayList<Row>()
            out.add(header)
            for (g in groups) {
                val distance = host.formatDistance(g.nearestMeters)
                val subtitle = if (g.count == 1) "$distance away" else "${g.count} nearby · nearest $distance"
                out.add(Row.Item(g.label, subtitle) { pick(g.target, g.label) })
            }
            show(out)
        }
    }
}
