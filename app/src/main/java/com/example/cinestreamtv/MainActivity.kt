package com.example.cinestreamtv

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FrameTvApp() }
    }
}

// ======================= Data Models =======================

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
    val progress: Float = 0f,
    val savedPosMs: Long = 0L
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
        o.put("savedPosMs", savedPosMs)
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
            o.optDouble("progress", 0.0).toFloat(),
            o.optLong("savedPosMs", 0L)
        )
    }
}

@Immutable
class RowData(val title: String, val list: List<Title>)

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

class Sizes(val posterH: Dp, val posterW: Dp, val cwH: Dp, val cwW: Dp)

fun sizesFor(maxH: Dp): Sizes {
    val ph = maxH * 0.38f
    val cwH = maxH * 0.28f
    return Sizes(ph, ph * 0.67f, cwH, cwH * 1.77f)
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
        val l = (listOf(t) + recent().filter { it.key != t.key }).take(15)
        writeList("recent", l)
        return l
    }

    fun updateProgress(t: Title, currentPosMs: Long, totalDurationMs: Long) {
        if (totalDurationMs <= 0L) return
        val pct = (currentPosMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0.05f, 0.98f)
        val updated = t.copy(progress = pct, savedPosMs = currentPosMs)
        val l = (listOf(updated) + recent().filter { it.key != t.key }).take(15)
        writeList("recent", l)
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
    ): String = withContext(Dispatchers.IO) {
        if (torboxKey.isNotBlank()) {
            try {
                val queryName = if (title.type == "tv") {
                    "${title.name} S%02dE%02d".format(Locale.US, season, episode)
                } else {
                    "${title.name} ${title.year}"
                }
                val encodedQuery = URLEncoder.encode(queryName, "UTF-8")
                val searchUrl = URL("$API_BASE/torrents/checkcached?query=$encodedQuery&format=list")
                val conn = searchUrl.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $torboxKey")
                conn.setRequestProperty("accept", "application/json")
                conn.connectTimeout = 6000
                conn.readTimeout = 6000

                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    if (json.optBoolean("success", false)) {
                        val data = json.optJSONArray("data")
                        if (data != null && data.length() > 0) {
                            val directUrl = data.getJSONObject(0).optString("url", "")
                            if (directUrl.isNotEmpty()) return@withContext directUrl
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback stream
            }
        }
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    }
}

// ======================= TMDB Engine =======================

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
            if (poster == null) continue
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
            RowDef("Top Picks For You", "/trending/all/week", null),
            RowDef("Action & Adventure", "/discover/movie", "movie"),
            RowDef("Top Series This Week", "/tv/popular", "tv"),
            RowDef("Sci-Fi & Fantasy Universe", "/discover/movie", "movie"),
            RowDef("Critically Acclaimed Cinema", "/movie/top_rated", "movie"),
            RowDef("Animation & Family Hits", "/discover/movie", "movie"),
            RowDef("Binge-Worthy TV Shows", "/tv/top_rated", "tv"),
            RowDef("Comedy Specials & Movies", "/discover/movie", "movie"),
            RowDef("Trending Dramas", "/discover/tv", "tv"),
            RowDef("Popular Now", "/movie/popular", "movie")
        )
        
        val params = mapOf(
            "Action & Adventure" to "with_genres=28",
            "Sci-Fi & Fantasy Universe" to "with_genres=878",
            "Animation & Family Hits" to "with_genres=16",
            "Comedy Specials & Movies" to "with_genres=35",
            "Trending Dramas" to "with_genres=18"
        )

        defs.map { d ->
            async {
                val queryParams = params[d.title] ?: ""
                RowData(d.title, list(if (queryParams.isNotEmpty()) "${d.path}?$queryParams" else d.path, key, d.type))
            }
        }.awaitAll().filter { it.list.isNotEmpty() }
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

// ======================= Image Engine =======================

fun img(path: String?, size: String): String? =
    if (path.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/$size$path"

fun vivid(argb: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    hsv[1] = (hsv[1] * 1.2f).coerceIn(0.25f, 0.85f)
    hsv[2] = hsv[2].coerceIn(0.7f, 1f)
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
    val a = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 0.35f, 0.65f)))
    val b = Color(android.graphics.Color.HSVToColor(floatArrayOf((h + 40f) % 360f, 0.45f, 0.4f)))
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
    val fade by animateFloatAsState(if (bmp != null) 1f else 0f, tween(400), label = "imgFade")
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.graphicsLayer { alpha = fade }
        )
    }
}

// ======================= Smooth Scrolling =======================

suspend fun LazyListState.centerItem(index: Int, durationMs: Int = 300) {
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

// ======================= Apple TV / Netflix Clean Glass Styling =======================

fun Modifier.appleGlass(shape: Shape, focused: Boolean): Modifier = this
    .clip(shape)
    .background(
        Color.White.copy(alpha = if (focused) 0.22f else 0.08f)
    )
    .border(
        width = if (focused) 2.5.dp else 1.dp,
        brush = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (focused) 0.9f else 0.35f),
                Color.White.copy(alpha = if (focused) 0.3f else 0.05f)
            )
        ),
        shape = shape
    )

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "pillScale")
    val shape = RoundedCornerShape(50)
    
    val modifierLook = if (primary) {
        Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = if (focused) 1f else 0.9f))
    } else {
        Modifier.appleGlass(shape, focused)
    }

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focusState -> focused = focusState.isFocused }
            .then(modifierLook)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (primary) Color(0xFF0F111A) else Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 13.dp)
        )
    }
}

// ======================= Universal Player =======================

@OptIn(UnstableApi::class)
@Composable
fun AppleTvPlayerScreen(
    title: String,
    titleObj: Title,
    streamUrl: String,
    store: Store,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var duration by remember { mutableStateOf(0L) }
    var currentPos by remember { mutableStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }

    val exoPlayer = remember(streamUrl) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("FrameTV/1.6 (Linux; Android)")
            .setAllowCrossProtocolRedirects(true)

        val uri = Uri.parse(streamUrl)
        val mediaSource = if (streamUrl.contains(".m3u8")) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri))
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri))
        }

        ExoPlayer.Builder(ctx).build().apply {
            setMediaSource(mediaSource)
            prepare()
            if (titleObj.savedPosMs > 0L) {
                seekTo(titleObj.savedPosMs)
            }
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(ctx, "Stream Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            store.updateProgress(titleObj, pos, dur)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4000)
            showControls = false
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { showControls = !showControls }
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f)
                            )
                        )
                    )
                    .padding(48.dp)
            ) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillButton("← Back", onClick = onBack)
                    Spacer(Modifier.width(20.dp))
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillButton("« 10s") {
                        exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                    }
                    PillButton(if (isPlaying) "❚❚ Pause" else "▶ Play", primary = true) {
                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                    }
                    PillButton("10s »") {
                        exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(duration))
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(currentPos), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                        Text(formatTime(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Slider(
                        value = if (duration > 0) currentPos.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { percent ->
                            exoPlayer.seekTo((percent * duration).toLong())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        )
                    )
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", min, sec)
}

// ======================= Root App Container =======================

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
    var activePlayback by remember { mutableStateOf<Triple<String, Title, String>?>(null) }
    var lastBackPress by remember { mutableStateOf(0L) }
    
    val baseBackground = Brush.verticalGradient(
        listOf(Color(0xFF1C2030), Color(0xFF0C0E14))
    )

    BackHandler {
        when {
            activePlayback != null -> activePlayback = null
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

    val onOpen: (Title) -> Unit = remember {
        { selectedTitle -> detail = selectedTitle }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(baseBackground)
    ) {
        val playerItem = activePlayback
        val d = detail

        if (playerItem != null) {
            AppleTvPlayerScreen(
                title = playerItem.first,
                titleObj = playerItem.second,
                streamUrl = playerItem.third,
                store = store,
                onBack = { activePlayback = null }
            )
        } else if (d != null) {
            DetailScreen(
                t = d,
                tmdbKey = tmdbKey,
                torboxKey = torboxKey,
                saved = saved.any { it.key == d.key },
                onPlay = { 
                    val updatedRecent = store.addRecent(d)
                    recent = updatedRecent
                    CoroutineScope(Dispatchers.IO).launch {
                        val stream = TorBoxScraper.resolveStream(d, 1, 1, torboxKey)
                        withContext(Dispatchers.Main) {
                            activePlayback = Triple(d.name, d, stream)
                        }
                    }
                },
                onPlayEpisode = { titleObj, sNum, eNum ->
                    val updatedRecent = store.addRecent(titleObj)
                    recent = updatedRecent
                    CoroutineScope(Dispatchers.IO).launch {
                        val stream = TorBoxScraper.resolveStream(titleObj, sNum, eNum, torboxKey)
                        withContext(Dispatchers.Main) {
                            activePlayback = Triple("${titleObj.name} S${sNum}E${eNum}", titleObj, stream)
                        }
                    }
                },
                onToggle = { saved = store.toggleSaved(d) }
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                TopNav(current = tab) { selectedTab -> tab = selectedTab }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.Home -> HomeScreen(
                            recentList = recent,
                            rows = rows,
                            status = status,
                            onOpen = onOpen
                        )
                        Tab.Search -> SearchScreen(
                            tmdbKey,
                            rows.firstOrNull()?.list ?: emptyList(),
                            onOpen
                        )
                        Tab.Library -> LibraryScreen(saved, recent, onOpen)
                        Tab.Settings -> SettingsScreen(store) { 
                            tmdbKey = store.get("tmdb")
                            torboxKey = store.get("torbox")
                        }
                    }
                }
            }
        }
    }
}

// ======================= Top Navigation Header =======================

@Composable
fun TopNav(current: Tab, onSelect: (Tab) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, start = 64.dp, end = 64.dp, bottom = 12.dp)
    ) {
        Row(
            Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .appleGlass(RoundedCornerShape(14.dp), true),
                contentAlignment = Alignment.Center
            ) {
                Text("F", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(14.dp))
            Text("Frame TV", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(
            Modifier
                .align(Alignment.Center)
                .appleGlass(RoundedCornerShape(50), false)
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Tab.values().forEach { tabItem ->
                NavTab(tabItem.label, tabItem == current) { onSelect(tabItem) }
            }
        }
    }
}

@Composable
fun NavTab(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "tabScale")
    val shape = RoundedCornerShape(50)
    val bg = when {
        focused -> Color.White.copy(alpha = 0.95f)
        selected -> Color.White.copy(alpha = 0.25f)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focusState -> focused = focusState.isFocused }
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
            color = if (focused) Color(0xFF0F111A) else Color.White.copy(alpha = if (selected) 1f else 0.7f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
        )
    }
}

// ======================= Home Screen =======================

@Composable
fun StatusMessage(status: String) {
    val msg = when (status) {
        "needkey" -> Pair("Add your TMDB key", "Open Settings and paste your TMDB API key to load movies and shows.")
        "loading" -> Pair("Loading Catalogs", "Fetching Apple TV categories…")
        else -> Pair("Couldn't load titles", "Check your TMDB key and internet connection in Settings.")
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 64.dp, end = 64.dp, top = 40.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(msg.first, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(msg.second, color = Color.White.copy(alpha = 0.65f), fontSize = 18.sp)
    }
}

@Composable
fun HomeScreen(
    recentList: List<Title>,
    rows: List<RowData>,
    status: String,
    onOpen: (Title) -> Unit
) {
    val listState = rememberLazyListState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sz = sizesFor(maxHeight)
        if (status != "ok") {
            StatusMessage(status)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                if (recentList.isNotEmpty()) {
                    item(key = "continue_watching_shelf") {
                        ContinueWatchingRow(recentList, sz, onOpen)
                    }
                }
                items(rows, key = { rowItem -> rowItem.title }) { row ->
                    CatalogRow(row, sz, onOpen)
                }
            }
        }
    }
}

// ======================= Netflix-Style Expanded Focus Card Engine =======================

@Composable
fun ContinueWatchingRow(list: List<Title>, sz: Sizes, onOpen: (Title) -> Unit) {
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.height(36.dp).padding(start = 64.dp), contentAlignment = Alignment.CenterStart) {
            Text("Continue Watching", color = Color.White.copy(alpha = 0.95f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            itemsIndexed(list, key = { _, titleItem -> "cw-${titleItem.key}" }) { i, t ->
                NetflixExpandedCard(
                    t = t,
                    width = sz.cwW,
                    height = sz.cwH,
                    isWide = true,
                    onFocused = { scope.launch { rowState.centerItem(i, 280) } },
                    onClick = { onOpen(t) }
                )
            }
        }
    }
}

@Composable
fun CatalogRow(row: RowData, sz: Sizes, onOpen: (Title) -> Unit) {
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.height(36.dp).padding(start = 64.dp), contentAlignment = Alignment.CenterStart) {
            Text(row.title, color = Color.White.copy(alpha = 0.92f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            itemsIndexed(row.list, key = { _, titleItem -> titleItem.key }) { i, t ->
                NetflixExpandedCard(
                    t = t,
                    width = sz.posterW,
                    height = sz.posterH,
                    isWide = false,
                    onFocused = { scope.launch { rowState.centerItem(i, 280) } },
                    onClick = { onOpen(t) }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun NetflixExpandedCard(
    t: Title,
    width: Dp,
    height: Dp,
    isWide: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    LaunchedEffect(focused) {
        if (focused) {
            delay(1500)
            showPreview = true
        } else {
            showPreview = false
        }
    }

    val scaleAnim by animateFloatAsState(
        targetValue = if (focused) 1.15f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "cardScale"
    )

    val shape = RoundedCornerShape(16.dp)
    val url = if (isWide) img(t.backdrop ?: t.poster, "w780") else img(t.poster ?: t.backdrop, "w342")
    val ph = remember(t.id) { placeholder(t.id) }

    val exoPlayer = remember(showPreview, t.key) {
        if (showPreview) {
            ExoPlayer.Builder(ctx).build().apply {
                val mediaItem = MediaItem.fromUri("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
                setMediaItem(mediaItem)
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                prepare()
                playWhenReady = true
            }
        } else null
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.release()
        }
    }

    Box(
        Modifier
            .width(width)
            .height(height)
            .scale(scaleAnim)
            .shadow(if (focused) 24.dp else 4.dp, shape)
            .zIndex(if (focused) 10f else 1f)
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (focusState.isFocused) onFocused()
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
        if (showPreview && exoPlayer != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        player = exoPlayer
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    .padding(12.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Column {
                    Text(
                        t.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${t.year}  ★ ${String.format(Locale.US, "%.1f", t.rating)}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            RemoteImage(url, Modifier.fillMaxSize())
            if (isWide && t.progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.5f))
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
}

// ======================= Detail View =======================

@Composable
fun DetailScreen(
    t: Title,
    tmdbKey: String,
    torboxKey: String,
    saved: Boolean,
    onPlay: () -> Unit,
    onPlayEpisode: (Title, Int, Int) -> Unit,
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
                        0f to Color(0xFF0C0E14),
                        0.5f to Color(0xFF0C0E14).copy(alpha = 0.82f),
                        1f to Color(0xFF0C0E14).copy(alpha = 0.1f)
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
                        1f to Color(0xFF0C0E14)
                    )
                )
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 120.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    t.name,
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(meta, color = Color.White.copy(alpha = 0.7f), fontSize = 17.sp)
                    if (t.rating > 0.0) {
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "★ " + String.format(Locale.US, "%.1f", t.rating),
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            item {
                Text(
                    t.overview.ifEmpty { "No description available." },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 17.sp,
                    lineHeight = 25.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 800.dp)
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
                    Spacer(Modifier.height(10.dp))
                    Text("Episodes", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(extra?.seasons ?: emptyList(), key = { seasonItem -> seasonItem.number }) { s ->
                            PillButton("Season ${s.number}", primary = s.number == season) {
                                season = s.number
                            }
                        }
                    }
                }
                item {
                    if (eps.isEmpty()) {
                        Text("Loading episodes…", color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            items(eps, key = { episodeItem -> episodeItem.number }) { e ->
                                EpisodeCard(e) {
                                    onPlayEpisode(t, season, e.number)
                                }
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
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(180), label = "epScale")
    val shape = RoundedCornerShape(16.dp)
    val url = img(e.still, "w300")

    Column(Modifier.width(260.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(
            Modifier
                .width(260.dp)
                .height(146.dp)
                .onFocusChanged { focusState ->
                    focused = focusState.isFocused
                }
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
                    fontSize = 50.sp,
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
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (e.runtime > 0) "${e.runtime} min" else e.air,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp
        )
    }
}

// ======================= Search Screen =======================

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
            delay(400)
            results = Tmdb.search(query.trim(), tmdbKey)
            searching = false
        }
    }

    val isQuery = query.trim().length >= 2
    val showing = if (isQuery) results else fallback
    val label = if (isQuery) "Results" else "Trending"
    val rowData = remember(showing, label) { RowData(label, showing) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sz = sizesFor(maxHeight)
        Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
            Box(
                Modifier
                    .padding(horizontal = 64.dp)
                    .fillMaxWidth()
                    .appleGlass(RoundedCornerShape(24.dp), focused)
                    .padding(horizontal = 24.dp, vertical = 18.dp)
            ) {
                if (query.isEmpty()) {
                    Text("Search movies and shows", color = Color.White.copy(alpha = 0.45f), fontSize = 20.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { newQuery -> query = newQuery },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState -> focused = focusState.isFocused }
                )
            }
            Spacer(Modifier.height(16.dp))
            when {
                tmdbKey.isBlank() -> Text(
                    "Add your TMDB key in Settings to search.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 17.sp,
                    modifier = Modifier.padding(start = 64.dp, top = 20.dp)
                )
                isQuery && showing.isEmpty() -> Text(
                    if (searching) "Searching…" else "No results",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 17.sp,
                    modifier = Modifier.padding(start = 64.dp, top = 20.dp)
                )
                else -> CatalogRow(rowData, sz, onOpen)
            }
        }
    }
}

// ======================= Library Screen =======================

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
                    .padding(start = 64.dp, end = 64.dp, top = 40.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Nothing here yet", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Add titles with My List, and what you play shows up here.",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 17.sp
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                if (saved.isNotEmpty()) {
                    item(key = "saved") { CatalogRow(RowData("My List", saved), sz, onOpen) }
                }
                if (recent.isNotEmpty()) {
                    item(key = "recent") { ContinueWatchingRow(recent, sz, onOpen) }
                }
            }
        }
    }
}

// ======================= Settings Screen =======================

@Composable
fun SettingsScreen(store: Store, onSaved: () -> Unit) {
    var section by remember { mutableStateOf(0) }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 64.dp, end = 64.dp, top = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Column(
            Modifier
                .width(260.dp)
                .fillMaxHeight()
                .appleGlass(RoundedCornerShape(24.dp), false)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Settings",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 12.dp)
            )
            SettingsTab("Services", section == 0) { section = 0 }
            SettingsTab("About", section == 1) { section = 1 }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .appleGlass(RoundedCornerShape(24.dp), false)
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
        focused -> Color.White.copy(alpha = 0.95f)
        selected -> Color.White.copy(alpha = 0.2f)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (focusState.isFocused) onSelect()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(
            label,
            color = if (focused) Color(0xFF0F111A) else Color.White.copy(alpha = if (selected) 1f else 0.7f),
            fontSize = 17.sp,
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
            Text("Services", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Text(
                "Configure your TMDB and TorBox scraper keys below.",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 16.sp,
                lineHeight = 22.sp
            )
        }
        item { SecretField("TMDB API key", tmdb) { newKey -> tmdb = newKey; saved = false } }
        item { SecretField("TorBox API key", torbox) { newKey -> torbox = newKey; saved = false } }
        item { SecretField("Real-Debrid API key", rd) { newKey -> rd = newKey; saved = false } }
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
                if (saved) Text("Saved", color = Color(0xFF7CE0A3), fontSize = 17.sp)
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
        Text("Frame TV", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Version 1.6", color = Color.White.copy(alpha = 0.65f), fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Metadata and artwork provided by TMDB.",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp
        )
        Text(
            "Built-in streaming scraper powered directly by TorBox caches.",
            color = Color.White.copy(alpha = 0.6f),
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
    val shape = RoundedCornerShape(18.dp)
    val active = focused || editing

    BackHandler(enabled = editing) { editing = false }

    LaunchedEffect(editing) {
        if (editing) {
            delay(100)
            runCatching { focusReq.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    Column {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color.White.copy(alpha = if (active) 0.18f else 0.06f))
                .border(
                    if (active) 2.5.dp else 1.dp,
                    Color.White.copy(alpha = if (active) 0.9f else 0.3f),
                    shape
                )
                .onFocusChanged { focusState -> focused = focusState.isFocused }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (!editing) editing = true }
                )
                .padding(horizontal = 22.dp, vertical = 16.dp)
        ) {
            if (editing) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { editing = false }),
                    textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusReq)
                )
            } else {
                Text(
                    if (value.isEmpty()) "Select to add" else maskKey(value),
                    color = if (value.isEmpty()) Color.White.copy(alpha = 0.4f) else Color.White,
                    fontSize = 18.sp
                )
            }
        }
    }
}

fun maskKey(v: String): String =
    if (v.length <= 8) "••••••••" else v.take(4) + "••••••••" + v.takeLast(4)
