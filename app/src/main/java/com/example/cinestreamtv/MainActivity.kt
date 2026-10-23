package com.example.cinestreamtv
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
 
// ───────────────────────────── Theme ─────────────────────────────
 
private val NetBlack = Color(0xFF0B0B10)
private val NetDarkGray = Color(0xFF17171E)
private val NetRed = Color(0xFFE50914)
private val NetWhite = Color(0xFFFFFFFF)
private val NetMuted = Color(0xFF9A9AA6)
private val JamieGold = Color(0xFFFFD166)
private val NetGreen = Color(0xFF46D369)
 
private val Screens = listOf("Search", "Home", "Shows", "Movies", "My List", "Settings")
 
// ───────────────────────────── Models ─────────────────────────────
 
data class Film(
    val id: String,
    val tmdbId: Int,
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
    val positionMs: Long,
    val durationMs: Long
) {
    val key: String get() = if (torrentId >= 0) "tb" + torrentId + ":" + fileId else url
}
 
data class TbFile(val torrentId: Int, val fileId: Int, val name: String, val size: Long)
 
data class PlayRequest(
    val name: String,
    val image: String,
    val url: String,
    val torrentId: Int,
    val fileId: Int,
    val startPositionMs: Long
) {
    val key: String get() = if (torrentId >= 0) "tb" + torrentId + ":" + fileId else url
}
 
data class Episode(
    val code: String,
    val name: String,
    val still: String,
    val overview: String,
    val runtime: String
)
 
data class HomeUiState(
    val catalog: List<Film> = emptyList(),
    val resume: List<Resume> = emptyList(),
    val watchlist: List<Film> = emptyList(),
    val isLoadingCatalog: Boolean = false,
    val catalogError: String = ""
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
    val episodes: List<Episode> = emptyList()
)
 
// ───────────────────────────── Helpers ─────────────────────────────
 
private fun JSONObject.safeString(key: String): String =
    if (isNull(key)) "" else optString(key, "")
 
private fun normalizeTitle(value: String): String =
    value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
 
private fun remainingLabel(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0L) return "Continue watching"
    val minutes = ((durationMs - positionMs) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes <= 0L -> "Almost finished"
        minutes >= 60L -> (minutes / 60L).toString() + "h " + (minutes % 60L).toString() + "m left"
        else -> minutes.toString() + " min left"
    }
}
 
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1.0) {
        (Math.round(gb * 10.0) / 10.0).toString() + " GB"
    } else {
        (bytes / 1_048_576).toString() + " MB"
    }
}
 
private fun tokenOverlap(a: String, b: String): Double {
    val left = normalizeTitle(a).split(" ").filter { it.length > 2 }.toSet()
    val right = normalizeTitle(b).split(" ").filter { it.length > 2 }.toSet()
    if (left.isEmpty() || right.isEmpty()) return 0.0
    return left.intersect(right).size.toDouble() / minOf(left.size, right.size)
}
 
// ───────────────────────────── Storage ─────────────────────────────
 
internal class AppStorage(private val prefs: SharedPreferences) {
 
    private fun <T> load(key: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]") ?: "[]")
        List(array.length()) { parse(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())
 
    private fun save(key: String, items: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }
 
    fun loadResume(): List<Resume> = load("resume") {
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
 
    fun saveResume(items: List<Resume>) = save("resume", items.map {
        JSONObject()
            .put("name", it.name).put("image", it.image).put("url", it.url)
            .put("torrentId", it.torrentId).put("fileId", it.fileId)
            .put("positionMs", it.positionMs).put("durationMs", it.durationMs)
    })
 
    fun loadWatchlist(): List<Film> = load("watchlist") {
        Film(
            id = it.safeString("id"),
            tmdbId = it.optInt("tmdbId", 0),
            name = it.safeString("name"),
            year = it.safeString("year"),
            kind = it.safeString("kind"),
            poster = it.safeString("poster"),
            backdrop = it.safeString("backdrop"),
            overview = it.safeString("overview"),
            rating = it.safeString("rating")
        )
    }
 
    fun saveWatchlist(items: List<Film>) = save("watchlist", items.map {
        JSONObject()
            .put("id", it.id).put("tmdbId", it.tmdbId).put("name", it.name)
            .put("year", it.year).put("kind", it.kind).put("poster", it.poster)
            .put("backdrop", it.backdrop).put("overview", it.overview)
            .put("rating", it.rating)
    })
 
    fun saveTorboxKey(value: String) = prefs.edit().putString("torbox_token", value).apply()
    fun saveTmdbKey(value: String) = prefs.edit().putString("tmdb_key", value).apply()
    fun loadTorboxKey(): String = prefs.getString("torbox_token", "").orEmpty()
    fun loadTmdbKey(): String = prefs.getString("tmdb_key", "").orEmpty()
}
 
// ───────────────────────────── Network ─────────────────────────────
 
private object Http {
 
    class HttpException(val statusCode: Int, message: String) : Exception(message)
 
    fun get(url: String, bearerToken: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
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
        val message = runCatching {
            val json = JSONObject(body)
            json.optString("detail").ifBlank { json.optString("status_message") }
        }.getOrDefault("")
        return message.ifBlank { "HTTP $status" }
    }
}
 
// ───────────────────────────── TMDB ─────────────────────────────
 
private object TmdbRepository {
 
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMAGE = "https://image.tmdb.org/t/p"
 
    /** A v4 read-access token is a JWT; a v3 key is a short hex string. */
    private fun isBearer(key: String): Boolean =
        key.startsWith("eyJ") || key.split(".").size == 3
 
    private fun request(path: String, apiKey: String): String {
        val key = apiKey.trim()
        return if (isBearer(key)) {
            Http.get(BASE_URL + path, key)
        } else {
            val separator = if (path.contains("?")) "&" else "?"
            Http.get(BASE_URL + path + separator + "api_key=" + URLEncoder.encode(key, "UTF-8"))
        }
    }
 
    suspend fun loadTrending(apiKey: String): Result<List<Film>> = withContext(Dispatchers.IO) {
        runCatching {
            coroutineScope {
                (1..5).map { page ->
                    async { parseFilms(request("/trending/all/day?page=$page", apiKey)) }
                }.awaitAll()
            }.flatten().distinctBy { it.id }
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
                val results = payload.optJSONArray("episodes") ?: return@runCatching emptyList<Episode>()
 
                (0 until results.length()).mapNotNull { index ->
                    val episode = results.optJSONObject(index) ?: return@mapNotNull null
                    val number = episode.optInt("episode_number", 0)
                    val still = episode.safeString("still_path")
                    val minutes = episode.optInt("runtime", 0)
 
                    Episode(
                        code = "S" + season.toString().padStart(2, '0') + "E" + number.toString().padStart(2, '0'),
                        name = episode.safeString("name"),
                        still = if (still.isBlank()) "" else "$IMAGE/w300$still",
                        overview = episode.safeString("overview"),
                        runtime = if (minutes > 0) minutes.toString() + "m" else ""
                    )
                }
            }
        }
 
    private fun parseFilms(response: String): List<Film> {
        val results = JSONObject(response).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { results.optJSONObject(it)?.toFilm() }
    }
 
    private fun JSONObject.toFilm(): Film? {
        val mediaType = safeString("media_type")
        if (mediaType == "person") return null
 
        val isSeries = mediaType == "tv" || safeString("first_air_date").isNotBlank()
 
        val posterPath = safeString("poster_path")
        if (posterPath.isBlank()) return null
 
        val title = safeString("title").ifBlank { safeString("name") }
        if (title.isBlank()) return null
 
        val tmdbId = optInt("id", 0)
        val backdropPath = safeString("backdrop_path")
        val rating = optDouble("vote_average", 0.0)
 
        return Film(
            id = (if (isSeries) "tv" else "movie") + tmdbId,
            tmdbId = tmdbId,
            name = title,
            year = safeString("release_date").ifBlank { safeString("first_air_date") }.take(4),
            kind = if (isSeries) "Series" else "Movie",
            poster = "$IMAGE/w500$posterPath",
            backdrop = if (backdropPath.isBlank()) "" else "$IMAGE/w1280$backdropPath",
            overview = safeString("overview"),
            rating = if (rating > 0.0) (Math.round(rating * 10.0) / 10.0).toString() else ""
        )
    }
}
 
// ───────────────────────────── TorBox ─────────────────────────────
 
private object TorBoxRepository {
 
    private const val BASE_URL = "https://api.torbox.app/v1/api"
    private val videoExtensions =
        listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".ts", ".m2ts")
 
    /** Loads every page of the library (not just the first 100 torrents). */
    suspend fun loadLibrary(apiKey: String): Result<List<TbFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val torrents = mutableListOf<JSONObject>()
            var offset = 0
            var more = true
 
            while (more && offset < 1_000) {
                val payload = JSONObject(
                    Http.get("$BASE_URL/torrents/mylist?limit=100&offset=$offset", apiKey.trim())
                )
                val page = payload.optJSONArray("data")
                if (page == null || page.length() == 0) {
                    more = false
                } else {
                    for (i in 0 until page.length()) {
                        page.optJSONObject(i)?.let { torrents.add(it) }
                    }
                    more = page.length() >= 100
                    offset += 100
                }
            }
 
            val files = mutableListOf<TbFile>()
            for (torrent in torrents) {
                val torrentId = torrent.optInt("id", -1)
                val torrentFiles = torrent.optJSONArray("files") ?: continue
 
                for (j in 0 until torrentFiles.length()) {
                    val file = torrentFiles.optJSONObject(j) ?: continue
                    val name = file.safeString("name")
 
                    if (videoExtensions.any { name.lowercase().endsWith(it) }) {
                        files.add(
                            TbFile(
                                torrentId = torrentId,
                                fileId = file.optInt("id", -1),
                                name = name.substringAfterLast('/'),
                                size = file.optLong("size", 0L)
                            )
                        )
                    }
                }
            }
            files.toList()
        }
    }
 
    suspend fun requestStreamLink(apiKey: String, torrentId: Int, fileId: Int): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$BASE_URL/torrents/requestdl?token=" + URLEncoder.encode(apiKey.trim(), "UTF-8") +
                    "&torrent_id=$torrentId&file_id=$fileId&zip_link=false"
                val response = JSONObject(Http.get(url))
 
                require(response.optBoolean("success", false)) {
                    response.safeString("detail").ifBlank { "TorBox did not return a stream link." }
                }
 
                val link = response.safeString("data")
                require(link.isNotBlank()) { "TorBox returned an empty stream link." }
                link
            }
        }
 
    suspend fun testConnection(apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            JSONObject(Http.get("$BASE_URL/user/me", apiKey.trim())).optBoolean("success", false)
        }
    }
 
    /** Every word of the title must appear in the file name. */
    fun matchFiles(files: List<TbFile>, title: String): List<TbFile> {
        val words = normalizeTitle(title).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
 
        return files.filter { file ->
            val haystack = normalizeTitle(file.name)
            words.all { haystack.contains(it) }
        }
    }
}
 
// ───────────────────────────── ViewModel ─────────────────────────────
 
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
 
    private var catalogJob: Job? = null
    private var searchJob: Job? = null
    private var episodesJob: Job? = null
 
    // ── Catalog (the debounce lives in CineStreamApp) ──
 
    fun loadCatalog(apiKey: String) {
        catalogJob?.cancel()
        val key = apiKey.trim()
 
        if (key.isBlank()) {
            _home.update {
                it.copy(
                    catalog = emptyList(),
                    isLoadingCatalog = false,
                    catalogError = "Add your TMDB API key in Settings to load the catalog."
                )
            }
            return
        }
 
        _home.update { it.copy(isLoadingCatalog = true, catalogError = "") }
 
        catalogJob = viewModelScope.launch {
            TmdbRepository.loadTrending(key)
                .onSuccess { films ->
                    _home.update {
                        it.copy(
                            catalog = films,
                            isLoadingCatalog = false,
                            catalogError = if (films.isEmpty()) "No titles were returned by TMDB." else ""
                        )
                    }
                }
                .onFailure { error ->
                    _home.update {
                        it.copy(
                            isLoadingCatalog = false,
                            catalogError = readableError(error, "Could not load the TMDB catalog.")
                        )
                    }
                }
        }
    }
 
    // ── Search ──
 
    fun onSearchQueryChange(value: String) {
        _search.update { it.copy(query = value, error = "") }
        searchJob?.cancel()
 
        if (value.isBlank()) {
            _search.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
 
        val tmdbKey = _settings.value.tmdbKey
 
        if (tmdbKey.isBlank()) {
            val local = _home.value.catalog.filter { it.name.contains(value, ignoreCase = true) }
            _search.update {
                it.copy(
                    results = local,
                    isSearching = false,
                    error = if (local.isEmpty()) "Add a TMDB API key in Settings to search all titles." else ""
                )
            }
            return
        }
 
        _search.update { it.copy(isSearching = true) }
 
        searchJob = viewModelScope.launch {
            delay(350)
            TmdbRepository.search(tmdbKey, value)
                .onSuccess { results ->
                    _search.update {
                        it.copy(
                            results = results,
                            isSearching = false,
                            error = if (results.isEmpty()) "No results found." else ""
                        )
                    }
                }
                .onFailure { error ->
                    _search.update {
                        it.copy(
                            results = emptyList(),
                            isSearching = false,
                            error = readableError(error, "Search failed. Check your connection or TMDB key.")
                        )
                    }
                }
        }
    }
 
    // ── Details, watchlist, episodes ──
 
    fun openDetails(film: Film) {
        val name = normalizeTitle(film.name)
        val resumeList = _home.value.resume
        val resumeItem = resumeList.firstOrNull { normalizeTitle(it.name) == name }
            ?: resumeList.firstOrNull { name.isNotBlank() && tokenOverlap(it.name, film.name) >= 0.7 }
 
        _detail.value = DetailUiState(
            film = film,
            resumePositionMs = resumeItem?.positionMs ?: 0L,
            resumeDurationMs = resumeItem?.durationMs ?: 0L
        )
 
        episodesJob?.cancel()
 
        val apiKey = _settings.value.tmdbKey
        if (film.kind == "Series" && apiKey.isNotBlank()) {
            episodesJob = viewModelScope.launch {
                TmdbRepository.loadSeasonCount(apiKey, film)
                    .onSuccess { count -> _detail.update { it.copy(seasonCount = count) } }
                fetchEpisodes(apiKey, film, 1)
            }
        }
    }
 
    fun setSeason(season: Int) {
        val film = _detail.value.film ?: return
        val apiKey = _settings.value.tmdbKey
        if (apiKey.isBlank()) return
 
        _detail.update { it.copy(season = season, episodes = emptyList(), matches = emptyList(), message = "") }
 
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch { fetchEpisodes(apiKey, film, season) }
    }
 
    private suspend fun fetchEpisodes(apiKey: String, film: Film, season: Int) {
        TmdbRepository.loadEpisodes(apiKey, film, season)
            .onSuccess { episodes ->
                _detail.update { if (it.season == season) it.copy(episodes = episodes) else it }
            }
            .onFailure {
                _detail.update { it.copy(episodes = emptyList()) }
            }
    }
 
    fun closeDetails() {
        episodesJob?.cancel()
        _detail.value = DetailUiState()
    }
 
    fun toggleWatchlist(film: Film) {
        val current = _home.value.watchlist
        val added = current.none { it.id == film.id }
        val updated = if (added) listOf(film) + current else current.filterNot { it.id == film.id }
 
        store.saveWatchlist(updated)
        _home.update { it.copy(watchlist = updated) }
        _detail.update { it.copy(message = if (added) "Added to My List." else "Removed from My List.") }
    }
 
    // ── TorBox lookup and playback ──
 
    fun findTorBoxMatches() = lookup(autoPlay = false, startMs = 0L, code = "")
 
    fun playFilm(startPositionMs: Long) = lookup(autoPlay = true, startMs = startPositionMs, code = "")
 
    fun playEpisode(episode: Episode) = lookup(autoPlay = true, startMs = 0L, code = episode.code)
 
    private fun lookup(autoPlay: Boolean, startMs: Long, code: String) {
        val film = _detail.value.film ?: return
 
        viewModelScope.launch {
            _detail.update {
                it.copy(isLoading = true, matches = emptyList(), message = "Searching your TorBox library...")
            }
 
            library().fold(
                onSuccess = { files ->
                    val found = TorBoxRepository.matchFiles(files, film.name)
                    val matches = if (code.isBlank()) {
                        found
                    } else {
                        found.filter { normalizeTitle(it.name).contains(code.lowercase()) }
                    }
 
                    when {
                        matches.isEmpty() -> _detail.update {
                            it.copy(isLoading = false, message = "This title was not found in your TorBox library.")
                        }
 
                        autoPlay && matches.size == 1 -> {
                            _detail.update { it.copy(isLoading = false, message = "") }
                            startTorBoxPlayback(matches.first(), film, startMs)
                        }
 
                        else -> _detail.update {
                            it.copy(
                                isLoading = false,
                                matches = matches,
                                message = "Choose a file below."
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _detail.update {
                        it.copy(
                            isLoading = false,
                            message = readableError(error, "Could not load your TorBox library.")
                        )
                    }
                }
            )
        }
    }
 
    fun playTorBoxFile(file: TbFile, startPositionMs: Long) =
        startTorBoxPlayback(file, _detail.value.film, startPositionMs)
 
    private fun startTorBoxPlayback(file: TbFile, film: Film?, startMs: Long) {
        _playRequest.value = PlayRequest(
            name = file.name,
            image = film?.backdrop.orEmpty().ifBlank { film?.poster.orEmpty() },
            url = "",
            torrentId = file.torrentId,
            fileId = file.fileId,
            startPositionMs = startMs
        )
    }
 
    fun playDirectLink() {
        val link = _settings.value.directLink.trim()
 
        if (!link.startsWith("http://", true) && !link.startsWith("https://", true)) {
            _settings.update { it.copy(torboxStatus = "Enter a valid http or https stream link.") }
            return
        }
 
        _playRequest.value = PlayRequest(
            name = link.substringAfterLast('/').substringBefore('?').ifBlank { "Stream" },
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
 
    fun saveProgress(request: PlayRequest, positionMs: Long, durationMs: Long) {
        if (positionMs < 3_000L) return
        if (durationMs <= 0L) return
 
        val finished = positionMs > durationMs * 0.95
        val rest = _home.value.resume.filterNot { it.key == request.key }
 
        val updated = if (finished) {
            rest
        } else {
            (
                listOf(
                    Resume(
                        name = request.name,
                        image = request.image,
                        url = request.url,
                        torrentId = request.torrentId,
                        fileId = request.fileId,
                        positionMs = positionMs,
                        durationMs = durationMs
                    )
                ) + rest
                ).take(10)
        }
 
        store.saveResume(updated)
        _home.update { it.copy(resume = updated) }
    }
 
    fun resumeFromHome(resume: Resume) {
        _playRequest.value = PlayRequest(
            name = resume.name,
            image = resume.image,
            url = resume.url,
            torrentId = resume.torrentId,
            fileId = resume.fileId,
            startPositionMs = resume.positionMs
        )
    }
 
    // ── Settings ──
 
    fun onTorboxKeyChange(value: String) {
        val trimmed = value.trim()
        _settings.update { it.copy(torboxKey = trimmed, torboxStatus = "") }
        store.saveTorboxKey(trimmed)
    }
 
    fun onTmdbKeyChange(value: String) {
        val trimmed = value.trim()
        _settings.update { it.copy(tmdbKey = trimmed) }
        store.saveTmdbKey(trimmed)
    }
 
    fun onDirectLinkChange(value: String) {
        _settings.update { it.copy(directLink = value) }
    }
 
    fun testTorboxConnection() {
        val apiKey = _settings.value.torboxKey
 
        if (apiKey.isBlank()) {
            _settings.update { it.copy(torboxStatus = "Enter your TorBox API key first.") }
            return
        }
 
        _settings.update { it.copy(torboxStatus = "Testing TorBox connection...") }
 
        viewModelScope.launch {
            TorBoxRepository.testConnection(apiKey)
                .onSuccess { ok ->
                    _settings.update {
                        it.copy(torboxStatus = if (ok) "Connected to TorBox." else "TorBox rejected this API key.")
                    }
                }
                .onFailure { error ->
                    _settings.update {
                        it.copy(torboxStatus = readableError(error, "Could not connect to TorBox."))
                    }
                }
        }
    }
 
    // ── Internals ──
 
    private suspend fun library(): Result<List<TbFile>> {
        val apiKey = _settings.value.torboxKey
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Add your TorBox API key in Settings."))
        }
        return TorBoxRepository.loadLibrary(apiKey)
    }
 
    private fun readableError(error: Throwable, fallback: String): String = when (error) {
        is Http.HttpException -> error.message ?: fallback
        is IllegalStateException -> error.message ?: fallback
        is IllegalArgumentException -> error.message ?: fallback
        else -> fallback
    }
}
 
// ───────────────────────────── Activity ─────────────────────────────
 
class MainActivity : ComponentActivity() {
 
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
 
        window.statusBarColor = android.graphics.Color.rgb(11, 11, 16)
        window.navigationBarColor = android.graphics.Color.rgb(11, 11, 16)
 
        setContent {
            val context = LocalContext.current
            val storage = remember {
                AppStorage(context.getSharedPreferences("cinestream", Context.MODE_PRIVATE))
            }
            val viewModel: AppViewModel = viewModel { AppViewModel(storage) }
 
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = NetBlack,
                    surface = NetDarkGray,
                    primary = NetRed,
                    onBackground = NetWhite,
                    onSurface = NetWhite
                )
            ) {
                CineStreamApp(viewModel)
            }
        }
    }
}
 
@Composable
private fun CineStreamApp(viewModel: AppViewModel) {
    val context = LocalContext.current
 
    val home by viewModel.home.collectAsState()
    val search by viewModel.search.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val playRequest by viewModel.playRequest.collectAsState()
 
    var selectedScreen by remember { mutableStateOf("Home") }
    var lastBackPressMs by remember { mutableStateOf(0L) }
 
    // The one and only debounce: loads right away on start, then waits
    // 600 ms after the last keystroke when the TMDB key changes.
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow { settings.tmdbKey }.collectLatest { key ->
            if (!first) delay(600)
            first = false
            viewModel.loadCatalog(key)
        }
    }
 
    BackHandler(enabled = true) {
        when {
            playRequest != null -> viewModel.closePlayer()
            detail.film != null -> viewModel.closeDetails()
            selectedScreen != "Home" -> selectedScreen = "Home"
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressMs < 2_000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressMs = now
                    Toast.makeText(context, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
 
    Box(
        Modifier
            .fillMaxSize()
            .background(NetBlack)
    ) {
        val activeRequest = playRequest
        val activeFilm = detail.film
 
        when {
            activeRequest != null -> PlayerScreen(
                request = activeRequest,
                torboxKey = settings.torboxKey,
                onProgress = { position, duration ->
                    viewModel.saveProgress(activeRequest, position, duration)
                },
                onClose = viewModel::closePlayer
            )
 
            activeFilm != null -> DetailsScreen(
                state = detail,
                isInWatchlist = home.watchlist.any { it.id == activeFilm.id },
                onPlay = viewModel::playFilm,
                onFindFiles = viewModel::findTorBoxMatches,
                onPickFile = viewModel::playTorBoxFile,
                onSelectSeason = viewModel::setSeason,
                onPlayEpisode = viewModel::playEpisode,
                onToggleWatchlist = { viewModel.toggleWatchlist(activeFilm) }
            )
 
            else -> Column(Modifier.fillMaxSize()) {
                TopNavigationBar(selectedScreen) { selectedScreen = it }
 
                when (selectedScreen) {
                    "Home" -> HomeScreen(home, viewModel::openDetails, viewModel::resumeFromHome)
 
                    "Shows" -> FilteredCatalogScreen(
                        title = "TV Shows",
                        films = home.catalog.filter { it.kind == "Series" },
                        onOpen = viewModel::openDetails
                    )
 
                    "Movies" -> FilteredCatalogScreen(
                        title = "Movies",
                        films = home.catalog.filter { it.kind == "Movie" },
                        onOpen = viewModel::openDetails
                    )
 
                    "My List" -> FilteredCatalogScreen(
                        title = "My Watchlist",
                        films = home.watchlist,
                        emptyMessage = "Your watchlist is empty.",
                        onOpen = viewModel::openDetails
                    )
 
                    "Search" -> SearchScreen(search, viewModel::onSearchQueryChange, viewModel::openDetails)
 
                    "Settings" -> SettingsScreen(
                        state = settings,
                        onTorboxKeyChange = viewModel::onTorboxKeyChange,
                        onTmdbKeyChange = viewModel::onTmdbKeyChange,
                        onDirectLinkChange = viewModel::onDirectLinkChange,
                        onTestTorbox = viewModel::testTorboxConnection,
                        onPlayDirectLink = viewModel::playDirectLink
                    )
                }
            }
        }
    }
}
 
// ───────────────────────────── Reusable UI ─────────────────────────────
 
@Composable
private fun Label(
    text: String,
    size: Int = 14,
    color: Color = NetWhite,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        textAlign = align,
        lineHeight = (size * 1.35f).sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
 
/** One focus/click wrapper for every card, pill and button (remote friendly). */
@Composable
private fun Focusable(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    scale: Float = 1.07f,
    border: Boolean = true,
    enabled: Boolean = true,
    bg: (Boolean) -> Color = { NetDarkGray },
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val animatedScale by animateFloatAsState(if (focused) scale else 1f, label = "focus-scale")
 
    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .scale(animatedScale)
            .clip(shape)
            .then(if (focused && border) Modifier.border(3.dp, NetWhite, shape) else Modifier)
            .background(bg(focused))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        content(this, focused)
    }
}
 
@Composable
private fun TopNavigationBar(current: String, onSelect: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(Color(0xFF1C1C24))
                .border(1.dp, Color(0xFF2E2E38), RoundedCornerShape(50.dp))
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Screens.forEach { screen ->
                val selected = current == screen
 
                Focusable(
                    shape = RoundedCornerShape(50.dp),
                    scale = 1.05f,
                    border = false,
                    bg = { focused -> if (selected || focused) NetWhite else Color.Transparent },
                    onClick = { onSelect(screen) }
                ) { focused ->
                    Label(
                        text = screen,
                        size = 14,
                        color = if (selected || focused) Color.Black else NetWhite,
                        weight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
 
@Composable
private fun PosterCard(film: Film, onFocus: (Boolean) -> Unit = {}, onClick: () -> Unit) {
    val art = film.poster.ifBlank { film.backdrop }
 
    Focusable(
        modifier = Modifier
            .width(150.dp)
            .height(225.dp),
        onFocus = onFocus,
        onClick = onClick
    ) {
        if (art.isNotBlank()) {
            AsyncImage(
                model = art,
                contentDescription = film.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
 
        if (film.rating.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Label("★ " + film.rating, 11, NetGreen, FontWeight.Bold)
            }
        }
    }
}
 
@Composable
private fun ResumeCard(resume: Resume, onClick: () -> Unit) {
    val progress = if (resume.durationMs > 0L) {
        (resume.positionMs.toFloat() / resume.durationMs).coerceIn(0f, 1f)
    } else 0f
 
    Focusable(
        modifier = Modifier
            .width(300.dp)
            .height(180.dp),
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
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0x55000000), Color(0xEE000000)))
                )
        )
 
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Label(resume.name, 14, NetWhite, FontWeight.Bold, maxLines = 1)
            Label(remainingLabel(resume.positionMs, resume.durationMs), 11, Color(0xFFDDDDDD), maxLines = 1)
 
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF55555F))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(NetRed)
                )
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
    ranked: Boolean = false
) {
    if (films.isEmpty()) return
 
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Label(title, 20, NetWhite, FontWeight.Bold, Modifier.padding(start = 48.dp, bottom = 10.dp))
 
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(films, key = { _, film -> film.id }) { index, film ->
                Row(verticalAlignment = Alignment.Bottom) {
                    if (ranked) {
                        Label(
                            text = (index + 1).toString(),
                            size = 80,
                            color = Color(0xFF5E5E6A),
                            weight = FontWeight.Black,
                            modifier = Modifier.padding(end = 6.dp),
                            maxLines = 1
                        )
                    }
 
                    PosterCard(
                        film = film,
                        onFocus = { focused -> if (focused) onFocus(film) },
                        onClick = { onOpen(film) }
                    )
                }
            }
        }
    }
}
 
@Composable
private fun PosterGrid(films: List<Film>, onOpen: (Film) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
        modifier = modifier
    ) {
        items(films, key = { it.id }) { film ->
            PosterCard(film = film, onClick = { onOpen(film) })
        }
    }
}
 
@Composable
private fun LoadingState(message: String = "Loading...") {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(color = NetRed)
        Label(message, 14, NetMuted)
    }
}
 
@Composable
private fun MessageState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Label(message, 16, NetWhite, align = TextAlign.Center)
    }
}
 
@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    progress: Float = 0f
) {
    Focusable(
        modifier = Modifier
            .width(320.dp)
            .height(52.dp),
        shape = RoundedCornerShape(8.dp),
        scale = 1.04f,
        border = false,
        enabled = enabled,
        bg = { focused ->
            when {
                focused -> NetWhite
                primary -> Color(0xFFE5E5E5)
                else -> Color(0xFF2A2A33)
            }
        },
        onClick = onClick
    ) { focused ->
        if (progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .background(NetRed)
            )
        }
 
        Label(
            text = label,
            size = 15,
            color = if (focused || primary) Color.Black else NetWhite,
            weight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 24.dp),
            maxLines = 1
        )
    }
}
 
@Composable
private fun EpisodeRow(episode: Episode, onClick: () -> Unit) {
    val title = if (episode.runtime.isBlank()) {
        episode.code + "  " + episode.name
    } else {
        episode.code + "  " + episode.name + "  -  " + episode.runtime
    }
 
    Focusable(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        scale = 1.02f,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(160.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF22222B))
            ) {
                if (episode.still.isNotBlank()) {
                    AsyncImage(
                        model = episode.still,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
 
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Label(title, 15, NetWhite, FontWeight.Bold, maxLines = 1)
                Label(episode.overview, 12, NetMuted, maxLines = 2)
            }
        }
    }
}
 
// ───────────────────────────── Screens ─────────────────────────────
 
@Composable
private fun HomeScreen(
    state: HomeUiState,
    onOpen: (Film) -> Unit,
    onResume: (Resume) -> Unit
) {
    when {
        state.isLoadingCatalog && state.catalog.isEmpty() -> LoadingState("Loading today's catalog...")
 
        state.catalog.isEmpty() -> MessageState(state.catalogError.ifBlank { "No titles available yet." })
 
        else -> {
            var heroId by remember { mutableStateOf(state.catalog.first().id) }
            val hero = state.catalog.firstOrNull { it.id == heroId } ?: state.catalog.first()
            val onFocus = { film: Film -> heroId = film.id }
 
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "hero") { HeroBanner(hero) { onOpen(hero) } }
 
                if (state.resume.isNotEmpty()) {
                    item(key = "continue") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                        ) {
                            Label(
                                "Continue Watching", 20, NetWhite, FontWeight.Bold,
                                Modifier.padding(start = 48.dp, bottom = 10.dp)
                            )
 
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 48.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(state.resume, key = { it.key }) { resume ->
                                    ResumeCard(resume) { onResume(resume) }
                                }
                            }
                        }
                    }
                }
 
                item(key = "trending") {
                    Shelf("Top 10 Trending Today", state.catalog.take(10), onOpen, onFocus, ranked = true)
                }
 
                item(key = "movies") {
                    Shelf("Trending Movies", state.catalog.filter { it.kind == "Movie" }, onOpen, onFocus)
                }
 
                item(key = "shows") {
                    Shelf("Trending TV Shows", state.catalog.filter { it.kind == "Series" }, onOpen, onFocus)
                }
            }
        }
    }
}
 
@Composable
private fun HeroBanner(film: Film, onOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(480.dp)
            .background(NetBlack)
            .clickable(onClick = onOpen)
    ) {
        Crossfade(targetState = film.backdrop.ifBlank { film.poster }, label = "hero-backdrop") { art ->
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
                .background(Brush.verticalGradient(listOf(Color.Transparent, NetBlack), startY = 250f))
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(listOf(NetBlack.copy(alpha = 0.92f), Color.Transparent), endX = 700f)
                )
        )
 
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 40.dp)
                .width(650.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Label("TRENDING TODAY", 11, NetMuted, FontWeight.Bold)
 
            Label(film.name, 44, NetWhite, FontWeight.Black, maxLines = 1)
 
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (film.rating.isNotBlank()) Label("★ " + film.rating, 13, NetGreen, FontWeight.Bold)
                Label(film.year, 13, NetMuted)
                Label("•", 13, NetMuted)
                Label(film.kind, 13, NetMuted)
            }
 
            Label(film.overview, 14, NetWhite.copy(alpha = 0.9f), maxLines = 2)
        }
    }
}
 
@Composable
private fun FilteredCatalogScreen(
    title: String,
    films: List<Film>,
    emptyMessage: String = "No titles available.",
    onOpen: (Film) -> Unit
) {
    if (films.isEmpty()) {
        MessageState(emptyMessage)
        return
    }
 
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 8.dp)
    ) {
        Label(title, 26, NetWhite, FontWeight.Bold, Modifier.padding(start = 8.dp))
        PosterGrid(films, onOpen, Modifier.fillMaxSize())
    }
}
 
@Composable
private fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpen: (Film) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 12.dp)
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text("Search titles") },
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = NetWhite,
                unfocusedTextColor = NetWhite,
                focusedBorderColor = NetWhite,
                unfocusedBorderColor = Color(0x33FFFFFF),
                focusedContainerColor = NetDarkGray,
                unfocusedContainerColor = NetDarkGray
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )
 
        when {
            state.isSearching -> LoadingState("Searching...")
            state.error.isNotBlank() -> MessageState(state.error)
            state.results.isEmpty() -> MessageState("Search for movies and TV shows.")
            else -> PosterGrid(state.results, onOpen, Modifier.fillMaxSize())
        }
    }
}
 
@Composable
private fun DetailsScreen(
    state: DetailUiState,
    isInWatchlist: Boolean,
    onPlay: (Long) -> Unit,
    onFindFiles: () -> Unit,
    onPickFile: (TbFile, Long) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onToggleWatchlist: () -> Unit
) {
    val film = state.film ?: return
 
    val hasProgress = state.resumePositionMs > 5_000L && state.resumeDurationMs > 0L
    val progress = if (hasProgress) {
        (state.resumePositionMs.toFloat() / state.resumeDurationMs).coerceIn(0f, 1f)
    } else 0f
 
    Box(
        Modifier
            .fillMaxSize()
            .background(NetBlack)
    ) {
        if (film.backdrop.isNotBlank()) {
            AsyncImage(
                model = film.backdrop,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
 
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(NetBlack, NetBlack.copy(alpha = 0.92f), NetBlack.copy(alpha = 0.35f))
                    )
                )
        )
 
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 56.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Label(film.name, 42, NetWhite, FontWeight.Black)
 
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (film.rating.isNotBlank()) Label("★ " + film.rating, 15, NetGreen, FontWeight.Bold)
                Label(film.year, 15, NetWhite, FontWeight.Medium)
                Label(film.kind, 15, NetMuted)
            }
 
            Label(film.overview, 15, NetWhite, modifier = Modifier.width(600.dp), maxLines = 4)
 
            Spacer(Modifier.height(4.dp))
 
            ActionButton(
                label = when {
                    state.isLoading -> "Loading..."
                    hasProgress -> "Resume - " + (state.resumePositionMs / 60_000L).toString() + " min in"
                    else -> "Play"
                },
                primary = true,
                enabled = !state.isLoading,
                progress = progress,
                onClick = { onPlay(state.resumePositionMs) }
            )
 
            ActionButton(label = "Find in TorBox", onClick = onFindFiles)
 
            ActionButton(
                label = if (isInWatchlist) "Added to My List" else "Add to My List",
                onClick = onToggleWatchlist
            )
 
            if (state.message.isNotBlank()) {
                Label(state.message, 14, NetMuted, modifier = Modifier.padding(top = 4.dp))
            }
 
            if (state.matches.isNotEmpty()) {
                Label("Select a file from TorBox", 18, NetWhite, FontWeight.Bold, Modifier.padding(top = 8.dp))
 
                state.matches.forEach { file ->
                    Focusable(
                        modifier = Modifier.fillMaxWidth(),
                        scale = 1.02f,
                        onClick = { onPickFile(file, if (film.kind == "Series") 0L else state.resumePositionMs) }
                    ) {
                        Label(
                            file.name + " (" + formatFileSize(file.size) + ")",
                            14,
                            NetWhite,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
 
            if (film.kind == "Series") {
                if (state.seasonCount > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.seasonCount) { index ->
                            val season = index + 1
                            val selected = state.season == season
 
                            Focusable(
                                shape = RoundedCornerShape(50.dp),
                                scale = 1.05f,
                                border = false,
                                bg = { focused -> if (selected || focused) NetWhite else Color(0xFF2A2A33) },
                                onClick = { onSelectSeason(season) }
                            ) { focused ->
                                Label(
                                    text = "Season " + season,
                                    size = 13,
                                    color = if (selected || focused) Color.Black else NetWhite,
                                    weight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
 
                state.episodes.forEach { episode ->
                    EpisodeRow(episode) { onPlayEpisode(episode) }
                }
            }
 
            Spacer(Modifier.height(32.dp))
        }
    }
}
 
@Composable
private fun SettingsField(label: String, value: String, onChange: (String) -> Unit) {
    Label(label, 13, NetMuted, modifier = Modifier.padding(top = 8.dp))
 
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = NetWhite,
            unfocusedTextColor = NetWhite,
            focusedContainerColor = NetDarkGray,
            unfocusedContainerColor = NetDarkGray,
            focusedBorderColor = NetWhite,
            unfocusedBorderColor = Color(0x33FFFFFF)
        ),
        modifier = Modifier.fillMaxWidth()
    )
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Label("App Settings", 26, NetWhite, FontWeight.Bold)
 
        SettingsField("TorBox API Key", state.torboxKey, onTorboxKeyChange)
        ActionButton("Test TorBox Connection", onTestTorbox)
        if (state.torboxStatus.isNotBlank()) Label(state.torboxStatus, 14, NetWhite)
 
        SettingsField("TMDB API Key", state.tmdbKey, onTmdbKeyChange)
 
        SettingsField("Direct Stream Link", state.directLink, onDirectLinkChange)
        ActionButton("Play Link", onPlayDirectLink)
 
        Label(
            "Made by Jamie", 16, JamieGold, FontWeight.Bold,
            Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            align = TextAlign.Center
        )
    }
}
 
// ───────────────────────────── Player ─────────────────────────────
 
@Composable
private fun PlayerScreen(
    request: PlayRequest,
    torboxKey: String,
    onProgress: (Long, Long) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
 
    var streamUrl by remember(request) { mutableStateOf(request.url) }
    var errorMessage by remember(request) { mutableStateOf("") }
 
    LaunchedEffect(request, torboxKey) {
        if (streamUrl.isBlank() && request.torrentId >= 0) {
            TorBoxRepository.requestStreamLink(torboxKey, request.torrentId, request.fileId).fold(
                onSuccess = { streamUrl = it },
                onFailure = { errorMessage = it.message ?: "Failed to fetch the stream link." }
            )
        }
    }
 
    if (streamUrl.isBlank()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (errorMessage.isBlank()) {
                    CircularProgressIndicator(color = NetRed)
                } else {
                    Label(errorMessage, 16, NetWhite)
                    Spacer(Modifier.height(16.dp))
                    ActionButton("Back", onClose)
                }
            }
        }
        return
    }
 
    val exoPlayer = remember(streamUrl) {
        val mimeType = when {
            streamUrl.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            streamUrl.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            streamUrl.contains(".mp4") -> MimeTypes.VIDEO_MP4
            else -> null
        }
 
        val itemBuilder = MediaItem.Builder().setUri(streamUrl)
        if (mimeType != null) itemBuilder.setMimeType(mimeType)
 
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(
                itemBuilder.build(),
                if (request.startPositionMs > 5_000L) request.startPositionMs else 0L
            )
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
            prepare()
            playWhenReady = true
        }
    }
 
    DisposableEffect(exoPlayer) {
        // Buffering also reports isPlaying == false, so only save on a real pause.
        var wasPlaying = false
 
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (wasPlaying && !isPlaying) {
                    onProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L))
                }
                wasPlaying = isPlaying
            }
 
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = error.errorCodeName
            }
        }
 
        exoPlayer.addListener(listener)
 
        onDispose {
            if (wasPlaying || exoPlayer.currentPosition > 0L) {
                onProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L))
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
 
    // Checkpoint every 10 seconds so a crash or power-off never loses progress.
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
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    player = exoPlayer
                    useController = true
                    isFocusable = true
                    keepScreenOn = true
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
 
        if (errorMessage.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC0B0B10), RoundedCornerShape(12.dp))
                    .padding(24.dp)
            ) {
                Label(errorMessage, 14, NetWhite, align = TextAlign.Center)
            }
        }
    }
}
