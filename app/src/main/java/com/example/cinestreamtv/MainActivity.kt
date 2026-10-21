package com.example.cinestreamtv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusRestorer
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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

// ---------- App Meta ----------
private const val APP_VERSION = "1.6.0"
private const val DEMO_NOTE = "Demo titles. Add a TMDB API key in Settings to unlock the full catalog."
private const val CACHE_TTL = 6 * 60 * 60 * 1000L   // FIX 6: catalog cache lifetime

// ---------- Theme Colors ----------
private val NetBlack = Color(0xFF0B0B0B)
private val NetDarkGray = Color(0xFF1F1F1F)
private val NetRed = Color(0xFFE50914)
private val NetWhite = Color(0xFFFFFFFF)
private val NetMuted = Color(0xFF9A9A9A)
private val NetGold = Color(0xFFD4AF37)

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

// FIX 5: parsed library entry, built once and reused
private data class LibFile(
    val file: TbFile,
    val title: String,
    val ep: Pair<Int, Int>?,
    val quality: String
)

private val Resume.key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url
private val PlayReq.key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url

private fun poster(path: String) = "https://image.tmdb.org/t/p/w500$path"
private fun wide(path: String) = "https://image.tmdb.org/t/p/w1280$path"

private enum class Screen { Home, Shows, Movies, MyList, Search, Settings }

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

// ---------- Helpers ----------
private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

// FIX 5: regex hoisted out of the function body
private val NON_ALNUM = Regex("[^a-z0-9]+")
private fun norm(s: String) = s.lowercase().replace(NON_ALNUM, " ").trim()

private val EP = Regex("(?i)s(\\d{1,2})[ ._-]?e(\\d{1,3})|(?<![0-9])(\\d{1,2})x(\\d{1,3})")
private val QUALITY = Regex("(?i)(2160p|1440p|1080p|720p|480p|4k)")

private val RELEASE_JUNK = setOf(
    "1080p", "720p", "2160p", "4k", "480p", "web", "dl", "webrip", "webdl", "bluray", "brrip",
    "bdrip", "hdrip", "dvdrip", "x264", "x265", "h264", "h265", "hevc", "avc", "aac", "ac3",
    "dts", "ddp", "dd5", "truehd", "atmos", "hdr", "dv", "remux", "proper", "repack", "extended",
    "amzn", "nf", "dsnp", "atvp", "hmax", "max", "ita", "eng", "multi", "subs"
)

private fun episodeOf(name: String): Pair<Int, Int>? {
    val m = EP.find(name) ?: return null
    val s = m.groupValues[1].ifEmpty { m.groupValues[3] }.toIntOrNull() ?: return null
    val e = m.groupValues[2].ifEmpty { m.groupValues[4] }.toIntOrNull() ?: return null
    return s to e
}

// FIX: lowercase the quality tag instead of SHOUTING it
private fun qualityOf(name: String): String =
    QUALITY.find(name)?.value?.lowercase()?.replace("4k", "2160p") ?: ""

private fun fmtSize(b: Long): String {
    val gb = b / 1073741824.0
    return if (gb >= 1) "${Math.round(gb * 10) / 10.0} GB" else "${b / 1048576} MB"
}

private fun epTag(season: Int, number: Int) = "S%02dE%02d".format(season, number)

private fun progKey(filmId: String, season: Int, number: Int) = "$filmId|$season|$number"

// FIX 5: title key strips the episode marker and release tags, so "You" stops matching "Your Honor"
private fun titleKey(fileName: String): String =
    norm(fileName.substringBefore(EP).ifEmpty { fileName })
        .split(' ')
        .filterNot { it in RELEASE_JUNK }
        .joinToString(" ")

// FIX 5: whole-word containment instead of raw `contains`
private fun LibFile.matches(showKey: String): Boolean {
    if (showKey.isEmpty()) return false
    val t = " $title "
    return t.contains(" $showKey ") || t.startsWith("$showKey ")
}

private fun parseLibrary(list: List<TbFile>): List<LibFile> =
    list.map { LibFile(it, titleKey(it.name), episodeOf(it.name), qualityOf(it.name)) }

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
    (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        Film(o.str("id"), o.optInt("tmdbId"), o.str("name"), o.str("year"), o.str("kind"),
            o.str("poster"), o.str("backdrop"), o.str("overview"), o.str("rating"), o.str("provider"))
    }
} catch (e: Exception) { emptyList() }

private fun saveWatchlist(p: SharedPreferences, list: List<Film>) {
    val arr = JSONArray()
    list.forEach {
        arr.put(
            JSONObject().put("id", it.id).put("tmdbId", it.tmdbId).put("name", it.name).put("year", it.year)
                .put("kind", it.kind).put("poster", it.poster).put("backdrop", it.backdrop)
                .put("overview", it.overview).put("rating", it.
