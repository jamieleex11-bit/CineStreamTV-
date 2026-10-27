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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

enum class Tab(val label: String) {
    Home("Home"), Search("Search"), Library("Library"), Settings("Settings")
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
        val l = (listOf(t.copy(progress = 0.1f)) + recent().filter { it.key != t.key }).take(15)
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

// Vertical rows: put item [index] at the top edge of the list.
suspend fun LazyListState.glideTo(index: Int, durationMs: Int = 450) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
    } else {
        val delta = (item.offset - layoutInfo.beforeContentPadding).toFloat()
        if (abs(delta) > 1f) {
            animateScrollBy(delta, tween(durationMs, easing = FastOutSlowInEasing))
        }
    }
}

// Horizontal rows: center item [index] in the row.
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
                Color.White.copy(alpha = if (strong) 0.30f else 0.13f),
                Color.White.copy(alpha = if (strong) 0.14f else 0.05f)
            )
        )
    )
    .border(
        1.dp,
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (strong) 0.80f else 0.40f),
                Color.White.copy(alpha = 0.05f)
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
            .background(Color.White.copy(alpha = if (focused) 1f else 0.86f))
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
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 34.dp, vertical = 15.dp)
        )
    }
}

// ======================= Root =======================

@Composable
fun FrameTvApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(Tab.Home) }
    var tmdbKey by remember { mutableStateOf(store.get("tmdb")) }
    var status by remember { mutableStateOf(if (store.get("tmdb").isBlank()) "needkey" else "loading") }
    var rows by remember { mutableStateOf<List<RowData>>(emptyList()) }
    var recent by remember { mutableStateOf(store.recent()) }
    var saved by remember { mutableStateOf(store.saved()) }
    var heroTarget by remember { mutableStateOf<Title?>(null) }
    var hero by remember { mutableStateOf<Title?>(null) }
    var glow by remember { mutableStateOf(Color(0xFF3A2C8D)) }
    var lastBackPress by remember { mutableStateOf(0L) }
    val homeState = rememberLazyListState()
    val base = Color(0xFF050509)

    // Back button: leave other tabs for Home, scroll Home to top, double-press to exit
    BackHandler {
        when {
            tab != Tab.Home -> {
                tab = Tab.Home
                scope.launch { homeState.scrollToItem(0) }
            }
            homeState.firstVisibleItemIndex > 0 || homeState.firstVisibleItemScrollOffset > 0 -> {
                scope.launch { homeState.animateScrollToItem(0) }
            }
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

    // load TMDB rows whenever the key changes
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
                heroTarget = loaded.first().list.first()
                status = "ok"
            }
        }
    }

    // wait until focus settles before changing the hero
    LaunchedEffect(Unit) {
        snapshotFlow { heroTarget }.collectLatest { t ->
            if (hero == null) {
                hero = t
            } else {
                delay(250)
                hero = t
            }
        }
    }

    // glow color comes from the hero backdrop
    LaunchedEffect(hero?.key) {
        val u = img(hero?.backdrop ?: hero?.poster, "w1280")
        if (u != null) {
            val l = Img.load(u)
            if (l != null) glow = l.color
        }
    }

    val c1 = animateColorAsState(glow, tween(900), label = "c1")
    val c2 = animateColorAsState(lerp(glow, Color(0xFF2B5BFF), 0.5f), tween(900), label = "c2")

    val onFocus = remember { { t: Title -> heroTarget = t } }
    val onPlay = remember { { t: Title -> recent = store.addRecent(t) } }
    val onToggle = remember { { t: Title -> saved = store.toggleSaved(t) } }

    val homeRows = remember(rows, recent) {
        val cw = if (recent.isNotEmpty()) listOf(RowData("Continue Watching", recent, true)) else emptyList()
        cw + rows
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(base)
    ) {
        // big backdrop behind everything, fades as you scroll down
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val f = if (homeState.firstVisibleItemIndex > 0) 1f
                    else (homeState.firstVisibleItemScrollOffset / 600f).coerceIn(0f, 1f)
                    alpha = if (tab == Tab.Home) 1f - 0.75f * f else 0.3f
                }
        ) {
            Crossfade(
                targetState = hero?.backdrop,
                animationSpec = tween(700),
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .fillMaxHeight(0.80f)
                    .align(Alignment.TopEnd),
                label = "backdrop"
            ) { b ->
                RemoteImage(img(b, "w1280"), Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to base,
                            0.30f to base.copy(alpha = 0.92f),
                            0.75f to Color.Transparent
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Transparent,
                            0.80f to base
                        )
                    )
            )
        }

        // colored glow drawn in the draw phase
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(c1.value.copy(alpha = 0.50f), Color.Transparent),
                            center = Offset(size.width * 0.85f, size.height * 0.05f),
                            radius = size.width * 0.7f
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(c2.value.copy(alpha = 0.32f), Color.Transparent),
                            center = Offset(size.width * 0.05f, size.height * 0.9f),
                            radius = size.width * 0.6f
                        )
                    )
                }
        )

        Column(Modifier.fillMaxSize()) {
            TopNav(tab) { tab = it }
            when (tab) {
                Tab.Home -> HomeScreen(homeState, hero, homeRows, status, saved, onFocus, onPlay, onToggle)
                Tab.Search -> SearchScreen(
                    tmdbKey,
                    rows.firstOrNull()?.list ?: emptyList(),
                    onFocus,
                    onPlay
                )
                Tab.Library -> LibraryScreen(saved, recent, onFocus, onPlay)
                Tab.Settings -> SettingsScreen(store) { tmdbKey = it }
            }
        }
    }
}

// ======================= Top nav =======================

@Composable
fun TopNav(current: Tab, onSelect: (Tab) -> Unit) {
    Box(
        Modifier
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
                    .glass(RoundedCornerShape(14.dp), true),
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
            .padding(horizontal = 64.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(msg.first, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(msg.second, color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp)
    }
}

@Composable
fun HomeScreen(
    state: LazyListState,
    hero: Title?,
    rows: List<RowData>,
    status: String,
    saved: List<Title>,
    onFocus: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onToggle: (Title) -> Unit
) {
    if (status != "ok" || hero == null) {
        StatusMessage(status)
    } else {
        HomeContent(state, hero, rows, saved, onFocus, onPlay, onToggle)
    }
}

@Composable
fun HomeContent(
    state: LazyListState,
    hero: Title,
    rows: List<RowData>,
    saved: List<Title>,
    onFocus: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onToggle: (Title) -> Unit
) {
    val playFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var lastRow by remember { mutableStateOf(-1) }

    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    // Hero goes to the very top; rows glide so their title sits at the top edge
    val goTo = remember(state, scope) {
        { row: Int ->
            if (row != lastRow) {
                lastRow = row
                scope.launch {
                    if (row == 0) state.animateScrollToItem(0)
                    else state.glideTo(row, 450)
                }
                Unit
            }
        }
    }

    val inList = saved.any { it.key == hero.key }

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "hero") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(470.dp)
                    .padding(horizontal = 64.dp, vertical = 16.dp)
            ) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .widthIn(max = 760.dp)
                ) {
                    Crossfade(targetState = hero, animationSpec = tween(450), label = "hero") { h ->
                        Column {
                            Text(
                                if (h.type == "tv") "FEATURED  ·  SERIES" else "FEATURED  ·  MOVIE",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                letterSpacing = 3.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                h.name,
                                color = Color.White,
                                fontSize = 60.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (h.year.isNotEmpty()) {
                                    Text(h.year, color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp)
                                    Spacer(Modifier.width(14.dp))
                                }
                                if (h.rating > 0.0) {
                                    Box(
                                        Modifier
                                            .glass(RoundedCornerShape(8.dp), false)
                                            .padding(horizontal = 10.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            "★ " + String.format("%.1f", h.rating),
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                h.overview,
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 19.sp,
                                lineHeight = 27.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) goTo(0) }
                    ) {
                        PillButton("▶  Play", Modifier.focusRequester(playFocus), primary = true) {
                            onPlay(hero)
                        }
                        PillButton(if (inList) "✓  In My List" else "+  My List") { onToggle(hero) }
                    }
                }
            }
        }

        itemsIndexed(rows, key = { _, r -> r.title }) { i, r ->
            PosterRow(r, i + 1, onFocus, onPlay, goTo)
        }
    }
}

// ======================= Rows & cards =======================

@Composable
fun PosterRow(
    row: RowData,
    rowIndex: Int,
    onFocus: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onRowFocus: (Int) -> Unit = { _ -> }
) {
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column {
        Text(
            row.title,
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 64.dp)
        )
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 64.dp, vertical = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            itemsIndexed(row.list, key = { _, t -> t.key }) { i, t ->
                PosterCard(
                    t,
                    row.wide,
                    onFocused = {
                        onFocus(t)
                        onRowFocus(rowIndex)
                        scope.launch { rowState.centerItem(i, 320) }
                    },
                    onClick = { onPlay(t) }
                )
            }
        }
    }
}

@Composable
fun PosterCard(t: Title, wide: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.10f else 1f, tween(220), label = "card")
    val shape = RoundedCornerShape(26.dp)
    val w = if (wide) 360.dp else 210.dp
    val h = if (wide) 202.dp else 315.dp
    val url = if (wide) img(t.backdrop ?: t.poster, "w780") else img(t.poster ?: t.backdrop, "w342")
    val ph = remember(t.id) { placeholder(t.id) }

    Column(
        Modifier
            .width(w)
            .graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(h)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .clip(shape)
                .background(Brush.linearGradient(ph))
                .border(
                    if (focused) 3.dp else 1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (focused) 1f else 0.45f),
                            Color.White.copy(alpha = if (focused) 0.55f else 0.05f)
                        )
                    ),
                    shape
                )
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
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            RemoteImage(url, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                            center = Offset(40f, 40f),
                            radius = 420f
                        )
                    )
            )
            if (wide) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.6f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.6f)
                            )
                        )
                )
            }
            if (wide && t.progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 18.dp, vertical = 16.dp)
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
        Spacer(Modifier.height(12.dp))
        Text(
            t.name,
            color = Color.White.copy(alpha = if (focused) 1f else 0.75f),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Text(
            (if (t.type == "tv") "Series" else "Movie") + (if (t.year.isNotEmpty()) " · ${t.year}" else ""),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

// ======================= Search =======================

@Composable
fun SearchScreen(
    tmdbKey: String,
    fallback: List<Title>,
    onFocus: (Title) -> Unit,
    onPlay: (Title) -> Unit
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

    Column(Modifier.padding(top = 30.dp)) {
        Box(
            Modifier
                .padding(horizontal = 64.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(32.dp), focused)
                .padding(horizontal = 32.dp, vertical = 22.dp)
        ) {
            if (query.isEmpty()) {
                Text("Search movies and shows", color = Color.White.copy(alpha = 0.5f), fontSize = 24.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 24.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
            )
        }
        Spacer(Modifier.height(16.dp))
        if (tmdbKey.isBlank()) {
            Text(
                "Add your TMDB key in Settings to search.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 20.sp,
                modifier = Modifier.padding(start = 64.dp, top = 20.dp)
            )
        } else if (isQuery && showing.isEmpty()) {
            Text(
                if (searching) "Searching…" else "No results",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 20.sp,
                modifier = Modifier.padding(start = 64.dp, top = 20.dp)
            )
        } else {
            PosterRow(rowData, 0, onFocus, onPlay)
        }
    }
}

// ======================= Library =======================

@Composable
fun LibraryScreen(
    saved: List<Title>,
    recent: List<Title>,
    onFocus: (Title) -> Unit,
    onPlay: (Title) -> Unit
) {
    val savedRow = remember(saved) { RowData("My List", saved, false) }
    val recentRow = remember(recent) { RowData("Recently Played", recent, true) }

    if (saved.isEmpty() && recent.isEmpty()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Nothing here yet", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Add titles with My List, and what you play shows up here.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 20.sp
            )
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(top = 30.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (saved.isNotEmpty()) {
                item(key = "saved") { PosterRow(savedRow, 0, onFocus, onPlay) }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent") { PosterRow(recentRow, 0, onFocus, onPlay) }
            }
        }
    }
}

// ======================= Settings =======================

@Composable
fun SettingsScreen(store: Store, onSaved: (String) -> Unit) {
    var section by remember { mutableStateOf(0) }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // sidebar
        Column(
            Modifier
                .width(260.dp)
                .fillMaxHeight()
                .glass(RoundedCornerShape(32.dp), false)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Settings",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 14.dp)
            )
            SettingsTab("Services", section == 0) { section = 0 }
            SettingsTab("About", section == 1) { section = 1 }
        }

        // content panel
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .glass(RoundedCornerShape(32.dp), false)
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
            .padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        Text(
            label,
            color = if (focused) Color.Black else Color.White.copy(alpha = if (selected) 1f else 0.7f),
            fontSize = 19.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ServicesPanel(store: Store, onSaved: (String) -> Unit) {
    var tmdb by remember { mutableStateOf(store.get("tmdb")) }
    var torbox by remember { mutableStateOf(store.get("torbox")) }
    var rd by remember { mutableStateOf(store.get("realdebrid")) }
    var saved by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(36.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Text("Services", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Text(
                "Paste each key once. They're saved on this device only. Press select on a box to type.",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 16.sp,
                lineHeight = 23.sp
            )
        }
        item {
            SecretField("TMDB API key", tmdb) { tmdb = it; saved = false }
        }
        item {
            SecretField("TorBox API key", torbox) { torbox = it; saved = false }
        }
        item {
            SecretField("Real-Debrid API key", rd) { rd = it; saved = false }
        }
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
                    onSaved(tmdb.trim())
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
        Text("Frame TV", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Version 1.0", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "Movie and series info and artwork come from TMDB.",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 16.sp
        )
        Text(
            "Streaming links are provided through TorBox and Real-Debrid.",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 16.sp
        )
    }
}

// Tap/select to start typing. Remote scrolling is never trapped by a text field.
@Composable
fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focusReq = remember { FocusRequester() }

    BackHandler(enabled = editing) { editing = false }

    LaunchedEffect(editing) {
        if (editing) runCatching { focusReq.requestFocus() }
    }

    Column {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), focused || editing)
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { editing = true }
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
                        .onFocusChanged { if (!it.isFocused) editing = false }
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

fun maskKey(v: String): String =
    if (v.length <= 8) "••••••••" else v.take(4) + "••••••••" + v.takeLast(4)
