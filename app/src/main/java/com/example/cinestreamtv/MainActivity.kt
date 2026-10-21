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
            val body = call("/trending/all/week", key)
            val arr = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
            val out = ArrayList<Film>()
            val providers = listOf("Netflix", " Apple TV+", "Disney+", "MAX", "Prime Video")
            
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = o.str("media_type")
                if (kind != "movie" && kind != "tv") continue
                val p = o.str("poster_path")
                if (p.isEmpty()) continue
                val id = o.optInt("id")
                val name = o.str("title").ifEmpty { o.str("name") }
                val date = o.str("release_date").ifEmpty { o.str("first_air_date") }
                val b = o.str("backdrop_path")
                val r = o.optDouble("vote_average", 0.0)
                val assignedProvider = providers[id % providers.size]

                out.add(
                    Film(
                        "$kind$id", id, name, date.take(4), if (kind == "tv") "Series" else "Movie",
                        poster(p), if (b.isEmpty()) "" else "https://image.tmdb.org/t/p/w1280$b",
                        o.str("overview"), if (r > 0) (Math.round(r * 10) / 10.0).toString() else "",
                        assignedProvider
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

    suspend fun loadLibrary() {
        if (torboxKey.isBlank()) { libStatus = "Add TorBox API Key."; return }
        libLoading = true
        val res = Tb.library(torboxKey)
        library = res.second
        libStatus = res.first.ifEmpty { if (res.second.isEmpty()) "No TorBox files found." else "" }
        libLoading = false
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
            results = Tmdb.catalog(tmdbKey).filter { it.name.contains(query, true) }
        }
        searching = false
    }

    BackHandler(enabled = play != null || detail != null) {
        if (play != null) play = null else detail = null
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
                    resumePos = matchingResume?.pos ?: 0L,
                    resumeDur = matchingResume?.dur ?: 0L,
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
                                else if (m.size == 1) startTb(m[0], d.poster, posToStart)
                                else matches = m
                            }
                            detailBusy = false
                        }
                    },
                    onPick = { f, posToStart -> startTb(f, d.poster, posToStart) },
                    onBack = { detail = null }
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    NetTopNav(current = screen, onNav = { screen = it })
                    when (screen) {
                        "Home" -> HomeScreen(catalog = catalog, resume = resume, onFilm = { open(it) }, onResume = { r -> play = PlayReq(r.name, r.image, r.url, r.torrentId, r.fileId, r.pos) })
                        "Shows" -> ProviderFilteredScreen(title = "TV Shows", catalog = catalog.filter { it.kind == "Series" }, onFilm = { open(it) })
                        "Movies" -> ProviderFilteredScreen(title = "Movies", catalog = catalog.filter { it.kind == "Movie" }, onFilm = { open(it) })
                        "Search" -> SearchScreen(query, { query = it }, results, searching) { open(it) }
                        "Settings" -> SettingsScreen(
                            torboxKey = torboxKey, onTorbox = { torboxKey = it.trim(); prefs.edit().putString("torbox_token", it.trim()).apply() },
                            onTest = { scope.launch { tbStatus = "Testing..."; tbStatus = Tb.test(torboxKey) } }, tbStatus = tbStatus,
                            tmdbKey = tmdbKey, onTmdb = { tmdbKey = it.trim(); prefs.edit().putString("tmdb_key", it.trim()).apply() },
