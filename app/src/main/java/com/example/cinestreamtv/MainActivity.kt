package com.example.cinestreamtv

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ---------- Theme Colors ----------
private val NetBlack = Color(0xFF141414)
private val NetDarkGray = Color(0xFF1F1F1F)
private val NetRed = Color(0xFFE50914)
private val NetWhite = Color(0xFFFFFFFF)
private val NetMuted = Color(0xFF8C8C8C)

// ---------- Data Models ----------
data class Film(
val id: String,
val tmdbId: Int,
val name: String,
val year: String,
val kind: String,
val poster: String,
val backdrop: String,
val overview: String,
val rating: String,
val provider: String
)

data class Resume(
val filmId: String,
val name: String,
val image: String,
val url: String,
val torrentId: Int,
val fileId: Int,
val pos: Long,
val dur: Long
)

data class TbFile(val torrentId: Int, val fileId: Int, val name: String, val size: Long)

data class PlayReq(
val filmId: String,
val name: String,
val image: String,
val url: String,
val torrentId: Int,
val fileId: Int,
val start: Long
)

data class Provider(val label: String, val tmdbId: Int)

data class Catalog(val all: List<Film>, val rows: List<Pair<String, List<Film>>>)

private val Resume.key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url
private val PlayReq.key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url

private fun poster(path: String) = "https://image.tmdb.org/t/p/w500$path"

private val demoFilms = listOf(
Film("d1", 101, "Ted Lasso", "2023", "Series", poster("/5fhZdwP1DVJ0FyVH6vrFdHwpXIn.jpg"), "https://image.tmdb.org/t/p/w1280/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
"An American football coach is hired to manage a British soccer team.", "8.6", "Apple TV+"),
Film("d2", 102, "Succession", "2023", "Series", poster("/7HW47XbkNQ5fiwQFYGWdw9gs144.jpg"), "https://image.tmdb.org/t/p/w1280/4HodYYKEIsGOdhrGi2Ud6XGjBn1.jpg",
"The Roy family is known for controlling the biggest media and entertainment company.", "8.8", "MAX"),
Film("d3", 103, "The Mandalorian", "2023", "Series", poster("/eU1i6eHXlzMOlEq0ku1Rzq7Y4wA.jpg"), "https://image.tmdb.org/t/p/w1280/xOMo8BRK7PfcJv9JCnx7s5hj0PX.jpg",
"The travels of a lone bounty hunter in the outer reaches of the galaxy.", "8.7", "Disney+"),
Film("d4", 104, "Stranger Things", "2022", "Series", poster("/49WJfeN0moxb9ANekyaGEA1XFR6.jpg"), "https://image.tmdb.org/t/p/w1280/rAiYTfKGqDCRIIqo664sY9XZIvQ.jpg",
"When a young boy vanishes, a small town uncovers a mystery involving secret experiments.", "8.7", "Netflix")
)

// ---------- Helpers ----------
private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

private val EP = Regex("(?i)s(\\d{1,2})[ ._-]?e(\\d{1,3})|(?<![0-9])(\\d{1,2})x(\\d{1,3})")
private val QUALITY = Regex("(?i)(2160p|1440p|1080p|720p|480p|4k)")

private fun episodeOf(name: String): Pair<Int, Int>? {
val m = EP.find(name) ?: return null
val s = m.groupValues[1].ifEmpty { m.groupValues[3] }.toIntOrNull() ?: return null
val e = m.groupValues[2].ifEmpty { m.groupValues[4] }.toIntOrNull() ?: return null
return s to e
}

private fun qualityOf(name: String): String =
QUALITY.find(name)?.value?.lowercase()?.replace("4k", "2160p")?.uppercase() ?: ""

private fun fmtSize(b: Long): String {
val gb = b / 1073741824.0
return if (gb >= 1) "${Math.round(gb * 10) / 10.0} GB" else "${b / 1048576} MB"
}

private fun loadResume(p: SharedPreferences): List<Resume> = try {
val arr = JSONArray(p.getString("resume", "[]"))
(0 until arr.length()).map {
val o = arr.getJSONObject(it)
Resume(o.str("filmId"), o.str("name"), o.str("image"), o.str("url"), o.optInt("tid", -1), o.optInt("fid", -1), o.optLong("pos"), o.optLong("dur"))
}
} catch (e: Exception) { emptyList() }

private fun saveResume(p: SharedPreferences, list: List<Resume>) {
val arr = JSONArray()
list.forEach {
arr.put(
JSONObject().put("filmId", it.filmId).put("name", it.name).put("image", it.image).put("url", it.url)
.put("tid", it.torrentId).put("fid", it.fileId).put("pos", it.pos).put("dur", it.dur)
)
}
p.edit().putString("resume", arr.toString()).apply()
}

private fun loadWatchlist(p: SharedPreferences): List<Film> = try {
val arr = JSONArray(p.getString("watchlist", "[]"))
(0 until arr.length()).map {
val o = arr.getJSONObject(it)
Film(o.str("id"), o.optInt("tmdbId"), o.str("name"), o.str("year"), o.str("kind"), o.str("poster"), o.str("backdrop"), o.str("overview"), o.str("rating"), o.str("provider"))
}
} catch (e: Exception) { emptyList() }

private fun saveWatchlist(p: SharedPreferences, list: List<Film>) {
val arr = JSONArray()
list.forEach {
arr.put(
JSONObject().put("id", it.id).put("tmdbId", it.tmdbId).put("name", it.name).put("year", it.year)
.put("kind", it.kind).put("poster", it.poster).put("backdrop", it.backdrop).put("overview", it.overview)
.put("rating", it.rating).put("provider", it.provider)
)
}
p.edit().putString("watchlist", arr.toString()).apply()
}

private fun httpGet(urlStr: String, bearer: String? = null): String {
val c = URL(urlStr).openConnection() as HttpURLConnection
c.connectTimeout = 15000
c.readTimeout = 20000
c.setRequestProperty("Accept", "application/json")
if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
try {
val code = c.responseCode
val stream = if (code in 200..299) c.inputStream else c.errorStream
val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
if (code !in 200..299) throw Exception("HTTP $code")
return body
} finally { c.disconnect() }
}

private object Tmdb {
private const val BASE = "https://api.themoviedb.org/3"
private const val REGION = "US"

// TMDB watch-provider IDs (US region)
val providers = listOf(
Provider("Netflix", 8),
Provider("Prime Video", 9),
Provider("Disney+", 337),
Provider("Apple TV+", 350),
Provider("MAX", 1899)
)

private fun call(path: String, key: String): String {
val sep = if (path.contains("?")) "&" else "?"
return if (key.length > 40) httpGet("$BASE$path", key)
else httpGet("$BASE$path${sep}api_key=$key")
}

private fun toFilm(o: JSONObject, fallbackKind: String, label: String): Film? {
val kind = o.str("media_type").ifEmpty { fallbackKind }
if (kind != "movie" && kind != "tv") return null
val p = o.str("poster_path")
if (p.isEmpty()) return null
val name = o.str("title").ifEmpty { o.str("name") }
if (name.isEmpty()) return null
val date = o.str("release_date").ifEmpty { o.str("first_air_date") }
val b = o.str("backdrop_path")
val r = o.optDouble("vote_average", 0.0)
val id = o.optInt("id")
return Film(
id = "$kind$id",
tmdbId = id,
name = name,
year = date.take(4),
kind = if (kind == "tv") "Series" else "Movie",
poster = poster(p),
backdrop = if (b.isEmpty()) "" else "https://image.tmdb.org/t/p/w1280$b",
overview = o.str("overview"),
rating = if (r > 0) (Math.round(r * 10) / 10.0).toString() else "",
provider = label
)
}

private fun parse(body: String, fallbackKind: String, label: String): List<Film> {
val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
return (0 until arr.length()).mapNotNull {
try { toFilm(arr.getJSONObject(it), fallbackKind, label) } catch (_: Exception) { null }
}
}

/** Trending row + one genuinely provider-filtered row per service. */
suspend fun load(key: String): Catalog = withContext(Dispatchers.IO) {
val all = LinkedHashMap<String, Film>()
try {
parse(call("/trending/all/week", key), "movie", "").forEach { all.putIfAbsent(it.id, it) }
} catch (_: Exception) {}

val rows = ArrayList<Pair<String, List<Film>>>()
for (p in providers) {
val row = LinkedHashMap<String, Film>()
for (kind in listOf("movie", "tv")) {
try {
val path = "/discover/$kind?with_watch_providers=${p.tmdbId}" +
"&watch_region=$REGION&with_watch_monetization_types=flatrate" +
"&sort_by=popularity.desc"
parse(call(path, key), kind, p.label).forEach { f ->
row.putIfAbsent(f.id, f)
all.putIfAbsent(f.id, f)
}
} catch (_: Exception) {}
}
if (row.isNotEmpty()) rows.add(p.label to row.values.toList())
}

val merged = all.values.toList()
if (merged.isEmpty()) Catalog(demoFilms, emptyList()) else Catalog(merged, rows)
}

suspend fun search(key: String, q: String): List<Film> = withContext(Dispatchers.IO) {
try {
parse(call("/search/multi?query=${URLEncoder.encode(q, "UTF-8")}&include_adult=false", key), "movie", "")
} catch (_: Exception) { emptyList() }
}
}

private object Tb {
private const val BASE = "https://api.torbox.app/v1/api"
private val VIDEO = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".ts")

private var cache: List<TbFile>? = null
private var cacheAt = 0L
private const val TTL = 5 * 60_000L

suspend fun library(key: String, force: Boolean = false): Pair<String, List<TbFile>> {
val cached = cache
if (!force && cached != null && System.currentTimeMillis() - cacheAt < TTL) return "" to cached
return withContext(Dispatchers.IO) {
try {
val body = httpGet("$BASE/torrents/mylist?limit=200&offset=0", key)
val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
val out = ArrayList<TbFile>()
for (i in 0 until data.length()) {
val t = data.getJSONObject(i)
val files = t.optJSONArray("files") ?: continue
for (j in 0 until files.length()) {
val f = files.getJSONObject(j)
val name = f.str("name")
val lower = name.lowercase()
if (VIDEO.any { lower.endsWith(it) } && !lower.contains("sample")) {
out.add(TbFile(t.optInt("id"), f.optInt("id"), name.substringAfterLast('/'), f.optLong("size")))
}
}
}
cache = out
cacheAt = System.currentTimeMillis()
"" to out
} catch (e: Exception) { "TorBox error." to emptyList() }
}
}

suspend fun link(key: String, torrentId: Int, fileId: Int): String? = withContext(Dispatchers.IO) {
try {
val body = httpGet("$BASE/torrents/requestdl?token=$key&torrent_id=$torrentId&file_id=$fileId&zip_link=false", key)
val o = JSONObject(body)
if (o.optBoolean("success", false)) o.str("data").ifEmpty { null } else null
} catch (e: Exception) { null }
}

suspend fun test(key: String): String = withContext(Dispatchers.IO) {
if (key.isBlank()) "Enter API Key" else {
try {
val o = JSONObject(httpGet("$BASE/user/me", key))
if (o.optBoolean("success", false)) "Connected" else "Invalid Key"
} catch (e: Exception) { "Failed" }
}
}
}

class MainActivity : ComponentActivity() {
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
window.statusBarColor = android.graphics.Color.rgb(20, 20, 20)
window.navigationBarColor = android.graphics.Color.rgb(20, 20, 20)
setContent { NetCleanApp() }
}
}

@Composable
fun NetCleanApp() {
val context = LocalContext.current
val prefs = remember { context.getSharedPreferences("netstream", Context.MODE_PRIVATE) }
val scope = rememberCoroutineScope()

var torboxKey by remember { mutableStateOf(prefs.getString("torbox_token", "") ?: "") }
var tmdbKey by remember { mutableStateOf(prefs.getString("tmdb_key", "") ?: "") }
var screen by remember { mutableStateOf("Home") }
var selectedProvider by remember { mutableStateOf<String?>(null) }
var detail by remember { mutableStateOf<Film?>(null) }
var play by remember { mutableStateOf<PlayReq?>(null) }
var catalog by remember { mutableStateOf(Catalog(demoFilms, emptyList())) }
var query by remember { mutableStateOf("") }
var results by remember { mutableStateOf<List<Film>>(emptyList()) }
var searching by remember { mutableStateOf(false) }
var library by remember { mutableStateOf<List<TbFile>>(emptyList()) }
var libStatus by remember { mutableStateOf("") }
var tbStatus by remember { mutableStateOf("") }
var linkText by remember { mutableStateOf("") }
var matches by remember { mutableStateOf<List<TbFile>>(emptyList()) }
var detailMsg by remember { mutableStateOf("") }
var detailBusy by remember { mutableStateOf(false) }
var homeLoading by remember { mutableStateOf(false) }
var homeError by remember { mutableStateOf("") }
val resume = remember { mutableStateListOf<Resume>().also { it.addAll(loadResume(prefs)) } }
val watchlist = remember { mutableStateListOf<Film>().also { it.addAll(loadWatchlist(prefs)) } }

suspend fun loadLibrary() {
if (torboxKey.isBlank()) { libStatus = "Add TorBox API Key."; return }
val res = Tb.library(torboxKey)
library = res.second
libStatus = res.first.ifEmpty { if (res.second.isEmpty()) "No TorBox files found." else "" }
}

fun open(f: Film) { detail = f; matches = emptyList(); detailMsg = "" }

fun startTb(f: TbFile, film: Film, startPos: Long = 0L) {
play = PlayReq(film.id, f.name, film.poster, "", f.torrentId, f.fileId, startPos)
}

fun startUrl(u: String, startPos: Long = 0L) {
val name = u.substringAfterLast('/').substringBefore('?').ifEmpty { "Stream" }
play = PlayReq("", name, "", u, -1, -1, startPos)
}

fun report(req: PlayReq, pos: Long, dur: Long) {
if (pos < 3000) return
val d = if (dur > 0) dur else 0L
val finished = d > 0 && pos > d * 0.95
val idx = resume.indexOfFirst { it.key == req.key }
if (idx >= 0) resume.removeAt(idx)
if (!finished) resume.add(0, Resume(req.filmId, req.name, req.image, req.url, req.torrentId, req.fileId, pos, d))
while (resume.size > 10) resume.removeAt(resume.size - 1)
saveResume(prefs, resume)
}

LaunchedEffect(tmdbKey) {
if (tmdbKey.isBlank()) { catalog = Catalog(demoFilms, emptyList()); return@LaunchedEffect }
homeLoading = true; homeError = ""
try {
val c = Tmdb.load(tmdbKey)
if (c.all.isNotEmpty()) catalog = c
} catch (e: Exception) { homeError = "Couldn't load catalog." }
homeLoading = false
}

LaunchedEffect(query, tmdbKey) {
if (query.isBlank()) { results = emptyList(); searching = false; return@LaunchedEffect }
searching = true
delay(250)
results = if (tmdbKey.isBlank()) catalog.all.filter { it.name.contains(query, true) }
else Tmdb.search(tmdbKey, query.trim())
searching = false
}

BackHandler(enabled = play != null || detail != null || selectedProvider != null) {
if (play != null) play = null
else if (detail != null) detail = null
else if (selectedProvider != null) selectedProvider = null
}

MaterialTheme(
colorScheme = darkColorScheme(
background = NetBlack, surface = NetDarkGray, primary = NetRed,
onBackground = NetWhite, onSurface = NetWhite
)
) {
Box(Modifier.fillMaxSize().background(NetBlack)) {
val p = play
val d = detail
val prov = selectedProvider

when {
p != null -> PlayerScreen(p, torboxKey) { pos, dur -> report(p, pos, dur) }

d != null -> {
val matchingResume = resume.firstOrNull { it.filmId == d.id }
val isInWatchlist = watchlist.any { it.id == d.id }
DetailsScreen(
film = d, msg = detailMsg, matches = matches, busy = detailBusy,
resumePos = matchingResume?.pos ?: 0L,
resumeDur = matchingResume?.dur ?: 0L,
isInWatchlist = isInWatchlist,
onToggleWatchlist = {
if (isInWatchlist) watchlist.removeAll { it.id == d.id } else watchlist.add(d)
saveWatchlist(prefs, watchlist)
},
onPlay = { posToStart ->
scope.launch {
detailBusy = true; matches = emptyList(); detailMsg = ""
loadLibrary()
if (torboxKey.isBlank() || library.isEmpty()) {
detailMsg = libStatus.ifEmpty { "No TorBox files found." }
} else {
val n = norm(d.name)
val m = library.filter { n.isNotEmpty() && norm(it.name).contains(n) }
if (m.isEmpty()) detailMsg = "Not found in TorBox library."
else if (m.size == 1) startTb(m[0], d, posToStart)
else matches = m
}
detailBusy = false
}
},
onPick = { f, posToStart -> startTb(f, d, posToStart) },
onBack = { detail = null }
)
}

// Nav lives in normal layout flow here, so it can never cover
// the scrolling content or fight it for input.
else -> Column(Modifier.fillMaxSize().background(NetBlack)) {
NetTopNav(current = screen) { screen = it; selectedProvider = null }

Box(Modifier.fillMaxWidth().weight(1f)) {
if (prov != null) {
val kindFilter = if (screen == "Shows") "Series" else "Movie"
val row = catalog.rows.firstOrNull { it.first == prov }?.second
?: catalog.all.filter { it.provider == prov }
ProviderCatalogScreen(
providerName = prov, kind = kindFilter,
catalog = row.filter { it.kind == kindFilter },
onFilm = { open(it) }, onBack = { selectedProvider = null }
)
} else {
when (screen) {
"Home" -> HomeScreen(
catalog = catalog.all, rows = catalog.rows, resume = resume,
loading = homeLoading, error = homeError,
onFilm = { open(it) },
onResume = { r -> play = PlayReq(r.filmId, r.name, r.image, r.url, r.torrentId, r.fileId, r.pos) }
)
"Shows" -> ProviderHubScreen(
title = "TV Shows Hub",
available = catalog.rows.map { it.first }.ifEmpty { Tmdb.providers.map { it.label } },
onSelect = { selectedProvider = it }
)
"Movies" -> ProviderHubScreen(
title = "Movies Hub",
available = catalog.rows.map { it.first }.ifEmpty { Tmdb.providers.map { it.label } },
onSelect = { selectedProvider = it }
)
"My List" -> MyListScreen(watchlist = watchlist, onFilm = { open(it) })
"Search" -> SearchScreen(query, { query = it }, results, searching) { open(it) }
else -> SettingsScreen(
torboxKey = torboxKey, onTorbox = { torboxKey = it.trim(); prefs.edit().putString("torbox_token", it.trim()).apply() },
onTest = { scope.launch { tbStatus = "Testing..."; tbStatus = Tb.test(torboxKey) } }, tbStatus = tbStatus,
tmdbKey = tmdbKey, onTmdb = { tmdbKey = it.trim(); prefs.edit().putString("tmdb_key", it.trim()).apply() },
link = linkText, onLink = { linkText = it }, onPlayLink = { if (linkText.startsWith("https://")) startUrl(linkText.trim()) }
)
}
}
}
}
}
}
}
}

// ---------- Top Navigation Bar ----------
@Composable
private fun NetTopNav(current: String, onNav: (String) -> Unit) {
Box(
Modifier.fillMaxWidth().padding(vertical = 10.dp),
contentAlignment = Alignment.Center
) {
Row(
modifier = Modifier
.clip(RoundedCornerShape(50.dp))
.background(Color(0xFF222222))
.border(1.dp, Color(0xFF444444), RoundedCornerShape(50.dp))
.padding(horizontal = 6.dp, vertical = 6.dp),
verticalAlignment = Alignment.CenterVertically,
horizontalArrangement = Arrangement.spacedBy(4.dp)
) {
CapsuleNavItem(label = "⌕", isSelected = current == "Search") { onNav("Search") }
CapsuleNavItem(label = "Home", isSelected = current == "Home") { onNav("Home") }
CapsuleNavItem(label = "Shows", isSelected = current == "Shows") { onNav("Shows") }
CapsuleNavItem(label = "Movies", isSelected = current == "Movies") { onNav("Movies") }
CapsuleNavItem(label = "My List", isSelected = current == "My List") { onNav("My List") }
CapsuleNavItem(label = "Settings", isSelected = current == "Settings") { onNav("Settings") }
}
}
}

@Composable
private fun CapsuleNavItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
var focused by remember { mutableStateOf(false) }
val requester = remember { FocusRequester() }
val active = isSelected || focused

Box(
modifier = Modifier
.onFocusChanged { focused = it.isFocused }
.scale(if (focused) 1.06f else 1f)
.clip(RoundedCornerShape(50.dp))
.background(if (active) NetWhite else Color.Transparent)
.focusRequester(requester)
.clickable {
// Keep focus in sync with selection on both remote and touch.
runCatching { requester.requestFocus() }
onClick()
}
.padding(horizontal = 18.dp, vertical = 8.dp),
contentAlignment = Alignment.Center
) {
Text(
text = label,
color = if (active) Color.Black else NetWhite,
fontSize = if (label == "⌕") 18.sp else 14.sp,
fontWeight = FontWeight.Bold
)
}
}

@Composable
private fun NetCard(
modifier: Modifier = Modifier,
onClick: () -> Unit,
content: @Composable BoxScope.(Boolean) -> Unit
) {
var focused by remember { mutableStateOf(false) }
Box(
modifier
.scale(if (focused) 1.06f else 1f)
.onFocusChanged { focused = it.isFocused }
.clip(RoundedCornerShape(6.dp))
.background(NetDarkGray)
.then(if (focused) Modifier.border(3.dp, NetWhite, RoundedCornerShape(6.dp)) else Modifier)
.clickable(onClick = onClick)
) {
content(this, focused)
}
}

@Composable
private fun PosterCard(film: Film, showProvider: Boolean = false, onClick: () -> Unit) {
NetCard(modifier = Modifier.width(150.dp).height(220.dp), onClick = onClick) {
AsyncImage(model = film.poster, contentDescription = film.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
if (showProvider && film.provider.isNotEmpty()) {
Box(
Modifier.align(Alignment.TopEnd).padding(6.dp)
.background(NetRed.copy(alpha = 0.92f), RoundedCornerShape(3.dp))
.padding(horizontal = 6.dp, vertical = 2.dp)
) {
Text(film.provider, color = NetWhite, fontSize = 9.sp, fontWeight = FontWeight.Bold)
}
}
}
}

@Composable
private fun ResumeCard(r: Resume, onClick: () -> Unit) {
NetCard(modifier = Modifier.width(260.dp).height(145.dp), onClick = onClick) {
if (r.image.isNotEmpty()) {
AsyncImage(model = r.image, contentDescription = r.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}
Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
Text(
r.name, color = NetWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
)
val frac = if (r.dur > 0) (r.pos.toFloat() / r.dur.toFloat()).coerceIn(0f, 1f) else 0f
Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0xFF555555))) {
Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(NetRed))
}
}
}

@Composable
private fun Shelf(title: String, list: List<Film>, onFilm: (Film) -> Unit) {
if (list.isEmpty()) return
Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
Text(
title, color = NetWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold,
modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
)
LazyRow(
contentPadding = PaddingValues(horizontal = 24.dp),
horizontalArrangement = Arrangement.spacedBy(14.dp)
) {
items(list, key = { it.id }) { f -> PosterCard(f) { onFilm(f) } }
}
}
}

@Composable
private fun HeroButton(label: String, filled: Boolean, requester: FocusRequester? = null, onClick: () -> Unit) {
var focused by remember { mutableStateOf(false) }
val base = if (filled) Color(0xFFE5E5E5) else Color(0x59FFFFFF)
Box(
modifier = Modifier
.height(44.dp)
.then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
.scale(if (focused) 1.05f else 1f)
.onFocusChanged { focused = it.isFocused }
.clip(RoundedCornerShape(6.dp))
.background(if (focused) NetWhite else base)
.then(if (focused) Modifier.border(2.dp, NetWhite, RoundedCornerShape(6.dp)) else Modifier)
.clickable(onClick = onClick)
.padding(horizontal = 22.dp),
contentAlignment = Alignment.Center
) {
Text(
label,
color = if (filled || focused) Color.Black else NetWhite,
fontSize = 15.sp, fontWeight = FontWeight.Bold
)
}
}

@Composable
private fun HeroBanner(film: Film, onOpen: () -> Unit) {
val requester = remember { FocusRequester() }
Box(
Modifier
.fillMaxWidth()
.height(380.dp)
.background(NetBlack)
) {
if (film.backdrop.isNotEmpty()) {
AsyncImage(
model = film.backdrop, contentDescription = film.name,
modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
)
}
Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent, NetBlack), startY = 0f, endY = 380f)))
Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(NetBlack.copy(alpha = 0.92f), Color.Transparent), endX = 700f)))

Column(
Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 28.dp).width(600.dp),
verticalArrangement = Arrangement.spacedBy(10.dp)
) {
Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Text("STREAMING ON", color = NetMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
Box(Modifier.background(NetRed, RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
Text(film.provider, color = NetWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}
}
Text(film.name, color = NetWhite, fontSize = 40.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)

Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
Text(film.year, color = NetMuted, fontSize = 13.sp)
Text("•", color = NetMuted)
Text(film.kind, color = NetMuted, fontSize = 13.sp)
if (film.rating.isNotEmpty()) {
Text("•", color = NetMuted)
Text("★ ${film.rating}", color = Color(0xFF46D369), fontSize = 13.sp, fontWeight = FontWeight.Bold)
}
}
Text(
film.overview, color = NetWhite.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 2,
overflow = TextOverflow.Ellipsis, lineHeight = 20.sp
)
Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
HeroButton("▶ Play", filled = true, requester = requester, onClick = onOpen)
HeroButton("ⓘ More Info", filled = false, onClick = onOpen)
}
}
}

LaunchedEffect(Unit) {
delay(250)
runCatching { requester.requestFocus() }
}
}

@Composable
private fun HomeScreen(
catalog: List<Film>,
rows: List<Pair<String, List<Film>>>,
resume: List<Resume>,
loading: Boolean,
error: String,
onFilm: (Film) -> Unit,
onResume: (Resume) -> Unit
) {
val hero = catalog.firstOrNull() ?: demoFilms.first()
val shelves = rows.filter { it.second.isNotEmpty() }

LazyColumn(Modifier.fillMaxSize()) {
item(key = "hero") { HeroBanner(hero) { onFilm(hero) } }

if (resume.isNotEmpty()) {
item(key = "resume") {
Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
Text(
"Continue Watching", color = NetWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold,
modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
)
LazyRow(
contentPadding = PaddingValues(horizontal = 24.dp),
horizontalArrangement = Arrangement.spacedBy(14.dp)
) {
items(resume, key = { it.key }) { r -> ResumeCard(r) { onResume(r) } }
}
}
}
}

if (error.isNotEmpty()) {
item(key = "err") {
Text(error, color = NetMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 24.dp, top = 8.dp))
}
}
if (loading) {
item(key = "loading") {
Row(
Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
verticalAlignment = Alignment.CenterVertically,
horizontalArrangement = Arrangement.spacedBy(12.dp)
) {
CircularProgressIndicator(color = NetRed, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
Text("Loading catalog...", color = NetMuted, fontSize = 13.sp)
}
}
}

item(key = "trending") {
Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
Text(
"Trending Now", color = NetWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold,
modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
)
LazyRow(
contentPadding = PaddingValues(horizontal = 24.dp),
horizontalArrangement = Arrangement.spacedBy(14.dp)
) {
items(catalog.take(20), key = { it.id }) { f -> PosterCard(f, showProvider = true) { onFilm(f) } }
}
}
}

shelves.forEach { (label, list) ->
item(key = "shelf_$label") { Shelf(label, list, onFilm) }
}

item(key = "spacer") { Spacer(Modifier.height(40.dp)) }
}
}

// ---------- Provider Hub Screen ----------
private val providerColors = mapOf(
"Netflix" to Color(0xFF661111),
"Disney+" to Color(0xFF113366),
"MAX" to Color(0xFF331166),
"Prime Video" to Color(0xFF115544),
"Apple TV+" to Color(0xFF111111)
)

@Composable
private fun ProviderHubScreen(title: String, available: List<String>, onSelect: (String) -> Unit) {
Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)) {
Text(title, color = NetWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 20.dp))

LazyVerticalGrid(
columns = GridCells.Fixed(2),
horizontalArrangement = Arrangement.spacedBy(20.dp),
verticalArrangement = Arrangement.spacedBy(20.dp),
modifier = Modifier.fillMaxSize()
) {
items(available) { label ->
var focused by remember { mutableStateOf(false) }
Box(
modifier = Modifier
.fillMaxWidth()
.height(140.dp)
.scale(if (focused) 1.04f else 1f)
.onFocusChanged { focused = it.isFocused }
.clip(RoundedCornerShape(12.dp))
.background(providerColors[label] ?: Color(0xFF222222))
.border(if (focused) 3.dp else 1.dp, if (focused) NetWhite else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
.clickable { onSelect(label) },
contentAlignment = Alignment.Center
) {
Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text(label, color = NetWhite, fontSize = 28.sp, fontWeight = FontWeight.Black)
Text("$label Collection", color = NetMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}
}
}
}
}
}

// ---------- My List / Watchlist Screen ----------
@Composable
private fun MyListScreen(watchlist: List<Film>, onFilm: (Film) -> Unit) {
Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)) {
Text("My Watchlist", color = NetWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 20.dp))

if (watchlist.isEmpty()) {
Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
Text("Your watchlist is empty. Add shows or movies to watch later!", color = NetMuted, fontSize = 16.sp)
}
} else {
LazyVerticalGrid(
columns = GridCells.Adaptive(150.dp),
horizontalArrangement = Arrangement.spacedBy(14.dp),
verticalArrangement = Arrangement.spacedBy(14.dp),
modifier = Modifier.fillMaxSize()
) {
items(watchlist, key = { it.id }) { f -> PosterCard(f) { onFilm(f) } }
}
}
}
}

// ---------- Provider Dedicated Catalog Screen ----------
@Composable
private fun ProviderCatalogScreen(providerName: String, kind: String, catalog: List<Film>, onFilm: (Film) -> Unit, onBack: () -> Unit) {
Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)) {
Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 20.dp)) {
var focused by remember { mutableStateOf(false) }
Box(
modifier = Modifier
.scale(if (focused) 1.1f else 1f)
.onFocusChanged { focused = it.isFocused }
.clip(RoundedCornerShape(6.dp))
.background(NetDarkGray)
.border(2.dp, if (focused) NetWhite else Color.Transparent, RoundedCornerShape(6.dp))
.clickable(onClick = onBack)
.padding(horizontal = 16.dp, vertical = 8.dp)
) {
Text("← Back", color = NetWhite, fontWeight = FontWeight.Bold)
}
Text("$providerName $kind", color = NetWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
}

if (catalog.isEmpty()) {
Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
Text("No items found in this category.", color = NetMuted, fontSize = 16.sp)
}
} else {
LazyVerticalGrid(
columns = GridCells.Adaptive(150.dp),
horizontalArrangement = Arrangement.spacedBy(14.dp),
verticalArrangement = Arrangement.spacedBy(14.dp),
modifier = Modifier.fillMaxSize()
) {
items(catalog, key = { it.id }) { f -> PosterCard(f) { onFilm(f) } }
}
}
}
}

@Composable
private fun SearchScreen(
query: String, onQuery: (String) -> Unit, results: List<Film>, searching: Boolean, onFilm: (Film) -> Unit
) {
Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)) {
OutlinedTextField(
value = query, onValueChange = onQuery, singleLine = true,
label = { Text("Search title, genre...") },
shape = RoundedCornerShape(6.dp),
colors = OutlinedTextFieldDefaults.colors(
focusedTextColor = NetWhite, unfocusedTextColor = NetWhite,
focusedBorderColor = NetWhite, unfocusedBorderColor = Color(0x33FFFFFF),
focusedContainerColor = NetDarkGray, unfocusedContainerColor = NetDarkGray
),
modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
)
if (searching) {
Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
CircularProgressIndicator(color = NetRed, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
Text("Searching...", color = NetMuted, fontSize = 13.sp)
}
} else if (query.isNotBlank() && results.isEmpty()) {
Text("No results for \"$query\".", color = NetMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
}
LazyVerticalGrid(
columns = GridCells.Adaptive(150.dp),
horizontalArrangement = Arrangement.spacedBy(14.dp),
verticalArrangement = Arrangement.spacedBy(14.dp),
modifier = Modifier.fillMaxSize()
) {
items(results, key = { it.id }) { f -> PosterCard(f, showProvider = true) { onFilm(f) } }
}
}
}

@Composable
private fun SettingsScreen(
torboxKey: String, onTorbox: (String) -> Unit, onTest: () -> Unit, tbStatus: String,
tmdbKey: String, onTmdb: (String) -> Unit, link: String, onLink: (String) -> Unit, onPlayLink: () -> Unit
) {
Column(
Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
verticalArrangement = Arrangement.spacedBy(12.dp)
) {
Text("App Settings", color = NetWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
Text("TorBox API Key", color = NetMuted, fontSize = 13.sp)
OutlinedTextField(
value = torboxKey, onValueChange = onTorbox, singleLine = true, shape = RoundedCornerShape(6.dp),
colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = NetDarkGray, unfocusedContainerColor = NetDarkGray, focusedBorderColor = NetWhite, unfocusedBorderColor = Color(0x33FFFFFF)),
modifier = Modifier.fillMaxWidth()
)
NetCard(onClick = onTest) { Text("Test TorBox Connection", color = NetWhite, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
if (tbStatus.isNotEmpty()) Text(tbStatus, color = NetWhite, fontSize = 14.sp)

Text("TMDB API Key (For Catalogs & Posters)", color = NetMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
OutlinedTextField(
value = tmdbKey, onValueChange = onTmdb, singleLine = true, shape = RoundedCornerShape(6.dp),
colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = NetDarkGray, unfocusedContainerColor = NetDarkGray, focusedBorderColor = NetWhite, unfocusedBorderColor = Color(0x33FFFFFF)),
modifier = Modifier.fillMaxWidth()
)

Text("Direct Stream Link", color = NetMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
OutlinedTextField(
value = link, onValueChange = onLink, singleLine = true, shape = RoundedCornerShape(6.dp),
colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = NetDarkGray, unfocusedContainerColor = NetDarkGray, focusedBorderColor = NetWhite, unfocusedBorderColor = Color(0x33FFFFFF)),
modifier = Modifier.fillMaxWidth()
)
NetCard(onClick = onPlayLink) { Text("Play Link", color = NetWhite, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
}
}

@Composable
private fun DetailsScreen(
film: Film,
msg: String,
matches: List<TbFile>,
busy: Boolean,
resumePos: Long,
resumeDur: Long,
isInWatchlist: Boolean,
onToggleWatchlist: () -> Unit,
onPlay: (Long) -> Unit,
onPick: (TbFile, Long) -> Unit,
onBack: () -> Unit
) {
val hasProgress = resumePos > 5000 && resumeDur > 0
val progressFraction = if (hasProgress) (resumePos.toFloat() / resumeDur.toFloat()).coerceIn(0f, 1f) else 0f
val buttonText = if (hasProgress) "Resume" else "Play"

val ordered = remember(matches) {
matches.sortedWith(
compareBy(
{ episodeOf(it.name)?.first ?: 99 },
{ episodeOf(it.name)?.second ?: 99 },
{ it.name.lowercase() }
)
)
}

Box(Modifier.fillMaxSize()) {
if (film.backdrop.isNotEmpty()) {
AsyncImage(model = film.backdrop, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}
Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(NetBlack, NetBlack.copy(alpha = 0.9f), NetBlack.copy(alpha = 0.4f)))))

Column(
Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 40.dp),
verticalArrangement = Arrangement.spacedBy(16.dp)
) {
Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
var bFocused by remember { mutableStateOf(false) }
Box(
Modifier
.onFocusChanged { bFocused = it.isFocused }
.clip(RoundedCornerShape(6.dp))
.background(if (bFocused) Color(0xFF333333) else Color.Transparent)
.clickable(onClick = onBack)
.padding(horizontal = 10.dp, vertical = 4.dp)
) { Text("← Back", color = NetWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold) }

Text("Streaming on:", color = NetMuted, fontSize = 14.sp)
Box(Modifier.background(NetRed, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
Text(film.provider, color = NetWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}
}

Text(film.name, color = NetWhite, fontSize = 40.sp, fontWeight = FontWeight.Black)

Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
if (film.rating.isNotEmpty()) {
Text("★ ${film.rating}", color = Color(0xFF46D369), fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
Text(film.year, color = NetWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium)
Text(film.kind, color = NetMuted, fontSize = 15.sp)
}

Text(film.overview, color = NetWhite, fontSize = 15.sp, maxLines = 4, lineHeight = 22.sp, modifier = Modifier.width(600.dp))

Spacer(Modifier.height(8.dp))

var playFocused by remember { mutableStateOf(false) }
Box(
modifier = Modifier
.width(320.dp)
.height(52.dp)
.scale(if (playFocused) 1.04f else 1f)
.onFocusChanged { playFocused = it.isFocused }
.clip(RoundedCornerShape(6.dp))
.background(if (playFocused) NetWhite else Color(0xFFE5E5E5))
.then(if (playFocused) Modifier.border(2.5.dp, NetWhite, RoundedCornerShape(6.dp)) else Modifier)
.clickable { if (!busy) onPlay(resumePos) },
contentAlignment = Alignment.CenterStart
) {
if (hasProgress) {
Box(Modifier.fillMaxSize().background(Color(0xFFB0B0B0)))
Box(Modifier.fillMaxWidth(progressFraction).fillMaxHeight().background(Color(0xFF8C8C8C)))
}
Row(
Modifier.fillMaxSize().padding(horizontal = 24.dp),
verticalAlignment = Alignment.CenterVertically,
horizontalArrangement = Arrangement.spacedBy(12.dp)
) {
Text("▶", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
Text(if (busy) "Loading..." else buttonText, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}
}

var listFocused by remember { mutableStateOf(false) }
Box(
modifier = Modifier
.width(320.dp)
.height(48.dp)
.scale(if (listFocused) 1.04f else 1f)
.onFocusChanged { listFocused = it.isFocused }
.clip(RoundedCornerShape(6.dp))
.background(if (listFocused) Color(0xFF444444) else Color(0xFF2B2B2B))
.border(if (listFocused) 2.dp else 1.dp, if (listFocused) NetWhite else Color(0x55FFFFFF), RoundedCornerShape(6.dp))
.clickable(onClick = onToggleWatchlist),
contentAlignment = Alignment.Center
) {
Text(if (isInWatchlist) "✓ In My List" else "+ Add to My List", color = NetWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

if (busy) {
Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
CircularProgressIndicator(color = NetRed, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
Text("Matching in your TorBox library...", color = NetMuted, fontSize = 14.sp)
}
}

if (msg.isNotEmpty()) Text(msg, color = NetMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))

if (ordered.isNotEmpty()) {
Spacer(Modifier.height(10.dp))
Text("Select File Variant from TorBox:", color = NetWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
ordered.forEach { f ->
val ep = episodeOf(f.name)
val tag = buildString {
if (ep != null) append("S%02dE%02d".format(ep.first, ep.second))
val q = qualityOf(f.name)
if (q.isNotEmpty()) { if (isNotEmpty()) append(" · "); append(q) }
if (isNotEmpty()) append(" · ")
append(fmtSize(f.size))
}
NetCard(modifier = Modifier.fillMaxWidth(), onClick = { onPick(f, resumePos) }) {
Column(Modifier.padding(16.dp)) {
Text(f.name, color = NetWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
Text(tag, color = NetMuted, fontSize = 12.sp)
}
}
}
}
}
}
}

@Composable
private fun PlayerScreen(req: PlayReq, torboxKey: String, onProgress: (Long, Long) -> Unit) {
val context = LocalContext.current
var url by remember(req) { mutableStateOf(req.url) }
var error by remember(req) { mutableStateOf("") }
var buffering by remember(req) { mutableStateOf(true) }

LaunchedEffect(req) {
if (url.isEmpty() && req.torrentId >= 0) {
val link = Tb.link(torboxKey, req.torrentId, req.fileId)
if (link == null) error = "Failed to fetch stream link." else url = link
}
}

if (url.isEmpty()) {
Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
if (error.isEmpty()) CircularProgressIndicator(color = NetRed) else Text(error, color = NetWhite)
}
return
}

val exo = remember(url) {
ExoPlayer.Builder(context).build().apply {
setAudioAttributes(
AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
true
)
setMediaItem(MediaItem.fromUri(url), req.start.coerceAtLeast(0L))
prepare()
playWhenReady = true
}
}

DisposableEffect(exo) {
val listener = object : Player.Listener {
override fun onPlaybackStateChanged(state: Int) {
buffering = state == Player.STATE_BUFFERING
}
override fun onPlayerError(e: PlaybackException) {
error = "Playback error: ${e.errorCodeName}"
}
}
exo.addListener(listener)
onDispose {
onProgress(exo.currentPosition, exo.duration)
exo.removeListener(listener)
exo.release()
}
}

val lifecycleOwner = LocalLifecycleOwner.current
DisposableEffect(lifecycleOwner, exo) {
val obs = LifecycleEventObserver { _, event ->
when (event) {
Lifecycle.Event.ON_STOP -> exo.pause()
Lifecycle.Event.ON_START -> exo.play()
else -> {}
}
}
lifecycleOwner.lifecycle.addObserver(obs)
onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
}

LaunchedEffect(exo) {
while (true) {
delay(5000)
onProgress(exo.currentPosition, exo.duration)
}
}

Box(Modifier.fillMaxSize().background(Color.Black)) {
AndroidView(
factory = { ctx ->
PlayerView(ctx).apply {
player = exo
useController = true
controllerShowTimeoutMs = 3500
isFocusable = true
layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
requestFocus()
}
},
modifier = Modifier.fillMaxSize()
)
if (buffering) CircularProgressIndicator(color = NetRed, modifier = Modifier.align(Alignment.Center))
}
}
