# TrashCompass v3.1 — What Changed

## Bug fixes

1. **Wikidata photos never loaded.** The P18 (image) claim parser called
   `optJSONObject("value")`, but in Wikidata's entity JSON the
   `datavalue.value` for a commonsMedia claim is a plain string, so the
   lookup always returned null. Fixed in `ImageResolver.resolveWikidata()`.

2. **The arrow ignored magnetic declination.** `TYPE_ROTATION_VECTOR`
   gives a heading relative to *magnetic* north, while
   `Location.bearingTo()` returns a bearing relative to *true* north
   (per the Android documentation of both APIs). The app compared the
   two directly, so the arrow was systematically off by the local
   declination — 10–15° or more in parts of North America and Europe.
   Now corrected with `GeomagneticField.getDeclination()`, recomputed
   whenever you move more than 10 km. (GPS-heading driving mode was
   already true-north and is unchanged.)

3. **Leaked coroutines and location updates.** Network and animation
   jobs ran in `CoroutineScope(Dispatchers.IO)` / `(Dispatchers.Main)`,
   which outlive the Activity — after rotating the screen or leaving the
   app, old jobs kept running and touched destroyed views. Location
   updates were requested once and never removed. Everything now uses
   `lifecycleScope`, and location updates start in `onResume` and stop
   in `onPause` (also saves battery while backgrounded).

4. **OSM ID collisions.** Nodes, ways, and relations have independent ID
   number spaces, but the app compared bare numeric IDs, so a node and a
   way with the same number could be confused. Identity is now
   `"type/id"`.

5. **Custom search could produce broken queries.** Free-text search was
   inserted into an Overpass regex unescaped; typing `(`, `?`, `*`, or a
   quote produced a malformed query and a misleading "Connection Failed".
   Input is now escaped.

6. **Stale search results could overwrite a new search.** If you changed
   targets while a slow request was in flight, the old response could
   land afterwards. Responses for a no-longer-current target are dropped,
   and a new fetch cancels the previous one.

7. **Permission denial dead-ended the app.** Denying location left a
   silent "Waiting for GPS..." forever. There's now a message and
   tap-to-retry.

## Speed

- **Instant first search from cached location (v3.1).** The app used to
  wait for a fresh GPS fix before searching — often several seconds,
  longer indoors. It now starts the search immediately using the fused
  provider's last-known location (if it's under 10 minutes old, measured
  with the monotonic elapsed-realtime clock), and the existing
  "refetch when moved >150 m" logic corrects the results once a real fix
  arrives.
- **Hedged mirror requests (v3.1).** Mirrors were tried one at a time,
  so one slow server cost its full timeout before the next was tried.
  Now, if the first mirror hasn't answered within 2.5 s, the next one is
  started in parallel; the first success wins and the rest are
  cancelled. Your wait is bounded by roughly the fastest responding
  mirror instead of the slowest — while a healthy first mirror still
  results in exactly one request, so this stays polite to the public
  Overpass servers.
- **POST + `[timeout:25]`.** Queries were sent by GET (URL-length limits)
  with no timeout setting, so the Overpass server default (180 s) applied
  and a struggling mirror could stall the app for minutes. Requests are
  now POSTed with a 25 s server-side timeout, an 8 s connect timeout, and
  a 32 s overall call timeout, with mirrors shuffled to spread load.
- **`nwr` queries.** One combined node+way+relation clause per filter
  instead of separate node/way clauses — less server work, and the app
  now finds *relations* (multipolygon parks, hospital grounds, big
  buildings) that v2.x silently missed.
- **Image downsampling.** Photos are decoded with `inSampleSize` so the
  longest side is ≤ 1280 px instead of decoding full-resolution images
  into memory (less jank, no more OutOfMemory risk on older phones).

## New features

- **Skip button.** When several targets are found, "SKIP →" points you at
  the next-nearest one (the closest bin is sometimes behind a fence).
  Long-press to reset. A counter shows e.g. "#2 of 14 found".
- **Haptic lock-on.** A short vibration pulse when the arrow points at
  the target (±12°, at most every 1.5 s). The VIBRATE permission was
  declared in v2.x but never used. Toggle in Settings.
- **Category browser.** "📂 Browse All Categories…" in the title menu
  opens a two-level picker over the whole catalog, since it's now far too
  big for one flat menu.
- **Much bigger catalog.** New categories and entries: Healthcare
  (`healthcare=*`), Public Transit (`public_transport=*`, bus stops),
  Streets (crossings, traffic signals, speed cameras, rest areas,
  trailheads), Land Use (cemetery, landfill, allotments…), Places
  (city/town/village…), more recycling fractions (cardboard, glass
  bottles, green waste, scrap metal, shoes, plastic bottles), dog waste
  bins, more sports, more vending machines, and assorted amenities
  (device charging stations, compressed air, smoking areas…). Tag names
  follow the OSM wiki Map Features pages; what you actually find still
  depends on local mapping coverage.
- **Multi-tag search syntax.** Catalog entries (and custom searches) can
  combine filters: `a=b OR c=d` searches both; `a=b&c=d` requires both
  (e.g. "Recycling Centre" = `amenity=recycling&recycling_type=centre`).
- **Remembers your last target** across launches, plus opening hours and
  operator shown in the info panel.

## Structure

`MainActivity.kt` (1,906 lines) was split into `MainActivity.kt`,
`OverpassClient.kt`, `ImageResolver.kt`, `TagRepository.kt`, and
`Amenity.kt`. Version bumped to 3.0 (versionCode 3). Dependencies:
okhttp 4.12.0, coroutines 1.8.1, play-services-location 21.3.0, and
lifecycle-runtime-ktx 2.8.7 (new — required for `lifecycleScope`).

> **Note:** this code was not compiled in the environment it was written
> in (no Android SDK access there). Open the project in Android Studio,
> let Gradle sync, and build — if anything trips, the errors should be
> trivial to fix.

## v3.2 — Every OSM tag, live

OSM tagging is free-form: any `key=value` pair is allowed, so **no
built-in list can ever contain "all" OSM features** — as of 2026 the
database contains over 100,000 distinct keys (source: OSM wiki, "Tags").
The complete, continuously updated index of everything that actually
exists in the database is the OSM project's own Taginfo service.

New: **"🌐 All OSM Tags (Live)"** in the title menu. Search any key
(matches ranked by real-world usage count), pick it, then page through
*every* value ever used with it — with usage counts and wiki
descriptions where available — filter values by substring, tap one, and
the compass hunts for it. This uses the documented Taginfo API
(taginfo.openstreetmap.org; endpoints verified against the taginfo
source code), which per its usage policy is intended for OSM community
use with a proper User-Agent — both respected here. The offline catalog
remains for quick access, and typing `key=value` directly in Custom
Search still works too.

## v3.3 — No OSM notation required

Feedback: the tag browser exposed raw OSM notation, which regular
people shouldn't need. Changes:

- **"🔍 Find Anything"** replaces the notation-centric custom search.
  One box, plain words. Typing "trash can" searches (1) the built-in
  catalog's human names, (2) the live OSM wiki word index via taginfo's
  `search/by_keyword` endpoint (verified in taginfo source: it matches
  your words against the words of each tag's wiki documentation, which
  is exactly how "trash can" finds amenity=waste_basket), and (3) a
  fallback that searches the actual *names* of places near you. Results
  show human names only.
- **Human names everywhere.** Tag values are prettified for display
  ("waste_basket" → "Waste Basket") in the compass title, the map pin
  label, "not found" messages, and the value browser (which also shows
  each tag's wiki description sentence when one exists). Typing
  `key=value` still works for power users.
- The full key/value database browser is still there, relabeled
  **"🛠 Advanced: Tag Database"** so it's out of the way of normal use.

## v3.4 — Find Anything actually finds anything

- Multi-word queries fixed: the taginfo keyword endpoint does a
  substring match against a tag's wiki words (see search.rb in the
  taginfo source), so a phrase like "trash can" could miss even when
  both words hit individually. The app now searches the phrase AND each
  word (plus naive singulars for plurals like "benches"), merges the
  hits, and ranks them: exact name matches first, then partial matches,
  boosted by how many of your words matched.
- Catalog matching is word-based too ("water drink" finds
  "Drinking Water").
- If neither the catalog nor the tag database recognizes the words, the
  app automatically searches for places NAMED what you typed (works for
  brands: "Aldi", "Starbucks") instead of showing an empty list.
- Duplicate friendly names from different contexts are disambiguated
  with a plain word in parentheses — e.g. "Bakery (Shop)" vs
  "Bakery (Craft)" — never raw notation. No "=" appears anywhere in the
  normal flow.

## v3.5 — One obvious way in

- A big search bar now sits on the main screen: "🔍 What are you
  looking for?" — no more discovering the hidden tap-the-title menu to
  search. Tap it, the keyboard opens automatically, type words.
- The keyboard's search key works; the dialog's Search button is no
  longer the only trigger.
- Tapping an autocomplete suggestion navigates immediately (type "toi",
  tap "Toilets", the arrow starts pointing) — zero further dialogs.
- Typing something that exactly matches a catalog entry skips the
  results list entirely and just starts the compass.
- The tap-the-title menu still exists for quick picks, categories, and
  the advanced tag browser.

## v3.6 — Nothing overlaps, anything can be found, updates install themselves

### Layout

- The main screen is now one vertical column instead of views pinned to
  each other with fixed sizes. The compass takes whatever height is left
  and shrinks on small screens, so it can no longer slide under the
  search bar or the distance read-out; if even the smallest compass does
  not fit (tiny screens, landscape, huge fonts) the page scrolls.
- The photo is a thumbnail beside the details instead of a 200×150 block
  above them, and the details are capped at four lines — tap them to read
  everything, tap the photo to see it full screen.
- System bars are handled with real window insets instead of guessed
  60 dp / 130 dp margins.
- "Searching" no longer appears twice, dialog text is readable in dark
  mode (it was forced to black), and a museum with a toilet is no longer
  labelled "Inside <its own name>".

### Search

- **One search screen** (tap the search bar or the title) with results as
  you type, replacing the dialog chain and the hidden title menu.
- **OpenStreetMap's own vocabulary, offline.** The app now bundles the
  preset list of the iD editor (`assets/osm_presets.tsv`, about 1,600
  feature types, generated by `tools/build_presets.py` from
  openstreetmap/id-tagging-schema, ISC licence). Every preset carries the
  everyday names and search words mappers use, so "garbage", "bin",
  "litter" and "rubbish" all find the trash can. Works without signal.
- **Everything else, live.** Below the offline matches the app lists
  further tags from the taginfo database whose value contains what was
  typed (endpoint `/api/4/search/by_value`), with how often each is
  mapped worldwide. Free-text values, lifecycle prefixes and one-off
  typos are filtered out. This replaces the wiki-keyword lookup, which
  matched inside words ("ice" hit "police" and "service").
- **What's around me?** Lists every kind of thing mapped within 300 m,
  by plain name, with how many there are and how far the nearest is —
  for discovering what there is to find without knowing what to type.
- **Rare things are found too.** When nothing is within the Settings
  radius the search widens automatically (×5, up to 50 km; 20 km for
  searches by name) and says so while it does.
- Recent searches are remembered, and the last target is restored on
  launch whatever kind it was (previously only catalog entries).

### Reliability

- Two of the four Overpass mirrors no longer respond. The main instance
  is now asked first, with its two servers and the remaining mirrors as
  hedged backups, and a server that answers "busy" (429/504) gets one
  retry.

### Updates

- The app checks this repository's GitHub Releases at launch. If a newer
  version is published, an "Update available" pill appears at the top;
  one tap downloads the APK and hands it to Android's installer. Android
  still shows its own confirmation, and the first time asks to allow
  installs from this app.
- To ship an update: bump `versionCode` and `versionName`, build a
  release APK signed with the same key, and publish a GitHub release
  tagged `v<versionName>` with the APK attached.

## v3.7 — Readable everywhere, and your history is yours

- **Contrast.** Every text/background pair on the app's own screens now
  meets WCAG AA (4.5:1). The blue of the search bar and buttons went from
  #2196F3 (white text at 3.1:1) to #1976D2 (4.6:1); the footer hint and
  Legal link are brighter; the compass status line is no longer dimmed.
  The app's own screens are dark in both system themes; dialogs follow
  the system theme and were checked in light and dark mode (the radius
  warning now uses the theme's error colour instead of hard-coded red).
- **Clear recent searches.** A row under the Recent list on the search
  screen removes them.
- **"Remember my searches" switch in Settings** (on by default). When
  off, the app stores neither recent searches nor the last target, opens
  on Trash Can, and whatever was stored is deleted. History never left
  the phone in either case.
