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
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.window.Dialog
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

class TitleExtra(
    val runtime: String,
    val genres: String,
    val seasons: List<SeasonInfo>,
    val releaseDate: String,
    val director: String,
    val cast: String,
    val voteCount: Int,
    val logoPath: String?
)

enum class Tab(val label: String) {
    Home("Home"), Search("Search"), Library("Library"), Settings("Settings")
}

class Sizes(val posterH: Dp, val posterW: Dp, val cwH: Dp, val cwW: Dp)

fun sizesFor(maxH: Dp): Sizes {
    val ph = maxH * 0.54f
    val pw = ph * 0.85f
    val cwH = maxH * 0.35f
    return Sizes(ph, pw, cwH, cwH * 1.77f)
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

    fun removeFromRecent(t: Title): List<Title> {
        val l = recent().filter { it.key != t.key }
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

    private fun get(path: String, key: String, params: String = "", appendToResponse: String = ""): JSONObject? {
        return try {
            val bearer = key.startsWith("eyJ")
            val sb = StringBuilder(BASE).append(path).append("?language=en-US")
            if (params.isNotEmpty()) sb.append("&").append(params)
            if (appendToResponse.isNotEmpty()) sb.append("&append_to_response=").append(appendToResponse)
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
        val o = get("/${t.type}/${t.id}", key, appendToResponse = "credits,images") ?: return@withContext TitleExtra("", "", emptyList(), "", "", "", 0, null)
        
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

        val releaseDate = o.optString("release_date").ifEmpty { o.optString("first_air_date", "") }
        val voteCount = o.optInt("vote_count", 0)

        val credits = o.optJSONObject("credits")
        val director = if (t.type == "movie") {
            val crew = credits?.optJSONArray("crew")
            var dir = ""
            if (crew != null) {
                for (i in 0 until crew.length()) {
                    val c = crew.getJSONObject(i)
                    if (c.optString("job") == "Director") {
                        dir = c.optString("name")
                        break
                    }
                }
            }
            dir
        } else {
            val creators = o.optJSONArray("created_by")
            if (creators != null && creators.length() > 0) {
                creators.getJSONObject(0).optString("name")
            } else ""
        }

        val castArr = credits?.optJSONArray("cast")
        val cast = if (castArr != null) {
            (0 until minOf(5, castArr.length())).joinToString(", ") { castArr.getJSONObject(it).optString("name") }
        } else ""

        val images = o.optJSONObject("images")
        val logos = images?.optJSONArray("logos")
        var logoPath: String? = null
        if (logos != null) {
            for (i in 0 until logos.length()) {
                val l = logos.getJSONObject(i)
                if (l.optString("iso_639_1") == "en" || l.optString("iso_639_1").isEmpty()) {
                    logoPath = l.optString("file_path")
                    break
                }
            }
            if (logoPath == null && logos.length() > 0) {
                logoPath = logos.getJSONObject(0).optString("file_path")
            }
        }

        TitleExtra(runtime, genres, seasons, releaseDate, director, cast, voteCount, logoPath)
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

// ======================= Styling Helpers =======================

fun Modifier.appleGlass(shape: Shape, focused: Boolean): Modifier = this
    .clip(shape)
    .background(
        Color.White.copy(alpha = if (focused) 0.18f else 0.06f)
    )
    .border(
        width = if (focused) 2.dp else 1.dp,
        brush = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (focused) 0.8f else 0.2f),
                Color.White.copy(alpha = if (focused) 0.2f else 0.04f)
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
            .setUserAgent("FrameTV/2.4 (Linux; Android)")
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
                recentList = recent,
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
                onToggleSaved = { 
                    saved = store.toggleSaved(d) 
                },
                onRemoveRecent = {
                    recent = store.removeFromRecent(d)
                }
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
                        Tab.Library -> LibraryScreen(saved, onOpen)
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
                contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(48.dp)
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

// ======================= Netflix-Style Expanding Focus Card Engine =======================

@Composable
fun ContinueWatchingRow(list: List<Title>, sz: Sizes, onOpen: (Title) -> Unit) {
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.height(36.dp).padding(start = 64.dp), contentAlignment = Alignment.CenterStart) {
            Text("Continue Watching", color = Color.White.copy(alpha = 0.95f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            itemsIndexed(list, key = { _, titleItem -> "cw-${titleItem.key}" }) { i, t ->
                NetflixExpandedCard(
                    t = t,
                    height = sz.cwH,
                    normalWidth = sz.cwW,
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
            Text(row.title, color = Color.White.copy(alpha = 0.92f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            itemsIndexed(row.list, key = { _, titleItem -> titleItem.key }) { i, t ->
                NetflixExpandedCard(
                    t = t,
                    height = sz.posterH,
                    normalWidth = sz.posterW,
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
    height: Dp,
    normalWidth: Dp,
    isWide: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    LaunchedEffect(focused) {
        if (focused) {
            delay(1200)
            showPreview = true
        } else {
            showPreview = false
        }
    }

    val landscapeW = height * 1.77f
    val targetWidth = if (isWide) landscapeW * 1.1f else landscapeW
    
    val animatedWidth by animateDpAsState(
        targetValue = if (focused) targetWidth else normalWidth,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "cardWidth"
    )

    val shape = RoundedCornerShape(16.dp)
    val activeImageUrl = if (focused) {
        img(t.backdrop ?: t.poster, "w780")
    } else {
        img(t.poster ?: t.backdrop, "w780")
    }

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
            .width(animatedWidth)
            .height(height)
            .shadow(if (focused) 32.dp else 4.dp, shape)
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
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                        )
                    )
                    .padding(16.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Column {
                    Text(
                        t.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${t.year}  ★ ${String.format(Locale.US, "%.1f", t.rating)}",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            RemoteImage(activeImageUrl, Modifier.fillMaxSize())
            if (isWide && t.progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .height(5.dp)
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

// ======================= Netflix TV Detail Screen (Precise D-pad Focus & Liquid Glass) =======================

@Composable
fun DetailScreen(
    t: Title,
    tmdbKey: String,
    torboxKey: String,
    saved: Boolean,
    recentList: List<Title>,
    onPlay: () -> Unit,
    onPlayEpisode: (Title, Int, Int) -> Unit,
    onToggleSaved: () -> Unit,
    onRemoveRecent: () -> Unit
) {
    var extra by remember(t.key) { mutableStateOf<TitleExtra?>(null) }
    var season by remember(t.key) { mutableStateOf(1) }
    var eps by remember(t.key) { mutableStateOf<List<Episode>>(emptyList()) }
    var showEpisodeBrowser by remember(t.key) { mutableStateOf(false) }
    var showSeasonPicker by remember(t.key) { mutableStateOf(false) }

    val playFocus = remember { FocusRequester() }
    val isShow = t.type == "tv"
    val isInContinueWatching = recentList.any { it.key == t.key }

    LaunchedEffect(t.key) {
        val e = Tmdb.details(t, tmdbKey)
        extra = e
        season = e.seasons.firstOrNull()?.number ?: 1
    }

    LaunchedEffect(t.key, season) {
        if (isShow) {
            eps = Tmdb.season(t.id, season, tmdbKey)
        }
    }

    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    val playButtonText = when {
        t.progress > 0f -> "Resume"
        isShow -> "Play S${season}:E1"
        else -> "Play"
    }

    val currentSeasonObj = extra?.seasons?.firstOrNull { it.number == season }
    val currentSeasonName = currentSeasonObj?.name ?: "Season $season"

    BackHandler(enabled = showEpisodeBrowser) {
        showEpisodeBrowser = false
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF141414))) {
        // Full-Screen Cinematic Backdrop
        RemoteImage(img(t.backdrop ?: t.poster, "original"), Modifier.fillMaxSize())
        
        // Left gradient shadow for text/button readability
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.65f)
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xFF141414).copy(alpha = 0.94f),
                        0.7f to Color(0xFF141414).copy(alpha = 0.65f),
                        1f to Color.Transparent
                    )
                )
        )

        // Bottom gradient shadow
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.65f to Color(0xFF141414).copy(alpha = 0.3f),
                        1f to Color(0xFF141414)
                    )
                )
        )

        if (showEpisodeBrowser) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 64.dp, vertical = 48.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${t.name} — Episodes",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if ((extra?.seasons?.size ?: 0) > 0) {
                        NetflixSeasonDropdownTrigger(
                            currentSeasonName = currentSeasonName,
                            onClick = { showSeasonPicker = true }
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (eps.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading episodes...", color = Color.White.copy(alpha = 0.5f), fontSize = 18.sp)
                    }
                } else {
                    val epListState = rememberLazyListState()
                    LazyColumn(
                        state = epListState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 60.dp)
                    ) {
                        itemsIndexed(eps, key = { _, ep -> ep.number }) { _, e ->
                            val progress = if (t.progress > 0f) t.progress else 0f
                            VerticalEpisodeRow(
                                e = e,
                                progress = progress,
                                onClick = { onPlayEpisode(t, season, e.number) }
                            )
                        }
                    }
                }
            }

            if (showSeasonPicker && extra?.seasons != null) {
                SeasonSelectionDialog(
                    seasons = extra!!.seasons,
                    currentSeason = season,
                    onSelect = { selectedSeason ->
                        season = selectedSeason
                        showSeasonPicker = false
                    },
                    onDismiss = { showSeasonPicker = false }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.6f)
                    .padding(start = 72.dp, top = 36.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.Center
            ) {
                // Logo or Title
                val logoUrl = img(extra?.logoPath, "w500")
                if (logoUrl != null) {
                    RemoteImage(
                        logoUrl,
                        Modifier
                            .height(90.dp)
                            .widthIn(max = 380.dp)
                    )
                } else {
                    Text(
                        t.name,
                        color = Color.White,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Metadata Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (t.year.isNotEmpty()) {
                        Text(t.year, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("•", color = Color.White.copy(alpha = 0.3f), fontSize = 15.sp)
                    }
                    Text(if (isShow) "Series" else "Movie", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (extra?.runtime?.isNotEmpty() == true) {
                        Text("•", color = Color.White.copy(alpha = 0.3f), fontSize = 15.sp)
                        Text(extra!!.runtime, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    if (t.rating > 0.0) {
                        Text("•", color = Color.White.copy(alpha = 0.3f), fontSize = 15.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("★", color = Color(0xFFFFC107), fontSize = 15.sp)
                            Text(
                                String.format(Locale.US, "%.1f", t.rating),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (extra?.genres?.isNotEmpty() == true) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        extra!!.genres,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Synopsis
                Text(
                    t.overview.ifEmpty { "No overview available." },
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(20.dp))

                // Slimmer Vertical Button Stack with Liquid Glass & Reliable Focus
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NetflixActionButton(
                        text = playButtonText,
                        icon = "▶",
                        isPrimary = true,
                        modifier = Modifier.focusRequester(playFocus),
                        onClick = onPlay
                    )

                    if (isShow) {
                        NetflixActionButton(
                            text = "Episodes",
                            icon = "☰",
                            isPrimary = false,
                            onClick = { showEpisodeBrowser = true }
                        )
                    }

                    NetflixActionButton(
                        text = if (saved) "Remove from My List" else "Add to My List",
                        icon = if (saved) "✓" else "+",
                        isPrimary = false,
                        onClick = onToggleSaved
                    )

                    if (isInContinueWatching) {
                        NetflixActionButton(
                            text = "Remove from Continue Watching",
                            icon = "✕",
                            isPrimary = false,
                            onClick = onRemoveRecent
                        )
                    }
                }
            }
        }
    }
}

// ======================= Slimmer Liquid Glass Netflix Button =======================

@Composable
fun NetflixActionButton(
    text: String,
    icon: String,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    // Liquid Glass unfocused styling vs Solid White focused styling
    val background = when {
        focused -> Color.White
        else -> Color(0xFF262626).copy(alpha = 0.7f)
    }

    val border = if (focused) {
        BorderStroke(1.dp, Color.White)
    } else {
        BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    }

    val contentColor = if (focused) Color(0xFF141414) else Color.White

    Box(
        modifier = modifier
            .width(340.dp)
            .clip(shape)
            .background(background)
            .border(border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                icon,
                color = contentColor,
                fontSize = if (icon == "▶") 15.sp else 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text,
                color = contentColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ======================= Vertical Episode Item =======================

@Composable
fun VerticalEpisodeRow(
    e: Episode,
    progress: Float,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val stillUrl = img(e.still, "w500")

    val bg = when {
        focused -> Color.White.copy(alpha = 0.15f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(
            Modifier
                .width(220.dp)
                .height(124.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(placeholder(e.number)))
        ) {
            if (stillUrl == null) {
                Text(
                    e.number.toString(),
                    color = Color.White.copy(alpha = 0.2f),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            RemoteImage(stillUrl, Modifier.fillMaxSize())

            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(Color(0xFFE50914))
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "${e.number}.",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    e.name,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (e.runtime > 0) {
                    Text(
                        "${e.runtime}m",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Text(
                e.overview.ifEmpty { "No episode description available." },
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ======================= Season Dropdown & Dialog =======================

@Composable
fun NetflixSeasonDropdownTrigger(
    currentSeasonName: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                currentSeasonName,
                color = if (focused) Color.Black else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "▾",
                color = if (focused) Color.Black else Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun SeasonSelectionDialog(
    seasons: List<SeasonInfo>,
    currentSeason: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        BackHandler { onDismiss() }

        Box(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF181818))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Select Season",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(seasons, key = { it.number }) { s ->
                        SeasonDialogItem(
                            season = s,
                            isSelected = s.number == currentSeason,
                            onClick = { onSelect(s.number) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SeasonDialogItem(
    season: SeasonInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Color.White else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                season.name,
                color = if (focused) Color.Black else if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            if (isSelected) {
                Text("✓", color = if (focused) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
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

// ======================= Library Screen (My List Only) =======================

@Composable
fun LibraryScreen(
    saved: List<Title>,
    onOpen: (Title) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sz = sizesFor(maxHeight)
        if (saved.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 64.dp, end = 64.dp, top = 40.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Your List is Empty", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Add movies and TV shows to your list to find them here easily.",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 17.sp
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 24.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                item(key = "saved_list") {
                    CatalogRow(RowData("My List", saved), sz, onOpen)
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
        Text("Version 2.4", color = Color.White.copy(alpha = 0.65f), fontSize = 17.sp)
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
