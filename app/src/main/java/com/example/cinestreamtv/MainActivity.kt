package com.example.cinestreamtv
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

// ============================ Netflix Cinema Colors ============================

private val NetBlack = Color(0xFF141414)
private val NetDeepBlack = Color(0xFF000000)
private val NetDarkGray = Color(0xFF1F1F1F)
private val NetCardBg = Color(0xFF181818)
private val NetRed = Color(0xFFE50914)
private val NetWhite = Color(0xFFFFFFFF)
private val NetMuted = Color(0xFFA3A3A3)
private val JamieGold = Color(0xFFFFD166)
private val NetGreen = Color(0xFF46D369)

private val Screens = listOf("Home", "Series", "Movies", "My List", "Settings")

// Layout constants. Every card and every grid reads these, which is what keeps
// rows and catalog walls perfectly even.
private val POSTER_WIDTH = 154.dp
private val POSTER_HEIGHT = 230.dp
private val CARD_GAP = 16.dp
private val RANK_SLOT = 56.dp
private val HERO_HEIGHT = 600.dp
private const val HERO_ROTATE_MS = 9_000
/** Clears the floating nav bar so nothing hides underneath it. */
private val NAV_CLEARANCE = 96.dp
private const val CATALOG_COLUMNS = 6

// ============================ Models ============================

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
    /** Real service this streams on, from TMDB discover. Blank when unknown. */
    val provider: String = ""
)

/**
 * A streaming service. tmdbIds holds the TMDB watch-provider ids for the US
 * store; several services changed id, so a pipe-separated fallback is listed.
 */
data class StreamingProvider(
    val name: String,
    val tmdbIds: String,
    val color: Color,
    val ring: Color,
    /** Short mark shown in the badge before a logo resolves. */
    val mark: String
)

private val CATALOG_PROVIDERS = listOf(
    StreamingProvider("Netflix", "8", Color(0xFFE50914), Color(0x88E50914), "N"),
    StreamingProvider("Apple TV+", "350", Color(0xEE000000), Color(0x88FFFFFF), "tv"),
    StreamingProvider("Disney+", "337", Color(0xEE06122C), Color(0x8838BDF8), "D"),
    StreamingProvider("Max", "1899|384", Color(0xEE13072B), Color(0x88A855F7), "MAX"),
    StreamingProvider("Prime Video", "9|119", Color(0xEE00182E), Color(0x8800A8E1), "prime")
)

private val PROVIDER_BY_NAME = CATALOG_PROVIDERS.associateBy { it.name }

data class Resume(
    val name: String,
    val image: String,
    val url: String,
    val torrentId: Int,
    val fileId: Int,
    val positionMs: Long,
    val durationMs: Long
) {
    val key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url
}

data class TbFile(
    val torrentId: Int,
    val fileId: Int,
    val name: String,
    val size: Long,
    val resolution: String = "",
    val score: Int = 0
)

data class PlayRequest(
    val name: String,
    val image: String,
    val url: String,
    val torrentId: Int,
    val fileId: Int,
    val startPositionMs: Long,
    val kind: String = "Movie",
    val season: Int = 1,
    val episodeCode: String = "",
    /** Title used for the resume row, so an episode can sit under its series. */
    val displayTitle: String = ""
) {
    val key: String get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url
}

data class Episode(
    val code: String,
    val name: String,
    val still: String,
    val overview: String,
    val runtime: String,
    val episodeNumber: Int = 1,
    val seasonNumber: Int = 1
)

data class HomeUiState(
    val catalog: List<Film> = emptyList(),
    val resume: List<Resume> = emptyList(),
    val watchlist: List<Film> = emptyList(),
    val isLoadingCatalog: Boolean = false,
    val catalogError: String = "",
    /** Service name -> titles that genuinely stream there. */
    val providerShelves: Map<String, List<Film>> = emptyMap(),
    /** Service name -> logo url, from the TMDB provider store. */
    val providerLogos: Map<String, String> = emptyMap(),
    val isLoadingProviders: Boolean = false
)

data class SearchUiState(
    val query: String = "",
    val results: List<Film> = emptyList(),
    val isSearching: Boolean = false,
    val error: String = ""
)

data class SettingsUiState(
    val torboxKey: String = "",
    val tmdbKey: String = "",
    val torboxStatus: String = "",
    val directLink: String = ""
)

data class DetailUiState(
    val film: Film? = null,
    val matches: List<TbFile> = emptyList(),
    val message: String = "",
    val isLoading: Boolean = false,
    val resumePositionMs: Long = 0L,
    val resumeDurationMs: Long = 0L,
    val season: Int = 1,
    val seasonCount: Int = 1,
    val seasons: List<Int> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val episodesLoading: Boolean = false,
    val episodesError: String = "",
    val showEpisodes: Boolean = false
)

// ============================ String Utilities ============================

private fun JSONObject.safeString(key: String): String =
    if (isNull(key)) "" else optString(key, "").trim()

private fun normalizeTitle(value: String): String =
    value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

private fun remainingLabel(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0L) return "Continue watching"
    val minutes = ((durationMs - positionMs) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes <= 0L -> "Almost finished"
        minutes >= 60L -> "${minutes / 60L}h ${minutes % 60L}m left"
        else -> "$minutes min left"
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes / 1_073_741_824.0
    // Locale-free on purpose: String.format would print a comma in some locales.
    val rounded = (gb * 10.0).toInt() / 10.0
    return if (gb >= 1.0) "$rounded GB" else "${bytes / 1_048_576} MB"
}

/** Netflix renders a percentage, derived here from the real TMDB vote average. */
private fun matchPercent(rating: String): Int? {
    val value = rating.toDoubleOrNull() ?: return null
    return (value * 10).toInt().coerceIn(1, 99)
}

// ============================ Persistent Storage ============================

internal class AppStorage(private val prefs: SharedPreferences) {

    private fun <T> load(key: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val raw = prefs.getString(key, "[]") ?: "[]"
        val array = JSONArray(raw)
        List(array.length()) { parse(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(key: String, items: List<JSONObject>) {
        runCatching { prefs.edit().putString(key, JSONArray(items).toString()).apply() }
    }

    fun loadResume(): List<Resume> = load("cinestream_resume_v2") {
        Resume(
            name = it.safeString("name"),
            image = it.safeString("image"),
            url = it.safeString("url"),
            torrentId = it.optInt("torrentId", -1),
            fileId = it.optInt("fileId", -1),
            positionMs = it.optLong("positionMs", 0L),
            durationMs = it.optLong("durationMs", 0L)
        )
    }

    fun saveResume(items: List<Resume>) = save("cinestream_resume_v2", items.map {
        JSONObject()
            .put("name", it.name).put("image", it.image).put("url", it.url)
            .put("torrentId", it.torrentId).put("fileId", it.fileId)
            .put("positionMs", it.positionMs).put("durationMs", it.durationMs)
    })

    fun loadWatchlist(): List<Film> = load("cinestream_watchlist_v2") {
        Film(
            id = it.safeString("id"),
            tmdbId = it.optInt("tmdbId", 0),
            name = it.safeString("name"),
            year = it.safeString("year"),
            kind = it.safeString("kind"),
            poster = it.safeString("poster"),
            backdrop = it.safeString("backdrop"),
            overview = it.safeString("overview"),
            rating = it.safeString("rating"),
            provider = it.safeString("provider")
        )
    }

    fun saveWatchlist(items: List<Film>) = save("cinestream_watchlist_v2", items.map {
        JSONObject()
            .put("id", it.id).put("tmdbId", it.tmdbId).put("name", it.name)
            .put("year", it.year).put("kind", it.kind).put("poster", it.poster)
            .put("backdrop", it.backdrop).put("overview", it.overview)
            .put("rating", it.rating).put("provider", it.provider)
    })

    fun saveTorboxKey(v: String) = prefs.edit().putString("torbox_token", v.trim()).apply()
    fun saveTmdbKey(v: String) = prefs.edit().putString("tmdb_key", v.trim()).apply()
    fun loadTorboxKey(): String = prefs.getString("torbox_token", "").orEmpty()
    fun loadTmdbKey(): String = prefs.getString("tmdb_key", "").orEmpty()
}

// ============================ HTTP Client ============================

private object Http {
    /** detail is non-null on purpose: Throwable.message is String? and that leaked. */
    class HttpException(val statusCode: Int, val detail: String) : Exception(detail)

    fun get(url: String, bearerToken: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "MollyTV/2.0")
            if (!bearerToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw HttpException(status, readable(status, body))
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun readable(status: Int, body: String): String {
        val msg = runCatching {
            val json = JSONObject(body)
            json.optString("detail").ifBlank { json.optString("status_message") }
        }.getOrDefault("")
        return msg.ifBlank { "HTTP $status" }
    }
}

// ============================ TMDB Repository ============================

private object TmdbRepository {
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMAGE = "https://image.tmdb.org/t/p"

    private fun isBearer(key: String): Boolean =
        key.startsWith("eyJ") || key.split(".").size == 3

    private fun request(path: String, apiKey: String): String {
        val key = apiKey.trim()
        return if (isBearer(key)) {
            Http.get(BASE_URL + path, key)
        } else {
            val sep = if (path.contains("?")) "&" else "?"
            Http.get(BASE_URL + path + sep + "api_key=" + URLEncoder.encode(key, "UTF-8"))
        }
    }

    suspend fun loadTrending(apiKey: String): Result<List<Film>> = withContext(Dispatchers.IO) {
        runCatching {
            coroutineScope {
                (1..4).map { page ->
                    async { parseFilms(request("/trending/all/day?page=$page", apiKey)) }
                }.awaitAll()
            }.flatten().distinctBy { it.id }
        }
    }

    /**
     * Titles that genuinely stream on one service. This replaced a guess that
     * searched the synopsis for words like "boys" and "dune".
     */
    suspend fun loadProviderTitles(apiKey: String, provider: StreamingProvider): Result<List<Film>> =
        withContext(Dispatchers.IO) {
            runCatching {
                coroutineScope {
                    listOf("movie", "tv").map { kind ->
                        async {
                            parseFilms(
                                request(
                                    "/discover/$kind" +
                                        "?with_watch_providers=" + provider.tmdbIds +
                                        "&watch_region=US" +
                                        "&sort_by=popularity.desc" +
                                        "&include_adult=false" +
                                        "&vote_count.gte=25&page=1",
                                    apiKey
                                )
                            )
                        }
                    }.awaitAll()
                }.flatten()
                    .distinctBy { it.id }
                    .map { it.copy(provider = provider.name) }
                    .sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            }
        }

    /** Logo paths for the US provider store, purely cosmetic. */
    suspend fun loadProviderLogos(apiKey: String): Result<Map<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject(request("/watch/providers/movie?watch_region=US", apiKey))
                val results = payload.optJSONArray("results") ?: return@runCatching emptyMap()
                val logos = mutableMapOf<String, String>()
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val logo = item.safeString("logo_path")
                    if (logo.isNotBlank()) {
                        logos[item.optInt("provider_id", -1).toString()] = "$IMAGE/w154$logo"
                    }
                }
                logos
            }
        }

    suspend fun search(apiKey: String, query: String): Result<List<Film>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            parseFilms(request("/search/multi?query=$q&include_adult=false", apiKey))
        }
    }

    suspend fun loadSeasonCount(apiKey: String, film: Film): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            JSONObject(request("/tv/" + film.tmdbId, apiKey))
                .optInt("number_of_seasons", 1)
                .coerceAtLeast(1)
        }
    }

    suspend fun loadEpisodes(apiKey: String, film: Film, season: Int): Result<List<Episode>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject(request("/tv/" + film.tmdbId + "/season/" + season, apiKey))
                val results = payload.optJSONArray("episodes") ?: return@runCatching emptyList()

                (0 until results.length()).mapNotNull { index ->
                    val ep = results.optJSONObject(index) ?: return@mapNotNull null
                    val num = ep.optInt("episode_number", 0)
                    val still = ep.safeString("still_path")
                    val mins = ep.optInt("runtime", 0)

                    Episode(
                        code = "S" + season.toString().padStart(2, '0') + "E" + num.toString().padStart(2, '0'),
                        name = ep.safeString("name"),
                        still = if (still.isBlank()) "" else "$IMAGE/w300$still",
                        overview = ep.safeString("overview"),
                        runtime = if (mins > 0) "${mins}m" else "",
                        episodeNumber = num,
                        seasonNumber = season
                    )
                }
            }
        }

    private fun parseFilms(response: String): List<Film> {
        val results = JSONObject(response).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { results.optJSONObject(it)?.toFilm() }
    }

    private fun JSONObject.toFilm(): Film? {
        if (safeString("media_type") == "person") return null
        val isSeries = safeString("media_type") == "tv" || safeString("first_air_date").isNotBlank()
        val posterPath = safeString("poster_path")
        val backdropPath = safeString("backdrop_path")
        if (posterPath.isBlank() && backdropPath.isBlank()) return null
        val title = safeString("title").ifBlank { safeString("name") }
        if (title.isBlank()) return null
        val tmdbId = optInt("id", 0)
        val rating = optDouble("vote_average", 0.0)

        return Film(
            id = (if (isSeries) "tv" else "movie") + tmdbId,
            tmdbId = tmdbId,
            name = title,
            year = safeString("release_date").ifBlank { safeString("first_air_date") }.take(4),
            kind = if (isSeries) "Series" else "Movie",
            poster = if (posterPath.isNotBlank()) "$IMAGE/w500$posterPath" else "",
            backdrop = if (backdropPath.isNotBlank()) "$IMAGE/w1280$backdropPath" else "",
            overview = safeString("overview"),
            rating = if (rating > 0.0) String.format(java.util.Locale.US, "%.1f", rating) else ""
        )
    }
}

// ============================ TorBox Repository ============================

private object TorBoxRepository {
    private const val BASE_URL = "https://api.torbox.app/v1/api"
    private val videoExtensions = setOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".ts", ".m2ts")

    /** Compiled once. It used to be compiled for every file in the library. */
    private val EPISODE_PATTERN: Pattern = Pattern.compile("s(\\d+)e(\\d+)")

    suspend fun loadLibrary(apiKey: String): Result<List<TbFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey.trim()
            if (key.isBlank()) {
                throw Http.HttpException(401, "Add your TorBox API key in Settings.")
            }

            val files = mutableListOf<TbFile>()
            var offset = 0
            var more = true
            var pages = 0

            while (more && offset < 500 && pages < 5) {
                pages++
                val res = Http.get("$BASE_URL/torrents/mylist?limit=100&offset=$offset", key)
                val data = JSONObject(res).optJSONArray("data")

                if (data == null || data.length() == 0) {
                    more = false
                } else {
                    for (i in 0 until data.length()) {
                        val torrent = data.optJSONObject(i) ?: continue

                        // requestdl rejects torrents that are still downloading, so
                        // they are skipped rather than handed to the player.
                        val state = torrent.optString("download_state", "finished")
                        if (state.isNotBlank() && state != "finished" && state != "cached") continue

                        val tId = torrent.optInt("id", -1)
                        val tFiles = torrent.optJSONArray("files") ?: continue

                        for (j in 0 until tFiles.length()) {
                            val f = tFiles.optJSONObject(j) ?: continue
                            val fname = f.safeString("name")
                            val ext = "." + fname.substringAfterLast('.', "")
                            if (!videoExtensions.contains(ext.lowercase())) continue

                            val clean = fname.substringAfterLast('/')
                            val resolution = when {
                                clean.contains("2160p", true) || clean.contains("4k", true) -> "4K UHD"
                                clean.contains("1080p", true) -> "1080p FHD"
                                else -> "HD"
                            }
                            files.add(
                                TbFile(
                                    torrentId = tId,
                                    fileId = f.optInt("id", -1),
                                    name = clean,
                                    size = f.optLong("size", 0L),
                                    resolution = resolution
                                )
                            )
                        }
                    }
                    more = data.length() >= 100
                    offset += 100
                }
            }
            files
        }
    }

    suspend fun requestStreamLink(apiKey: String, torrentId: Int, fileId: Int): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = apiKey.trim()
                val url = "$BASE_URL/torrents/requestdl?token=" + URLEncoder.encode(key, "UTF-8") +
                        "&torrent_id=$torrentId&file_id=$fileId&zip_link=false"
                val res = JSONObject(Http.get(url))
                require(res.optBoolean("success", false)) {
                    res.safeString("detail").ifBlank { "TorBox stream request rejected." }
                }
                val link = res.safeString("data")
                require(link.isNotBlank()) { "TorBox returned empty stream link." }
                link
            }
        }

    suspend fun testConnection(apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            JSONObject(Http.get("$BASE_URL/user/me", apiKey.trim())).optBoolean("success", false)
        }
    }

    fun matchFiles(files: List<TbFile>, title: String, episodeCode: String = ""): List<TbFile> {
        val cleanWords = normalizeTitle(title).split(" ").filter { it.length > 1 }
        if (cleanWords.isEmpty()) return emptyList()

        return files.filter { file ->
            val hay = normalizeTitle(file.name)
            if (!cleanWords.all { hay.contains(it) }) return@filter false

            if (episodeCode.isBlank()) {
                true
            } else {
                val ep = episodeCode.lowercase()
                val matcher = EPISODE_PATTERN.matcher(ep)
                if (matcher.find()) {
                    val s = matcher.group(1)?.toIntOrNull() ?: 1
                    val e = matcher.group(2)?.toIntOrNull() ?: 1
                    listOf(
                        ep,
                        "s${s}e$e",
                        "s%02de%02d".format(s, e),
                        "${s}x$e"
                    ).any { hay.contains(it) }
                } else {
                    hay.contains(ep)
                }
            }
        }.sortedByDescending { it.size }
    }
}

// ============================ ViewModel ============================

internal class AppViewModel(private val store: AppStorage) : ViewModel() {
    private val _home = MutableStateFlow(
        HomeUiState(resume = store.loadResume(), watchlist = store.loadWatchlist())
    )
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _settings = MutableStateFlow(
        SettingsUiState(torboxKey = store.loadTorboxKey(), tmdbKey = store.loadTmdbKey())
    )
    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

    private val _detail = MutableStateFlow(DetailUiState())
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    private val _playRequest = MutableStateFlow<PlayRequest?>(null)
    val playRequest: StateFlow<PlayRequest?> = _playRequest.asStateFlow()

    private val searchQueryFlow = MutableStateFlow("")
    private var episodesJob: Job? = null
    private var catalogJob: Job? = null
    private var providerJob: Job? = null

    /** Session cache so the next-episode countdown does not re-fetch the library. */
    private var libraryCache: List<TbFile> = emptyList()
    private var libraryFetched = false

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            searchQueryFlow
                .debounce(350)
                .distinctUntilChanged()
                .collectLatest { query -> performSearch(query) }
        }
    }

    fun loadCatalog(apiKey: String) {
        val key = apiKey.trim()
        catalogJob?.cancel()
        if (key.isBlank()) return

        _home.update { it.copy(isLoadingCatalog = true, catalogError = "") }
        catalogJob = viewModelScope.launch {
            TmdbRepository.loadTrending(key)
                .onSuccess { films ->
                    _home.update {
                        it.copy(
                            catalog = films,
                            isLoadingCatalog = false,
                            catalogError = if (films.isEmpty()) "TMDB returned no titles." else ""
                        )
                    }
                    loadProviderShelves(key)
                }
                .onFailure { err ->
                    _home.update {
                        it.copy(
                            isLoadingCatalog = false,
                            catalogError = err.message ?: "Could not load the catalog."
                        )
                    }
                }
        }
    }

    /** One discover query per service, all in parallel, each shelf independent. */
    private fun loadProviderShelves(apiKey: String) {
        providerJob?.cancel()
        _home.update { it.copy(isLoadingProviders = true) }

        providerJob = viewModelScope.launch {
            TmdbRepository.loadProviderLogos(apiKey).onSuccess { logos ->
                _home.update { it.copy(providerLogos = logos) }
            }

            val shelves = coroutineScope {
                CATALOG_PROVIDERS.map { provider ->
                    async {
                        provider.name to TmdbRepository.loadProviderTitles(apiKey, provider)
                            .getOrDefault(emptyList())
                    }
                }.awaitAll()
            }.toMap()

            _home.update {
                it.copy(
                    providerShelves = shelves.filterValues { titles -> titles.isNotEmpty() },
                    isLoadingProviders = false
                )
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _search.update { it.copy(query = query) }
        searchQueryFlow.value = query
    }

    private suspend fun performSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _search.update { it.copy(results = emptyList(), isSearching = false, error = "") }
            return
        }
        val tmdbKey = _settings.value.tmdbKey
        if (tmdbKey.isBlank()) {
            val local = _home.value.catalog.filter { it.name.contains(trimmed, ignoreCase = true) }
            _search.update {
                it.copy(
                    results = local,
                    isSearching = false,
                    error = if (local.isEmpty()) "Add a TMDB key in Settings to search every title." else ""
                )
            }
            return
        }
        _search.update { it.copy(isSearching = true, error = "") }
        TmdbRepository.search(tmdbKey, trimmed)
            .onSuccess { list ->
                _search.update {
                    it.copy(
                        results = list,
                        isSearching = false,
                        error = if (list.isEmpty()) "No results found." else ""
                    )
                }
            }
            .onFailure { err ->
                _search.update { it.copy(isSearching = false, error = err.message ?: "Search failed.") }
            }
    }

    fun openDetails(film: Film) {
        val needle = normalizeTitle(film.name)
        val resumeItem = _home.value.resume.firstOrNull { normalizeTitle(it.name) == needle }

        _detail.value = DetailUiState(
            film = film,
            resumePositionMs = resumeItem?.positionMs ?: 0L,
            resumeDurationMs = resumeItem?.durationMs ?: 0L,
            seasons = if (film.kind == "Series") (1..10).toList() else emptyList()
        )

        episodesJob?.cancel()
        val apiKey = _settings.value.tmdbKey
        if (film.kind == "Series" && apiKey.isNotBlank()) {
            episodesJob = viewModelScope.launch {
                TmdbRepository.loadSeasonCount(apiKey, film)
                    .onSuccess { count ->
                        _detail.update { it.copy(seasonCount = count, seasons = (1..count).toList()) }
                    }
                fetchEpisodes(apiKey, film, 1)
            }
        }
    }

    fun setSeason(season: Int) {
        val film = _detail.value.film ?: return
        val apiKey = _settings.value.tmdbKey
        _detail.update { it.copy(season = season, episodes = emptyList(), episodesError = "") }
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch { fetchEpisodes(apiKey, film, season) }
    }

    /** The Episodes pill. Netflix keeps the list hidden until you ask for it. */
    fun toggleEpisodes() {
        val film = _detail.value.film ?: return
        val showing = !_detail.value.showEpisodes
        _detail.update { it.copy(showEpisodes = showing) }

        if (showing && _detail.value.episodes.isEmpty() && !_detail.value.episodesLoading) {
            val apiKey = _settings.value.tmdbKey
            if (apiKey.isBlank()) {
                _detail.update {
                    it.copy(episodesError = "Add a TMDB key in Settings to load episode guides.")
                }
            } else {
                episodesJob?.cancel()
                episodesJob = viewModelScope.launch { fetchEpisodes(apiKey, film, _detail.value.season) }
            }
        }
    }

    private suspend fun fetchEpisodes(apiKey: String, film: Film, season: Int) {
        _detail.update { it.copy(episodesLoading = true, episodesError = "") }
        TmdbRepository.loadEpisodes(apiKey, film, season)
            .onSuccess { eps ->
                _detail.update {
                    if (it.season == season) it.copy(episodes = eps, episodesLoading = false) else it
                }
            }
            .onFailure { err ->
                _detail.update {
                    if (it.season == season) {
                        it.copy(
                            episodes = emptyList(),
                            episodesLoading = false,
                            episodesError = err.message ?: "Could not load this season."
                        )
                    } else {
                        it
                    }
                }
            }
    }

    fun closeDetails() {
        episodesJob?.cancel()
        _detail.value = DetailUiState()
    }

    fun toggleWatchlist(film: Film) {
        val list = _home.value.watchlist
        val exists = list.any { it.id == film.id }
        val updated = if (exists) list.filterNot { it.id == film.id } else listOf(film) + list
        store.saveWatchlist(updated)
        _home.update { it.copy(watchlist = updated) }
    }

    fun playFilm(startMs: Long) = lookup(autoPlay = true, startMs = startMs, code = "")
    fun findTorBoxMatches() = lookup(autoPlay = false, startMs = 0L, code = "")
    fun playEpisode(episode: Episode) = lookup(autoPlay = true, startMs = 0L, code = episode.code)

    private suspend fun fetchLibrary(): List<TbFile> {
        if (libraryFetched) return libraryCache
        val files = TorBoxRepository.loadLibrary(_settings.value.torboxKey).getOrThrow()
        libraryCache = files
        libraryFetched = true
        return files
    }

    private fun lookup(autoPlay: Boolean, startMs: Long, code: String) {
        val film = _detail.value.film ?: return

        if (_settings.value.torboxKey.isBlank()) {
            _detail.update {
                it.copy(isLoading = false, message = "Add your TorBox API key in Settings to play.")
            }
            return
        }

        viewModelScope.launch {
            _detail.update { it.copy(isLoading = true, matches = emptyList(), message = "") }
            runCatching { fetchLibrary() }.fold(
                onSuccess = { files ->
                    val matches = TorBoxRepository.matchFiles(files, film.name, code)
                    when {
                        autoPlay && matches.isNotEmpty() -> {
                            _detail.update { it.copy(isLoading = false) }
                            startPlayback(matches.first(), film, startMs, code)
                        }
                        matches.isEmpty() -> _detail.update {
                            it.copy(
                                isLoading = false,
                                message = if (code.isBlank()) {
                                    "This title is not in your TorBox library."
                                } else {
                                    "No file matched $code."
                                }
                            )
                        }
                        else -> _detail.update {
                            it.copy(isLoading = false, matches = matches, message = "Select a stream:")
                        }
                    }
                },
                onFailure = { err ->
                    libraryFetched = false
                    _detail.update { it.copy(isLoading = false, message = err.message ?: "TorBox error") }
                }
            )
        }
    }

    fun playNextEpisode(currentCode: String) {
        val eps = _detail.value.episodes
        val idx = eps.indexOfFirst { it.code == currentCode }
        if (idx >= 0 && idx < eps.size - 1) {
            playEpisode(eps[idx + 1])
        }
    }

    fun playTorBoxFile(file: TbFile, startMs: Long) =
        startPlayback(file, _detail.value.film, startMs, "")

    private fun startPlayback(file: TbFile, film: Film?, startMs: Long, epCode: String) {
        _playRequest.value = PlayRequest(
            name = file.name,
            displayTitle = if (epCode.isNotBlank() && film != null) {
                film.name + " " + epCode
            } else {
                film?.name ?: file.name
            },
            image = film?.backdrop.orEmpty().ifBlank { film?.poster.orEmpty() },
            url = "",
            torrentId = file.torrentId,
            fileId = file.fileId,
            startPositionMs = startMs,
            kind = film?.kind ?: "Movie",
            episodeCode = epCode
        )
    }

    fun playDirectLink() {
        val link = _settings.value.directLink.trim()
        if (!link.startsWith("http://", true) && !link.startsWith("https://", true)) {
            _settings.update { it.copy(torboxStatus = "Enter a valid http or https URL.") }
            return
        }
        _playRequest.value = PlayRequest(
            name = link.substringAfterLast('/').substringBefore('?').ifBlank { "Stream" },
            displayTitle = link.substringAfterLast('/').substringBefore('?').ifBlank { "Stream" },
            image = "",
            url = link,
            torrentId = -1,
            fileId = -1,
            startPositionMs = 0L
        )
    }

    fun closePlayer() {
        _playRequest.value = null
    }

    fun saveProgress(req: PlayRequest, positionMs: Long, durationMs: Long) {
        if (positionMs < 3000L || durationMs <= 0L) return
        val finished = positionMs > durationMs * 0.95
        val list = _home.value.resume.filterNot { it.key == req.key }
        val updated = if (finished) {
            list
        } else {
            (
                listOf(
                    Resume(
                        name = req.displayTitle.ifBlank { req.name },
                        image = req.image,
                        url = req.url,
                        torrentId = req.torrentId,
                        fileId = req.fileId,
                        positionMs = positionMs,
                        durationMs = durationMs
                    )
                ) + list
                ).take(12)
        }
        store.saveResume(updated)
        _home.update { it.copy(resume = updated) }
    }

    fun resumeFromHome(res: Resume) {
        _playRequest.value = PlayRequest(
            name = res.name,
            displayTitle = res.name,
            image = res.image,
            url = res.url,
            torrentId = res.torrentId,
            fileId = res.fileId,
            startPositionMs = res.positionMs
        )
    }

    fun onTorboxKeyChange(k: String) {
        _settings.update { it.copy(torboxKey = k.trim(), torboxStatus = "") }
        store.saveTorboxKey(k)
        libraryFetched = false
    }

    fun onTmdbKeyChange(k: String) {
        _settings.update { it.copy(tmdbKey = k.trim()) }
        store.saveTmdbKey(k)
    }

    fun onDirectLinkChange(v: String) {
        _settings.update { it.copy(directLink = v) }
    }

    fun testTorboxConnection() {
        val k = _settings.value.torboxKey
        if (k.isBlank()) {
            _settings.update { it.copy(torboxStatus = "Enter your TorBox key first.") }
            return
        }
        _settings.update { it.copy(torboxStatus = "Testing connection...") }
        viewModelScope.launch {
            TorBoxRepository.testConnection(k)
                .onSuccess { ok ->
                    _settings.update {
                        it.copy(torboxStatus = if (ok) "Connected to TorBox." else "Key rejected.")
                    }
                }
                .onFailure { err ->
                    _settings.update { it.copy(torboxStatus = err.message ?: "Connection failed.") }
                }
        }
    }
}

// ============================ Main Activity ============================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // statusBarColor is deprecated on API 35 and clips edge-to-edge content.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val storage = remember {
                AppStorage(context.getSharedPreferences("cinestream_v2", Context.MODE_PRIVATE))
            }
            // remember { } avoids the lifecycle-viewmodel-compose dependency, which the
            // original build did not have on its classpath.
            val viewModel = remember { AppViewModel(storage) }
            val settings by viewModel.settings.collectAsState()

            // ONE debounce, in one place. It used to fire four trending requests per
            // keystroke while the key was being typed.
            LaunchedEffect(Unit) {
                var previous = settings.tmdbKey.trim()
                viewModel.loadCatalog(previous)
                snapshotFlow { settings.tmdbKey }
                    .collectLatest { current ->
                        val trimmed = current.trim()
                        if (trimmed == previous) return@collectLatest
                        previous = trimmed
                        delay(600)
                        viewModel.loadCatalog(current)
                    }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = NetBlack,
                    surface = NetDarkGray,
                    primary = NetRed,
                    onBackground = NetWhite,
                    onSurface = NetWhite
                )
            ) {
                CineStreamTvApp(viewModel)
            }
        }
    }
}

// ============================ Core TV Application ============================

@Composable
private fun CineStreamTvApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    val home by viewModel.home.collectAsState()
    val search by viewModel.search.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val playRequest by viewModel.playRequest.collectAsState()

    var selectedScreen by remember { mutableStateOf("Home") }

    // The home list owns a scroll state so the nav bar can sit over the hero and
    // fade to solid the moment you move.
    val homeListState = rememberLazyListState()
    val navSolid = selectedScreen != "Home" ||
        homeListState.firstVisibleItemIndex > 0 ||
        homeListState.firstVisibleItemScrollOffset > 40

    BackHandler {
        when {
            playRequest != null -> viewModel.closePlayer()
            detail.film != null -> viewModel.closeDetails()
            selectedScreen != "Home" -> selectedScreen = "Home"
            else -> (context as? Activity)?.finish()
        }
    }

    Box(Modifier.fillMaxSize().background(NetBlack)) {
        val req = playRequest
        val film = detail.film

        when {
            req != null -> TvPlayerWithCountdown(
                request = req,
                torboxKey = settings.torboxKey,
                onProgress = { pos, dur -> viewModel.saveProgress(req, pos, dur) },
                onClose = viewModel::closePlayer,
                onNextEpisode = { viewModel.playNextEpisode(req.episodeCode) }
            )

            film != null -> DetailsScreen(
                state = detail,
                isInWatchlist = home.watchlist.any { it.id == film.id },
                onPlay = { startMs -> viewModel.playFilm(startMs) },
                onSelectSeason = { season -> viewModel.setSeason(season) },
                onToggleEpisodes = viewModel::toggleEpisodes,
                onPlayEpisode = { ep -> viewModel.playEpisode(ep) },
                onToggleWatchlist = { viewModel.toggleWatchlist(film) },
                onFindFiles = { viewModel.findTorBoxMatches() },
                onPickFile = { file, startMs -> viewModel.playTorBoxFile(file, startMs) },
                onClose = viewModel::closeDetails
            )

            else -> Box(Modifier.fillMaxSize()) {
                when (selectedScreen) {
                    "Home" -> NetflixHomeScreen(
                        state = home,
                        listState = homeListState,
                        onOpen = viewModel::openDetails,
                        onResume = viewModel::resumeFromHome,
                        onPlayHero = { filmToPlay ->
                            viewModel.openDetails(filmToPlay)
                            viewModel.playFilm(0L)
                        },
                        onToggleWatchlist = viewModel::toggleWatchlist
                    )

                    "Series" -> CatalogGridScreen(
                        title = "TV Series",
                        films = home.providerShelves.values.flatten().filter { it.kind == "Series" },
                        providerShelves = home.providerShelves,
                        providerLogos = home.providerLogos,
                        emptyMessage = "No series available.",
                        onOpen = viewModel::openDetails
                    )

                    "Movies" -> CatalogGridScreen(
                        title = "Movies",
                        films = home.providerShelves.values.flatten().filter { it.kind == "Movie" },
                        providerShelves = home.providerShelves,
                        providerLogos = home.providerLogos,
                        emptyMessage = "No movies available.",
                        onOpen = viewModel::openDetails
                    )

                    "My List" -> CatalogGridScreen(
                        title = "My List",
                        films = home.watchlist,
                        providerShelves = emptyMap(),
                        providerLogos = home.providerLogos,
                        emptyMessage = "Your list is empty. Open any title and press My List.",
                        onOpen = viewModel::openDetails
                    )

                    "Search" -> SearchScreen(
                        state = search,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onOpen = viewModel::openDetails
                    )

                    "Settings" -> SettingsScreen(
                        state = settings,
                        onTorboxKeyChange = viewModel::onTorboxKeyChange,
                        onTmdbKeyChange = viewModel::onTmdbKeyChange,
                        onDirectLinkChange = viewModel::onDirectLinkChange,
                        onTestTorbox = viewModel::testTorboxConnection,
                        onPlayDirectLink = viewModel::playDirectLink
                    )
                }

                // Floating above everything, exactly like the TV app.
                NetflixTopNavBar(
                    current = selectedScreen,
                    solid = navSolid,
                    onSelect = { selectedScreen = it },
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}

// ============================ Shared TV primitives ============================

@Composable
private fun Label(
    text: String,
    size: Int = 14,
    color: Color = NetWhite,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    tracking: Float = 0f,
    opacity: Float = 1f,
    lineHeight: Int = 0,
    fontFamily: FontFamily? = null
) {
    Text(
        text = text,
        modifier = modifier.graphicsLayer { alpha = opacity },
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        fontFamily = fontFamily,
        textAlign = align,
        letterSpacing = tracking.sp,
        lineHeight = if (lineHeight > 0) lineHeight.sp else (size * 1.4f).sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/** Small all-caps kicker, the way Netflix labels a badge. */
@Composable
private fun Kicker(text: String, color: Color = NetMuted, modifier: Modifier = Modifier) {
    Label(text, 10, color, FontWeight.Black, modifier, tracking = 1.6f)
}

/**
 * The one focus/click wrapper every pill, card and row goes through. A TV remote
 * needs to show where you are: scale up, lift, and draw a ring.
 */
@Composable
private fun TvFocusable(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    scale: Float = 1.06f,
    border: Boolean = true,
    enabled: Boolean = true,
    lift: Boolean = true,
    ringColor: Color = NetWhite,
    bg: (Boolean) -> Color = { NetDarkGray },
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val animatedScale by animateFloatAsState(
        targetValue = if (focused) scale else 1f,
        animationSpec = tween(180),
        label = "tv-scale"
    )
    val elevation by animateDpAsState(
        targetValue = if (focused && lift) 20.dp else 0.dp,
        animationSpec = tween(180),
        label = "tv-elevation"
    )
    val ring by animateColorAsState(
        targetValue = if (focused && border) ringColor else Color.Transparent,
        animationSpec = tween(120),
        label = "tv-ring"
    )

    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                cameraDistance = 12f * density
            }
            // clip before border, or the ring is drawn outside the clip
            .clip(shape)
            .then(if (focused && border) Modifier.border(3.dp, ring, shape) else Modifier)
            .shadow(elevation, shape, clip = false, spotColor = NetDeepBlack, ambientColor = NetDeepBlack)
            .background(bg(focused))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        content(this, focused)
    }
}

/**
 * A nav / filter pill. The active one is a solid white bubble, focused adds a
 * ring - the two states are deliberately distinct.
 */
@Composable
private fun TvPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: String = ""
) {
    TvFocusable(
        shape = RoundedCornerShape(50.dp),
        scale = 1.06f,
        lift = false,
        bg = { focused ->
            when {
                selected -> NetWhite
                focused -> Color(0xFF333333)
                else -> Color(0xFF222222)
            }
        },
        onClick = onClick
    ) { focused ->
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (leading.isNotBlank()) {
                Label(leading, 12, if (selected || focused) Color.Black else NetMuted, FontWeight.Bold)
            }
            Label(
                label,
                13,
                if (selected || focused) Color.Black else NetMuted,
                if (selected) FontWeight.Black else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

// ============================ Top Nav ============================

@Composable
private fun NetflixTopNavBar(
    current: String,
    solid: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrim by animateColorAsState(
        targetValue = if (solid) NetBlack else Color.Transparent,
        animationSpec = tween(260),
        label = "nav-scrim"
    )
    val hairline by animateColorAsState(
        targetValue = if (solid) Color(0x1FFFFFFF) else Color.Transparent,
        animationSpec = tween(260),
        label = "nav-hairline"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scrim)
            .border(1.dp, hairline)
            .padding(horizontal = 48.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // MollyTV wordmark: "Molly" in white, "TV" in red, tight together.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Molly", 28, NetWhite, FontWeight.Black, tracking = -1f)
            Label("TV", 28, NetRed, FontWeight.Black, tracking = -1f)
        }

        Spacer(Modifier.width(28.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Screens.forEach { screen ->
                TvPill(label = screen, selected = current == screen, onClick = { onSelect(screen) })
            }
        }

        Spacer(Modifier.weight(1f))

        // Dedicated search pill on the right, so the tabs never crowd.
        TvPill(
            label = "Search",
            leading = "\uD83D\uDD0D",
            selected = current == "Search",
            onClick = { onSelect("Search") }
        )
    }
}

// ============================ Home ============================

@Composable
private fun NetflixHomeScreen(
    state: HomeUiState,
    listState: LazyListState,
    onOpen: (Film) -> Unit,
    onResume: (Resume) -> Unit,
    onPlayHero: (Film) -> Unit,
    onToggleWatchlist: (Film) -> Unit
) {
    // Rows never reorder while the provider queries settle.
    val providerRows = remember(state.providerShelves) {
        CATALOG_PROVIDERS.map { it.name }.filter { state.providerShelves.containsKey(it) }
    }

    val heroPool = remember(state.catalog) { state.catalog.take(6) }
    var heroIndex by remember { mutableIntStateOf(0) }

    // Focusing a poster promotes it to the hero and resets the timer.
    val promoteHero: (Film) -> Unit = { film ->
        val next = heroPool.indexOfFirst { it.id == film.id }
        if (next >= 0) heroIndex = next
    }

    LaunchedEffect(heroPool, heroIndex) {
        if (heroPool.size > 1) {
            delay(HERO_ROTATE_MS.toLong())
            heroIndex = (heroIndex + 1) % heroPool.size
        }
    }

    when {
        state.isLoadingCatalog && state.catalog.isEmpty() -> LoadingState("Loading today's catalog...")

        state.catalog.isEmpty() -> MessageState(state.catalogError.ifBlank { "No titles available yet." })

        else -> {
            val hero = heroPool[heroIndex % heroPool.size]

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 56.dp)
            ) {
                item(key = "hero_billboard") {
                    HeroBanner(
                        film = hero,
                        listState = listState,
                        poolSize = heroPool.size,
                        activeIndex = heroIndex,
                        onPlay = { onPlayHero(hero) },
                        onOpen = { onOpen(hero) },
                        isInWatchlist = state.watchlist.any { it.id == hero.id },
                        onToggleWatchlist = { onToggleWatchlist(hero) }
                    )
                }

                if (state.resume.isNotEmpty()) {
                    item(key = "continue_watching") {
                        Column(Modifier.padding(vertical = 12.dp)) {
                            ShelfHeader("Continue Watching for Jamie", "Pick up where you stopped")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 48.dp),
                                horizontalArrangement = Arrangement.spacedBy(CARD_GAP)
                            ) {
                                items(state.resume, key = { it.key }) { res ->
                                    ResumeCard(res) { onResume(res) }
                                }
                            }
                        }
                    }
                }

                item(key = "top_10_shelf") {
                    Shelf(
                        title = "Top 10 in Movies & TV Today",
                        subtitle = "What everyone is watching",
                        films = state.catalog.take(10),
                        onOpen = onOpen,
                        onFocus = promoteHero,
                        ranked = true
                    )
                }

                item(key = "trending_shelf") {
                    Shelf(
                        title = "Trending Now",
                        subtitle = "Fresh on the charts",
                        films = state.catalog.drop(10).take(18),
                        onOpen = onOpen,
                        onFocus = promoteHero
                    )
                }

                items(providerRows, key = { it }) { name ->
                    ProviderShelf(
                        name = name,
                        logo = providerLogoUrl(name, state.providerLogos),
                        films = state.providerShelves[name].orEmpty().take(20),
                        isLoading = state.isLoadingProviders,
                        onOpen = onOpen,
                        onFocus = promoteHero
                    )
                }
            }
        }
    }
}

private fun providerLogoUrl(name: String, logos: Map<String, String>): String? {
    val ids = PROVIDER_BY_NAME[name]?.tmdbIds?.split("|") ?: emptyList()
    return logos.entries.firstOrNull { ids.contains(it.key) }?.value
}

// ============================ Hero Billboard ============================

@Composable
private fun HeroBanner(
    film: Film,
    listState: LazyListState,
    poolSize: Int,
    activeIndex: Int,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    isInWatchlist: Boolean,
    onToggleWatchlist: () -> Unit
) {
    val art = film.backdrop.ifBlank { film.poster }

    // Slow push-in. Subtle, but it stops a still hero looking like a photograph.
    val kenBurns = rememberInfiniteTransition(label = "ken-burns")
    val zoom by kenBurns.animateFloat(
        initialValue = 1.02f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(HERO_ROTATE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hero-zoom"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT)
    ) {
        if (art.isNotBlank()) {
            AsyncImage(
                model = art,
                contentDescription = film.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        if (listState.firstVisibleItemIndex == 0) {
                            val scrolled = listState.firstVisibleItemScrollOffset.toFloat()
                            translationY = scrolled * 0.45f
                            alpha = 1f - (scrolled / 1100f).coerceIn(0f, 0.55f)
                        }
                    },
                contentScale = ContentScale.Crop
            )
        }

        // Three scrims: bottom-up fade, left-to-right for legibility, top wash for the nav.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to NetDeepBlack.copy(alpha = 0.6f),
                        0.32f to Color.Transparent,
                        0.68f to Color.Transparent,
                        1f to NetBlack
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to NetBlack,
                        0.45f to NetBlack.copy(alpha = 0.86f),
                        0.78f to NetBlack.copy(alpha = 0.2f),
                        1f to Color.Transparent
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 48.dp)
                .width(680.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Label("N", 26, NetRed, FontWeight.Black)
                Label(
                    if (film.kind == "Series") "SERIES" else "FILM",
                    11, NetWhite, FontWeight.Black, tracking = 2f
                )
                Label("\u2022", 11, NetMuted)
                val match = matchPercent(film.rating)
                if (match != null) {
                    Label("$match% Match", 13, NetGreen, FontWeight.Bold)
                }
                if (film.provider.isNotBlank()) {
                    Box(
                        Modifier
                            .background(Color(0x66000000), RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Label(film.provider, 10, NetWhite, FontWeight.Bold)
                    }
                }
            }

            Label(film.name, 52, NetWhite, FontWeight.Black, tracking = -1.6f, maxLines = 2)

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (film.rating.isNotBlank()) {
                    Label("\u2605 " + film.rating, 13, NetGreen, FontWeight.Bold)
                }
                if (film.year.isNotBlank()) {
                    Label(film.year, 13, NetMuted, FontWeight.Medium)
                }
                Label(film.kind, 13, NetMuted)
            }

            Label(
                film.overview.ifBlank { "No synopsis is available for this title yet." },
                15, NetWhite.copy(alpha = 0.9f),
                maxLines = 3,
                lineHeight = 22
            )

            Spacer(Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pure white Netflix play button.
                TvFocusable(
                    modifier = Modifier.width(190.dp).height(52.dp),
                    shape = RoundedCornerShape(6.dp),
                    scale = 1.05f,
                    border = false,
                    bg = { focused -> if (focused) Color(0xFFE5E5E5) else NetWhite },
                    onClick = onPlay
                ) {
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Label("\u25B6", 15, Color.Black, FontWeight.Bold)
                        Label("Play", 16, Color.Black, FontWeight.Black)
                    }
                }

                TvFocusable(
                    modifier = Modifier.width(200.dp).height(52.dp),
                    shape = RoundedCornerShape(6.dp),
                    scale = 1.05f,
                    border = false,
                    bg = { focused -> if (focused) Color(0xFF8A8A8D) else Color(0xAA6D6D6E) },
                    onClick = onOpen
                ) {
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Label("\u2139", 15, NetWhite, FontWeight.Bold)
                        Label("More Info", 15, NetWhite, FontWeight.Bold)
                    }
                }

                TvFocusable(
                    modifier = Modifier.width(160.dp).height(52.dp),
                    shape = RoundedCornerShape(6.dp),
                    scale = 1.05f,
                    bg = { focused ->
                        when {
                            focused -> Color(0xFF4A4A56)
                            isInWatchlist -> Color(0x66FFFFFF)
                            else -> Color(0x55222222)
                        }
                    },
                    onClick = onToggleWatchlist
                ) {
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Label(
                            if (isInWatchlist) "\u2713" else "+",
                            15,
                            if (isInWatchlist) NetGreen else NetWhite,
                            FontWeight.Bold
                        )
                        Label(
                            if (isInWatchlist) "In My List" else "My List",
                            14, NetWhite, FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Rotation indicator: also tells you the hero is alive.
        if (poolSize > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(poolSize) { i ->
                    Box(
                        Modifier
                            .width(if (i == activeIndex % poolSize) 26.dp else 10.dp)
                            .height(3.dp)
                            .background(
                                if (i == activeIndex % poolSize) NetRed else Color(0x66FFFFFF),
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }
    }
}

// ============================ Rows ============================

@Composable
private fun ShelfHeader(title: String, subtitle: String = "") {
    Row(
        Modifier.padding(start = 48.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(24.dp)
                .background(NetRed, RoundedCornerShape(2.dp))
        )
        Column {
            Label(title, 21, NetWhite, FontWeight.Bold, tracking = -0.4f)
            if (subtitle.isNotBlank()) {
                Label(subtitle, 12, NetMuted, FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Shelf(
    title: String,
    films: List<Film>,
    onOpen: (Film) -> Unit,
    onFocus: (Film) -> Unit = {},
    ranked: Boolean = false,
    subtitle: String = ""
) {
    if (films.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        ShelfHeader(title, subtitle)

        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP)
        ) {
            itemsIndexed(films, key = { _, f -> f.id }) { index, film ->
                Row(verticalAlignment = Alignment.Bottom) {
                    if (ranked) {
                        // Fixed width. It used to change for two-digit numbers, which is
                        // exactly why the Top 10 row did not line up.
                        Box(
                            modifier = Modifier
                                .width(RANK_SLOT)
                                .height(POSTER_HEIGHT),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            Label(
                                (index + 1).toString(),
                                120,
                                Color(0xFF3A3A3A),
                                FontWeight.Black,
                                Modifier.offset(x = (-6).dp, y = (-4).dp),
                                maxLines = 1,
                                tracking = -8f
                            )
                        }
                    }
                    PosterCard(film = film, onClick = { onOpen(film) }, onFocus = { if (it) onFocus(film) })
                }
            }
        }
    }
}

/** A service row, with the real logo beside its name. */
@Composable
private fun ProviderShelf(
    name: String,
    logo: String?,
    films: List<Film>,
    isLoading: Boolean,
    onOpen: (Film) -> Unit,
    onFocus: (Film) -> Unit
) {
    val provider = PROVIDER_BY_NAME[name] ?: return

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(
            Modifier.padding(start = 48.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = name,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(provider.color, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Label(provider.mark, 14, NetWhite, FontWeight.Black)
                }
            }
            Label(name, 21, NetWhite, FontWeight.Bold, tracking = -0.4f)
            if (isLoading && films.isEmpty()) {
                Label("loading", 12, NetMuted, FontWeight.SemiBold)
            }
        }

        if (films.isEmpty()) {
            Row(
                Modifier.padding(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(CARD_GAP)
            ) {
                repeat(4) {
                    Box(
                        Modifier
                            .width(POSTER_WIDTH)
                            .height(POSTER_HEIGHT)
                            .clip(RoundedCornerShape(8.dp))
                            .background(NetCardBg)
                    )
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(CARD_GAP)
            ) {
                items(films, key = { it.id }) { film ->
                    PosterCard(
                        film = film,
                        onClick = { onOpen(film) },
                        onFocus = { if (it) onFocus(film) }
                    )
                }
            }
        }
    }
}

// ============================ Cards ============================

@Composable
private fun PosterCard(
    film: Film,
    onClick: () -> Unit,
    onFocus: (Boolean) -> Unit = {}
) {
    val art = film.poster.ifBlank { film.backdrop }
    val provider = if (film.provider.isNotBlank()) PROVIDER_BY_NAME[film.provider] else null
    val titleAlpha by animateFloatAsState(if (film.provider.isNotBlank()) 1f else 0f, label = "noop")

    TvFocusable(
        modifier = Modifier
            .width(POSTER_WIDTH)
            .height(POSTER_HEIGHT),
        shape = RoundedCornerShape(8.dp),
        scale = 1.06f,
        onFocus = onFocus,
        onClick = onClick
    ) { focused ->
        Box(Modifier.fillMaxSize().background(NetCardBg)) {
            if (art.isNotBlank()) {
                AsyncImage(
                    model = art,
                    contentDescription = film.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.5f to Color.Transparent,
                        1f to Color(0xEE000000)
                    )
                )
        )

        // Real service badge, top-left. Nothing is shown when we do not know.
        if (provider != null) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(provider.color, RoundedCornerShape(4.dp))
                    .border(1.dp, provider.ring, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Label(provider.mark, 9, NetWhite, FontWeight.Black, maxLines = 1)
            }
        }

        if (film.rating.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Label("\u2605 " + film.rating, 10, NetGreen, FontWeight.Bold)
            }
        }

        // Title reveals on focus, like the TV app.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Label(
                film.name, 11, NetWhite, FontWeight.Bold,
                maxLines = 1, opacity = if (focused) 1f else titleAlpha.coerceAtLeast(0.85f)
            )
            Label(
                film.year,
                9, NetMuted, FontWeight.SemiBold,
                maxLines = 1, opacity = if (focused) 1f else 0f, tracking = 0.3f
            )
        }
    }
}

@Composable
private fun ResumeCard(resume: Resume, onClick: () -> Unit) {
    val progress = if (resume.durationMs > 0L) {
        (resume.positionMs.toFloat() / resume.durationMs).coerceIn(0f, 1f)
    } else 0f

    TvFocusable(
        modifier = Modifier
            .width(300.dp)
            .height(170.dp),
        shape = RoundedCornerShape(8.dp),
        scale = 1.05f,
        onClick = onClick
    ) {
        if (resume.image.isNotBlank()) {
            AsyncImage(
                model = resume.image,
                contentDescription = resume.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color(0x14000000),
                        1f to Color(0xDD000000)
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Label(resume.name, 14, NetWhite, FontWeight.Bold, maxLines = 1)
            Label(remainingLabel(resume.positionMs, resume.durationMs), 11, NetMuted)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x44FFFFFF))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(NetRed)
                )
            }
        }

        Box(
            Modifier
                .align(Alignment.Center)
                .size(50.dp)
                .background(Color(0x99000000), CircleShape)
        ) {
            Label("\u25B6", 19, NetWhite, FontWeight.Bold, Modifier.align(Alignment.Center))
        }
    }
}

// ============================ Catalog Grid ============================

@Composable
private fun CatalogGridScreen(
    title: String,
    films: List<Film>,
    providerShelves: Map<String, List<Film>>,
    providerLogos: Map<String, String>,
    emptyMessage: String,
    onOpen: (Film) -> Unit
) {
    val tabs = remember(providerShelves) {
        listOf("All Studios") + CATALOG_PROVIDERS.map { it.name }
            .filter { providerShelves.containsKey(it) }
    }
    var selected by remember { mutableStateOf("All Studios") }
    val active = if (selected in tabs) selected else "All Studios"

    val visible = remember(films, providerShelves, active) {
        if (active == "All Studios") {
            films
        } else {
            (providerShelves[active] ?: emptyList())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(NetBlack)
            .padding(start = 48.dp, end = 48.dp, top = NAV_CLEARANCE)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Label(title, 28, NetWhite, FontWeight.Black, tracking = -1f)
            Label(
                "${visible.size} titles",
                13, NetMuted, FontWeight.SemiBold,
                Modifier.padding(bottom = 6.dp)
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            items(tabs, key = { it }) { name ->
                val logo = if (name == "All Studios") null else providerLogoUrl(name, providerLogos)
                Box {
                    TvPill(
                        label = when (name) {
                            "All Studios" -> "All Studios"
                            "Max" -> "Max / HBO"
                            else -> name
                        },
                        selected = active == name,
                        onClick = { selected = name }
                    )
                    if (logo != null) {
                        AsyncImage(
                            model = logo,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .size(16.dp)
                                .alpha(0f),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            if (visible.isEmpty()) {
                MessageState(if (films.isEmpty()) emptyMessage else "Nothing from $active yet.")
            } else {
                // Fixed column count so the wall is always even.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(CATALOG_COLUMNS),
                    horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(bottom = 48.dp)
                ) {
                    items(visible, key = { it.id }) { film ->
                        PosterCard(film = film, onClick = { onOpen(film) })
                    }
                }
            }
        }
    }
}

// ============================ Search ============================

@Composable
private fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpen: (Film) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(NetBlack)
            .padding(start = 48.dp, end = 48.dp, top = NAV_CLEARANCE)
    ) {
        Label("Search", 28, NetWhite, FontWeight.Black, tracking = -1f, modifier = Modifier.padding(bottom = 16.dp))

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text("Search movies, TV series, people...") },
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = NetWhite,
                unfocusedTextColor = NetWhite,
                focusedBorderColor = NetWhite,
                unfocusedBorderColor = Color(0x44FFFFFF),
                focusedContainerColor = NetDarkGray,
                unfocusedContainerColor = NetDarkGray
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        val suggestions = listOf("Dune", "Severance", "Oppenheimer", "The Boys", "Shogun", "Interstellar")
        if (state.query.isBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                suggestions.forEach { suggestion ->
                    TvPill(label = suggestion, selected = false, onClick = { onQueryChange(suggestion) })
                }
            }
        }

        when {
            state.isSearching -> LoadingState("Searching...")
            state.error.isNotBlank() -> MessageState(state.error)
            state.results.isEmpty() -> MessageState("Search movies and TV shows.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(CATALOG_COLUMNS),
                horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp)
            ) {
                items(state.results, key = { it.id }) { film ->
                    PosterCard(film = film, onClick = { onOpen(film) })
                }
            }
        }
    }
}

// ============================ States ============================

@Composable
private fun LoadingState(message: String = "Loading...") {
    val transition = rememberInfiniteTransition(label = "loading")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "loading-phase"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(color = NetRed, strokeWidth = 3.5.dp)
        Label(message, 13, NetMuted, FontWeight.Medium, opacity = 0.55f + 0.45f * phase)
    }
}

@Composable
private fun MessageState(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 56.dp),
        contentAlignment = Alignment.Center
    ) {
        Label(message, 16, Color(0xFFD2D2D9), align = TextAlign.Center)
    }
}

// ============================ Details ============================

@Composable
private fun DetailsScreen(
    state: DetailUiState,
    isInWatchlist: Boolean,
    onPlay: (Long) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onToggleEpisodes: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onToggleWatchlist: () -> Unit,
    onFindFiles: () -> Unit,
    onPickFile: (TbFile, Long) -> Unit,
    onClose: () -> Unit
) {
    val film = state.film ?: return
    val hasProgress = state.resumePositionMs > 5_000L && state.resumeDurationMs > 0L
    val progress = if (hasProgress) {
        (state.resumePositionMs.toFloat() / state.resumeDurationMs).coerceIn(0f, 1f)
    } else 0f

    Box(Modifier.fillMaxSize().background(NetBlack)) {
        Crossfade(targetState = film.backdrop.ifBlank { film.poster }, label = "detail-art") { art ->
            Box(Modifier.fillMaxSize().background(NetCardBg)) {
                if (art.isNotBlank()) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to NetBlack,
                        0.55f to NetBlack.copy(alpha = 0.94f),
                        1f to NetBlack.copy(alpha = 0.3f)
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
                        1f to NetBlack
                    )
                )
        )

        TvFocusable(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(28.dp)
                .size(48.dp),
            shape = CircleShape,
            scale = 1.1f,
            border = false,
            lift = false,
            bg = { if (it) NetWhite else Color(0x99101010) },
            onClick = onClose
        ) { focused ->
            Label(
                "\u2715", 16,
                if (focused) Color.Black else NetWhite,
                FontWeight.Bold,
                Modifier.align(Alignment.Center)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 48.dp, end = 48.dp, top = 88.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Label("N", 22, NetRed, FontWeight.Black)
                Label(
                    if (film.kind == "Series") "SERIES" else "FILM",
                    11, NetWhite, FontWeight.Black, tracking = 2f
                )
                if (film.provider.isNotBlank()) {
                    Box(
                        Modifier
                            .background(NetRed, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Kicker(film.provider, NetWhite)
                    }
                }
            }

            Label(film.name, 44, NetWhite, FontWeight.Black, tracking = -1.4f, maxLines = 2)

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val match = matchPercent(film.rating)
                if (match != null) Label("$match% Match", 14, NetGreen, FontWeight.Bold)
                if (film.rating.isNotBlank()) {
                    Label("\u2605 " + film.rating, 14, NetGreen, FontWeight.Bold)
                }
                if (film.year.isNotBlank()) Label(film.year, 14, NetWhite, FontWeight.SemiBold)
                Label(film.kind, 14, NetMuted)
            }

            Label(
                film.overview.ifBlank { "No synopsis is available for this title yet." },
                15, Color(0xFFE6E6EA),
                modifier = Modifier.width(620.dp),
                maxLines = 4,
                lineHeight = 23
            )

            Spacer(Modifier.height(4.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // -- Primary action, on its own line. --
                TvFocusable(
                    modifier = Modifier.width(260.dp).height(56.dp),
                    shape = RoundedCornerShape(6.dp),
                    scale = 1.04f,
                    border = false,
                    enabled = !state.isLoading,
                    bg = { focused -> if (focused) Color(0xFFE5E5E5) else NetWhite },
                    onClick = { onPlay(state.resumePositionMs) }
                ) {
                    if (progress > 0f) {
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(Color(0x33FFFFFF))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(progress)
                                    .fillMaxHeight()
                                    .background(NetRed)
                            )
                        }
                    }
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Label("\u25B6", 15, Color.Black, FontWeight.Bold)
                        Label(
                            when {
                                state.isLoading -> "Searching TorBox"
                                hasProgress -> "Resume"
                                else -> "Play"
                            },
                            17, Color.Black, FontWeight.Bold
                        )
                    }
                }

                // -- Episodes sits directly beneath Play, the way Netflix does it. --
                if (film.kind == "Series") {
                    TvFocusable(
                        modifier = Modifier.width(260.dp).height(50.dp),
                        shape = RoundedCornerShape(6.dp),
                        scale = 1.04f,
                        border = false,
                        bg = { focused -> if (focused) Color(0xFF4A4A56) else Color(0x99262630) },
                        onClick = onToggleEpisodes
                    ) {
                        Row(
                            Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Label(
                                if (state.showEpisodes) "\u2303" else "\u2304",
                                15,
                                if (state.showEpisodes) NetRed else NetWhite,
                                FontWeight.Bold
                            )
                            Label(
                                if (state.showEpisodes) "Hide Episodes" else "Episodes",
                                15, NetWhite, FontWeight.SemiBold
                            )
                        }
                    }
                }

                // -- Secondary actions, side by side on the last line. --
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvFocusable(
                        modifier = Modifier.width(230.dp).height(50.dp),
                        shape = RoundedCornerShape(6.dp),
                        scale = 1.04f,
                        border = false,
                        bg = { focused -> if (focused) Color(0xFF4A4A56) else Color(0x99262630) },
                        onClick = onFindFiles
                    ) {
                        Row(
                            Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Label("\uD83D\uDD0D", 14, NetWhite)
                            Label("Find in TorBox", 14, NetWhite, FontWeight.SemiBold)
                        }
                    }

                    TvFocusable(
                        modifier = Modifier.width(170.dp).height(50.dp),
                        shape = RoundedCornerShape(6.dp),
                        scale = 1.04f,
                        bg = { focused ->
                            when {
                                focused -> Color(0xFF4A4A56)
                                isInWatchlist -> Color(0x66FFFFFF)
                                else -> Color(0x55222222)
                            }
                        },
                        onClick = onToggleWatchlist
                    ) {
                        Row(
                            Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Label(
                                if (isInWatchlist) "\u2713" else "+",
                                15,
                                if (isInWatchlist) NetGreen else NetWhite,
                                FontWeight.Bold
                            )
                            Label(
                                if (isInWatchlist) "In My List" else "My List",
                                14, NetWhite, FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (state.message.isNotBlank()) {
                Label(state.message, 14, NetMuted)
            }

            // ---- Episodes ----
            if (film.kind == "Series" && state.showEpisodes) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Label("Episodes", 24, NetWhite, FontWeight.Bold)
                        Label("Season ${state.season}", 14, NetMuted, FontWeight.SemiBold)
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.seasons, key = { it }) { season ->
                            val selected = state.season == season
                            TvFocusable(
                                shape = RoundedCornerShape(6.dp),
                                scale = 1.06f,
                                border = false,
                                lift = false,
                                bg = { focused ->
                                    when {
                                        selected -> NetWhite
                                        focused -> Color(0xFF4A4A56)
                                        else -> Color(0x99262630)
                                    }
                                },
                                onClick = { onSelectSeason(season) }
                            ) { focused ->
                                Label(
                                    "S$season",
                                    14,
                                    if (selected || focused) Color.Black else NetWhite,
                                    FontWeight.Bold,
                                    Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }

                    when {
                        state.episodesLoading -> LoadingState("Loading episodes...")
                        state.episodesError.isNotBlank() -> Label(state.episodesError, 14, NetMuted)
                        state.episodes.isEmpty() -> Label(
                            "No episode information available for this season.",
                            14, NetMuted
                        )
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.episodes.forEachIndexed { index, ep ->
                                EpisodeRow(ep, index) { onPlayEpisode(ep) }
                            }
                        }
                    }
                }
            }

            // ---- TorBox streams ----
            if (state.matches.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("TorBox Streams", 24, NetWhite, FontWeight.Bold)
                    state.matches.forEach { file ->
                        TvFocusable(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            scale = 1.01f,
                            bg = { focused -> if (focused) Color(0xFF2E2E2E) else NetCardBg },
                            onClick = { onPickFile(file, state.resumePositionMs) }
                        ) { focused ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Label("\u25B6", 14, if (focused) NetRed else NetMuted)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Label(file.name, 14, NetWhite, FontWeight.Medium, maxLines = 1)
                                    Label(file.resolution, 11, NetMuted, FontWeight.SemiBold)
                                }
                                Label(formatFileSize(file.size), 13, NetMuted, FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** One Netflix-style episode row: still, ordinal, title, runtime, synopsis. */
@Composable
private fun EpisodeRow(episode: Episode, index: Int, onClick: () -> Unit) {
    TvFocusable(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        scale = 1.01f,
        bg = { focused -> if (focused) Color(0xFF2E2E2E) else NetCardBg },
        onClick = onClick
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(150.dp)
                    .height(86.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF222222))
            ) {
                if (episode.still.isNotBlank()) {
                    AsyncImage(
                        model = episode.still,
                        contentDescription = episode.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.5f to Color.Transparent,
                                1f to Color(0x99000000)
                            )
                        )
                )
                if (focused) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0x66000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Label("\u25B6", 22, NetWhite)
                    }
                }
                Kicker(
                    episode.code,
                    Color(0xFFE6E6EA),
                    Modifier.align(Alignment.BottomStart).padding(6.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label((index + 1).toString(), 15, NetMuted, FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Label(episode.name, 16, NetWhite, FontWeight.Bold, Modifier.weight(1f), maxLines = 1)
                    if (episode.runtime.isNotBlank()) {
                        Label(episode.runtime, 13, NetMuted, FontWeight.SemiBold)
                    }
                }
                if (episode.overview.isNotBlank()) {
                    Label(episode.overview, 13, NetMuted, maxLines = 2)
                }
            }
        }
    }
}

// ============================ Player with Next-Episode Countdown ============================

@Composable
private fun TvPlayerWithCountdown(
    request: PlayRequest,
    torboxKey: String,
    onProgress: (Long, Long) -> Unit,
    onClose: () -> Unit,
    onNextEpisode: () -> Unit
) {
    val context = LocalContext.current
    var streamUrl by remember(request) { mutableStateOf(request.url) }
    var streamError by remember(request) { mutableStateOf("") }
    var playbackError by remember(request) { mutableStateOf("") }
    var countdownActive by remember(request) { mutableStateOf(false) }
    var countdownSeconds by remember(request) { mutableIntStateOf(10) }
    var controlsVisible by remember(request) { mutableStateOf(true) }

    // Resolve a TorBox link, with a real error state instead of an infinite spinner.
    LaunchedEffect(request, torboxKey) {
        if (streamUrl.isBlank() && request.torrentId >= 0) {
            if (torboxKey.isBlank()) {
                streamError = "Add your TorBox API key in Settings to play."
                return@LaunchedEffect
            }
            TorBoxRepository.requestStreamLink(torboxKey, request.torrentId, request.fileId).fold(
                onSuccess = { streamUrl = it },
                onFailure = { streamError = it.message ?: "Could not get a stream link." }
            )
        }
    }

    // 10-second next-episode countdown.
    LaunchedEffect(countdownActive, countdownSeconds) {
        if (!countdownActive) return@LaunchedEffect
        if (countdownSeconds <= 0) {
            countdownActive = false
            onNextEpisode()
        } else {
            delay(1000L)
            countdownSeconds -= 1
        }
    }

    // Hide the controls after a few seconds of inactivity.
    LaunchedEffect(request, controlsVisible) {
        if (controlsVisible) {
            delay(4000)
            controlsVisible = false
        }
    }

    if (streamUrl.isBlank()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(NetDeepBlack),
            contentAlignment = Alignment.Center
        ) {
            when {
                streamError.isNotBlank() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Label("Could not start this stream", 22, NetWhite, FontWeight.Bold)
                    Label(streamError, 14, NetMuted, align = TextAlign.Center, modifier = Modifier.width(520.dp))
                    TvFocusable(
                        modifier = Modifier.width(180.dp).height(48.dp),
                        shape = RoundedCornerShape(6.dp),
                        bg = { NetWhite },
                        onClick = onClose
                    ) {
                        Label(
                            "Back to Browse", 15, Color.Black, FontWeight.Bold,
                            Modifier.align(Alignment.Center)
                        )
                    }
                }
                else -> CircularProgressIndicator(color = NetRed)
            }
        }
        return
    }

    // Explicit MIME type, so HLS and DASH pick the right renderers.
    val mimeType = when {
        streamUrl.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        streamUrl.contains(".mpd") -> MimeTypes.APPLICATION_MPD
        streamUrl.contains(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }

    val mediaItem = remember(streamUrl, mimeType) {
        val builder = MediaItem.Builder().setUri(streamUrl)
        if (mimeType != null) builder.setMimeType(mimeType)
        builder.build()
    }

    // Audio focus plus pause-on-unplug. Both of these are Builder options.
    val exoPlayer: ExoPlayer = remember(streamUrl) {
        val player: ExoPlayer = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.setMediaItem(mediaItem, request.startPositionMs)
        player.prepare()
        player.playWhenReady = true
        player
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && request.kind == "Series") {
                    countdownActive = true
                    countdownSeconds = 10
                    onProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.errorCodeName
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            onProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L))
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Checkpoint every 10 seconds so a crash or power cut never loses your place.
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(10_000)
            if (exoPlayer.isPlaying) {
                onProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L))
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NetDeepBlack)
            .clickable { controlsVisible = true }
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    player = exoPlayer
                    useController = true
                    isFocusable = true
                    keepScreenOn = true
                    // PlayerView owns this: it keeps the last frame on screen during a reset.
                    setKeepContentOnPlayerReset(true)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    requestFocus()
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        if (playbackError.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xDD000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Label("Playback error", 22, NetWhite, FontWeight.Bold)
                    Label(playbackError, 14, NetMuted)
                    Label(
                        "Browsers and some TV decoders cannot play MKV or DTS audio. MP4 and HLS are safe.",
                        13, NetMuted, align = TextAlign.Center, modifier = Modifier.width(480.dp)
                    )
                    TvFocusable(
                        modifier = Modifier.width(180.dp).height(48.dp),
                        shape = RoundedCornerShape(6.dp),
                        bg = { NetWhite },
                        onClick = onClose
                    ) {
                        Label(
                            "Back to Browse", 15, Color.Black, FontWeight.Bold,
                            Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }

        // Netflix-style next-episode overlay.
        if (countdownActive && playbackError.isBlank()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xEE141414)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Label("Next episode in", 20, NetMuted, FontWeight.Bold, tracking = 1f)
                    Label("$countdownSeconds", 72, NetWhite, FontWeight.Black)

                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TvFocusable(
                            modifier = Modifier.width(180.dp).height(52.dp),
                            shape = RoundedCornerShape(6.dp),
                            border = false,
                            bg = { NetWhite },
                            onClick = { countdownActive = false; onNextEpisode() }
                        ) {
                            Label(
                                "Play Now", 15, Color.Black, FontWeight.Bold,
                                Modifier.align(Alignment.Center)
                            )
                        }

                        TvFocusable(
                            modifier = Modifier.width(200.dp).height(52.dp),
                            shape = RoundedCornerShape(6.dp),
                            border = false,
                            bg = { if (it) Color(0xFF4A4A56) else Color(0x99262630) },
                            onClick = { countdownActive = false }
                        ) {
                            Label(
                                "Cancel Autoplay", 15, NetWhite, FontWeight.SemiBold,
                                Modifier.align(Alignment.Center)
                            )
                        }

                        TvFocusable(
                            modifier = Modifier.width(200.dp).height(52.dp),
                            shape = RoundedCornerShape(6.dp),
                            border = false,
                            bg = { if (it) Color(0xFF333333) else Color.Transparent },
                            onClick = onClose
                        ) {
                            Label(
                                "Back to Browse", 15, NetMuted, FontWeight.SemiBold,
                                Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================ Settings ============================

@Composable
private fun SettingsFieldDialog(
    title: String,
    icon: String,
    description: String,
    placeholder: String,
    initial: String,
    accent: Color,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var temp by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Label(icon, 20, NetWhite)
                Label(title, 18, NetWhite, FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label(description, 13, NetMuted)
                OutlinedTextField(
                    value = temp,
                    onValueChange = { temp = it },
                    singleLine = true,
                    placeholder = { Text(placeholder, color = NetMuted) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = NetWhite,
                        unfocusedTextColor = NetWhite,
                        focusedContainerColor = NetDarkGray,
                        unfocusedContainerColor = NetDarkGray,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Color(0x44FFFFFF)
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(temp); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = NetWhite, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save", fontWeight = FontWeight.Black)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (temp.isNotBlank()) {
                    TextButton(onClick = { onSave(""); onDismiss() }) {
                        Text("Clear", color = NetRed, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = NetMuted)
                }
            }
        },
        containerColor = Color(0xFF1E1E28),
        textContentColor = NetWhite
    )
}

@Composable
private fun SettingCard(
    icon: String,
    iconTint: Color,
    title: String,
    subtitle: String,
    status: String,
    statusGood: Boolean,
    maskedValue: String,
    actionLabel: String,
    onAction: () -> Unit,
    extra: @Composable () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF1B1B22),
        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = iconTint.copy(alpha = 0.16f)) {
                        Label(icon, 16, NetWhite, modifier = Modifier.padding(6.dp))
                    }
                    Column {
                        Label(title, 16, NetWhite, FontWeight.Bold)
                        Label(subtitle, 11, NetMuted)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (statusGood) NetGreen.copy(alpha = 0.16f) else Color(0x22FFFFFF)
                ) {
                    Label(
                        status,
                        10,
                        if (statusGood) NetGreen else NetMuted,
                        FontWeight.Bold,
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NetDarkGray,
                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Label(
                        maskedValue,
                        13,
                        if (statusGood) NetWhite else NetMuted,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    if (statusGood) {
                        Label("\u2713 Configured", 11, NetGreen, FontWeight.Bold)
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvFocusable(
                    modifier = Modifier.width(260.dp).height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    scale = 1.04f,
                    border = false,
                    bg = { if (it) Color(0xFFE5E5E5) else NetWhite },
                    onClick = onAction
                ) {
                    Label(
                        actionLabel, 13, Color.Black, FontWeight.Black,
                        Modifier.align(Alignment.Center), maxLines = 1
                    )
                }
                extra()
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: SettingsUiState,
    onTorboxKeyChange: (String) -> Unit,
    onTmdbKeyChange: (String) -> Unit,
    onDirectLinkChange: (String) -> Unit,
    onTestTorbox: () -> Unit,
    onPlayDirectLink: () -> Unit
) {
    var showTorboxDialog by remember { mutableStateOf(false) }
    var showTmdbDialog by remember { mutableStateOf(false) }
    var showDirectLinkDialog by remember { mutableStateOf(false) }

    if (showTorboxDialog) {
        SettingsFieldDialog(
            title = "TorBox Cloud Debrid Token",
            icon = "\u2601",
            description = "Paste your TorBox API token from torbox.app/settings to enable cloud playback.",
            placeholder = "Paste TorBox token",
            initial = state.torboxKey,
            accent = NetGreen,
            onDismiss = { showTorboxDialog = false },
            onSave = onTorboxKeyChange
        )
    }

    if (showTmdbDialog) {
        SettingsFieldDialog(
            title = "TMDB API Key or Bearer",
            icon = "\uD83D\uDD11",
            description = "Your TMDB v3 key or v4 read token, from themoviedb.org/settings/api.",
            placeholder = "Paste TMDB key",
            initial = state.tmdbKey,
            accent = Color(0xFF38BDF8),
            onDismiss = { showTmdbDialog = false },
            onSave = onTmdbKeyChange
        )
    }

    if (showDirectLinkDialog) {
        SettingsFieldDialog(
            title = "Direct Stream URL",
            icon = "\u25B6",
            description = "Any external http(s) video URL. HLS (.m3u8) and MP4 are safest.",
            placeholder = "https://example.com/stream.m3u8",
            initial = state.directLink,
            accent = Color(0xFFA855F7),
            onDismiss = { showDirectLinkDialog = false },
            onSave = onDirectLinkChange
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NetBlack)
            .verticalScroll(rememberScrollState())
            .padding(start = 48.dp, end = 48.dp, top = NAV_CLEARANCE, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Label("Settings", 28, NetWhite, FontWeight.Black, tracking = -1f)

        // ---- Profile card ----
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF1B1B22),
            border = BorderStroke(1.dp, Color(0x22FFFFFF)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(NetRed, Color(0xFFFF8C00)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Label("J", 26, NetWhite, FontWeight.Black)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Label("Jamie", 22, NetWhite, FontWeight.Black)
                            Surface(shape = RoundedCornerShape(6.dp), color = JamieGold.copy(alpha = 0.2f)) {
                                Label(
                                    "GOOGLE TV 4K", 9, JamieGold, FontWeight.Black,
                                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Label("TorBox Debrid \u2022 TMDB Live Catalog \u2022 Media3 ExoPlayer", 12, NetMuted)
                    }
                }
            }
        }

        // ---- TorBox ----
        SettingCard(
            icon = "\u2601",
            iconTint = NetGreen,
            title = "TorBox Cloud Debrid Token",
            subtitle = "Stream cached torrents instantly without downloading",
            status = if (state.torboxKey.isNotBlank()) "TOKEN SET" else "NOT SET",
            statusGood = state.torboxKey.isNotBlank(),
            maskedValue = if (state.torboxKey.isNotBlank()) {
                "tb_" + "\u2022".repeat(22)
            } else {
                "No token configured. Press the button below to enter one."
            },
            actionLabel = if (state.torboxKey.isNotBlank()) "\u270F  Edit Token" else "\u270F  Enter Token",
            onAction = { showTorboxDialog = true }
        ) {
            TvFocusable(
                modifier = Modifier.width(200.dp).height(46.dp),
                shape = RoundedCornerShape(10.dp),
                scale = 1.04f,
                border = false,
                bg = { if (it) Color(0xFF3A3A46) else Color(0xFF2A2A38) },
                onClick = onTestTorbox
            ) {
                Label(
                    "Test Connection", 13, NetWhite, FontWeight.Bold,
                    Modifier.align(Alignment.Center)
                )
            }
        }

        if (state.torboxStatus.isNotBlank()) {
            Label(
                state.torboxStatus,
                13,
                if (state.torboxStatus.contains("Connected", true)) NetGreen
                else if (state.torboxStatus.contains("Testing", true)) NetMuted
                else NetRed,
                FontWeight.Bold,
                Modifier.padding(start = 4.dp)
            )
        }

        // ---- TMDB ----
        SettingCard(
            icon = "\uD83D\uDD11",
            iconTint = Color(0xFF38BDF8),
            title = "TMDB API Key / Bearer",
            subtitle = "Posters, backdrops, episode guides and per-service shelves",
            status = if (state.tmdbKey.isNotBlank()) "KEY ACTIVE" else "NOT SET",
            statusGood = state.tmdbKey.isNotBlank(),
            maskedValue = if (state.tmdbKey.isNotBlank()) "\u2022".repeat(34) else "No key. Without one the catalog stays empty.",
            actionLabel = if (state.tmdbKey.isNotBlank()) "\u270F  Edit TMDB Key" else "\u270F  Enter TMDB Key",
            onAction = { showTmdbDialog = true }
        )

        // ---- Direct link ----
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF1B1B22),
            border = BorderStroke(1.dp, Color(0x22FFFFFF)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFA855F7).copy(alpha = 0.16f)) {
                        Label("\u25B6", 16, NetWhite, modifier = Modifier.padding(6.dp))
                    }
                    Column {
                        Label("Direct Stream Link", 16, NetWhite, FontWeight.Bold)
                        Label("Play any external video URL. HLS and MP4 play natively.", 11, NetMuted)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = NetDarkGray,
                    border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Label(
                        state.directLink.ifBlank { "No stream URL set" },
                        13,
                        if (state.directLink.isNotBlank()) NetWhite else NetMuted,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvFocusable(
                        modifier = Modifier.width(280.dp).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        scale = 1.04f,
                        border = false,
                        bg = { if (it) Color(0xFFE5E5E5) else NetWhite },
                        onClick = { showDirectLinkDialog = true }
                    ) {
                        Label(
                            "\u270F  Enter Stream URL", 13, Color.Black, FontWeight.Black,
                            Modifier.align(Alignment.Center)
                        )
                    }

                    TvFocusable(
                        modifier = Modifier.width(180.dp).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        scale = 1.04f,
                        border = false,
                        bg = { if (it) Color(0xFFB855F7) else Color(0xFFA855F7) },
                        onClick = onPlayDirectLink
                    ) {
                        Label(
                            "\u25B6  Play Stream", 13, NetWhite, FontWeight.Bold,
                            Modifier.align(Alignment.Center)
                        )
                    }
                }

                Label("One-tap test streams:", 11, NetMuted, FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Sintel (MP4)" to
                            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
                        "Tears of Steel" to
                            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
                        "HLS Stream" to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                    ).forEach { (label, url) ->
                        TvFocusable(
                            shape = RoundedCornerShape(8.dp),
                            scale = 1.05f,
                            border = false,
                            bg = { if (it) Color(0xFF3A3A46) else NetDarkGray },
                            onClick = {
                                onDirectLinkChange(url)
                                onPlayDirectLink()
                            }
                        ) {
                            Label(
                                label, 11, NetWhite, FontWeight.SemiBold,
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // ---- Signature ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Label("\u2764  Made by Jamie", 16, JamieGold, FontWeight.Black)
            Label("MollyTV \u2022 Google TV & Android TV Edition", 11, NetMuted)
        }
    }
}
