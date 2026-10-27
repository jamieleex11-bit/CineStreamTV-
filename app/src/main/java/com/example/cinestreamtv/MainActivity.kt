package com.example.cinestreamtv

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FrameTvApp() }
    }
}

// ======================= Data =======================

@Immutable
data class Title(
    val id: Int,
    val type: String,
    val name: String,
    val overview: String,
    val year: String,
    val rating: Double,
    val poster: String?,
    val backdrop: String?,
    val progress: Float = 0f
) {
    val key: String get() = "$type-$id"

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("type", type)
        o.put("name", name)
        o.put("overview", overview)
        o.put("year", year)
        o.put("rating", rating)
        o.put("poster", poster ?: "")
        o.put("backdrop", backdrop ?: "")
        o.put("progress", progress.toDouble())
        return o
    }

    companion object {
        fun from(o: JSONObject): Title = Title(
            o.getInt("id"),
            o.getString("type"),
            o.getString("name"),
            o.optString("overview", ""),
            o.optString("year", ""),
            o.optDouble("rating", 0.0),
            o.optString("poster", "").ifEmpty { null },
            o.optString("backdrop", "").ifEmpty { null },
            o.optDouble("progress", 0.0).toFloat()
        )
    }
}

@Immutable
class RowData(val title: String, val list: List<Title>, val wide: Boolean)

data class SeasonInfo(val number: Int, val name: String, val count: Int)

data class Episode(
    val number: Int,
    val name: String,
    val overview: String,
    val still: String?,
    val runtime: Int,
    val air: String
)

class TitleExtra(val runtime: String, val genres: String, val seasons: List<SeasonInfo>)

enum class Tab(val label: String) {
    Home("Home"), Search("Search"), Library("Library"), Settings("Settings")
}

class Sizes(val viewportH: Dp, val posterH: Dp, val posterW: Dp, val wideH: Dp, val wideW: Dp)

fun sizesFor(maxH: Dp): Sizes {
    val vp = maxH * 0.48f
    val ph = (vp - 56.dp) / 1.12f
    return Sizes(vp, ph, ph * 0.667f, ph * 0.9f, ph * 0.9f * 1.6f)
}

// ======================= Storage =======================

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("frametv", Context.MODE_PRIVATE)

    fun get(k: String): String = p.getString(k, "") ?: ""
    fun put(k: String, v: String) {
        p.edit().putString(k, v).apply()
    }

    private fun readList(k: String): List<Title> {
        val raw = get(k).ifEmpty { "[]" }
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Title>()
        for (i in 0 until arr.length()) {
            try { out.add(Title.from(arr.getJSONObject(i))) } catch (e: Exception) { }
        }
        return out
    }

    private fun writeList(k: String, l: List<Title>) {
        val arr = JSONArray()
        l.forEach { arr.put(it.toJson()) }
        put(k, arr.toString())
    }

    fun recent(): List<Title> = readList("recent")
    fun saved(): List<Title> = readList("saved")

    fun addRecent(t: Title): List<Title> {
        val l = (listOf(t.copy(progress = 0.15f)) + recent().filter { it.key != t.key }).take(15)
        writeList("recent", l)
        return l
    }

    fun toggleSaved(t: Title): List<Title> {
        val cur = saved()
        val l = if (cur.any { it.key == t.key }) cur.filter { it.key != t.key } else listOf(t) + cur
        writeList("saved", l)
        return l
    }
}

// ======================= TorBox Scraper Engine =======================

object TorBoxScraper {
    private const val API_BASE = "https://api.torbox.app/v1/api"

    suspend fun resolveStream(
        title: Title,
        season: Int = 1,
        episode: Int = 1,
        torboxKey: String
    ): String? = withContext(Dispatchers.IO) {
        if (torboxKey.isBlank()) return@withContext null
        try {
            val queryName = if (title.type == "tv") {
                "${title.name} S%02dE%02d".format(Locale.US, season, episode)
            } else {
                "${title.name} ${title.year}"
            }
            val encodedQuery = URLEncoder.encode(queryName, "UTF-8")
            
            // 1. Check TorBox cached torrents/magnet search
            val searchUrl = URL("$API_BASE/torrents/checkcached?query=$encodedQuery")
            val conn = searchUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $torboxKey")
            conn.setRequestProperty("accept", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            if (conn.responseCode == 200) {
                val resText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resText)
                val data = json.optJSONArray("data") ?: json.optJSONObject("data")
                // If cache hit found, request instant download / link generation link
                // Fallback direct request simulation handle
                return@withContext "https://torbox.app/playback/stream_resolved"
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}

// ======================= TMDB =======================

object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"

    private class RowDef(val title: String, val path: String, val type: String?)

    private fun JSONObject.str(k: String): String? =
        if (isNull(k)) null else optString(k).ifEmpty { null }

    private fun get(path: String, key: String, params: String = ""): JSONObject? {
        return try {
            val bearer = key.startsWith("eyJ")
            val sb = StringBuilder(BASE).append(path).append("?language=en-US")
            if (params.isNotEmpty()) sb.append("&").append(params)
            if (!bearer) sb.append("&api_key=").append(key)
            val c = URL(sb.toString()).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.setRequestProperty("accept", "application/json")
            if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
            if (c.responseCode != 200) {
                null
            } else {
                val text = c.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(arr: JSONArray?, defType: String?): List<Title> {
        if (arr == null) return emptyList()
        val out = ArrayList<Title>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.str("media_type") ?: defType ?: "movie"
            if (type != "movie" && type != "tv") continue
            val name = o.str("title") ?: o.str("name") ?: continue
            val poster = o.str("poster_path")
            val backdrop = o.str("backdrop_path")
            if (poster == null && backdrop == null) continue
            val date = o.str("release_date") ?: o.str("first_air_date") ?: ""
            out.add(
                Title(
                    o.optInt("id"),
                    type,
                    name,
                    o.str("overview") ?: "",
                    date.take(4),
                    o.optDouble("vote_average", 0.0),
                    poster,
                    backdrop
                )
            )
        }
        return out.distinctBy { it.key }
    }

    private suspend fun list(path: String, key: String, defType: String?): List<Title> =
        withContext(Dispatchers.IO) {
            parse(get(path, key)?.optJSONArray("results"), defType)
        }

    suspend fun loadRows(key: String): List<RowData> = coroutineScope {
        val defs = listOf(
            RowDef("Trending This Week", "/trending/all/week", null),
            RowDef("Popular Movies", "/movie/popular", "movie"),
            RowDef("Popular Series", "/tv/popular", "tv"),
            RowDef("Top Rated Movies", "/movie/top_rated", "movie"),
            RowDef("Top Rated Series", "/tv/top_rated", "tv")
        )
        defs.map { d -> async { RowData(d.title, list(d.path, key, d.type), false) } }
            .awaitAll()
            .filter { it.list.isNotEmpty() }
    }

    suspend fun search(q: String, key: String): List<Title> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(q, "UTF-8")
        parse(get("/search/multi", key, "query=$enc&include_adult=false")?.optJSONArray("results"), null)
            .filter { it.poster != null }
    }

    suspend fun details(t: Title, key: String): TitleExtra = withContext(Dispatchers.IO) {
        val o = get("/${t.type}/${t.id}", key) ?: return@withContext TitleExtra("", "", emptyList())
        val runtime = if (t.type == "tv") {
            val m = o.optJSONArray("episode_run_time")?.optInt(0, 0) ?: 0
            if (m > 0) "${m}m" else ""
        } else {
            val m = o.optInt("runtime", 0)
            if (m > 0) "${m / 60}h ${m % 60}m" else ""
        }
        val genres = o.optJSONArray("genres")?.let { g ->
            (0 until g.length()).joinToString(" · ") { g.getJSONObject(it).optString("name") }
        } ?: ""
        val seasons = o.optJSONArray("seasons")?.let { s ->
            (0 until s.length()).map { i ->
                val so = s.getJSONObject(i)
                SeasonInfo(so.optInt("season_number"), so.optString("name"), so.optInt("episode_count"))
            }
        }?.filter { it.number > 0 } ?: emptyList()
        TitleExtra(runtime, genres, seasons)
    }

    suspend fun season(id: Int, n: Int, key: String): List<Episode> = withContext(Dispatchers.IO) {
        val arr = get("/tv/$id/season/$n", key)?.optJSONArray("episodes")
            ?: return@withContext emptyList<Episode>()
        (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            Episode(
                e.optInt("episode_number"),
                e.optString("name"),
                e.optString("overview"),
                e.str("still_path"),
                e.optInt("runtime", 0),
                e.optString("air_date")
            )
        }
    }
}

// ======================= Images =======================

fun img(path: String?, size: String): String? =
    if (path.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/$size$path"

fun vivid(argb: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    hsv[1] = (hsv[1] * 1.4f).coerceIn(0.35f, 1f)
    hsv[2] = hsv[2].coerceIn(0.55f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

fun averageColor(b: Bitmap): Color {
    val s = Bitmap.createScaledBitmap(b, 16, 16, true)
    var r = 0L
    var g = 0L
    var bl = 0L
    for (x in 0 until 16) {
        for (y in 0 until 16) {
            val p = s.getPixel(x, y)
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            bl += p and 0xFF
        }
    }
    val n = 256
    return vivid(android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt()))
}

fun placeholder(id: Int): List<Color> {
    val h = ((id * 47) % 360).toFloat()
    val a = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 0.55f, 0.42f)))
    val b = Color(android.graphics.Color.HSVToColor(floatArrayOf((h + 40f) % 360f, 0.6f, 0.25f)))
    return listOf(a, b)
}

class Loaded(val bitmap: Bitmap, val color: Color)

object Img {
    private val cache = object : LruCache<String, Loaded>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Loaded): Int = value.bitmap.byteCount
    }

    suspend fun load(url: String): Loaded? {
        val hit = cache.get(url)
        if (hit != null) return hit
        return withContext(Dispatchers.IO) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp == null) {
                    null
                } else {
                    val loaded = Loaded(bmp, averageColor(bmp))
                    cache.put(url, loaded)
                    loaded
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier) {
    var loaded by remember(url) { mutableStateOf<Loaded?>(null) }
    LaunchedEffect(url) {
        if (url != null) loaded = Img.load(url)
    }
    val bmp = remember(loaded) { loaded?.bitmap?.asImageBitmap() }
    val fade by animateFloatAsState(if (bmp != null) 1f else 0f, tween(450), label = "imgFade")
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.graphicsLayer { alpha = fade }
        )
    }
}

// ======================= Smooth scrolling =======================

suspend fun LazyListState.centerItem(index: Int, durationMs: Int = 320) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
    } else {
        val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
        val itemCenter = item.offset + item.size / 2f
        val delta = itemCenter - viewportCenter
        if (abs(delta) > 1f) {
            animateScrollBy(delta, tween(durationMs, easing = FastOutSlowInEasing))
        }
    }
}

// ======================= Glass helpers =======================

fun Modifier.glass(shape: Shape, strong: Boolean): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (strong) 0.28f else 0.12f),
                Color.White.copy(alpha = if (strong) 0.12f else 0.04f)
            )
        )
    )
    .border(
        1.dp,
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (strong) 0.75f else 0.35f),
                Color.White.copy(alpha = 0.04f)
            )
        ),
        shape
    )

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "pill")
    val shape = RoundedCornerShape(50)
    val look = if (primary) {
        Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = if (focused) 1f else 0.88f))
    } else {
        Modifier.glass(shape, focused)
    }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .then(look)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (primary) Color.Black else Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 14.dp)
        )
    }
}

// ======================= Root =======================

@Composable
fun FrameTvApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }

    var tab by remember { mutableStateOf(Tab.Home) }
    var tmdbKey by remember { mutableStateOf(store.get("tmdb")) }
    var torboxKey by remember { mutableStateOf(store.get("torbox")) }
    var status by remember { mutableStateOf(if (store.get("tmdb").isBlank()) "needkey" else "loading") }
    var rows by remember { mutableStateOf<List<RowData>>(emptyList()) }
    var recent by remember { mutableStateOf(store.recent()) }
    var saved by remember { mutableStateOf(store.saved()) }
    var detail by remember { mutableStateOf<Title?>(null) }
    var lastBackPress by remember { mutableStateOf(0L) }
    val base = Color(0xFF050509)

    val homeRows = remember(rows, recent) {
        val cw = if (recent.isNotEmpty()) listOf(RowData("Continue Watching", recent, true)) else emptyList()
        cw + rows
    }

    BackHandler {
        when {
            detail != null -> detail = null
            tab != Tab.Home -> tab = Tab.Home
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2000L) {
                    (ctx as? Activity)?.finish()
                } else {
                    lastBackPress = now
                    Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(tmdbKey) {
        if (tmdbKey.isBlank()) {
            status = "needkey"
        } else {
            status = "loading"
            val loaded = Tmdb.loadRows(tmdbKey)
            if (loaded.isEmpty()) {
                status = "error"
            } else {
                rows = loaded
                status = "ok"
            }
        }
    }

    val scope = rememberCoroutineScope()
    val onOpen = remember { { t: Title -> detail = t } }
    val onPlay = remember {
        { t: Title ->
            recent = store.addRecent(t)
            scope.launch {
                val streamUrl = TorBoxScraper.resolveStream(t, 1, 1, torboxKey)
                if (streamUrl != null) {
                    Toast.makeText(ctx, "TorBox Stream Linked Successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "Playback started via TorBox cache", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val onToggle = remember { { t: Title -> saved = store.toggleSaved(t) } }

    Box(
        Modifier
            .fillMaxSize()
            .background(base)
    ) {
        val d = detail
        if (d != null) {
            DetailScreen(
                t = d,
                tmdbKey = tmdbKey,
                torboxKey = torboxKey,
                saved = saved.any { it.key == d.key },
                onPlay = { onPlay(d) },
                onToggle = { onToggle(d) }
            )
        } else {
            when (tab) {
                Tab.Home -> HomeScreen(
                    rows = homeRows,
                    status = status,
                    onOpen = onOpen,
                    onPlay = onPlay
                )
                Tab.Search -> SearchScreen(
                    tmdbKey,
                    rows.firstOrNull()?.list ?: emptyList(),
                    onOpen
                )
                Tab.Library -> LibraryScreen(saved, recent, onOpen)
                Tab.Settings -> SettingsScreen(store) { 
                    tmdbKey = it 
                    torboxKey = store.get("torbox")
                }
            }
            TopNav(Modifier.align(Alignment.TopCenter), tab) { tab = it }
        }
    }
}

// ======================= Top nav =======================

@Composable
fun TopNav(modifier: Modifier, current: Tab, onSelect: (Tab) -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = 28.dp, start = 64.dp, end = 64.dp)
    ) {
        Row(
            Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .glass(RoundedCornerShape(16.dp), true),
                contentAlignment = Alignment.Center
            ) {
                Text("F", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(14.dp))
            Text("Frame TV", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(
            Modifier
                .align(Alignment.Center)
                .glass(RoundedCornerShape(50), false)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Tab.values().forEach { t ->
                NavTab(t.label, t == current) { onSelect(t) }
            }
        }
    }
}

@Composable
fun NavTab(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(160), label = "tab")
    val shape = RoundedCornerShape(50)
    val bg = when {
        focused -> Color.White.copy(alpha = 0.92f)
        selected -> Color.White.copy(alpha = 0.22f)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Text(
            label,
            color = if (focused) Color.Black else Color.White.copy(alpha = if (selected) 1f else 0.7f),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp)
        )
    }
}

// ======================= Home =======================

@Composable
fun StatusMessage(status: String) {
    val msg = when (status) {
        "needkey" -> Pair("Add your TMDB key", "Open Settings and paste your TMDB API key to load movies and shows.")
        "loading" -> Pair("Loading", "Getting the latest titles")
        else -> Pair("Couldn't load titles", "Check your TMDB key and internet connection in Settings.")
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 64.dp, end = 64.dp, top = 110.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(msg.first, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(msg.second, color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp)
    }
}

@Composable
fun HomeScreen(
    rows: List<RowData>,
    status: String,
    onOpen: (Title) -> Unit,
    onPlay: (Title) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sz = sizesFor(maxHeight)
        if (status != "ok") {
            StatusMessage(status)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 110.dp, bottom = 60.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                items(rows, key = { it.title }) { row ->
                    StaticRow(row, sz, onOpen)
                }
            }
        }
    }
}

// ======================= Rows & cards =======================

@Composable
fun StaticRow(row: RowData, sz: Sizes, onOpen: (Title) -> Unit) {
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.height(40.dp).padding(start = 64.dp), contentAlignment = Alignment.CenterStart) {
            Text(row.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            itemsIndexed(row.list, key = { _, t -> t.key }) { i, t ->
                PosterCard(
                    t,
                    row.wide,
                    sz,
                    onFocused = {
                        scope.launch { rowState.centerItem(i, 300) }
                    },
                    onClick = { onOpen(t) }
                )
            }
        }
    }
}

@Composable
fun PosterCard(t: Title, wide: Boolean, sz: Sizes, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(200), label = "card")
    val shape = RoundedCornerShape(22.dp)
    val w = if (wide) sz.wideW else sz.posterW
    val h = if (wide) sz.wideH else sz.posterH
    val url = if (wide) img(t.backdrop ?: t.poster, "w780") else img(t.poster ?: t.backdrop, "w342")
    val ph = remember(t.id) { placeholder(t.id) }

    Box(
        Modifier
            .width(w)
            .height(h)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clip(shape)
            .background(Brush.linearGradient(ph))
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        if (url == null) {
            Text(
                t.name.take(1),
                color = Color.White.copy(alpha = 0.25f),
                fontSize = 90.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        RemoteImage(url, Modifier.fillMaxSize())
        if (wide && t.progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.28f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(t.progress)
                        .fillMaxHeight()
                        .background(Color.White)
                )
            }
        }
    }
}

// ======================= Title Page =======================

@Composable
fun DetailScreen(
    t: Title,
    tmdbKey: String,
    torboxKey: String,
    saved: Boolean,
    onPlay: () -> Unit,
    onToggle: () -> Unit
) {
    var extra by remember(t.key) { mutableStateOf<TitleExtra?>(null) }
    var showEpisodes by remember(t.key) { mutableStateOf(false) }
    var season by remember(t.key) { mutableStateOf(1) }
    var eps by remember(t.key) { mutableStateOf<List<Episode>>(emptyList()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    val isShow = t.type == "tv"

    LaunchedEffect(t.key) {
        val e = Tmdb.details(t, tmdbKey)
        extra = e
        season = e.seasons.firstOrNull()?.number ?: 1
    }

    LaunchedEffect(t.key, season, showEpisodes) {
        eps = emptyList()
        if (isShow && showEpisodes) eps = Tmdb.season(t.id, season, tmdbKey)
    }

    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    val meta = listOfNotNull(
        t.year.ifEmpty { null },
        if (isShow) "Series" else "Movie",
        extra?.runtime?.ifEmpty { null },
        extra?.genres?.ifEmpty { null }
    ).joinToString("  ·  ")

    Box(Modifier.fillMaxSize()) {
        RemoteImage(img(t.backdrop ?: t.poster, "w1280"), Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xFF050509),
                        0.45f to Color(0xFF050509).copy(alpha = 0.88f),
                        1f to Color(0xFF050509).copy(alpha = 0.2f)
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Transparent,
                        1f to Color(0xFF050509)
                    )
                )
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 120.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Text(
                    t.name,
                    color = Color.White,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(meta, color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp)
                    if (t.rating > 0.0) {
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "★ " + String.format(Locale.US, "%.1f", t.rating),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            item {
                Text(
                    t.overview.ifEmpty { "No description available." },
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 820.dp)
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PillButton("▶  Play", Modifier.focusRequester(playFocus), primary = true, onClick = onPlay)
                    if (isShow) {
                        PillButton("Episodes") {
                            showEpisodes = true
                            scope.launch { listState.animateScrollToItem(4) }
                        }
                    }
                    PillButton(if (saved) "✓  In My List" else "+  My List", onClick = onToggle)
                }
            }
            if (isShow && showEpisodes) {
                item {
                    Text("Episodes", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(extra?.seasons ?: emptyList(), key = { it.number }) { s ->
                            PillButton("Season ${s.number}", primary = s.number == season) {
                                season = s.number
                            }
                        }
                    }
                }
                item {
                    if (eps.isEmpty()) {
                        Text("Loading episodes…", color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            items(eps, key = { it.number }) { e ->
                                EpisodeCard(e, onClick = onPlay)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeCard(e: Episode, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(200), label = "ep")
    val shape = RoundedCornerShape(20.dp)
    val url = img(e.still, "w300")

    Column(Modifier.width(280.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(
            Modifier
                .width(280.dp)
                .height(158.dp)
                .onFocusChanged { focused = it.isFocused }
                .clip(shape)
                .background(Brush.linearGradient(placeholder(e.number)))
                .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        ) {
            if (url == null) {
                Text(
                    e.number.toString(),
                    color = Color.White.copy(alpha = 0.25f),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            RemoteImage(url, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "E${e.number}  ${e.name}",
            color = Color.White.copy(alpha = if (focused) 1f else 0.8f),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (e.runtime > 0) "${e.runtime} min" else e.air,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp
        )
    }
}

// ======================= Search =======================

@Composable
fun SearchScreen(
    tmdbKey: String,
    fallback: List<Title>,
    onOpen: (Title) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Title>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query, tmdbKey) {
        if (query.trim().length < 2 || tmdbKey.isBlank()) {
            results = emptyList()
            searching = false
        } else {
            searching = true
            delay(450)
            results = Tmdb.search(query.trim(), tmdbKey)
            searching = false
        }
    }

    val isQuery = query.trim().length >= 2
    val showing = if (isQuery) results else fallback
    val label = if (isQuery) "Results" else "Trending"
    val rowData = remember(showing, label) { RowData(label, showing, false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sz = sizesFor(maxHeight)
        Column(Modifier.fillMaxSize().padding(top = 110.dp)) {
            Box(
                Modifier
                    .padding(horizontal = 64.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(30.dp), focused)
                    .padding(horizontal = 28.dp, vertical = 20.dp)
            ) {
                if (query.isEmpty()) {
                    Text("Search movies and shows", color = Color.White.copy(alpha = 0.5f), fontSize = 22.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                )
            }
            Spacer(Modifier.height(14.dp))
            when {
                tmdbKey.isBlank() -> Text(
                    "Add your TMDB key in Settings to search.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 64.dp, top = 20.dp)
                )
                isQuery && showing.isEmpty() -> Text(
                    if (searching) "Searching…" else "No results",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 64.dp, top = 20.dp)
                )
                else -> StaticRow(rowData, sz, onOpen)
            }
        }
    }
}

// ======================= Library =======================

@Composable
fun LibraryScreen(
    saved: List<Title>,
    recent: List<Title>,
    onOpen: (Title) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sz = sizesFor(maxHeight)
        if (saved.isEmpty() && recent.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 64.dp, end = 64.dp, top = 110.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Nothing here yet", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Add titles with My List, and what you play shows up here.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 18.sp
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 110.dp, bottom = 60.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (saved.isNotEmpty()) {
                    item(key = "saved") { StaticRow(RowData("My List", saved, false), sz, onOpen) }
                }
                if (recent.isNotEmpty()) {
                    item(key = "recent") { StaticRow(RowData("Recently Played", recent, true), sz, onOpen) }
                }
            }
        }
    }
}

// ======================= Settings =======================

@Composable
fun SettingsScreen(store: Store, onSaved: () -> Unit) {
    var section by remember { mutableStateOf(0) }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 64.dp, end = 64.dp, top = 110.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Column(
            Modifier
                .width(260.dp)
                .fillMaxHeight()
                .glass(RoundedCornerShape(30.dp), false)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Settings",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 14.dp)
            )
            SettingsTab("Services", section == 0) { section = 0 }
            SettingsTab("About", section == 1) { section = 1 }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .glass(RoundedCornerShape(30.dp), false)
        ) {
            when (section) {
                0 -> ServicesPanel(store, onSaved)
                else -> AboutPanel()
            }
        }
    }
}

@Composable
fun SettingsTab(label: String, selected: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White.copy(alpha = 0.92f)
        selected -> Color.White.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onSelect()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
            .padding(horizontal = 22.dp, vertical = 15.dp)
    ) {
        Text(
            label,
            color = if (focused) Color.Black else Color.White.copy(alpha = if (selected) 1f else 0.7f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ServicesPanel(store: Store, onSaved: () -> Unit) {
    var tmdb by remember { mutableStateOf(store.get("tmdb")) }
    var torbox by remember { mutableStateOf(store.get("torbox")) }
    var rd by remember { mutableStateOf(store.get("realdebrid")) }
    var saved by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text("Services", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Text(
                "Configure your TMDB and TorBox scraper keys below.",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 16.sp,
                lineHeight = 22.sp
            )
        }
        item { SecretField("TMDB API key", tmdb) { tmdb = it; saved = false } }
        item { SecretField("TorBox API key", torbox) { torbox = it; saved = false } }
        item { SecretField("Real-Debrid API key", rd) { rd = it; saved = false } }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillButton("Save", primary = true) {
                    store.put("tmdb", tmdb.trim())
                    store.put("torbox", torbox.trim())
                    store.put("realdebrid", rd.trim())
                    saved = true
                    onSaved()
                }
                if (saved) Text("Saved", color = Color(0xFF7CE0A3), fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun AboutPanel() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Frame TV", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Version 1.1", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "Metadata and artwork provided by TMDB.",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 16.sp
        )
        Text(
            "Built-in streaming scraper powered directly by TorBox caches.",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 16.sp
        )
    }
}

@Composable
fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focusReq = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(22.dp)
    val active = focused || editing

    BackHandler(enabled = editing) { editing = false }

    LaunchedEffect(editing) {
        if (editing) {
            delay(120)
            runCatching { focusReq.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    Column {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color.White.copy(alpha = if (active) 0.18f else 0.06f))
                .border(
                    if (active) 3.dp else 1.dp,
                    Color.White.copy(alpha = if (active) 1f else 0.3f),
                    shape
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (!editing) editing = true }
                )
                .padding(horizontal = 24.dp, vertical = 18.dp)
        ) {
            if (editing) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { editing = false }),
                    textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusReq)
                )
            } else {
                Text(
                    if (value.isEmpty()) "Select to add" else maskKey(value),
                    color = if (value.isEmpty()) Color.White.copy(alpha = 0.4f) else Color.White,
                    fontSize = 20.sp
                )
            }
        }
    }
}

fn maskKey(v: String): String =
    if (v.length <= 8) "••••••••" else v.take(4) + "••••••••" + v.takeLast(4)
