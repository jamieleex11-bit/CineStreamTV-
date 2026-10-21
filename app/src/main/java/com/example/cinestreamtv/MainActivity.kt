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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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

// ---------- Modern Netflix TV Color Palette ----------
private val NetBlack = Color(0xFF141414)
private val NetSurface = Color(0xFF1B1B1B)
private val NetFocus = Color(0xFF2B2B2B)
private val NetRed = Color(0xFFE50914)
private val NetWhite = Color(0xFFFFFFFF)
private val NetMuted = Color(0xFF8C8C8C)

// ---------- Data Models ----------
data class Film(
    val id: String,
    val name: String,
    val year: String,
    val kind: String,
    val poster: String,
    val backdrop: String,
    val overview: String,
    val rating: String
)

data class Resume(
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
    val name: String,
    val image: String,
    val url: String,
    val torrentId: Int,
    val fileId: Int,
    val start: Long
)

private data class RowDef(val title: String, val path: String, val kind: String)

private val Resume.key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url
private val PlayReq.key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url

private fun poster(path: String) = "https://image.tmdb.org/t/p/w500$path"

private val demoFilms = listOf(
    Film("d1", "Stranger Things", "2022", "Series", poster("/49WJfeN0moxb9IPfGn8AIqMGskD.jpg"), "https://image.tmdb.org/t/p/w1280/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
        "When a young boy vanishes, a small town uncovers a mystery involving secret experiments and terrifying supernatural forces.", "8.6"),
    Film("d2", "Bridgerton", "2020", "Series", poster("/luoKpgVwi1E5nQsi7W0UuKHu2Rq.jpg"), "https://image.tmdb.org/t/p/w1280/kXJQv9GBnYJ0AvkWMe5JGPUlOW5.jpg",
        "The eight close-knit siblings of the Bridgerton family look for love and happiness in London high society.", "8.1"),
    Film("d3", "Interstellar", "2014", "Movie", poster("/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"), "https://image.tmdb.org/t/p/w1280/rAiYTfKGqDCRIIqo664sY9XZIvQ.jpg",
        "A team travels beyond this galaxy to discover whether mankind has a future among the stars.", "8.6"),
    Film("d4", "Dune: Part Two", "2024", "Movie", poster("/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg"), "https://image.tmdb.org/t/p/w1280/xOMo8BRK7PfcJv9JCnx7s5hj0PX.jpg",
        "Paul Atreides unites with Chani and the Fremen while seeking revenge.", "8.3")
)

private val demoRows: List<Pair<String, List<Film>>> = listOf(
    "Trending Now" to demoFilms,
    "Continue Watching & Favorites" to demoFilms.reversed()
)

// ---------- Helpers ----------
private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

private fun fmtSize(b: Long): String {
    val gb = b / 1073741824.0
    return if (gb >= 1) "${Math.round(gb * 10) / 10.0} GB" else "${b / 1048576} MB"
}

private fun loadResume(p: SharedPreferences): List<Resume> = try {
    val arr = JSONArray(p.getString("resume", "[]"))
    (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        Resume(o.str("name"), o.str("image"), o.str("url"), o.optInt("tid", -1), o.optInt("fid", -1), o.optLong("pos"), o.optLong("dur"))
    }
} catch (e: Exception) { emptyList() }

private fun saveResume(p: SharedPreferences, list: List<Resume>) {
    val arr = JSONArray()
    list.forEach {
        arr.put(
            JSONObject().put("name", it.name).put("image", it.image).put("url", it.url)
                .put("tid", it.torrentId).put("fid", it.fileId).put("pos", it.pos).put("dur", it.dur)
        )
    }
    p.edit().putString("resume", arr.toString()).apply()
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
    private fun call(path: String, key: String): String {
        val sep = if (path.contains("?")) "&" else "?"
        return if (key.length > 40) httpGet("$BASE$path", key) else httpGet("$BASE$path${sep}api_key=$key")
    }
    suspend fun list(path: String, key: String, forceKind: String): List<Film> = withContext(Dispatchers.IO) {
        try {
            val arr = JSONObject(call(path, key)).optJSONArray("results") ?: return@withContext emptyList()
            val out = ArrayList<Film>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = if (forceKind.isNotEmpty()) forceKind else o.str("media_type")
                if (kind != "movie" && kind != "tv") continue
                val p = o.str("poster_path")
                if (p.isEmpty()) continue
                val name = o.str("title").ifEmpty { o.str("name") }
                val date = o.str("release_date").ifEmpty { o.str("first_air_date") }
                val b = o.str("backdrop_path")
                val r = o.optDouble("vote_average", 0.0)
                out.add(
                    Film(
                        "$kind${o.optInt("id")}", name, date.take(4), if (kind == "tv") "Series" else "Movie",
                        poster(p), if (b.isEmpty()) "" else "https://image.tmdb.org/t/p/w1280$b",
                        o.str("overview"), if (r > 0) (Math.round(r * 10) / 10.0).toString() else ""
                    )
                )
            }
            out
        } catch (e: Exception) { emptyList() }
    }
}

private object Tb {
    private const val BASE = "https://api.torbox.app/v1/api"
    private val VIDEO = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".ts")

    suspend fun library(key: String): Pair<String, List<TbFile>> = withContext(Dispatchers.IO) {
        try {
            val body = httpGet("$BASE/torrents/mylist?limit=100&offset=0", key)
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val out = ArrayList<TbFile>()
            for (i in 0 until data.length()) {
                val t = data.getJSONObject(i)
                val files = t.optJSONArray("files") ?: continue
                for (j in 0 until files.length()) {
                    val f = files.getJSONObject(j)
                    val name = f.str("name")
                    if (VIDEO.any { name.lowercase().endsWith(it) }) {
                        out.add(TbFile(t.optInt("id"), f.optInt("id"), name.substringAfterLast('/'), f.optLong("size")))
                    }
                }
            }
            Pair("", out)
        } catch (e: Exception) { Pair("TorBox connection error.", emptyList()) }
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
                if (o.optBoolean("success", false)) "Connected Successfully" else "Invalid API Key"
            } catch (e: Exception) { "Connection Failed" }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(20, 20, 20)
        window.navigationBarColor = android.graphics.Color.rgb(20, 20, 20)
        setContent { NetStreamApp() }
    }
}

@Composable
fun NetStreamApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("netstream", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var torboxKey by remember { mutableStateOf(prefs.getString("torbox_token", "") ?: "") }
    var tmdbKey by remember { mutableStateOf(prefs.getString("tmdb_key", "") ?: "") }
    var screen by remember { mutableStateOf("Home") }
    var detail by remember { mutableStateOf<Film?>(null) }
    var play by remember { mutableStateOf<PlayReq?>(null) }
    var rows by remember { mutableStateOf(demoRows) }
    var catalogStatus by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Film>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf<List<TbFile>>(emptyList()) }
    var libStatus by remember { mutableStateOf("") }
    var libLoading by remember { mutableStateOf(false) }
    var tbStatus by remember { mutableStateOf("") }
    var linkText by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<TbFile>>(emptyList()) }
    var detailMsg by remember { mutableStateOf("") }
    var detailBusy by remember { mutableStateOf(false) }
    val resume = remember { mutableStateListOf<Resume>().also { it.addAll(loadResume(prefs)) } }

    suspend fun loadLibrary() {
        if (torboxKey.isBlank()) { libStatus = "Add TorBox API Key in Settings."; return }
        libLoading = true
        val res = Tb.library(torboxKey)
        library = res.second
        libStatus = res.first.ifEmpty { if (res.second.isEmpty()) "No files found in TorBox." else "" }
        libLoading = false
    }

    fun open(f: Film) { detail = f; matches = emptyList(); detailMsg = "" }

    fun startTb(f: TbFile, image: String) {
        val start = resume.firstOrNull { it.torrentId == f.torrentId && it.fileId == f.fileId }?.pos ?: 0L
        play = PlayReq(f.name, image, "", f.torrentId, f.fileId, start)
    }

    fun startUrl(u: String) {
        val name = u.substringAfterLast('/').substringBefore('?').ifEmpty { "Stream" }
        val start = resume.firstOrNull { it.url == u }?.pos ?: 0L
        play = PlayReq(name, "", u, -1, -1, start)
    }

    fun report(req: PlayReq, pos: Long, dur: Long) {
        if (pos < 3000) return
        val d = if (dur > 0) dur else 0L
        val finished = d > 0 && pos > d * 0.95
        val idx = resume.indexOfFirst { it.key == req.key }
        if (idx >= 0) resume.removeAt(idx)
        if (!finished) resume.add(0, Resume(req.name, req.image, req.url, req.torrentId, req.fileId, pos, d))
        while (resume.size > 10) resume.removeAt(resume.size - 1)
        saveResume(prefs, resume)
    }

    LaunchedEffect(tmdbKey) {
        if (tmdbKey.isBlank()) { rows = demoRows; return@LaunchedEffect }
        val defs = listOf(
            RowDef("Trending Now", "/trending/all/week", ""),
            RowDef("Popular Movies", "/movie/popular", "movie"),
            RowDef("Popular TV Shows", "/tv/popular", "tv")
        )
        val loaded = ArrayList<Pair<String, List<Film>>>()
        for (d in defs) {
            val r = Tmdb.list(d.path, tmdbKey, d.kind)
            if (r.isNotEmpty()) loaded.add(d.title to r)
        }
        if (loaded.isNotEmpty()) rows = loaded
    }

    LaunchedEffect(query, tmdbKey) {
        if (query.isBlank()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        if (tmdbKey.isBlank()) {
            results = demoFilms.filter { it.name.contains(query, true) }
        } else {
            delay(300)
            results = Tmdb.list("/search/multi?query=${URLEncoder.encode(query, "UTF-8")}&include_adult=false", tmdbKey, "")
        }
        searching = false
    }

    LaunchedEffect(screen) { if (screen == "Library") loadLibrary() }

    BackHandler(enabled = play != null || detail != null) {
        if (play != null) play = null else detail = null
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = NetBlack, surface = NetSurface, primary = NetRed,
            onBackground = NetWhite, onSurface = NetWhite
        )
    ) {
        Box(Modifier.fillMaxSize().background(NetBlack)) {
            val p = play
            val d = detail
            if (p != null) {
                PlayerScreen(p, torboxKey) { pos, dur -> report(p, pos, dur) }
            } else if (d != null) {
                DetailsScreen(
                    film = d, msg = detailMsg, matches = matches, busy = detailBusy,
                    onPlay = {
                        scope.launch {
                            detailBusy = true; matches = emptyList(); detailMsg = ""
                            loadLibrary()
                            if (torboxKey.isBlank() || library.isEmpty()) {
                                detailMsg = libStatus.ifEmpty { "No TorBox files available." }
                            } else {
                                val n = norm(d.name)
                                val m = library.filter { n.isNotEmpty() && norm(it.name).contains(n) }
                                if (m.isEmpty()) detailMsg = "Not found in your TorBox files."
                                else if (m.size == 1) startTb(m[0], d.poster)
                                else matches = m
                            }
                            detailBusy = false
                        }
                    },
                    onPick = { startTb(it, d.poster) },
                    onBack = { detail = null }
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    TopNav(screen) { screen = it }
                    when (screen) {
                        "Home" -> HomeScreen(
                            rows = rows, resume = resume, hasTmdb = tmdbKey.isNotBlank(),
                            onFilm = { open(it) },
                            onResume = { r -> play = PlayReq(r.name, r.image, r.url, r.torrentId, r.fileId, r.pos) },
                            onSettings = { screen = "Settings" }
                        )
                        "Search" -> SearchScreen(query, { query = it }, results, searching, tmdbKey.isNotBlank()) { open(it) }
                        "Library" -> LibraryScreen(library, libStatus, libLoading,
                            onRefresh = { scope.launch { loadLibrary() } },
                            onPlay = { startTb(it, "") })
                        "Settings" -> SettingsScreen(
                            torboxKey = torboxKey,
                            onTorbox = { torboxKey = it.trim(); prefs.edit().putString("torbox_token", it.trim()).apply() },
                            onTest = { scope.launch { tbStatus = "Testing..."; tbStatus = Tb.test(torboxKey) } },
                            tbStatus = tbStatus,
                            tmdbKey = tmdbKey,
                            onTmdb = { tmdbKey = it.trim(); prefs.edit().putString("tmdb_key", it.trim()).apply() },
                            catalogStatus = catalogStatus,
                            link = linkText, onLink = { linkText = it },
                            onPlayLink = { if (linkText.startsWith("https://")) startUrl(linkText.trim()) }
                        )
                    }
                }
            }
        }
    }
}

// ---------- Modern Streamlined Components (Rounded & Smooth) ----------

@Composable
private fun ModernCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp), // Modern softer curved cards
    onClick: () -> Unit,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .scale(if (focused) 1.06f else 1f)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) NetFocus else NetSurface)
            .then(if (focused) Modifier.border(2.5.dp, NetWhite, shape) else Modifier)
            .clickable(onClick = onClick)
    ) {
        content(this, focused)
    }
}

@Composable
private fun NetButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .scale(if (focused) 1.04f else 1f)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) NetWhite else Color(0x55808080))
            .then(if (focused) Modifier.border(2.dp, NetWhite, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (primary) Color.Black else NetWhite,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun TopNav(current: String, onNav: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Text("N", color = NetRed, fontSize = 30.sp, fontWeight = FontWeight.Black)
        listOf("Home", "Search", "Library", "Settings").forEach { tab ->
            var focused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .scale(if (focused) 1.05f else 1f)
                    .clickable { onNav(tab) }
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    tab,
                    color = if (current == tab) NetWhite else NetMuted,
                    fontSize = 15.sp,
                    fontWeight = if (current == tab) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun PosterCard(film: Film, onClick: () -> Unit) {
    ModernCard(modifier = Modifier.width(135.dp).height(200.dp), onClick = onClick) {
        AsyncImage(model = film.poster, contentDescription = film.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun ContinueCard(r: Resume, onClick: () -> Unit) {
    ModernCard(modifier = Modifier.width(260.dp).height(145.dp), onClick = onClick) {
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
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            title, color = NetWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(list) { f -> PosterCard(f) { onFilm(f) } }
        }
    }
}

@Composable
private fun HeroBanner(film: Film, onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(350.dp)) {
        if (film.backdrop.isNotEmpty()) {
            AsyncImage(model = film.backdrop, contentDescription = film.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, NetBlack), startY = 120f)))
        
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 32.dp).width(500.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("N", color = NetRed, fontSize = 14.sp, fontWeight = FontWeight.Black)
                Text("SERIES", color = NetWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            Text(film.name, color = NetWhite, fontSize = 34.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(film.overview, color = NetWhite, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NetButton("▶ Play", primary = true) { onOpen() }
                NetButton("ℹ More Info") { onOpen() }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    rows: List<Pair<String, List<Film>>>,
    resume: List<Resume>,
    hasTmdb: Boolean,
    onFilm: (Film) -> Unit,
    onResume: (Resume) -> Unit,
    onSettings: () -> Unit
) {
    val hero = rows.firstOrNull()?.second?.firstOrNull() ?: demoFilms.first()
    LazyColumn(Modifier.fillMaxSize()) {
        item { HeroBanner(hero) { onFilm(hero) } }
        if (resume.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("Continue Watching", color = NetWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(resume) { r -> ContinueCard(r) { onResume(r) } }
                    }
                }
            }
        }
        items(rows) { row -> Shelf(row.first, row.second, onFilm) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun SearchScreen(
    query: String, onQuery: (String) -> Unit, results: List<Film>, searching: Boolean, hasTmdb: Boolean, onFilm: (Film) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            label = { Text("Search title...") },
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = NetWhite, unfocusedTextColor = NetWhite, focusedBorderColor = NetWhite),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(135.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(results) { f -> PosterCard(f) { onFilm(f) } }
        }
    }
}

@Composable
private fun LibraryScreen(files: List<TbFile>, status: String, loading: Boolean, onRefresh: () -> Unit, onPlay: (TbFile) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("TorBox Cloud Library", color = NetWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            NetButton("Refresh") { onRefresh() }
            if (loading) CircularProgressIndicator(color = NetRed, modifier = Modifier.size(20.dp))
        }
        if (status.isNotEmpty()) Text(status, color = NetMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(files) { f ->
                ModernCard(modifier = Modifier.fillMaxWidth(), onClick = { onPlay(f) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, color = NetWhite, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(fmtSize(f.size), color = NetMuted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    torboxKey: String, onTorbox: (String) -> Unit, onTest: () -> Unit, tbStatus: String,
    tmdbKey: String, onTmdb: (String) -> Unit, catalogStatus: String, link: String, onLink: (String) -> Unit, onPlayLink: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", color = NetWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("TorBox API Key", color = NetMuted, fontSize = 14.sp)
        OutlinedTextField(value = torboxKey, onValueChange = onTorbox, singleLine = true, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth())
        NetButton("Test TorBox Connection") { onTest() }
        if (tbStatus.isNotEmpty()) Text(tbStatus, color = NetWhite, fontSize = 14.sp)
        
        Text("TMDB API Key (For Catalogs & Posters)", color = NetMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        OutlinedTextField(value = tmdbKey, onValueChange = onTmdb, singleLine = true, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth())
        
        Text("Direct Stream Link", color = NetMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        OutlinedTextField(value = link, onValueChange = onLink, singleLine = true, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth())
        NetButton("Play Link") { onPlayLink() }
    }
}

@Composable
private fun DetailsScreen(film: Film, msg: String, matches: List<TbFile>, busy: Boolean, onPlay: () -> Unit, onPick: (TbFile) -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (film.backdrop.isNotEmpty()) {
            AsyncImage(model = film.backdrop, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(NetBlack, NetBlack.copy(alpha = 0.9f), NetBlack.copy(alpha = 0.3f)))))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 40.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                AsyncImage(model = film.poster, contentDescription = film.name, modifier = Modifier.width(180.dp).height(270.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(film.name, color = NetWhite, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text(film.overview, color = NetWhite, fontSize = 15.sp, maxLines = 5, lineHeight = 22.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NetButton(if (busy) "Loading..." else "▶ Play", primary = true) { if (!busy) onPlay() }
                        NetButton("Back") { onBack() }
                    }
                    if (msg.isNotEmpty()) Text(msg, color = NetMuted, fontSize = 14.sp)
                    if (matches.isNotEmpty()) {
                        Text("Select File Variant:", color = NetWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        matches.forEach { f ->
                            ModernCard(modifier = Modifier.fillMaxWidth(), onClick = { onPick(f) }) {
                                Text(f.name + " (" + fmtSize(f.size) + ")", color = NetWhite, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
                            }
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

    LaunchedEffect(req) {
        if (url.isEmpty() && req.torrentId >= 0) {
            val link = Tb.link(torboxKey, req.torrentId, req.fileId)
            if (link == null) error = "Failed to fetch stream link from TorBox." else url = link
        }
    }

    if (url.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error.isEmpty()) CircularProgressIndicator(color = NetRed) else Text(error, color = NetWhite)
        }
    } else {
        val exo = remember(url) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                if (req.start > 5000) seekTo(req.start)
                prepare()
                playWhenReady = true
            }
        }
        LaunchedEffect(exo) {
            while (true) {
                delay(5000)
                onProgress(exo.currentPosition, exo.duration)
            }
        }
        DisposableEffect(exo) {
            onDispose {
                onProgress(exo.currentPosition, exo.duration)
                exo.release()
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exo
                        useController = true
                        isFocusable = true
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        requestFocus()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
