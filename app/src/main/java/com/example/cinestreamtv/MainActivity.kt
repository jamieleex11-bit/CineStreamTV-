package com.example.cinestreamtv

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

private const val APP_VERSION = "1.6.0"

private val NetBlack = Color(0xFF0B0B0B)
private val NetDarkGray = Color(0xFF1F1F1F)
private val NetRed = Color(0xFFE50914)
private val NetWhite = Color(0xFFFFFFFF)
private val NetMuted = Color(0xFF9A9A9A)
private val NetGold = Color(0xFFD4AF37)

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

data class Episode(
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String,
    val still: String,
    val air: String,
    val runtime: Int
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

data class Catalog(
    val all: List<Film>,
    val rows: List<Pair<String, List<Film>>>,
    val ranked: Set<String> = emptySet()
)

private val Resume.key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url
private val PlayReq.key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url

private val FilmSaver: Saver<Film?, Any> = Saver(
    save = { f ->
        f?.let {
            listOf(it.id, it.tmdbId, it.name, it.year, it.kind, it.poster, it.backdrop, it.overview, it.rating, it.provider)
        }
    },
    restore = { v ->
        @Suppress("UNCHECKED_CAST")
        val l = v as List<Any?>
        Film(
            l[0] as String, l[1] as Int, l[2] as String, l[3] as String, l[4] as String,
            l[5] as String, l[6] as String, l[7] as String, l[8] as String, l[9] as String
        )
    }
)

private val PlayReqSaver: Saver<PlayReq?, Any> = Saver(
    save = { p ->
        p?.let { listOf(it.filmId, it.name, it.image, it.url, it.torrentId, it.fileId, it.start) }
    },
    restore = { v ->
        @Suppress("UNCHECKED_CAST")
        val l = v as List<Any?>
        PlayReq(
            l[0] as String, l[1] as String, l[2] as String, l[3] as String,
            l[4] as Int, l[5] as Int, l[6] as Long
        )
    }
)

private fun poster(path: String) = "https://image.tmdb.org/t/p/w500$path"
private fun wide(path: String) = "https://image.tmdb.org/t/p/w1280$path"

private val NON_ALNUM = Regex("[^a-z0-9]+")
private fun norm(s: String) = s.lowercase().replace(NON_ALNUM, " ").trim()

private val EP = Regex("(?i)s(\\d{1,2})[ ._-]?e(\\d{1,3})|(?<![0-9])(\\d{1,2})x(\\d{1,3})")
private val QUALITY = Regex("(?i)(2160p|1440p|1080p|720p|480p|4k)")

private val JUNK = setOf(
    "1080p", "720p", "2160p", "4k", "web", "dl", "webdl", "webrip", "bluray", "brrip",
    "bdrip", "hdrip", "dvdrip", "x264", "x265", "h264", "h265", "hevc", "avc", "aac",
    "ac3", "dts", "ddp", "atmos", "hdr", "dv", "remux", "proper", "repack", "amzn",
    "nf", "dsnp", "hmax", "atvp", "multi", "dual", "subs", "internal"
)

private fun episodeOf(name: String): Pair<Int, Int>? {
    val m = EP.find(name) ?: return null
    val s = m.groupValues[1].ifEmpty { m.groupValues[3] }.toIntOrNull() ?: return null
    val e = m.groupValues[2].ifEmpty { m.groupValues[4] }.toIntOrNull() ?: return null
    return s to e
}

private fun qualityOf(name: String): String =
    QUALITY.find(name)?.value?.lowercase()?.replace("4k", "2160p")?.uppercase() ?: ""

private fun titleKey(fileName: String): String =
    norm(fileName.substringBefore(EP).ifEmpty { fileName })
        .split(' ')
        .filter { it.isNotEmpty() && it !in JUNK }
        .joinToString(" ")

private fun squash(s: String) = norm(s).replace(" ", "")

private fun titleMatches(show: String, fileTitle: String): Boolean {
    val s = squash(show)
    val f = squash(fileTitle)
    if (s.length < 2 || f.isEmpty()) return false
    if (s.length < 3) return f == s || f.startsWith(s)
    return f == s || f.startsWith(s) || f.contains(s)
}

private fun fmtSize(b: Long): String {
    val gb = b / 1073741824.0
    return if (gb >= 1) "${Math.round(gb * 10) / 10.0} GB" else "${b / 1048576} MB"
}

private fun epTag(season: Int, number: Int) = "S%02dE%02d".format(season, number)

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

private fun Film.toJson(): JSONObject = JSONObject()
    .put("id", id).put("tmdbId", tmdbId).put("name", name).put("year", year).put("kind", kind)
    .put("poster", poster).put("backdrop", backdrop).put("overview", overview)
    .put("rating", rating).put("provider", provider)

private fun filmFrom(o: JSONObject) = Film(
    o.str("id"), o.optInt("tmdbId"), o.str("name"), o.str("year"), o.str("kind"),
    o.str("poster"), o.str("backdrop"), o.str("overview"), o.str("rating"), o.str("provider")
)

private val demoFilms = listOf(
    Film("d1", 101, "Ted Lasso", "2023", "Series", poster("/5fhZdwP1DVJ0FyVH6vrFdHwpXIn.jpg"), wide("/56v2KjBlU4XaOv9rVYEQypROD7P.jpg"),
        "An American football coach is hired to manage a British soccer team.", "8.6", "Apple TV+"),
    Film("d2", 102, "Succession", "2023", "Series", poster("/7HW47XbkNQ5fiwQFYGWdw9gs144.jpg"), wide("/4HodYYKEIsGOdhrGi2Ud6XGjBn1.jpg"),
        "The Roy family controls the biggest media and entertainment company in the world.", "8.8", "MAX"),
    Film("d3", 103, "The Mandalorian", "2023", "Series", poster("/eU1i6eHXlzMOlEq0ku1Rzq7Y4wA.jpg"), wide("/xOMo8BRK7PfcJv9JCnx7s5hj0PX.jpg"),
        "The travels of a lone bounty hunter in the outer reaches of the galaxy.", "8.7", "Disney+"),
    Film("d4", 104, "Stranger Things", "2022", "Series", poster("/49WJfeN0moxb9ANekyaGEA1XFR6.jpg"), wide("/rAiYTfKGqDCRIIqo664sY9XZIvQ.jpg"),
        "When a young boy vanishes, a small town uncovers a mystery involving secret experiments.", "8.7", "Netflix"),
    Film("d5", 105, "Breaking Bad", "2013", "Series", poster("/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"), wide("/9faGSFi5jam6pDWGNd0p8JcJgXQ.jpg"),
        "A chemistry teacher diagnosed with cancer turns to making methamphetamine.", "9.3", "Netflix"),
    Film("d6", 106, "Game of Thrones", "2019", "Series", poster("/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg"), wide("/2OMB0ynKlyIenMJWI2Dy9IWT4c.jpg"),
        "Noble families wage war for control of the Iron Throne of Westeros.", "8.4", "MAX"),
    Film("d7", 107, "The Last of Us", "2023", "Series", poster("/uKvVjHNqB5VmOrdxqAt2F7J78ED.jpg"), wide("/uDgy6hyPd82kOHh6I95FLtLnj6p.jpg"),
        "A hardened survivor escorts a teenage girl across a post-apocalyptic America.", "8.7", "MAX"),
    Film("d8", 108, "Wednesday", "2022", "Series", poster("/9PFonBhy4cQy7Jz20NpMygczOkv.jpg"), wide("/iHSwvRVsRyxpX7FE7GbviaDvgGZ.jpg"),
        "Wednesday Addams navigates her years at Nevermore Academy.", "8.1", "Netflix"),
    Film("d9", 109, "Oppenheimer", "2023", "Movie", poster("/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg"), wide("/fm6KqXpk3M2HVveHwCrBSSBaO0V.jpg"),
        "The story of the physicist who led the race to build the atomic bomb.", "8.1", "Prime Video"),
    Film("d10", 110, "Dune", "2021", "Movie", poster("/d5NXSklXo0qyIYkgV94XAgMIckC.jpg"), wide("/jYEW5xZkZk2WTrdbMGAPFuBqbDc.jpg"),
        "A noble family becomes embroiled in a war for a desert planet's spice trade.", "7.8", "MAX"),
    Film("d11", 111, "Interstellar", "2014", "Movie", poster("/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"), wide("/xJHokMbljvjADYdit5fK5VQsXEG.jpg"),
        "Explorers travel through a wormhole in search of a new home for humanity.", "8.4", "Prime Video"),
    Film("d12", 112, "Parasite", "2019", "Movie", poster("/7IiTTgloJzvGI1TAYymCfbfl3vT.jpg"), wide("/TU9NIjwzjoKPwQHoHshkFcQUCG.jpg"),
        "A poor family schemes to become employed by a wealthy household.", "8.5", "Prime Video")
)

private fun buildFallbackRows(all: List<Film>): List<Pair<String, List<Film>>> {
    if (all.isEmpty()) return emptyList()
    val out = ArrayList<Pair<String, List<Film>>>()
    out.add("Trending Now" to all.take(30))
    val movies = all.filter { it.kind == "Movie" }
    val series = all.filter { it.kind == "Series" }
    if (series.isNotEmpty()) out.add("TV Shows" to series)
    if (movies.isNotEmpty()) out.add("Movies" to movies)
    for (p in listOf("Netflix", "Prime Video", "Disney+", "Apple TV+", "MAX")) {
        val list = all.filter { it.provider == p }
        if (list.isNotEmpty()) out.add(p to list)
    }
    return out
}

private fun loadResume(p: SharedPreferences): List<Resume> = try {
    val arr = JSONArray(p.getString("resume", "[]"))
    (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        Resume(o.str("filmId"), o.str("name"), o.str("image"), o.str("url"),
            o.optInt("tid", -1), o.optInt("fid", -1), o.optLong("pos"), o.optLong("dur"))
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
    (0 until arr.length()).map { filmFrom(arr.getJSONObject(it)) }
} catch (e: Exception) { emptyList() }

private fun saveWatchlist(p: SharedPreferences, list: List<Film>) {
    val arr = JSONArray()
    list.forEach { arr.put(it.toJson()) }
    p.edit().putString("watchlist", arr.toString()).apply()
}

private object Cache {
    private const val KEY = "catalog_cache_v2"
    private const val MAX_PER_ROW = 24

    fun save(p: SharedPreferences, c: Catalog) {
        try {
            val root = JSONObject()
            root.put("at", System.currentTimeMillis())
            root.put("ranked", JSONArray(c.ranked.toList()))
            val rows = JSONArray()
            c.rows.forEach { (label, films) ->
                if (films.isEmpty()) return@forEach
                val arr = JSONArray()
                films.take(MAX_PER_ROW).forEach { arr.put(it.toJson()) }
                rows.put(JSONObject().put("label", label).put("films", arr))
            }
            root.put("rows", rows)
            p.edit().putString(KEY, root.toString()).apply()
        } catch (_: Exception) {}
    }

    fun load(p: SharedPreferences): Catalog? = try {
        val raw = p.getString(KEY, null) ?: return null
        val root = JSONObject(raw)
        val rowsArr = root.optJSONArray("rows") ?: return null
        val ranked = root.optJSONArray("ranked")
            ?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
            ?: emptySet()
        val rows = ArrayList<Pair<String, List<Film>>>()
        val all = LinkedHashMap<String, Film>()
        for (i in 0 until rowsArr.length()) {
            val r = rowsArr.getJSONObject(i)
            val arr = r.optJSONArray("films") ?: continue
            val films = (0 until arr.length()).mapNotNull {
                runCatching { filmFrom(arr.getJSONObject(it)) }.getOrNull()
            }
            if (films.isEmpty()) continue
            rows.add(r.str("label") to films)
            films.forEach { all.putIfAbsent(it.id, it) }
        }
        if (all.isEmpty()) null else Catalog(all.values.toList(), rows, ranked)
    } catch (_: Exception) { null }
}

private fun httpGet(urlStr: String, bearer: String? = null): String {
    val c = URL(urlStr).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 20000
    c.useCaches = false
    c.setRequestProperty("Accept", "application/json")
    c.setRequestProperty("User-Agent", "CineStreamTV/$APP_VERSION")
    if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
    try {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("HTTP $code")
        return body
    } finally { c.disconnect() }
}

private data class RowSpec(
    val label: String,
    val paths: List<Pair<String, String>>,
    val provider: String = ""
)

private object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val REGION = "US"
    private const val PAGES = 3

    private val gate = Semaphore(10)

    val providers = listOf(
        Provider("Netflix", 8),
        Provider("Prime Video", 9),
        Provider("Disney+", 337),
        Provider("Apple TV+", 350),
        Provider("MAX", 1899)
    )

    private val movieGenres = listOf(
        "Action & Adventure" to 28,
        "Comedy" to 35,
        "Horror" to 27,
        "Sci-Fi" to 878,
        "Animation" to 16,
        "Documentaries" to 99,
        "Thrillers" to 53,
        "Crime" to 80,
        "Romance" to 10749
    )

    private val tvGenres = listOf(
        "Drama Series" to 18,
        "Sci-Fi & Fantasy TV" to 10765,
        "Reality TV" to 10764,
        "Mystery Series" to 9648
    )

    private fun call(path: String, key: String): String {
        val sep = if (path.contains("?")) "&" else "?"
        return if (key.contains('.')) httpGet("$BASE$path", key)
        else httpGet("$BASE$path${sep}api_key=$key")
    }

    private fun toFilm(o: JSONObject, fallbackKind: String, providerLabel: String): Film? {
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
            backdrop = if (b.isEmpty()) "" else wide(b),
            overview = o.str("overview"),
            rating = if (r > 0) (Math.round(r * 10) / 10.0).toString() else "",
            provider = providerLabel
        )
    }

    private fun parse(body: String, fallbackKind: String, providerLabel: String): List<Film> {
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            try { toFilm(arr.getJSONObject(it), fallbackKind, providerLabel) } catch (_: Exception) { null }
        }
    }

    suspend fun loadQuick(key: String): Catalog = withContext(Dispatchers.IO) {
        val quick = listOf(
            Triple("Trending Now", "/trending/all/week", "movie"),
            Triple("New Releases", "/movie/now_playing", "movie"),
            Triple("Binge-Worthy TV", "/tv/popular", "tv")
        )
        val all = LinkedHashMap<String, Film>()
        val rows = ArrayList<Pair<String, List<Film>>>()
        for ((label, path, kind) in quick) {
            try {
                val list = parse(call(path, key), kind, "")
                if (list.isNotEmpty()) {
                    rows.add(label to list)
                    list.forEach { all.putIfAbsent(it.id, it) }
                }
            } catch (_: Exception) {}
        }
        if (all.isEmpty()) Catalog(demoFilms, buildFallbackRows(demoFilms))
        else Catalog(all.values.toList(), rows)
    }

    private fun discover(kind: String, query: String, page: Int) =
        "/discover/$kind?$query&sort_by=popularity.desc&page=$page"

    private fun specs(): List<RowSpec> {
        val s = ArrayList<RowSpec>()

        s.add(RowSpec("Top 10 Today", listOf("/trending/all/day" to "movie")))
        s.add(RowSpec("Trending Now", (1..PAGES).map { "/trending/all/week?page=$it" to "movie" }))
        s.add(RowSpec("New Releases", (1..PAGES).map { "/movie/now_playing?page=$it" to "movie" }))
        s.add(RowSpec("Coming Soon", (1..PAGES).map { "/movie/upcoming?page=$it" to "movie" }))
        s.add(RowSpec("Critically Acclaimed", (1..PAGES).map { "/movie/top_rated?page=$it" to "movie" }))

        for (p in providers) {
            val q = "with_watch_providers=${p.tmdbId}&watch_region=$REGION&with_watch_monetization_types=flatrate"
            val paths = ArrayList<Pair<String, String>>()
            for (pg in 1..2) {
                paths.add(discover("movie", q, pg) to "movie")
                paths.add(discover("tv", q, pg) to "tv")
            }
            s.add(RowSpec(p.label, paths, provider = p.label))
        }

        for ((label, id) in movieGenres) {
            s.add(RowSpec(label, (1..2).map { discover("movie", "with_genres=$id", it) to "movie" }))
        }
        for ((label, id) in tvGenres) {
            s.add(RowSpec(label, (1..2).map { discover("tv", "with_genres=$id", it) to "tv" }))
        }

        s.add(RowSpec("Binge-Worthy TV", (1..PAGES).map { "/tv/popular?page=$it" to "tv" }))
        s.add(RowSpec("Top Rated TV", (1..PAGES).map { "/tv/top_rated?page=$it" to "tv" }))
        s.add(RowSpec("Airing This Week", (1..2).map { "/tv/on_the_air?page=$it" to "tv" }))

        return s
    }

    private fun fetchRow(key: String, spec: RowSpec): Pair<String, List<Film>> {
        val row = LinkedHashMap<String, Film>()
        for ((path, kind) in spec.paths) {
            try {
                parse(call(path, key), kind, spec.provider).forEach { row.putIfAbsent(it.id, it) }
            } catch (_: Exception) {}
        }
        return spec.label to row.values.toList()
    }

    suspend fun load(key: String): Catalog = withContext(Dispatchers.IO) {
        val specs = specs()
        val ranked = specs.take(1).map { it.label }.toSet()

        val fetched = coroutineScope {
            specs.map { spec -> async { gate.withPermit { fetchRow(key, spec) } } }.awaitAll()
        }.filter { it.second.isNotEmpty() }

        val all = LinkedHashMap<String, Film>()
        fetched.forEach { (_, list) ->
            list.forEach { f ->
                val existing = all[f.id]
                if (existing == null) all[f.id] = f
                else if (existing.provider.isEmpty() && f.provider.isNotEmpty()) all[f.id] = f
            }
        }

        when {
            all.isEmpty() -> Catalog(demoFilms, buildFallbackRows(demoFilms))
            fetched.isEmpty() -> Catalog(all.values.toList(), buildFallbackRows(all.values.toList()))
            else -> Catalog(all.values.toList(), fetched, ranked)
        }
    }

    suspend fun search(key: String, q: String): List<Film> = withContext(Dispatchers.IO) {
        try {
            parse(call("/search/multi?query=${URLEncoder.encode(q, "UTF-8")}&include_adult=false", key), "movie", "")
        } catch (_: Exception) { emptyList() }
    }

    suspend fun seasons(key: String, tmdbId: Int): List<Int> = withContext(Dispatchers.IO) {
        try {
            val arr = JSONObject(call("/tv/$tmdbId", key)).optJSONArray("seasons") ?: return@withContext emptyList()
            (0 until arr.length())
                .map { arr.getJSONObject(it).optInt("season_number", -1) }
                .filter { it >= 0 }
                .sortedWith(compareBy({ if (it == 0) 1 else 0 }, { it }))
        } catch (e: Exception) { emptyList() }
    }

    suspend fun season(key: String, tmdbId: Int, season: Int): List<Episode> = withContext(Dispatchers.IO) {
        try {
            val arr = JSONObject(call("/tv/$tmdbId/season/$season", key)).optJSONArray("episodes")
                ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.getJSONObject(i)
                val num = e.optInt("episode_number", -1)
                if (num < 0) return@mapNotNull null
                val still = e.str("still_path")
                Episode(
                    season = season,
                    number = num,
                    name = e.str("name"),
                    overview = e.str("overview"),
                    still = if (still.isEmpty()) "" else "https://image.tmdb.org/t/p/w300$still",
                    air = e.str("air_date"),
                    runtime = e.optInt("runtime", 0)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun imdb(tmdbKey: String, omdbKey: String, film: Film): String? = withContext(Dispatchers.IO) {
        try {
            val kind = if (film.kind == "Series") "tv" else "movie"
            val imdbId = JSONObject(call("/$kind/${film.tmdbId}/external_ids", tmdbKey)).str("imdb_id")
            if (imdbId.isEmpty()) return@withContext null
            val r = JSONObject(httpGet("https://www.omdbapi.com/?i=$imdbId&apikey=$omdbKey")).str("imdbRating")
            if (r.isEmpty() || r == "N/A") null else r
        } catch (e: Exception) { null }
    }
}

private object Tb {
    private const val BASE = "https://api.torbox.app/v1/api"
    private val VIDEO = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".ts")

    private var cache: List<TbFile>? = null
    private var cacheAt = 0L
    private const val TTL = 120_000L
    private const val PAGE = 200
    private const val MAX_TORRENTS = 2000

    suspend fun library(key: String, force: Boolean = false): Pair<String, List<TbFile>> {
        val cached = cache
        if (!force && cached != null && System.currentTimeMillis() - cacheAt < TTL) return "" to cached

        return withContext(Dispatchers.IO) {
            try {
                val out = ArrayList<TbFile>()
                var offset = 0
                while (offset < MAX_TORRENTS) {
                    val body = httpGet("$BASE/torrents/mylist?limit=$PAGE&offset=$offset&bypass_cache=$force", key)
                    val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
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
                    if (data.length() < PAGE) break
                    offset += PAGE
                }
                cache = out
                cacheAt = System.currentTimeMillis()
                "" to out
            } catch (e: Exception) {
                val stale = cache
                if (stale != null) "Couldn't refresh — showing cached library." to stale
                else "TorBox error." to emptyList()
            }
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
        window.statusBarColor = android.graphics.Color.rgb(11, 11, 11)
        window.navigationBarColor = android.graphics.Color.rgb(11, 11, 11)
        setContent { NetCleanApp() }
    }
}

@Composable
fun NetCleanApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("netstream", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val stateHolder = rememberSaveableStateHolder()

    var torboxKey by remember { mutableStateOf(prefs.getString("torbox_token", "") ?: "") }
    var tmdbKey by remember { mutableStateOf(prefs.getString("tmdb_key", "") ?: "") }
    var omdbKey by remember { mutableStateOf(prefs.getString("omdb_key", "") ?: "") }

    var screen by rememberSaveable { mutableStateOf("Home") }
    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var linkText by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable(stateSaver = FilmSaver) { mutableStateOf<Film?>(null) }
    var play by rememberSaveable(stateSaver = PlayReqSaver) { mutableStateOf<PlayReq?>(null) }

    val cached = remember { Cache.load(prefs) }
    var catalog by remember { mutableStateOf(cached ?: Catalog(demoFilms, buildFallbackRows(demoFilms))) }
    var hasCache by remember { mutableStateOf(cached != null) }

    var results by remember { mutableStateOf<List<Film>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf<List<TbFile>>(emptyList()) }
    var libStatus by remember { mutableStateOf("") }
    var tbStatus by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<TbFile>>(emptyList()) }
    var detailMsg by remember { mutableStateOf("") }
    var detailBusy by remember { mutableStateOf(false) }
    var homeLoading by remember { mutableStateOf(false) }
    var homeNote by remember { mutableStateOf("") }
    var catalogNonce by remember { mutableStateOf(0) }

    val resume = remember { mutableStateListOf<Resume>().also { it.addAll(loadResume(prefs)) } }
    val watchlist = remember { mutableStateListOf<Film>().also { it.addAll(loadWatchlist(prefs)) } }

    val libByEpisode = remember(library) {
        val m = HashMap<Pair<Int, Int>, MutableList<TbFile>>()
        for (f in library) {
            val ep = episodeOf(f.name) ?: continue
            m.getOrPut(ep) { mutableListOf() }.add(f)
        }
        m
    }
    val libTitle = remember(library) { library.associateWith { titleKey(it.name) } }

    suspend fun loadLibrary(force: Boolean = false) {
        if (torboxKey.isBlank()) { libStatus = "Add your TorBox API key in Settings."; return }
        val res = Tb.library(torboxKey, force)
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

    var lastSavedKey by remember { mutableStateOf("") }
    var lastSavedPos by remember { mutableStateOf(-1L) }

    fun report(req: PlayReq, pos: Long, dur: Long) {
        if (pos < 3000 || dur <= 0) return
        if (req.key == lastSavedKey && abs(pos - lastSavedPos) < 5000) return
        lastSavedKey = req.key
        lastSavedPos = pos

        val finished = pos > dur * 0.95
        val idx = resume.indexOfFirst { it.key == req.key }
        if (idx >= 0) resume.removeAt(idx)
        if (!finished) resume.add(0, Resume(req.filmId, req.name, req.image, req.url, req.torrentId, req.fileId, pos, dur))
        while (resume.size > 10) resume.removeAt(resume.size - 1)
        saveResume(prefs, resume)
    }

    fun fileForEpisode(show: Film, season: Int, number: Int): TbFile? =
        libByEpisode[season to number]?.firstOrNull { titleMatches(show.name, libTitle[it] ?: "") }

    fun filesForShow(show: Film): List<TbFile> =
        library.filter { titleMatches(show.name, libTitle[it] ?: "") }

    LaunchedEffect(tmdbKey, catalogNonce) {
        val key = tmdbKey.trim()
        if (key.isBlank()) {
            homeNote = if (hasCache) "" else "Demo titles — add a TMDB API key in Settings."
            return@LaunchedEffect
        }
        delay(600)
        homeLoading = true
        if (!hasCache) {
            runCatching { Tmdb.loadQuick(key) }.getOrNull()?.let { catalog = it }
        }
        runCatching { Tmdb.load(key) }
            .onSuccess { full ->
                catalog = full
                hasCache = true
                homeNote = ""
                Cache.save(prefs, full)
            }
            .onFailure {
                homeNote = if (hasCache) "" else "Catalog failed to load — check your connection or API key."
            }
        homeLoading = false
    }

    LaunchedEffect(query, tmdbKey) {
        if (query.isBlank()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(300)
        results = if (tmdbKey.isBlank()) catalog.all.filter { it.name.contains(query, true) }
        else Tmdb.search(tmdbKey, query.trim())
        searching = false
    }

    LaunchedEffect(detail?.id, torboxKey) {
        val d = detail
        if (d != null && d.kind == "Series" && torboxKey.isNotBlank() && library.isEmpty()) loadLibrary()
    }

    BackHandler(enabled = detail != null || selectedProvider != null || (screen == "Search" && query.isNotBlank())) {
        when {
            detail != null -> detail = null
            selectedProvider != null -> selectedProvider = null
            else -> query = ""
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = NetBlack, surface = NetDarkGray, primary = NetRed,
            onBackground = NetWhite, onSurface = NetWhite
        )
    ) {
        Box(Modifier.fillMaxSize().background(NetBlack)) {
            val contentKey = when {
                play != null -> "player"
                detail != null -> "detail_${detail?.id}"
                selectedProvider != null -> "provider_$selectedProvider"
                else -> "tab_$screen"
            }

            stateHolder.SaveableStateProvider(contentKey) {
                val p = play
                val d = detail
                val prov = selectedProvider

                when {
                    p != null -> PlayerScreen(
                        req = p,
                        torboxKey = torboxKey,
                        onProgress = { pos, dur -> report(p, pos, dur) },
                        onExit = { play = null }
                    )

                    d != null -> {
                        val matchingResume = resume.firstOrNull { it.filmId == d.id }
                        val isInWatchlist = watchlist.any { it.id == d.id }
                        DetailsScreen(
                            film = d,
                            msg = detailMsg,
                            matches = matches,
                            busy = detailBusy,
                            resumePos = matchingResume?.pos ?: 0L,
                            resumeDur = matchingResume?.dur ?: 0L,
                            isInWatchlist = isInWatchlist,
                            tmdbKey = tmdbKey,
                            omdbKey = omdbKey,
                            libraryReady = library.isNotEmpty(),
                            resumeSnapshot = resume.toList(),
                            episodeFile = { s, n -> fileForEpisode(d, s, n) != null },
                            onToggleWatchlist = {
                                if (isInWatchlist) watchlist.removeAll { it.id == d.id } else watchlist.add(d)
                                saveWatchlist(prefs, watchlist)
                            },
                            onEpisode = { season, number ->
                                scope.launch {
                                    detailBusy = true; detailMsg = ""; matches = emptyList()
                                    loadLibrary()
                                    val hit = fileForEpisode(d, season, number)
                                    if (hit != null) {
                                        startTb(hit, d, 0L)
                                    } else {
                                        val all = filesForShow(d)
                                        detailMsg = if (all.isEmpty())
                                            "${epTag(season, number)} isn't in your TorBox library."
                                        else {
                                            matches = all
                                            "${epTag(season, number)} not found — pick a file below."
                                        }
                                    }
                                    detailBusy = false
                                }
                            },
                            onPlay = { posToStart ->
                                scope.launch {
                                    detailBusy = true; matches = emptyList(); detailMsg = ""
                                    loadLibrary()
                                    if (torboxKey.isBlank() || library.isEmpty()) {
                                        detailMsg = libStatus.ifEmpty { "No TorBox files found." }
                                    } else {
                                        val m = filesForShow(d)
                                        if (m.isEmpty()) detailMsg = "Not found in TorBox library."
                                        else if (m.size == 1) startTb(m[0], d, posToStart)
                                        else matches = m
                                    }
                                    detailBusy = false
                                }
                            },
                            onPick = { f, posToStart -> startTb(f, d, posToStart) },
                            onRefreshLibrary = { scope.launch { detailBusy = true; loadLibrary(force = true); detailBusy = false } },
                            onBack = { detail = null }
                        )
                    }

                    else -> Column(Modifier.fillMaxSize().background(NetBlack)) {
                        NetTopNav(current = screen) { screen = it; selectedProvider = null }

                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            if (prov != null) {
                                val kindFilter = if (screen == "Shows") "Series" else "Movie"
                                ProviderCatalogScreen(
                                    providerName = prov,
                                    kind = kindFilter,
                                    catalog = catalog.all.filter { it.provider == prov && it.kind == kindFilter },
                                    onFilm = { open(it) },
                                    onBack = { selectedProvider = null }
                                )
                            } else {
                                when (screen) {
                                    "Home" -> HomeScreen(
                                        catalog = catalog,
                                        resume = resume,
                                        loading = homeLoading,
                                        note = homeNote,
                                        onFilm = { open(it) },
                                        onResume = { r -> play = PlayReq(r.filmId, r.name, r.image, r.url, r.torrentId, r.fileId, r.pos) },
                                        onRemoveResume = { r ->
                                            resume.removeAll { it.key == r.key }
                                            saveResume(prefs, resume)
                                        }
                                    )
                                    "Shows" -> ProviderHubScreen(
                                        title = "TV Shows",
                                        subtitle = "Browse by streaming service",
                                        available = Tmdb.providers.map { it.label },
                                        onSelect = { selectedProvider = it }
                                    )
                                    "Movies" -> ProviderHubScreen(
                                        title = "Movies",
                                        subtitle = "Browse by streaming service",
                                        available = Tmdb.providers.map { it.label },
                                        onSelect = { selectedProvider = it }
                                    )
                                    "My List" -> MyListScreen(
                                        watchlist = watchlist,
                                        onFilm = { open(it) },
                                        onRemove = { f ->
                                            watchlist.removeAll { it.id == f.id }
                                            saveWatchlist(prefs, watchlist)
                                        }
                                    )
                                    "Search" -> SearchScreen(query, { query = it }, results, searching) { open(it) }
                                    else -> SettingsScreen(
                                        torboxKey = torboxKey,
                                        onTorbox = { torboxKey = it.trim(); prefs.edit().putString("torbox_token", it.trim()).apply() },
                                        onTest = { scope.launch { tbStatus = "Testing..."; tbStatus = Tb.test(torboxKey) } },
                                        tbStatus = tbStatus,
                                        tmdbKey = tmdbKey,
                                        onTmdb = { tmdbKey = it.trim(); prefs.edit().putString("tmdb_key", it.trim()).apply() },
                                        omdbKey = omdbKey,
                                        onOmdb = { omdbKey = it.trim(); prefs.edit().putString("omdb_key", it.trim()).apply() },
                                        link = linkText,
                                        onLink = { linkText = it },
                                        onPlayLink = {
                                            val u = linkText.trim()
                                            if (u.startsWith("http://") || u.startsWith("https://")) startUrl(u)
                                        },
                                        onRefresh = { catalogNonce++ }
                                    )
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
private fun NetTopNav(current: String, onNav: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(NetBlack).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(Color(0xFF1A1A1A))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CapsuleNavItem(label = "⌕", isCurrent = current == "Search") { onNav("Search") }
            CapsuleNavItem(label = "Home", isCurrent = current == "Home") { onNav("Home") }
            CapsuleNavItem(label = "Shows", isCurrent = current == "Shows") { onNav("Shows") }
            CapsuleNavItem(label = "Movies", isCurrent = current == "Movies") { onNav("Movies") }
            CapsuleNavItem(label = "My List", isCurrent = current == "My List") { onNav("My List") }
            CapsuleNavItem(label = "Settings", isCurrent = current == "Settings") { onNav("Settings") }
        }
    }
}

@Composable
private fun CapsuleNavItem(label: String, isCurrent: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "navScale")

    val textColor = when {
        focused -> Color.Black
        isCurrent -> NetWhite
        else -> NetMuted
    }

    Box(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .scale(scale)
            .clip(RoundedCornerShape(50.dp))
            .background(if (focused) NetWhite else Color.Transparent)
            .focusRequester(requester)
            .clickable { runCatching { requester.requestFocus() }; onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = if (label == "⌕") 18.sp else 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun NetCard(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "cardScale")
    Box(
        modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(NetDarkGray)
            .then(if (focused) Modifier.border(3.dp, NetWhite, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
    ) {
        content(this, focused)
    }
}

@Composable
private fun RemoveBadge(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (focused) NetWhite else Color(0xD9000000))
            .border(1.dp, Color(0x66FFFFFF), CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onRemove),
        contentAlignment = Alignment.Center
    ) {
        Text("✕", color = if (focused) Color.Black else NetWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PosterCard(
    film: Film,
    showProvider: Boolean = false,
    width: Dp = 150.dp,
    focusRequester: FocusRequester? = null,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    NetCard(
        modifier = Modifier.width(width).height(width * 1.47f),
        focusRequester = focusRequester,
        onClick = onClick
    ) { focused ->
        AsyncImage(
            model = film.poster,
            contentDescription = film.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)), startY = 200f)))
        if (focused) {
            Text(
                film.name, color = NetWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            )
        }
        if (showProvider && film.provider.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(NetRed.copy(alpha = 0.92f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(film.provider, color = NetWhite, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (onRemove != null) {
            RemoveBadge(onRemove, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}

@Composable
private fun RankCard(rank: Int, film: Film, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            rank.toString(),
            color = Color(0xFF3D3D3D),
            fontSize = 92.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.offset(x = 10.dp).padding(end = 2.dp)
        )
        PosterCard(film, width = 140.dp, onClick = onClick)
    }
}

@Composable
private fun ResumeCard(r: Resume, onClick: () -> Unit, onRemove: () -> Unit) {
    Box(Modifier.width(260.dp).height(145.dp)) {
        NetCard(Modifier.fillMaxSize(), onClick = onClick) {
            if (r.image.isNotEmpty()) {
                AsyncImage(model = r.image, contentDescription = r.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))
            Text(
                r.name, color = NetWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            )
            val frac = if (r.dur > 0) (r.pos.toFloat() / r.dur.toFloat()).coerceIn(0f, 1f) else 0f
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0xFF555555))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(NetRed))
            }
        }
        RemoveBadge(onRemove, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

@Composable
private fun RowTitle(title: String) {
    Row(
        Modifier.padding(start = 32.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.width(3.dp).height(18.dp).background(NetRed, RoundedCornerShape(2.dp)))
        Text(title, color = NetWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
    }
}

@Composable
private fun HeroButton(label: String, filled: Boolean, requester: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "heroBtn")
    val base = if (filled) Color(0xFFF2F2F2) else Color(0x59FFFFFF)
    Box(
        modifier = Modifier
            .height(46.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) NetWhite else base)
            .then(if (focused) Modifier.border(2.dp, NetWhite, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (filled || focused) Color.Black else NetWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeroContent(film: Film, onOpen: () -> Unit) {
    val requester = remember { FocusRequester() }

    Column(
        modifier = Modifier.padding(start = 32.dp, bottom = 30.dp).widthIn(max = 660.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (film.provider.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("STREAMING ON", color = NetMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
                Box(Modifier.background(NetRed, RoundedCornerShape(2.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                    Text(film.provider, color = NetWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            film.name, color = NetWhite, fontSize = 42.sp, fontWeight = FontWeight.Black,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 46.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (film.year.isNotEmpty()) Text(film.year, color = NetMuted, fontSize = 13.sp)
            Text("•", color = NetMuted)
            Text(film.kind, color = NetMuted, fontSize = 13.sp)
            if (film.rating.isNotEmpty()) {
                Text("•", color = NetMuted)
                Text("★ ${film.rating}", color = Color(0xFF46D369), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            film.overview, color = NetWhite.copy(alpha = 0.88f), fontSize = 14.sp, maxLines = 3,
            overflow = TextOverflow.Ellipsis, lineHeight = 21.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroButton("▶ Play", filled = true, requester = requester, onClick = onOpen)
            HeroButton("ⓘ More Info", filled = false, onClick = onOpen)
        }
    }

    LaunchedEffect(Unit) {
        delay(350)
        runCatching { requester.requestFocus() }
    }
}

@Composable
private fun HomeScreen(
    catalog: Catalog,
    resume: List<Resume>,
    loading: Boolean,
    note: String,
    onFilm: (Film) -> Unit,
    onResume: (Resume) -> Unit,
    onRemoveResume: (Resume) -> Unit
) {
    val hero = catalog.all.firstOrNull() ?: demoFilms.first()

    val rows = remember(catalog) {
        catalog.rows.ifEmpty { buildFallbackRows(catalog.all) }
    }

    val heroHeight = (LocalConfiguration.current.screenHeightDp * 0.56f).dp
    val heroPx = with(LocalDensity.current) { heroHeight.toPx() }
    val listState = rememberLazyListState()

    val heroAlpha by remember {
        derivedStateOf {
            val offset = if (listState.firstVisibleItemIndex > 0) heroPx
            else listState.firstVisibleItemScrollOffset.toFloat()
            (1f - offset / (heroPx * 0.85f)).coerceIn(0f, 1f)
        }
    }

    Box(Modifier.fillMaxSize().background(NetBlack)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .graphicsLayer { alpha = heroAlpha }
        ) {
            if (hero.backdrop.isNotEmpty()) {
                AsyncImage(model = hero.backdrop, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent, NetBlack), startY = 0f, endY = heroPx)
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(NetBlack.copy(alpha = 0.9f), Color.Transparent), endX = 800f)
                )
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    HeroContent(hero, onOpen = { onFilm(hero) })
                }
            }

            if (note.isNotEmpty()) {
                item(key = "note") {
                    Text(note, color = NetGold, fontSize = 13.sp, modifier = Modifier.padding(start = 32.dp, top = 6.dp))
                }
            }

            if (loading) {
                item(key = "loading") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = NetRed, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Building your library...", color = NetMuted, fontSize = 13.sp)
                    }
                }
            }

            if (resume.isNotEmpty()) {
                item(key = "resume") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                        RowTitle("Continue Watching")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.focusGroup()
                        ) {
                            items(resume, key = { it.key }) { r ->
                                ResumeCard(r, onClick = { onResume(r) }, onRemove = { onRemoveResume(r) })
                            }
                        }
                    }
                }
            }

            rows.forEach { (label, list) ->
                if (list.isEmpty()) return@forEach
                item(key = "row_$label") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                        RowTitle(label)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.focusGroup()
                        ) {
                            if (label in catalog.ranked) {
                                itemsIndexed(list.take(10), key = { _, f -> f.id }) { i, f ->
                                    RankCard(i + 1, f) { onFilm(f) }
                                }
                            } else {
                                items(list, key = { it.id }) { f ->
                                    PosterCard(f, showProvider = true) { onFilm(f) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val providerColors = mapOf(
    "Netflix" to Color(0xFF661111),
    "Disney+" to Color(0xFF113366),
    "MAX" to Color(0xFF331166),
    "Prime Video" to Color(0xFF115544),
    "Apple TV+" to Color(0xFF1A1A1A)
)

@Composable
private fun ProviderHubScreen(title: String, subtitle: String, available: List<String>, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 24.dp)) {
        Text(title, color = NetWhite, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = NetMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 22.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize().focusGroup()
        ) {
            items(available) { label ->
                var focused by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "provScale")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .scale(scale)
                        .onFocusChanged { focused = it.isFocused }
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(providerColors[label] ?: Color(0xFF222222), Color(0xFF141414))))
                        .border(if (focused) 3.dp else 1.dp, if (focused) NetWhite else Color(0x33FFFFFF), RoundedCornerShape(14.dp))
                        .clickable { onSelect(label) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, color = NetWhite, fontSize = 30.sp, fontWeight = FontWeight.Black)
                        Box(Modifier.width(40.dp).height(2.dp).background(NetGold))
                        Text("Browse collection", color = NetMuted, fontSize = 13.sp, letterSpacing = 0.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MyListScreen(watchlist: List<Film>, onFilm: (Film) -> Unit, onRemove: (Film) -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 24.dp)) {
        Text("My List", color = NetWhite, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 22.dp))

        if (watchlist.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Your list is empty. Add shows or movies to watch later.", color = NetMuted, fontSize = 16.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize().focusGroup()
            ) {
                items(watchlist, key = { it.id }) { f ->
                    PosterCard(f, onRemove = { onRemove(f) }) { onFilm(f) }
                }
            }
        }
    }
}

@Composable
private fun ProviderCatalogScreen(providerName: String, kind: String, catalog: List<Film>, onFilm: (Film) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 22.dp)) {
            var focused by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "backScale")
            Box(
                modifier = Modifier
                    .scale(scale)
                    .onFocusChanged { focused = it.isFocused }
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (focused) Color(0xFF333333) else NetDarkGray)
                    .border(2.dp, if (focused) NetWhite else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("← Back", color = NetWhite, fontWeight = FontWeight.Bold)
            }
            Text("$providerName · $kind", color = NetWhite, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("${catalog.size} titles", color = NetMuted, fontSize = 13.sp)
        }

        if (catalog.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing in this category yet.", color = NetMuted, fontSize = 16.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize().focusGroup()
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
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 16.dp)) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            label = { Text("Search the whole catalog...") },
            shape = RoundedCornerShape(8.dp),
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
        } else if (query.isNotBlank()) {
            Text("${results.size} results for \"$query\"", color = NetMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize().focusGroup()
        ) {
            items(results, key = { it.id }) { f -> PosterCard(f, showProvider = true) { onFilm(f) } }
        }
    }
}

@Composable
private fun SettingsScreen(
    torboxKey: String, onTorbox: (String) -> Unit, onTest: () -> Unit, tbStatus: String,
    tmdbKey: String, onTmdb: (String) -> Unit,
    omdbKey: String, onOmdb: (String) -> Unit,
    link: String, onLink: (String) -> Unit, onPlayLink: () -> Unit,
    onRefresh: () -> Unit
) {
    val field = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = NetDarkGray, unfocusedContainerColor = NetDarkGray,
        focusedBorderColor = NetWhite, unfocusedBorderColor = Color(0x33FFFFFF),
        focusedTextColor = NetWhite, unfocusedTextColor = NetWhite
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", color = NetWhite, fontSize = 30.sp, fontWeight = FontWeight.Black)

        Text("TorBox API Key", color = NetMuted, fontSize = 13.sp)
        OutlinedTextField(value = torboxKey, onValueChange = onTorbox, singleLine = true, shape = RoundedCornerShape(8.dp), colors = field, modifier = Modifier.fillMaxWidth())
        NetCard(onClick = onTest) { Text("Test TorBox Connection", color = NetWhite, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
        if (tbStatus.isNotEmpty()) Text(tbStatus, color = NetWhite, fontSize = 14.sp)

        Text("TMDB API Key (catalog)", color = NetMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        OutlinedTextField(value = tmdbKey, onValueChange = onTmdb, singleLine = true, shape = RoundedCornerShape(8.dp), colors = field, modifier = Modifier.fillMaxWidth())
        Text("v3 key or v4 read token — both work.", color = Color(0xFF5A5A5A), fontSize = 11.sp)
        NetCard(onClick = onRefresh) { Text("Refresh Catalog", color = NetWhite, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }

        Text("OMDb API Key (IMDb ratings — optional)", color = NetMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        OutlinedTextField(value = omdbKey, onValueChange = onOmdb, singleLine = true, shape = RoundedCornerShape(8.dp), colors = field, modifier = Modifier.fillMaxWidth())

        Text("Direct Stream Link", color = NetMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        OutlinedTextField(value = link, onValueChange = onLink, singleLine = true, shape = RoundedCornerShape(8.dp), colors = field, modifier = Modifier.fillMaxWidth())
        NetCard(onClick = onPlayLink) { Text("Play Link", color = NetWhite, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }

        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1FFFFFFF)))
        Spacer(Modifier.height(16.dp))

        Text("CineStream TV", color = NetWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("Version $APP_VERSION", color = NetMuted, fontSize = 12.sp)
        Text("Made by Jamie", color = NetGold, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("Catalog data by TMDB · Ratings by IMDb/OMDb", color = Color(0xFF5A5A5A), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun EpisodeRow(ep: Episode, available: Boolean, progress: Float?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "epScale")

    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color(0xFF2A2A2A) else Color(0x12FFFFFF))
            .border(if (focused) 2.dp else 1.dp, if (focused) NetWhite else Color(0x1FFFFFFF), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.width(150.dp).height(84.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF222222))) {
            if (ep.still.isNotEmpty()) {
                AsyncImage(model = ep.still, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(
                Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(Color(0xB3000000)),
                contentAlignment = Alignment.Center
            ) { Text("▶", color = NetWhite, fontSize = 12.sp) }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(NetRed))
                }
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ep.number.toString(), color = NetWhite, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text(
                    ep.name.ifEmpty { "Episode ${ep.number}" },
                    color = NetWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ep.runtime > 0) Text("${ep.runtime}m", color = NetMuted, fontSize = 12.sp)
                if (ep.air.isNotEmpty()) Text(ep.air, color = NetMuted, fontSize = 12.sp)
                if (progress != null) Text("In progress", color = NetGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                ep.overview.ifEmpty { "No description available." },
                color = NetMuted, fontSize = 12.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 16.sp
            )
        }

        if (available) {
            Box(Modifier.background(NetRed, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text("READY", color = NetWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
    resumePos: Long,
    resumeDur: Long,
    isInWatchlist: Boolean,
    tmdbKey: String,
    omdbKey: String,
    libraryReady: Boolean,
    resumeSnapshot: List<Resume>,
    episodeFile: (Int, Int) -> Boolean,
    onToggleWatchlist: () -> Unit,
    onEpisode: (Int, Int) -> Unit,
    onPlay: (Long) -> Unit,
    onPick: (TbFile, Long) -> Unit,
    onRefreshLibrary: () -> Unit,
    onBack: () -> Unit
) {
    val hasProgress = resumePos > 5000 && resumeDur > 0
    val progressFraction = if (hasProgress) (resumePos.toFloat() / resumeDur.toFloat()).coerceIn(0f, 1f) else 0f
    val buttonText = if (hasProgress) "Resume" else "Play"

    var imdb by remember(film.id) { mutableStateOf("") }
    LaunchedEffect(film.id) {
        if (omdbKey.isNotBlank() && tmdbKey.isNotBlank()) {
            val r = Tmdb.imdb(tmdbKey, omdbKey, film)
            if (r != null) imdb = r
        }
    }

    val progressByEp = remember(resumeSnapshot, film.id) {
        resumeSnapshot.filter { it.filmId == film.id && it.dur > 0 }
            .mapNotNull { r ->
                episodeOf(r.name)?.let { ep -> ep to (r.pos.toFloat() / r.dur.toFloat()).coerceIn(0f, 1f) }
            }
            .toMap()
    }

    var seasons by remember(film.id) { mutableStateOf<List<Int>>(emptyList()) }
    var selectedSeason by rememberSaveable(film.id) { mutableStateOf(-1) }
    var episodes by remember(film.id) { mutableStateOf<List<Episode>>(emptyList()) }
    var epLoading by remember(film.id) { mutableStateOf(false) }

    LaunchedEffect(film.id, tmdbKey) {
        if (tmdbKey.isBlank()) return@LaunchedEffect
        val s = Tmdb.seasons(tmdbKey, film.tmdbId)
        seasons = s
        if (s.isNotEmpty()) selectedSeason = s.first()
    }

    LaunchedEffect(film.id, selectedSeason, tmdbKey) {
        if (tmdbKey.isBlank() || selectedSeason < 0) { episodes = emptyList(); return@LaunchedEffect }
        epLoading = true
        episodes = Tmdb.season(tmdbKey, film.tmdbId, selectedSeason)
        epLoading = false
    }

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
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(NetBlack, NetBlack.copy(alpha = 0.92f), NetBlack.copy(alpha = 0.45f)))))

        LazyColumn(
            modifier = Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 44.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "back") {
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

                    if (film.provider.isNotEmpty()) {
                        Text("Streaming on", color = NetMuted, fontSize = 13.sp)
                        Box(Modifier.background(NetRed, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(film.provider, color = NetWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item(key = "title") {
                Text(film.name, color = NetWhite, fontSize = 44.sp, fontWeight = FontWeight.Black, lineHeight = 48.sp)
            }

            item(key = "meta") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (film.rating.isNotEmpty()) Text("★ ${film.rating}", color = Color(0xFF46D369), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (imdb.isNotEmpty()) {
                        Box(Modifier.background(NetGold.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text("IMDb $imdb", color = NetGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (film.year.isNotEmpty()) Text(film.year, color = NetWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(film.kind, color = NetMuted, fontSize = 15.sp)
                }
            }

            item(key = "overview") {
                Text(film.overview, color = NetWhite, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.widthIn(max = 640.dp))
            }

            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var playFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .width(330.dp)
                            .height(54.dp)
                            .scale(if (playFocused) 1.04f else 1f)
                            .onFocusChanged { playFocused = it.isFocused }
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (playFocused) NetWhite else Color(0xFFF2F2F2))
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
                            .width(330.dp)
                            .height(48.dp)
                            .scale(if (listFocused) 1.04f else 1f)
                            .onFocusChanged { listFocused = it.isFocused }
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (listFocused) Color(0xFF3A3A3A) else Color(0xFF242424))
                            .border(1.dp, if (listFocused) NetWhite else Color(0x44FFFFFF), RoundedCornerShape(6.dp))
                            .clickable(onClick = onToggleWatchlist),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isInWatchlist) "✓ In My List" else "+ Add to My List", color = NetWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (busy) {
                item(key = "busy") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = NetRed, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Matching in your TorBox library...", color = NetMuted, fontSize = 14.sp)
                    }
                }
            }

            if (msg.isNotEmpty()) {
                item(key = "msg") { Text(msg, color = NetGold, fontSize = 14.sp) }
            }

            if (film.kind == "Series") {
                item(key = "ep_header") {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1FFFFFFF)))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            Text("Episodes", color = NetWhite, fontSize = 22.sp, fontWeight = FontWeight.Black)
                            if (episodes.isNotEmpty()) Text("${episodes.size} in season", color = NetMuted, fontSize = 13.sp)
                        }
                    }
                }

                if (tmdbKey.isBlank()) {
                    item(key = "ep_nokey") {
                        Text("Add a TMDB API key in Settings to browse episodes.", color = NetMuted, fontSize = 14.sp)
                    }
                }

                if (seasons.isNotEmpty()) {
                    item(key = "seasons") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
                            items(seasons) { s ->
                                var f by remember { mutableStateOf(false) }
                                val isSel = s == selectedSeason
                                Box(
                                    Modifier
                                        .onFocusChanged { f = it.isFocused }
                                        .clip(RoundedCornerShape(50.dp))
                                        .background(if (isSel) NetWhite else Color(0xFF262626))
                                        .border(1.dp, if (f) NetWhite else Color(0x33FFFFFF), RoundedCornerShape(50.dp))
                                        .clickable { selectedSeason = s }
                                        .padding(horizontal = 18.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        if (s == 0) "Specials" else "Season $s",
                                        color = if (isSel) Color.Black else NetWhite,
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                when {
                    epLoading -> item(key = "ep_loading") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = NetRed, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Loading episodes...", color = NetMuted, fontSize = 13.sp)
                        }
                    }
                    episodes.isEmpty() -> if (tmdbKey.isNotBlank()) item(key = "ep_empty") {
                        Text("No episode data for this season.", color = NetMuted, fontSize = 14.sp)
                    }
                    else -> items(episodes, key = { "${it.season}_${it.number}" }) { ep ->
                        EpisodeRow(
                            ep = ep,
                            available = episodeFile(ep.season, ep.number),
                            progress = progressByEp[ep.season to ep.number],
                            onClick = { onEpisode(ep.season, ep.number) }
                        )
                    }
                }

                if (!libraryReady) {
                    item(key = "ep_nolib") {
                        Text("Add your TorBox key to see which episodes are ready.", color = Color(0xFF5A5A5A), fontSize = 12.sp)
                    }
                }
            }

            if (ordered.isNotEmpty()) {
                item(key = "files_header") {
                    Row(
                        Modifier.padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("${ordered.size} files in your library", color = NetWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        var rFocused by remember { mutableStateOf(false) }
                        Box(
                            Modifier
                                .onFocusChanged { rFocused = it.isFocused }
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (rFocused) Color(0xFF333333) else Color(0xFF1A1A1A))
                                .border(1.dp, if (rFocused) NetWhite else Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                                .clickable(onClick = onRefreshLibrary)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text("↻ Refresh", color = NetWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                items(ordered, key = { "${it.torrentId}_${it.fileId}" }) { f ->
                    val ep = episodeOf(f.name)
                    val tag = buildString {
                        if (ep != null) append(epTag(ep.first, ep.second))
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
private fun PlayerScreen(req: PlayReq, torboxKey: String, onProgress: (Long, Long) -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    var url by remember(req) { mutableStateOf(req.url) }
    var error by remember(req) { mutableStateOf("") }
    var buffering by remember(req) { mutableStateOf(true) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    BackHandler {
        val pv = playerView
        if (pv != null && pv.isControllerVisible) pv.hideController() else onExit()
    }

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
            setWakeMode(C.WAKE_MODE_NETWORK)
            setSeekBackIncrementMs(10_000)
            setSeekForwardIncrementMs(10_000)
            val item = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(req.name)
                        .apply { if (req.image.isNotEmpty()) setArtworkUri(req.image.toUri()) }
                        .build()
                )
                .build()
            setMediaItem(item, req.start.coerceAtLeast(0L))
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
        var wasPlaying = true
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlaying = exo.playWhenReady
                    exo.pause()
                }
                Lifecycle.Event.ON_START -> exo.playWhenReady = wasPlaying
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
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShowSubtitleButton(true)
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    setKeepContentOnPlayerReset(true)
                    isFocusable = true
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    playerView = this
                    requestFocus()
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (buffering) CircularProgressIndicator(color = NetRed, modifier = Modifier.align(Alignment.Center))
        if (error.isNotEmpty()) {
            Text(error, color = NetWhite, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
    }
}
```
