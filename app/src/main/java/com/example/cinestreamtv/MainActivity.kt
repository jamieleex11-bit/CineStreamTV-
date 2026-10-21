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

// ---------- Colors ----------
private val Bg = Color(0xFF040714)
private val Deep = Color(0xFF0F1F5C)
private val Surf = Color(0xFF1A2147)
private val Muted = Color(0xFFA9B0C8)
private val Red = Color(0xFFE50914)
private val Glass = Color(0x33FFFFFF)

// ---------- Data ----------
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
    Film("d1", "Interstellar", "2014", "Movie", poster("/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"), "",
        "A team travels beyond this galaxy to discover whether mankind has a future among the stars.", "8.5"),
    Film("d2", "Dune: Part Two", "2024", "Movie", poster("/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg"), "",
        "Paul Atreides unites with Chani and the Fremen while seeking revenge.", "8.2"),
    Film("d3", "The Batman", "2022", "Movie", poster("/74xTEgt7R36Fpooo50r9T25onhq.jpg"), "",
        "A detective uncovers corruption in Gotham City.", "7.7"),
    Film("d4", "Oppenheimer", "2023", "Movie", poster("/ptpr0kGAckfQkJeJIt8st5dglvd.jpg"), "",
        "The story of the physicist whose work changed the world.", "8.1"),
    Film("d5", "Breaking Bad", "2008", "Series", poster("/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg"), "",
        "A chemistry teacher enters the world of crime.", "8.9"),
    Film("d6", "Dark", "2017", "Series", poster("/apbrbWs8M9lyOpJYYy0UXjN5J0E.jpg"), "",
        "A disappearance exposes the secrets of a small German town.", "8.4"),
    Film("d7", "Inception", "2010", "Movie", poster("/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg"), "",
        "A skilled thief enters dreams to steal secrets.", "8.4")
)

private val demoRows: List<Pair<String, List<Film>>> = listOf(
    "Trending" to demoFilms,
    "Movies" to demoFilms.filter { it.kind == "Movie" },
    "Series" to demoFilms.filter { it.kind == "Series" }
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
} catch (e: Exception) {
    emptyList()
}

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
    } finally {
        c.disconnect()
    }
}

// ---------- TMDB (catalogue + search) ----------
private object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"

    private fun call(path: String, key: String): String {
        val sep = if (path.contains("?")) "&" else "?"
        return if (key.length > 40) httpGet("$BASE$path", key) else httpGet("$BASE$path${sep}api_key=$key")
    }

    suspend fun list(path: String, key: String, forceKind: String): List<Film> = withContext(Dispatchers.IO) {
        try {
            parse(call(path, key), forceKind)
        } catch (e: Exception) {
            emptyList<Film>()
        }
    }

    private fun parse(body: String, forceKind: String): List<Film> {
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
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
        return out
    }
}

// ---------- TorBox (your own library) ----------
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
            Pair<String, List<TbFile>>("", out)
        } catch (e: Exception) {
            Pair<String, List<TbFile>>("Couldn't reach TorBox (${e.message}). Check your API key.", emptyList())
        }
    }

    suspend fun link(key: String, torrentId: Int, fileId: Int): String? = withContext(Dispatchers.IO) {
        try {
            val body = httpGet("$BASE/torrents/requestdl?token=$key&torrent_id=$torrentId&file_id=$fileId&zip_link=false", key)
            val o = JSONObject(body)
            if (o.optBoolean("success", false)) o.str("data").ifEmpty { null } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun test(key: String): String = withContext(Dispatchers.IO) {
        if (key.isBlank()) {
            "Enter your TorBox API key first."
        } else {
            try {
                val o = JSONObject(httpGet("$BASE/user/me", key))
                if (o.optBoolean("success", false)) "Connected to TorBox" else "TorBox said: ${o.str("detail")}"
            } catch (e: Exception) {
                "Couldn't connect (${e.message}). Check the key."
            }
        }
    }
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(4, 7, 20)
        window.navigationBarColor = android.graphics.Color.rgb(4, 7, 20)
        setContent { CineStreamApp() }
    }
}

@Composable
fun CineStreamApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cinestream", Context.MODE_PRIVATE) }
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
        if (torboxKey.isBlank()) {
            libStatus = "Add your TorBox API key in Settings first."
            return
        }
        libLoading = true
        val res = Tb.library(torboxKey)
        library = res.second
        libStatus = if (res.first.isNotEmpty()) res.first
        else if (res.second.isEmpty()) "No video files in your TorBox library yet."
        else ""
        libLoading = false
    }

    fun open(f: Film) {
        detail = f
        matches = emptyList()
        detailMsg = ""
    }

    fun startTb(f: TbFile, image: String) {
        val start = resume.firstOrNull { it.torrentId == f.torrentId && it.fileId == f.fileId }?.pos ?: 0L
        play = PlayReq(f.name, image, "", f.torrentId, f.fileId, start)
    }

    fun startUrl(u: String) {
        val name = u.substringAfterLast('/').substringBefore('?').ifEmpty { "Video" }
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
        while (resume.size > 12) resume.removeAt(resume.size - 1)
        saveResume(prefs, resume)
    }

    LaunchedEffect(tmdbKey) {
        if (tmdbKey.isBlank()) {
            rows = demoRows
            catalogStatus = ""
            return@LaunchedEffect
        }
        val defs = listOf(
            RowDef("Trending Movies", "/trending/movie/week", "movie"),
            RowDef("Trending Series", "/trending/tv/week", "tv"),
            RowDef("Popular Movies", "/movie/popular", "movie"),
            RowDef("Popular Series", "/tv/popular", "tv"),
            RowDef("Top Rated Movies", "/movie/top_rated", "movie")
        )
        val loaded = ArrayList<Pair<String, List<Film>>>()
        for (d in defs) {
            val r = Tmdb.list(d.path, tmdbKey, d.kind)
            if (r.isNotEmpty()) {
                loaded.add(d.title to r)
                rows = ArrayList(loaded)
            }
        }
        catalogStatus = if (loaded.isEmpty()) "TMDB didn't respond. Check the key." else "Catalogue connected."
    }

    LaunchedEffect(query, tmdbKey) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        if (tmdbKey.isBlank()) {
            results = demoFilms.filter { it.name.contains(query, true) }
        } else {
            delay(450)
            results = Tmdb.list("/search/multi?query=${URLEncoder.encode(query, "UTF-8")}&include_adult=false", tmdbKey, "")
        }
        searching = false
    }

    LaunchedEffect(screen) {
        if (screen == "Library") loadLibrary()
    }

    BackHandler(enabled = play != null || detail != null) {
        if (play != null) play = null else detail = null
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = Surf, primary = Red,
            onBackground = Color.White, onSurface = Color.White
        )
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Deep, Bg), endY = 1400f))) {
            val p = play
            val d = detail
            if (p != null) {
                PlayerScreen(p, torboxKey) { pos, dur -> report(p, pos, dur) }
            } else if (d != null) {
                DetailsScreen(
                    film = d, msg = detailMsg, matches = matches, busy = detailBusy,
                    onPlay = {
                        scope.launch {
                            detailBusy = true
                            matches = emptyList()
                            detailMsg = ""
                            loadLibrary()
                            if (torboxKey.isBlank() || library.isEmpty()) {
                                detailMsg = libStatus
                            } else {
                                val n = norm(d.name)
                                val m = library.filter { n.isNotEmpty() && norm(it.name).contains(n) }
                                if (m.isEmpty()) detailMsg = "Not in your TorBox library yet. Add it in TorBox, then try again."
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
                            onTest = { scope.launch { tbStatus = "Checking..."; tbStatus = Tb.test(torboxKey) } },
                            tbStatus = tbStatus,
                            tmdbKey = tmdbKey,
                            onTmdb = { tmdbKey = it.trim(); prefs.edit().putString("tmdb_key", it.trim()).apply() },
                            catalogStatus = catalogStatus,
                            link = linkText, onLink = { linkText = it },
                            onPlayLink = {
                                if (linkText.startsWith("https://")) startUrl(linkText.trim())
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------- Building blocks ----------
@Composable
private fun Focusable(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    bg: Color = Color.Transparent,
    onClick: () -> Unit,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .scale(if (focused) 1.06f else 1f)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(bg)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clickable(onClick = onClick)
    ) {
        content(this, focused)
    }
}

@Composable
private fun Pill(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Focusable(shape = RoundedCornerShape(50), bg = if (primary) Red else Glass, onClick = onClick) {
        Text(
            label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp)
        )
    }
}

@Composable
private fun TopNav(current: String, onNav: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 56.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("CineStream", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(28.dp))
        listOf("Home", "Search", "Library", "Settings").forEach { tab ->
            Focusable(
                shape = RoundedCornerShape(50),
                bg = if (current == tab) Glass else Color.Transparent,
                onClick = { onNav(tab) }
            ) {
                Text(
                    tab, color = if (current == tab) Color.White else Muted, fontSize = 16.sp,
                    fontWeight = if (current == tab) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun PosterCard(film: Film, onClick: () -> Unit) {
    Focusable(
        modifier = Modifier.width(150.dp).height(225.dp),
        shape = RoundedCornerShape(14.dp), bg = Surf, onClick = onClick
    ) {
        AsyncImage(
            model = film.poster, contentDescription = film.name,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun ResumeCard(r: Resume, onClick: () -> Unit) {
    Focusable(
        modifier = Modifier.width(280.dp).height(158.dp),
        shape = RoundedCornerShape(14.dp), bg = Surf, onClick = onClick
    ) {
        if (r.image.isNotEmpty()) {
            AsyncImage(
                model = r.image, contentDescription = r.name,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))))
        Text(
            r.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 16.dp)
        )
        val frac = if (r.dur > 0) (r.pos.toFloat() / r.dur.toFloat()).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Red))
        }
    }
}

@Composable
private fun Shelf(title: String, list: List<Film>, onFilm: (Film) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 56.dp, top = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(list) { f -> PosterCard(f) { onFilm(f) } }
        }
    }
}

@Composable
private fun Hero(film: Film, onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(320.dp)) {
        if (film.backdrop.isNotEmpty()) {
            AsyncImage(
                model = film.backdrop, contentDescription = film.name,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            AsyncImage(
                model = film.poster, contentDescription = film.name,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 110.dp).height(270.dp)
                    .clip(RoundedCornerShape(18.dp)),
                contentScale = ContentScale.Fit
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Bg, Bg.copy(alpha = 0.8f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Bg))))
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 56.dp).width(520.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(film.name, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOf(film.kind, film.year, if (film.rating.isNotEmpty()) "Rated ${film.rating}" else "")
                .filter { it.isNotEmpty() }.joinToString("   ")
            Text(meta, color = Muted, fontSize = 16.sp)
            Text(film.overview, color = Color.White, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 6.dp)) { Pill("Watch now", primary = true) { onOpen() } }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Color.White, unfocusedBorderColor = Color(0x66FFFFFF),
    focusedLabelColor = Color.White, unfocusedLabelColor = Muted,
    cursorColor = Red
)

// ---------- Screens ----------
@Composable
private fun HomeScreen(
    rows: List<Pair<String, List<Film>>>,
    resume: List<Resume>,
    hasTmdb: Boolean,
    onFilm: (Film) -> Unit,
    onResume: (Resume) -> Unit,
    onSettings: () -> Unit
) {
    val hero = rows.firstOrNull()?.second?.let { l -> l.firstOrNull { it.backdrop.isNotEmpty() } ?: l.firstOrNull() }
        ?: demoFilms.first()
    LazyColumn(Modifier.fillMaxSize()) {
        item { Hero(hero) { onFilm(hero) } }
        if (!hasTmdb) {
            item {
                Focusable(
                    modifier = Modifier.padding(horizontal = 56.dp, vertical = 10.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp), bg = Surf, onClick = onSettings
                ) {
                    Text(
                        "Showing sample titles. Add your free TMDB key in Settings to get the full catalogue and search.",
                        color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(18.dp)
                    )
                }
            }
        }
        if (resume.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "Continue Watching", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 56.dp, top = 8.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(resume) { r -> ResumeCard(r) { onResume(r) } }
                    }
                }
            }
        }
        items(rows) { row -> Shelf(row.first, row.second, onFilm) }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<Film>,
    searching: Boolean,
    hasTmdb: Boolean,
    onFilm: (Film) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            label = { Text("Search movies and series") },
            shape = RoundedCornerShape(50), colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 56.dp)
        )
        val hint = when {
            query.isBlank() -> if (hasTmdb) "Type a title to search." else "Add your free TMDB key in Settings to search every title."
            searching -> "Searching..."
            results.isEmpty() -> "No matches."
            else -> ""
        }
        if (hint.isNotEmpty()) {
            Text(hint, color = Muted, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 62.dp, vertical = 14.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(results) { f -> PosterCard(f) { onFilm(f) } }
        }
    }
}

@Composable
private fun LibraryScreen(
    files: List<TbFile>,
    status: String,
    loading: Boolean,
    onRefresh: () -> Unit,
    onPlay: (TbFile) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("My TorBox", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Pill("Refresh") { onRefresh() }
            if (loading) CircularProgressIndicator(modifier = Modifier.size(26.dp), color = Red, strokeWidth = 3.dp)
        }
        if (status.isNotEmpty()) {
            Text(status, color = Muted, fontSize = 16.sp, modifier = Modifier.padding(vertical = 10.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(files) { f ->
                Focusable(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp), bg = Surf, onClick = { onPlay(f) }
                ) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            f.name, color = Color.White, fontSize = 16.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text(fmtSize(f.size), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    torboxKey: String, onTorbox: (String) -> Unit, onTest: () -> Unit, tbStatus: String,
    tmdbKey: String, onTmdb: (String) -> Unit, catalogStatus: String,
    link: String, onLink: (String) -> Unit, onPlayLink: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        Text("TorBox", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Paste your TorBox API key. It stays on this device. Your TorBox files then show up in Library.", color = Muted, fontSize = 15.sp)
        OutlinedTextField(
            value = torboxKey, onValueChange = onTorbox, singleLine = true,
            label = { Text("TorBox API key") }, shape = RoundedCornerShape(16.dp),
            colors = fieldColors(), modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Pill("Test connection") { onTest() }
            if (tbStatus.isNotEmpty()) Text(tbStatus, color = Color.White, fontSize = 16.sp)
        }

        Text("Catalogue and search", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text(
            "Get a free key at themoviedb.org: profile, Settings, API, then copy the API Key. This is what lets Search find any title.",
            color = Muted, fontSize = 15.sp
        )
        OutlinedTextField(
            value = tmdbKey, onValueChange = onTmdb, singleLine = true,
            label = { Text("TMDB API key") }, shape = RoundedCornerShape(16.dp),
            colors = fieldColors(), modifier = Modifier.fillMaxWidth()
        )
        if (catalogStatus.isNotEmpty()) Text(catalogStatus, color = Color.White, fontSize = 16.sp)

        Text("Play a link", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text("Paste a direct https video link you have the right to watch.", color = Muted, fontSize = 15.sp)
        OutlinedTextField(
            value = link, onValueChange = onLink, singleLine = true,
            label = { Text("https://...") }, shape = RoundedCornerShape(16.dp),
            colors = fieldColors(), modifier = Modifier.fillMaxWidth()
        )
        Pill("Play link") { onPlayLink() }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun DetailsScreen(
    film: Film,
    msg: String,
    matches: List<TbFile>,
    busy: Boolean,
    onPlay: () -> Unit,
    onPick: (TbFile) -> Unit,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        if (film.backdrop.isNotEmpty()) {
            AsyncImage(
                model = film.backdrop, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Bg, Bg.copy(alpha = 0.92f), Bg.copy(alpha = 0.45f)))))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 40.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                AsyncImage(
                    model = film.poster, contentDescription = film.name,
                    modifier = Modifier.width(200.dp).height(300.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(film.name, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                    val meta = listOf(film.kind, film.year, if (film.rating.isNotEmpty()) "Rated ${film.rating}" else "")
                        .filter { it.isNotEmpty() }.joinToString("   ")
                    Text(meta, color = Muted, fontSize = 16.sp)
                    Text(film.overview, color = Color.White, fontSize = 17.sp, lineHeight = 25.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Pill(if (busy) "Looking..." else "Play", primary = true) { if (!busy) onPlay() }
                        Pill("Back") { onBack() }
                    }
                    if (msg.isNotEmpty()) Text(msg, color = Muted, fontSize = 16.sp)
                    if (matches.isNotEmpty()) {
                        Text("Choose a file", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        matches.forEach { f ->
                            Focusable(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp), bg = Surf, onClick = { onPick(f) }
                            ) {
                                Text(
                                    f.name + "   " + fmtSize(f.size), color = Color.White, fontSize = 15.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                                )
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
            if (link == null) error = "Couldn't get a stream link from TorBox. Check your key and that the file is ready." else url = link
        }
    }

    if (url.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error.isEmpty()) {
                CircularProgressIndicator(color = Red)
            } else {
                Text(error, color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(48.dp))
            }
        }
    } else {
        var playError by remember(url) { mutableStateOf("") }
        val exo = remember(url) {
            ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(e: PlaybackException) {
                        playError = "Can't play this file (${e.errorCodeName})."
                    }
                })
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
            if (playError.isNotEmpty()) {
                Text(
                    playError, color = Color.White, fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(32.dp)
                )
            }
        }
    }
}
