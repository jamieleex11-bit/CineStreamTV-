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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

// ───────────────────────────── Netflix Cinema Colors ─────────────────────────────

private val NetBlack = Color(0xFF141414)
private val NetDeepBlack = Color(0xFF000000)
private val NetDarkGray = Color(0xFF1F1F1F)
private val NetCardBg = Color(0xFF181818)
private val NetRed = Color(0xFFE50914)
private val NetWhite = Color(0xFFFFFFFF)
private val NetMuted = Color(0xFFA3A3A3)
private val JamieGold = Color(0xFFFFD166)
private val NetGreen = Color(0xFF46D369)

private val Screens = listOf("Home", "Series", "Movies", "Search", "My List", "Settings")

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
    val key: String get() = if (torrentId >= 0) "tb${torrentId}:${fileId}" else url
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
    val episodeCode: String = ""
) {
    val key: String get() = if (torrentId >= 0) "tb${torrentId}:${fileId}" else url
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

// ───────────────────────────── String Utilities ─────────────────────────────

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
    return if (gb >= 1.0) {
        String.format("%.1f GB", gb)
    } else {
        "${bytes / 1_048_576} MB"
    }
}

// ───────────────────────────── Persistent Storage ─────────────────────────────

internal class AppStorage(private val prefs: SharedPreferences) {

    private fun <T> load(key: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val raw = prefs.getString(key, "[]") ?: "[]"
        val array = JSONArray(raw)
        List(array.length()) { parse(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(key: String, items: List<JSONObject>) {
        runCatching {
            prefs.edit().putString(key, JSONArray(items).toString()).apply()
        }
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
            rating = it.safeString("rating")
        )
    }

    fun saveWatchlist(items: List<Film>) = save("cinestream_watchlist_v2", items.map {
        JSONObject()
            .put("id", it.id).put("tmdbId", it.tmdbId).put("name", it.name)
            .put("year", it.year).put("kind", it.kind).put("poster", it.poster)
            .put("backdrop", it.backdrop).put("overview", it.overview)
            .put("rating", it.rating)
    })

    fun saveTorboxKey(v: String) = prefs.edit().putString("torbox_token", v.trim()).apply()
    fun saveTmdbKey(v: String) = prefs.edit().putString("tmdb_key", v.trim()).apply()
    fun loadTorboxKey(): String = prefs.getString("torbox_token", "").orEmpty()
    fun loadTmdbKey(): String = prefs.getString("tmdb_key", "").orEmpty()
}

// ───────────────────────────── HTTP Client ─────────────────────────────

private object Http {
    class HttpException(val statusCode: Int, message: String) : Exception(message)

    fun get(url: String, bearerToken: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "CineStreamTV/2.0")
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

// ───────────────────────────── TMDB Repository ─────────────────────────────

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
            rating = if (rating > 0.0) String.format("%.1f", rating) else ""
        )
    }
}

// ───────────────────────────── TorBox Repository ─────────────────────────────

private object TorBoxRepository {
    private const val BASE_URL = "https://api.torbox.app/v1/api"
    private val videoExtensions = setOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".ts", ".m2ts")

    suspend fun loadLibrary(apiKey: String): Result<List<TbFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey.trim()
            val files = mutableListOf<TbFile>()
            var offset = 0
            var more = true
            var pages = 0

            while (more && offset < 500 && pages < 5) {
                pages++
                val res = Http.get("$BASE_URL/torrents/mylist?limit=100&offset=$offset", key)
                val payload = JSONObject(res)
                val data = payload.optJSONArray("data")
                if (data == null || data.length() == 0) {
                    more = false
                } else {
                    for (i in 0 until data.length()) {
                        val torrent = data.optJSONObject(i) ?: continue
                        val tId = torrent.optInt("id", -1)
                        val tFiles = torrent.optJSONArray("files") ?: continue

                        for (j in 0 until tFiles.length()) {
                            val f = tFiles.optJSONObject(j) ?: continue
                            val fname = f.safeString("name")
                            val ext = "." + fname.substringAfterLast('.', "")
                            if (videoExtensions.contains(ext.lowercase())) {
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
                val res = JSONObject(Http.get(url, key))
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
            val titleMatches = cleanWords.all { hay.contains(it) }
            if (!titleMatches) return@filter false

            if (episodeCode.isNotBlank()) {
                val ep = episodeCode.lowercase()
                val p1 = Pattern.compile("s(\\d+)e(\\d+)")
                val m = p1.matcher(ep)
                if (m.find()) {
                    val s = m.group(1)?.toIntOrNull() ?: 1
                    val e = m.group(2)?.toIntOrNull() ?: 1
                    val patterns = listOf(
                        ep,
                        "s${s}e${e}",
                        "s%02de%02d".format(s, e),
                        "${s}x${e}"
                    )
                    patterns.any { hay.contains(it) }
                } else {
                    hay.contains(ep)
                }
            } else {
                true
            }
        }.sortedByDescending { it.size }
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

    private val searchQueryFlow = MutableStateFlow("")
    private var episodesJob: Job? = null

    init {
        viewModelScope.launch {
            searchQueryFlow
                .debounce(350)
                .distinctUntilChanged()
                .collectLatest { query -> performSearch(query) }
        }
    }

    fun loadCatalog(apiKey: String) {
        val key = apiKey.trim()
        if (key.isBlank()) return
        _home.update { it.copy(isLoadingCatalog = true, catalogError = "") }
        viewModelScope.launch {
            TmdbRepository.loadTrending(key)
                .onSuccess { films -> _home.update { it.copy(catalog = films, isLoadingCatalog = false) } }
                .onFailure { err -> _home.update { it.copy(isLoadingCatalog = false, catalogError = err.message ?: "") } }
        }
    }

    fun onSearchQueryChange(query: String) {
        _search.update { it.copy(query = query) }
        searchQueryFlow.value = query
    }

    private suspend fun performSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _search.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        val tmdbKey = _settings.value.tmdbKey
        if (tmdbKey.isBlank()) {
            val local = _home.value.catalog.filter { it.name.contains(trimmed, ignoreCase = true) }
            _search.update { it.copy(results = local, isSearching = false) }
            return
        }
        _search.update { it.copy(isSearching = true) }
        TmdbRepository.search(tmdbKey, trimmed)
            .onSuccess { list -> _search.update { it.copy(results = list, isSearching = false) } }
            .onFailure { err -> _search.update { it.copy(isSearching = false, error = err.message ?: "Failed") } }
    }

    fun openDetails(film: Film) {
        val resumeItem = _home.value.resume.firstOrNull { it.name.equals(film.name, ignoreCase = true) }
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
        _detail.update { it.copy(season = season, episodes = emptyList(), matches = emptyList()) }
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch { fetchEpisodes(apiKey, film, season) }
    }

    private suspend fun fetchEpisodes(apiKey: String, film: Film, season: Int) {
        TmdbRepository.loadEpisodes(apiKey, film, season)
            .onSuccess { eps -> _detail.update { it.copy(episodes = eps) } }
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

    private fun lookup(autoPlay: Boolean, startMs: Long, code: String) {
        val film = _detail.value.film ?: return
        viewModelScope.launch {
            _detail.update { it.copy(isLoading = true, matches = emptyList()) }
            TorBoxRepository.loadLibrary(_settings.value.torboxKey).fold(
                onSuccess = { files ->
                    val matches = TorBoxRepository.matchFiles(files, film.name, code)
                    if (autoPlay && matches.isNotEmpty()) {
                        _detail.update { it.copy(isLoading = false) }
                        startPlayback(matches.first(), film, startMs, code)
                    } else {
                        _detail.update { it.copy(isLoading = false, matches = matches, message = if (matches.isEmpty()) "No matches found." else "Select stream below:") }
                    }
                },
                onFailure = { err ->
                    _detail.update { it.copy(isLoading = false, message = err.message ?: "TorBox error") }
                }
            )
        }
    }

    fun playNextEpisode(currentCode: String) {
        val eps = _detail.value.episodes
        val idx = eps.findIndex { it.code == currentCode }
        if (idx >= 0 && idx < eps.size - 1) {
            playEpisode(eps[idx + 1])
        }
    }

    private fun <T> List<T>.findIndex(predicate: (T) -> Boolean): Int {
        for (i in indices) { if (predicate(this[i])) return i }
        return -1
    }

    fun playTorBoxFile(file: TbFile, startMs: Long) =
        startPlayback(file, _detail.value.film, startMs, "")

    private fun startPlayback(file: TbFile, film: Film?, startMs: Long, epCode: String) {
        _playRequest.value = PlayRequest(
            name = file.name,
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
            _settings.update { it.copy(torboxStatus = "Enter valid URL.") }
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

    fun saveProgress(req: PlayRequest, positionMs: Long, durationMs: Long) {
        if (positionMs < 3000L || durationMs <= 0L) return
        val finished = positionMs > durationMs * 0.95
        val list = _home.value.resume.filterNot { it.key == req.key }
        val updated = if (finished) list else (listOf(
            Resume(
                name = req.name,
                image = req.image,
                url = req.url,
                torrentId = req.torrentId,
                fileId = req.fileId,
                positionMs = positionMs,
                durationMs = durationMs
            )
        ) + list).take(12)
        store.saveResume(updated)
        _home.update { it.copy(resume = updated) }
    }

    fun resumeFromHome(res: Resume) {
        _playRequest.value = PlayRequest(
            name = res.name,
            image = res.image,
            url = res.url,
            torrentId = res.torrentId,
            fileId = res.fileId,
            startPositionMs = res.positionMs
        )
    }

    fun onTorboxKeyChange(k: String) {
        _settings.update { it.copy(torboxKey = k.trim()) }
        store.saveTorboxKey(k)
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
            _settings.update { it.copy(torboxStatus = "Enter TorBox key first.") }
            return
        }
        _settings.update { it.copy(torboxStatus = "Testing connection...") }
        viewModelScope.launch {
            TorBoxRepository.testConnection(k)
                .onSuccess { ok ->
                    _settings.update { it.copy(torboxStatus = if (ok) "Connected to TorBox." else "Key rejected.") }
                }
                .onFailure {
                    _settings.update { st -> st.copy(torboxStatus = "Connection failed.") }
                }
        }
    }
}

// ───────────────────────────── Main Activity ─────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(20, 20, 20)
        window.navigationBarColor = android.graphics.Color.rgb(20, 20, 20)

        setContent {
            val context = LocalContext.current
            val storage = remember { AppStorage(context.getSharedPreferences("cinestream_v2", Context.MODE_PRIVATE)) }
            // Using remember { AppViewModel(...) } avoids unresolved 'viewModel' reference error!
            val viewModel: AppViewModel = remember { AppViewModel(storage) }

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

// ───────────────────────────── Core TV Application ─────────────────────────────

@Composable
private fun CineStreamTvApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    val home by viewModel.home.collectAsState()
    val search by viewModel.search.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val playRequest by viewModel.playRequest.collectAsState()

    var selectedScreen by remember { mutableStateOf("Home") }

    LaunchedEffect(settings.tmdbKey) {
        viewModel.loadCatalog(settings.tmdbKey)
    }

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
                onPlayEpisode = { ep -> viewModel.playEpisode(ep) },
                onToggleWatchlist = { viewModel.toggleWatchlist(film) },
                onFindFiles = { viewModel.findTorBoxMatches() },
                onPickFile = { file, startMs -> viewModel.playTorBoxFile(file, startMs) }
            )

            else -> Column(Modifier.fillMaxSize()) {
                NetflixTopNavBar(selectedScreen) { selectedScreen = it }
                when (selectedScreen) {
                    "Home" -> NetflixHomeScreen(home, viewModel::openDetails, viewModel::resumeFromHome)
                    "Series" -> CatalogGridScreen("TV Series", home.catalog.filter { it.kind == "Series" }, viewModel::openDetails)
                    "Movies" -> CatalogGridScreen("Movies", home.catalog.filter { it.kind == "Movie" }, viewModel::openDetails)
                    "My List" -> CatalogGridScreen("My List", home.watchlist, viewModel::openDetails)
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

// ───────────────────────────── 10-Second Countdown Player Overlay ─────────────────────────────

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
    var countdownActive by remember(request) { mutableStateOf(false) }
    var countdownSeconds by remember(request) { mutableStateOf(10) }

    LaunchedEffect(request) {
        if (streamUrl.isBlank() && request.torrentId >= 0) {
            TorBoxRepository.requestStreamLink(torboxKey, request.torrentId, request.fileId).onSuccess {
                streamUrl = it
            }
        }
    }

    // 10-Second Countdown timer
    LaunchedEffect(countdownActive, countdownSeconds) {
        if (countdownActive) {
            if (countdownSeconds <= 0) {
                countdownActive = false
                onNextEpisode()
            } else {
                delay(1000L)
                countdownSeconds -= 1
            }
        }
    }

    val exoPlayer = remember(streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            if (streamUrl.isNotBlank()) {
                setMediaItem(MediaItem.fromUri(streamUrl), request.startPositionMs)
                prepare()
                playWhenReady = true
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && request.kind == "Series") {
                    countdownActive = true
                    countdownSeconds = 10
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            onProgress(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L))
            exoPlayer.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer; useController = true } },
            modifier = Modifier.fillMaxSize()
        )

        // 10-Second Countdown Overlay (Netflix Style)
        if (countdownActive) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xEE141414)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = "Next Episode Playing in $countdownSeconds seconds",
                        color = NetWhite,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { countdownActive = false; onNextEpisode() },
                            colors = ButtonDefaults.buttonColors(containerColor = NetWhite, contentColor = Color.Black)
                        ) {
                            Text("Play Now", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { countdownActive = false },
                            colors = ButtonDefaults.buttonColors(containerColor = NetDarkGray, contentColor = NetWhite)
                        ) {
                            Text("Cancel Autoplay")
                        }

                        Button(
                            onClick = onClose,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = NetMuted)
                        ) {
                            Text("Back to Browse")
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── All Composable Screens (100% Implemented) ─────────────────────────────

@Composable
private fun NetflixTopNavBar(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("NETFLIX", color = NetRed, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Screens.forEach { screen ->
                Text(
                    text = screen,
                    color = if (current == screen) NetWhite else NetMuted,
                    fontSize = 15.sp,
                    fontWeight = if (current == screen) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.clickable { onSelect(screen) }
                )
            }
        }
    }
}

@Composable
private fun NetflixHomeScreen(
    state: HomeUiState,
    onOpen: (Film) -> Unit,
    onResume: (Resume) -> Unit
) {
    if (state.catalog.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = NetRed)
        }
        return
    }

    val hero = state.catalog.first()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "hero") {
            HeroBanner(hero) { onOpen(hero) }
        }

        if (state.resume.isNotEmpty()) {
            item(key = "resume") {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("Continue Watching for Jamie", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NetWhite, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(state.resume, key = { it.key }) { res ->
                            ResumeCard(res) { onResume(res) }
                        }
                    }
                }
            }
        }

        item(key = "top10") {
            Shelf("Top 10 in Movies & TV Today", state.catalog.take(10), onOpen, ranked = true)
        }

        item(key = "movies") {
            Shelf("Trending Movies", state.catalog.filter { it.kind == "Movie" }, onOpen)
        }

        item(key = "shows") {
            Shelf("Binge-Worthy TV Series", state.catalog.filter { it.kind == "Series" }, onOpen)
        }
    }
}

@Composable
private fun HeroBanner(film: Film, onOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(480.dp)
            .clickable(onClick = onOpen)
    ) {
        val art = film.backdrop.ifBlank { film.poster }
        if (art.isNotBlank()) {
            AsyncImage(
                model = art,
                contentDescription = film.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, NetBlack), startY = 220f))
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(NetBlack.copy(alpha = 0.9f), Color.Transparent), endX = 700f))
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 40.dp)
                .width(620.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("N", color = NetRed, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text(film.kind.uppercase(), color = NetWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("• 98% Match", color = NetGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Text(film.name, fontSize = 42.sp, fontWeight = FontWeight.Black, color = NetWhite, maxLines = 1)
            Text(film.overview, fontSize = 14.sp, color = NetWhite.copy(alpha = 0.85f), maxLines = 3)
        }
    }
}

@Composable
private fun Shelf(
    title: String,
    films: List<Film>,
    onOpen: (Film) -> Unit,
    ranked: Boolean = false
) {
    if (films.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NetWhite, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(films, key = { _, f -> f.id }) { index, film ->
                Row(verticalAlignment = Alignment.Bottom) {
                    if (ranked) {
                        Text(
                            text = (index + 1).toString(),
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF444444),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    PosterCard(film = film, onClick = { onOpen(film) })
                }
            }
        }
    }
}

@Composable
private fun PosterCard(film: Film, onClick: () -> Unit) {
    val art = film.poster.ifBlank { film.backdrop }
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "card_scale")

    Box(
        modifier = Modifier
            .width(150.dp)
            .height(225.dp)
            .scale(scale)
            .clip(RoundedCornerShape(6.dp))
            .border(if (focused) 3.dp else 0.dp, NetWhite, RoundedCornerShape(6.dp))
            .background(NetDarkGray)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        if (art.isNotBlank()) {
            AsyncImage(model = art, contentDescription = film.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (film.rating.isNotBlank()) {
            Box(Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text("★ " + film.rating, fontSize = 11.sp, color = NetGreen, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ResumeCard(resume: Resume, onClick: () -> Unit) {
    val progress = if (resume.durationMs > 0L) (resume.positionMs.toFloat() / resume.durationMs).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .width(280.dp)
            .height(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NetDarkGray)
            .clickable(onClick = onClick)
    ) {
        if (resume.image.isNotBlank()) {
            AsyncImage(model = resume.image, contentDescription = resume.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))))

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(resume.name, fontSize = 13.sp, color = NetWhite, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(remainingLabel(resume.positionMs, resume.durationMs), fontSize = 11.sp, color = NetMuted)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF444444))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(NetRed))
            }
        }
    }
}

@Composable
private fun CatalogGridScreen(
    title: String,
    films: List<Film>,
    onOpen: (Film) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = NetWhite, modifier = Modifier.padding(bottom = 16.dp))
        if (films.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No titles in this section yet.", color = NetMuted, fontSize = 16.sp)
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(films, key = { it.id }) { film ->
                    PosterCard(film = film, onClick = { onOpen(film) })
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpen: (Film) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text("Search movies, TV series, actors...") },
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = NetWhite,
                unfocusedTextColor = NetWhite,
                focusedBorderColor = NetWhite,
                unfocusedBorderColor = Color(0x44FFFFFF),
                focusedContainerColor = NetDarkGray,
                unfocusedContainerColor = NetDarkGray
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )

        when {
            state.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = NetRed) }
            state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Search movies and TV shows.", color = NetMuted, fontSize = 16.sp) }
            else -> LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(state.results, key = { it.id }) { film ->
                    PosterCard(film = film, onClick = { onOpen(film) })
                }
            }
        }
    }
}

@Composable
private fun DetailsScreen(
    state: DetailUiState,
    isInWatchlist: Boolean,
    onPlay: (Long) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onToggleWatchlist: () -> Unit,
    onFindFiles: () -> Unit,
    onPickFile: (TbFile, Long) -> Unit
) {
    val film = state.film ?: return

    Box(Modifier.fillMaxSize().background(NetBlack)) {
        if (film.backdrop.isNotBlank()) {
            AsyncImage(model = film.backdrop, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(NetBlack, NetBlack.copy(alpha = 0.9f), NetBlack.copy(alpha = 0.4f)))))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(film.name, fontSize = 40.sp, fontWeight = FontWeight.Black, color = NetWhite)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (film.rating.isNotBlank()) Text("★ " + film.rating, color = NetGreen, fontWeight = FontWeight.Bold)
                Text(film.year, color = NetWhite)
                Text(film.kind, color = NetMuted)
            }
            Text(film.overview, fontSize = 14.sp, color = NetWhite, modifier = Modifier.width(600.dp), maxLines = 4)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onPlay(state.resumePositionMs) },
                    colors = ButtonDefaults.buttonColors(containerColor = NetWhite, contentColor = Color.Black)
                ) {
                    Text(if (state.resumePositionMs > 5000L) "Resume" else "Play", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onFindFiles,
                    colors = ButtonDefaults.buttonColors(containerColor = NetDarkGray, contentColor = NetWhite)
                ) {
                    Text("Find in TorBox")
                }

                Button(
                    onClick = onToggleWatchlist,
                    colors = ButtonDefaults.buttonColors(containerColor = NetDarkGray, contentColor = NetWhite)
                ) {
                    Text(if (isInWatchlist) "In My List" else "+ My List")
                }
            }

            if (state.matches.isNotEmpty()) {
                Text("TorBox Streams", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = NetWhite)
                state.matches.forEach { file ->
                    Button(
                        onClick = { onPickFile(file, state.resumePositionMs) },
                        colors = ButtonDefaults.buttonColors(containerColor = NetCardBg, contentColor = NetWhite),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(file.name + " (" + formatFileSize(file.size) + ")", maxLines = 1)
                    }
                }
            }

            if (film.kind == "Series" && state.episodes.isNotEmpty()) {
                Text("Episodes", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NetWhite)
                state.episodes.forEach { ep ->
                    EpisodeRow(ep) { onPlayEpisode(ep) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NetDarkGray)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (episode.still.isNotBlank()) {
            AsyncImage(model = episode.still, contentDescription = episode.name, modifier = Modifier.width(130.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(episode.code + " • " + episode.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NetWhite)
            Text(episode.overview, fontSize = 12.sp, color = NetMuted, maxLines = 2)
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = NetWhite)

        Text("TorBox API Key", color = NetMuted, fontSize = 14.sp)
        OutlinedTextField(
            value = state.torboxKey,
            onValueChange = onTorboxKeyChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = NetWhite, unfocusedTextColor = NetWhite, focusedContainerColor = NetDarkGray, unfocusedContainerColor = NetDarkGray)
        )
        Button(onClick = onTestTorbox, colors = ButtonDefaults.buttonColors(containerColor = NetWhite, contentColor = Color.Black)) {
            Text("Test TorBox Connection")
        }
        if (state.torboxStatus.isNotBlank()) {
            Text(state.torboxStatus, color = NetGreen, fontSize = 13.sp)
        }

        Text("TMDB API Key", color = NetMuted, fontSize = 14.sp)
        OutlinedTextField(
            value = state.tmdbKey,
            onValueChange = onTmdbKeyChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = NetWhite, unfocusedTextColor = NetWhite, focusedContainerColor = NetDarkGray, unfocusedContainerColor = NetDarkGray)
        )

        Text("Direct Stream Link", color = NetMuted, fontSize = 14.sp)
        OutlinedTextField(
            value = state.directLink,
            onValueChange = onDirectLinkChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = NetWhite, unfocusedTextColor = NetWhite, focusedContainerColor = NetDarkGray, unfocusedContainerColor = NetDarkGray)
        )
        Button(onClick = onPlayDirectLink, colors = ButtonDefaults.buttonColors(containerColor = NetWhite, contentColor = Color.Black)) {
            Text("Play Stream Link")
        }

        Text("Made by Jamie", color = JamieGold, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(top = 32.dp), textAlign = TextAlign.Center)
    }
}
