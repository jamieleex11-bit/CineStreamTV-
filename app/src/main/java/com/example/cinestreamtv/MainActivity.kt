package com.example.cinestreamtv

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
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
private val JamieGold = Color(0xFFFFD166)

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

private val Resume.key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url
private val PlayReq.key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url

private fun poster(path: String) = "https://image.tmdb.org/t/p/w500$path"

private val demoFilms = listOf(
    Film("d1", 101, "Ted Lasso", "2023", "Series", poster("/kXJQv9GBnYJ0AvkWMe5JGPUlOW5.jpg"), "https://image.tmdb.org/t/p/w1280/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
        "An American football coach is hired to manage a British soccer team.", "8.6", " Apple TV+"),
    Film("d2", 102, "Succession", "2023", "Series", poster("/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg"), "https://image.tmdb.org/t/p/w1280/4HodYYKEIsGOdhrGi2Ud6XGjBn1.jpg",
        "The Roy family is known for controlling the biggest media and entertainment company.", "8.8", "MAX"),
    Film("d3", 103, "The Mandalorian", "2023", "Series", poster("/z4nriHz7ioQ0gX9Kj5kX5kX5kX5.jpg"), "https://image.tmdb.org/t/p/w1280/xOMo8BRK7PfcJv9JCnx7s5hj0PX.jpg",
        "The travels of a lone bounty hunter in the outer reaches of the galaxy.", "8.7", "Disney+"),
    Film("d4", 104, "Stranger Things", "2022", "Series", poster("/b0PlSFdDwbyK0cf5RxwDpaOJQvQ.jpg"), "https://image.tmdb.org/t/p/w1280/rAiYTfKGqDCRIIqo664sY9XZIvQ.jpg",
        "When a young boy vanishes, a small town uncovers a mystery involving secret experiments.", "8.7", "Netflix")
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

    suspend fun catalog(key: String): List<Film> = withContext(Dispatchers.IO) {
        try {
            val out = ArrayList<Film>()
            val providers = listOf("Netflix", " Apple TV+", "Disney+", "MAX", "Prime Video")
            // Load several pages of today's trending titles for a much larger catalog.
            val seen = HashSet<String>()
            for (page in 1..5) {
              val body = call("/trending/all/day?page=$page", key)
              val arr = JSONObject(body).optJSONArray("results") ?: continue
              for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = o.str("media_type")
                if (kind != "movie" && kind != "tv") continue
                val p = o.str("poster_path")
                if (p.isEmpty()) continue
                val id = o.optInt("id")
                if (!seen.add("$kind$id")) continue
                val name = o.str("title").ifEmpty { o.str("name") }
                val date = o.str("release_date").ifEmpty { o.str("first_air_date") }
                val b = o.str("backdrop_path")
                val r = o.optDouble("vote_average", 0.0)
                val assignedProvider = providers[Math.floorMod(id, providers.size)]

                out.add(
                    Film(
                        "$kind$id", id, name, date.take(4), if (kind == "tv") "Series" else "Movie",
                        poster(p), if (b.isEmpty()) "" else "https://image.tmdb.org/t/p/w1280$b",
                        o.str("overview"), if (r > 0) (Math.round(r * 10) / 10.0).toString() else "",
                        assignedProvider
                    )
                )
              }
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    suspend fun search(key: String, query: String): List<Film> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val body = call("/search/multi?query=$encoded&include_adult=false", key)
            val arr = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
            val providers = listOf("Netflix", " Apple TV+", "Disney+", "MAX", "Prime Video")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = o.str("media_type")
                    if (kind != "movie" && kind != "tv") continue
                    val posterPath = o.str("poster_path")
                    if (posterPath.isBlank()) continue
                    val id = o.optInt("id")
                    val name = o.str("title").ifBlank { o.str("name") }
                    val date = o.str("release_date").ifBlank { o.str("first_air_date") }
                    val backdropPath = o.str("backdrop_path")
                    val rating = o.optDouble("vote_average", 0.0)
                    add(Film(
                        "$kind$id", id, name, date.take(4), if (kind == "tv") "Series" else "Movie",
                        poster(posterPath), if (backdropPath.isBlank()) "" else "https://image.tmdb.org/t/p/w1280$backdropPath",
                        o.str("overview"), if (rating > 0) (Math.round(rating * 10) / 10.0).toString() else "",
                        providers[Math.floorMod(id, providers.size)]
                    ))
                }
            }
        } catch (_: Exception) { emptyList() }
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
        } catch (e: Exception) { Pair("TorBox error.", emptyList()) }
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
    var lastBackPressTime by remember { mutableStateOf(0L) }
    var detail by remember { mutableStateOf<Film?>(null) }
    var play by remember { mutableStateOf<PlayReq?>(null) }
    var catalog by remember { mutableStateOf(demoFilms) }
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
    val watchlist = remember { mutableStateListOf<Film>().also { list ->
        try {
            val saved = JSONArray(prefs.getString("watchlist", "[]"))
            for (i in 0 until saved.length()) {
                val o = saved.getJSONObject(i)
                list.add(Film(o.optString("id"), o.optInt("tmdbId"), o.optString("name"), o.optString("year"), o.optString("kind"), o.optString("poster"), o.optString("backdrop"), o.optString("overview"), o.optString("rating"), o.optString("provider")))
            }
        } catch (_: Exception) { }
    } }
    fun saveWatchlist() {
        val arr = JSONArray()
        watchlist.forEach { f -> arr.put(JSONObject().put("id", f.id).put("tmdbId", f.tmdbId).put("name", f.name).put("year", f.year).put("kind", f.kind).put("poster", f.poster).put("backdrop", f.backdrop).put("overview", f.overview).put("rating", f.rating).put("provider", f.provider)) }
        prefs.edit().putString("watchlist", arr.toString()).apply()
    }
    fun toggleWatchlist(f: Film) {
        val index = watchlist.indexOfFirst { it.id == f.id }
        if (index >= 0) watchlist.removeAt(index) else watchlist.add(0, f)
        saveWatchlist()
    }

    suspend fun loadLibrary(): List<TbFile> {
        if (torboxKey.isBlank()) {
            libStatus = "Add TorBox API Key."
            return emptyList()
        }
        libLoading = true
        return try {
            val res = Tb.library(torboxKey)
            library = res.second
            libStatus = res.first.ifEmpty { if (res.second.isEmpty()) "No TorBox files found." else "" }
            res.second
        } finally {
            libLoading = false
        }
    }

    fun open(f: Film) { detail = f; matches = emptyList(); detailMsg = "" }

    fun startTb(f: TbFile, image: String, startPos: Long = 0L) {
        play = PlayReq(f.name, image, "", f.torrentId, f.fileId, startPos)
    }

    fun startUrl(u: String, startPos: Long = 0L) {
        val name = u.substringAfterLast('/').substringBefore('?').ifEmpty { "Stream" }
        play = PlayReq(name, "", u, -1, -1, startPos)
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
        if (tmdbKey.isNotBlank()) {
            val fetched = Tmdb.catalog(tmdbKey)
            if (fetched.isNotEmpty()) catalog = fetched
        }
    }

    LaunchedEffect(query, tmdbKey) {
        if (query.isBlank()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        if (tmdbKey.isBlank()) {
            results = catalog.filter { it.name.contains(query, true) }
        } else {
            delay(150)
            results = Tmdb.search(tmdbKey, query)
        }
        searching = false
    }

    // Back button behaviour for Android TV and mobile:
    // 1. Close the player, if it is open.
    // 2. Close the details page, if it is open.
    // 3. From any other section, return to Home (the top bubble navigation remains visible).
    // 4. On Home, press Back twice within 2 seconds to exit.
    BackHandler(enabled = true) {
        when {
            play != null -> play = null
            detail != null -> detail = null
            screen != "Home" -> screen = "Home"
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(context, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
            if (p != null) {
                PlayerScreen(p, torboxKey) { pos, dur -> report(p, pos, dur) }
            } else if (d != null) {
                val matchingResume = resume.firstOrNull { it.name.contains(d.name, true) }
                DetailsScreen(
                    film = d, msg = detailMsg, matches = matches, busy = detailBusy,
                    isInWatchlist = watchlist.any { it.id == d.id },
                    onToggleWatchlist = { toggleWatchlist(d) },
                    onEpisodes = {
                        scope.launch {
                            detailBusy = true; matches = emptyList(); detailMsg = "Looking for episodes in your TorBox library..."
                            val loadedFiles = loadLibrary()
                            val n = norm(d.name)
                            matches = loadedFiles.filter { n.isNotEmpty() && norm(it.name).contains(n) }
                            detailMsg = if (matches.isEmpty()) "No matching episodes found in your TorBox library." else "Choose an episode below."
                            detailBusy = false
                        }
                    },
                    resumePos = matchingResume?.pos ?: 0L,
                    resumeDur = matchingResume?.dur ?: 0L,
                    onPlay = { posToStart ->
                        scope.launch {
                            detailBusy = true; matches = emptyList(); detailMsg = ""
                            val loadedFiles = loadLibrary()
                            if (torboxKey.isBlank() || loadedFiles.isEmpty()) {
                                detailMsg = libStatus.ifEmpty { "No TorBox files found." }
                            } else {
                                val n = norm(d.name)
                                val m = loadedFiles.filter { n.isNotEmpty() && norm(it.name).contains(n) }
                                if (m.isEmpty()) detailMsg = "Not found in TorBox library."
                                else if (m.size == 1) startTb(m[0], d.backdrop.ifBlank { d.poster }, posToStart)
                                else matches = m
                            }
                            detailBusy = false
                        }
                    },
                    onPick = { f, posToStart -> startTb(f, d.backdrop.ifBlank { d.poster }, posToStart) },
                    onBack = { detail = null }
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    NetTopNav(current = screen, onNav = { screen = it })
                    when (screen) {
                        "Home" -> HomeScreen(catalog = catalog, resume = resume, onFilm = { open(it) }, onResume = { r -> play = PlayReq(r.name, r.image, r.url, r.torrentId, r.fileId, r.pos) })
                        "Shows" -> ProviderFilteredScreen(title = "TV Shows", catalog = catalog.filter { it.kind == "Series" }, onFilm = { open(it) })
                        "Movies" -> ProviderFilteredScreen(title = "Movies", catalog = catalog.filter { it.kind == "Movie" }, onFilm = { open(it) })
                        "My List" -> ProviderFilteredScreen(title = "My Watchlist", catalog = watchlist.toList(), onFilm = { open(it) })
                        "Search" -> SearchScreen(query, { query = it }, results, searching) { open(it) }
                        "Settings" -> SettingsScreen(
                            torboxKey = torboxKey, onTorbox = { torboxKey = it.trim(); prefs.edit().putString("torbox_token", it.trim()).apply() },
                            onTest = { scope.launch { tbStatus = "Testing..."; tbStatus = Tb.test(torboxKey) } }, tbStatus = tbStatus,
                            tmdbKey = tmdbKey, onTmdb = { tmdbKey = it.trim(); prefs.edit().putString("tmdb_key", it.trim()).apply() },
                            link = linkText, onLink = { linkText = it }, onPlayLink = {
                                val directUrl = linkText.trim()
                                if (directUrl.startsWith("https://", ignoreCase = true) || directUrl.startsWith("http://", ignoreCase = true)) {
                                    startUrl(directUrl)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------- Exact 1-to-1 Netflix Capsule Top Navigation Bar ----------

@Composable
private fun NetTopNav(current: String, onNav: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(NetBlack)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(Color(0xFF222222))
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(50.dp))
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
    val active = isSelected || focused
    
    Box(
        modifier = Modifier
            .scale(if (focused) 1.05f else 1f)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(50.dp))
            .background(if (active) NetWhite else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 8.dp),
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
    onFocus: (() -> Unit)? = null,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .scale(if (focused) 1.06f else 1f)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .clip(RoundedCornerShape(6.dp))
            .background(NetDarkGray)
            .then(if (focused) Modifier.border(3.dp, NetWhite, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick)
    ) {
        content(this, focused)
    }
}

@Composable
private fun PosterCard(film: Film, onClick: () -> Unit, onFocus: (() -> Unit)? = null) {
    NetCard(modifier = Modifier.width(150.dp).height(220.dp), onClick = onClick, onFocus = onFocus) {
        AsyncImage(model = film.poster, contentDescription = film.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun ResumeCard(r: Resume, onClick: () -> Unit) {
    val progress = if (r.dur > 0L) (r.pos.toFloat() / r.dur.toFloat()).coerceIn(0f, 1f) else 0f
    val remainingMs = (r.dur - r.pos).coerceAtLeast(0L)
    val remainingMinutes = (remainingMs / 60000L).toInt()
    val remainingLabel = if (r.dur > 0L && remainingMinutes > 0) {
        if (remainingMinutes >= 60) "${remainingMinutes / 60}h ${remainingMinutes % 60}m left" else "$remainingMinutes min left"
    } else "Continue watching"

    NetCard(modifier = Modifier.width(300.dp).height(190.dp), onClick = onClick) {
        if (r.image.isNotBlank()) {
            AsyncImage(
                model = r.image,
                contentDescription = r.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0x55000000), Color(0xEE000000)))
            )
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = r.name,
                color = NetWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(remainingLabel, color = Color(0xFFDDDDDD), fontSize = 11.sp, maxLines = 1)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF777777))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(NetRed))
            }
        }
    }
}

@Composable
private fun Shelf(
    title: String,
    list: List<Film>,
    onFilm: (Film) -> Unit,
    onFilmFocus: (Film) -> Unit = {}
) {
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            title, color = NetWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(list, key = { it.id }, contentType = { "poster" }) { f ->
                PosterCard(f, onClick = { onFilm(f) }, onFocus = { onFilmFocus(f) })
            }
        }
    }
}

// ---------- Full Edge-to-Edge Immersive Hero Banner ----------
@Composable
private fun HeroBanner(film: Film, onOpen: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(500.dp)
            .background(NetBlack)
            .clickable(onClick = onOpen)
    ) {
        Crossfade(targetState = film.backdrop, label = "hero-backdrop") { backdrop ->
            if (backdrop.isNotEmpty()) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = film.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, NetBlack), startY = 250f)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(NetBlack.copy(alpha = 0.9f), Color.Transparent), endX = 700f)))
        
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 40.dp).width(650.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("STREAMING ON", color = NetMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Box(Modifier.background(NetRed, RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(film.provider, color = NetWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(film.name, color = NetWhite, fontSize = 44.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            
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
        }
    }
}

@Composable
private fun HomeScreen(
    catalog: List<Film>,
    resume: List<Resume>,
    onFilm: (Film) -> Unit,
    onResume: (Resume) -> Unit
) {
    val defaultHero = catalog.firstOrNull() ?: demoFilms.first()
    var selectedHero by remember(catalog) { mutableStateOf(defaultHero) }
    val hero = selectedHero.takeIf { selected -> catalog.any { it.id == selected.id } || demoFilms.any { it.id == selected.id } } ?: defaultHero
    val onHeroFocus: (Film) -> Unit = { selectedHero = it }

    val trending = catalog.take(10)
    val appleFilms = catalog.filter { it.provider == " Apple TV+" }
    val disneyFilms = catalog.filter { it.provider == "Disney+" }
    val hboFilms = catalog.filter { it.provider == "MAX" }
    val netflixFilms = catalog.filter { it.provider == "Netflix" }
    val primeFilms = catalog.filter { it.provider == "Prime Video" }

    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()

    LazyColumn(
        state = homeListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item(key = "hero", contentType = "hero") { HeroBanner(hero) { onFilm(hero) } }
        
        if (resume.isNotEmpty()) {
            item(key = "continue-watching", contentType = "continue-watching") {
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(
                        "Continue Watching", color = NetWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(resume, key = { it.key }) { r -> ResumeCard(r) { onResume(r) } }
                    }
                }
            }
        }

        item(key = "trending", contentType = "trending") { TrendingTodayShelf(trending, onFilm, onHeroFocus) }
        item(key = "apple", contentType = "shelf") { Shelf(" Apple TV+ Originals", appleFilms, onFilm, onHeroFocus) }
        item(key = "disney", contentType = "shelf") { Shelf("Disney+ Collection", disneyFilms, onFilm, onHeroFocus) }
        item(key = "max", contentType = "shelf") { Shelf("MAX (HBO)", hboFilms, onFilm, onHeroFocus) }
        item(key = "netflix", contentType = "shelf") { Shelf("Netflix Originals", netflixFilms, onFilm, onHeroFocus) }
        item(key = "prime", contentType = "shelf") { Shelf("Prime Video", primeFilms, onFilm, onHeroFocus) }

        item(key = "home-bottom-space", contentType = "spacer") { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ProviderFilteredScreen(title: String, catalog: List<Film>, onFilm: (Film) -> Unit) {
    val apple = catalog.filter { it.provider == " Apple TV+" }
    val disney = catalog.filter { it.provider == "Disney+" }
    val hbo = catalog.filter { it.provider == "MAX" }
    val netflix = catalog.filter { it.provider == "Netflix" }
    val prime = catalog.filter { it.provider == "Prime Video" }

    LazyColumn(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
        item {
            Text(
                title, color = NetWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 48.dp, bottom = 16.dp)
            )
        }
        item { Shelf(" Apple TV+", apple, onFilm) }
        item { Shelf("Disney+", disney, onFilm) }
        item { Shelf("MAX (HBO)", hbo, onFilm) }
        item { Shelf("Netflix", netflix, onFilm) }
        item { Shelf("Prime Video", prime, onFilm) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun SearchScreen(
    query: String, onQuery: (String) -> Unit, results: List<Film>, searching: Boolean, onFilm: (Film) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(results, key = { it.id }) { f -> PosterCard(f, onClick = { onFilm(f) }) }
        }
    }
}

@Composable
private fun SettingsScreen(
    torboxKey: String, onTorbox: (String) -> Unit, onTest: () -> Unit, tbStatus: String,
    tmdbKey: String, onTmdb: (String) -> Unit, link: String, onLink: (String) -> Unit, onPlayLink: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Spacer(Modifier.height(28.dp))
        Text("Made by Jamie", color = JamieGold, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ActionPillButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.width(320.dp).height(48.dp).scale(if (focused) 1.03f else 1f)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) NetWhite else Color(0xFF303030))
            .border(1.dp, if (focused) NetWhite else Color(0xFF555555), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, color = if (focused) Color.Black else NetWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
private fun TrendingTodayShelf(list: List<Film>, onFilm: (Film) -> Unit, onFocus: (Film) -> Unit) {
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("Top 10 Trending Today", color = NetWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 48.dp, bottom = 10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list.take(10), key = { "trending-${it.id}" }, contentType = { "trending-card" }) { film ->
                val rank = list.indexOf(film) + 1
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                    Text(rank.toString(), color = Color(0xFF8A8A8A), fontSize = 84.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(62.dp).align(Alignment.Bottom))
                    PosterCard(film, onClick = { onFilm(film) }, onFocus = { onFocus(film) })
                }
            }
        }
    }
}

@Composable
private fun DetailsScreen(
    film: Film,
    msg: String,
    matches: List<TbFile>,
    busy: Boolean,
    isInWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    onEpisodes: () -> Unit,
    resumePos: Long,
    resumeDur: Long,
    onPlay: (Long) -> Unit,
    onPick: (TbFile, Long) -> Unit,
    onBack: () -> Unit
) {
    val hasProgress = resumePos > 5000 && resumeDur > 0
    val progressFraction = if (hasProgress) (resumePos.toFloat() / resumeDur.toFloat()).coerceIn(0f, 1f) else 0f
    val buttonText = if (hasProgress) "Resume" else "Play"

    Box(Modifier.fillMaxSize()) {
        if (film.backdrop.isNotEmpty()) {
            AsyncImage(
                model = film.backdrop, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(NetBlack, NetBlack.copy(alpha = 0.9f), NetBlack.copy(alpha = 0.4f)))))
        
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Streaming on:", color = NetMuted, fontSize = 14.sp)
                Box(Modifier.background(NetRed, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(film.provider, color = NetWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text(film.name, color = NetWhite, fontSize = 42.sp, fontWeight = FontWeight.Black)

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
                    .border(if (playFocused) 2.5.dp else 0.dp, NetWhite, RoundedCornerShape(6.dp))
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
                    Text(
                        text = if (busy) "Loading..." else buttonText,
                        color = Color.Black,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            ActionPillButton(label = if (film.kind == "Series") "Episodes" else "Find in TorBox", onClick = onEpisodes)
            ActionPillButton(label = if (isInWatchlist) "✓ Added to My List" else "+ Add to My List", onClick = onToggleWatchlist)

            if (msg.isNotEmpty()) {
                Text(msg, color = NetMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }

            if (matches.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Select File Variant from TorBox:", color = NetWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                matches.forEach { f ->
                    NetCard(modifier = Modifier.fillMaxWidth(), onClick = { onPick(f, resumePos) }) {
                        Text(f.name + " (" + fmtSize(f.size) + ")", color = NetWhite, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
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
            if (link == null) error = "Failed to fetch stream link." else url = link
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
