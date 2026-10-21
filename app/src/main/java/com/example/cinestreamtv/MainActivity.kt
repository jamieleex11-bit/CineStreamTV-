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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val NetBlack = Color(0xFF141414)
private val NetDarkGray = Color(0xFF1F1F1F)
private val NetRed = Color(0xFFE50914)
private val NetWhite = Color(0xFFFFFFFF)
private val NetMuted = Color(0xFF8C8C8C)
private val JamieGold = Color(0xFFFFD166)
private val NetGreen = Color(0xFF46D369)

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

data class Resume(
    val name: String,
    val image: String,
    val url: String,
    val torrentId: Int,
    val fileId: Int,
    val positionMs: Long,
    val durationMs: Long
) {
    val key: String
        get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url
}

data class TbFile(
    val torrentId: Int,
    val fileId: Int,
    val name: String,
    val size: Long
)

data class PlayRequest(
    val name: String,
    val image: String,
    val url: String,
    val torrentId: Int,
    val fileId: Int,
    val startPositionMs: Long
) {
    val key: String
        get() = if (torrentId >= 0) "tb$torrentId:$fileId" else url
}

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

data class LibraryUiState(
    val files: List<TbFile> = emptyList(),
    val isLoading: Boolean = false,
    val status: String = ""
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
    val resumeDurationMs: Long = 0L
)

private fun JSONObject.safeString(key: String): String =
    if (isNull(key)) "" else optString(key, "")

private fun normalizeTitle(value: String): String =
    value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

private fun formatFileSize(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1.0) {
        "${Math.round(gb * 10.0) / 10.0} GB"
    } else {
        "${bytes / 1_048_576} MB"
    }
}

private fun posterUrl(path: String): String =
    "https://image.tmdb.org/t/p/w500$path"

private fun backdropUrl(path: String): String =
    "https://image.tmdb.org/t/p/w1280$path"

private class AppStorage(
    private val prefs: SharedPreferences
) {
    fun loadResume(): List<Resume> = runCatching {
        val array = JSONArray(prefs.getString(KEY_RESUME, "[]") ?: "[]")

        List(array.length()) { index ->
            val item = array.getJSONObject(index)

            Resume(
                name = item.safeString("name"),
                image = item.safeString("image"),
                url = item.safeString("url"),
                torrentId = item.optInt("torrentId", -1),
                fileId = item.optInt("fileId", -1),
                positionMs = item.optLong("positionMs", 0L),
                durationMs = item.optLong("durationMs", 0L)
            )
        }
    }.getOrDefault(emptyList())

    fun saveResume(items: List<Resume>) {
        val array = JSONArray()

        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("name", item.name)
                    .put("image", item.image)
                    .put("url", item.url)
                    .put("torrentId", item.torrentId)
                    .put("fileId", item.fileId)
                    .put("positionMs", item.positionMs)
                    .put("durationMs", item.durationMs)
            )
        }

        prefs.edit().putString(KEY_RESUME, array.toString()).apply()
    }

    fun loadWatchlist(): List<Film> = runCatching {
        val array = JSONArray(prefs.getString(KEY_WATCHLIST, "[]") ?: "[]")

        List(array.length()) { index ->
            val item = array.getJSONObject(index)

            Film(
                id = item.safeString("id"),
                tmdbId = item.optInt("tmdbId", 0),
                name = item.safeString("name"),
                year = item.safeString("year"),
                kind = item.safeString("kind"),
                poster = item.safeString("poster"),
                backdrop = item.safeString("backdrop"),
                overview = item.safeString("overview"),
                rating = item.safeString("rating"),
                provider = item.safeString("provider")
            )
        }
    }.getOrDefault(emptyList())

    fun saveWatchlist(items: List<Film>) {
        val array = JSONArray()

        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("tmdbId", item.tmdbId)
                    .put("name", item.name)
                    .put("year", item.year)
                    .put("kind", item.kind)
                    .put("poster", item.poster)
                    .put("backdrop", item.backdrop)
                    .put("overview", item.overview)
                    .put("rating", item.rating)
                    .put("provider", item.provider)
            )
        }

        prefs.edit().putString(KEY_WATCHLIST, array.toString()).apply()
    }

    fun saveTorboxKey(value: String) {
        prefs.edit().putString(KEY_TORBOX, value).apply()
    }

    fun saveTmdbKey(value: String) {
        prefs.edit().putString(KEY_TMDB, value).apply()
    }

    fun loadTorboxKey(): String =
        prefs.getString(KEY_TORBOX, "").orEmpty()

    fun loadTmdbKey(): String =
        prefs.getString(KEY_TMDB, "").orEmpty()

    private companion object {
        const val KEY_RESUME = "resume"
        const val KEY_WATCHLIST = "watchlist"
        const val KEY_TORBOX = "torbox_token"
        const val KEY_TMDB = "tmdb_key"
    }
}
private object Http {

    class HttpException(
        val statusCode: Int,
        val body: String
    ) : Exception("HTTP $statusCode")

    fun get(
        url: String,
        bearerToken: String? = null,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection

        return try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json")

            if (!bearerToken.isNullOrBlank()) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $bearerToken"
                )
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (statusCode !in 200..299) {
                throw HttpException(statusCode, body)
            }

            body
        } finally {
            connection.disconnect()
        }
    }
}

private object TmdbRepository {

    private const val BASE_URL = "https://api.themoviedb.org/3"

    private val fallbackProviders = listOf(
        "Netflix",
        "Apple TV+",
        "Disney+",
        "MAX",
        "Prime Video"
    )

    suspend fun loadTrending(
        apiKey: String
    ): Result<List<Film>> = withContext(Dispatchers.IO) {
        runCatching {
            val films = mutableListOf<Film>()
            val seenIds = mutableSetOf<String>()

            for (page in 1..5) {
                val response = request(
                    path = "/trending/all/day?page=$page",
                    apiKey = apiKey
                )

                val results = JSONObject(response)
                    .optJSONArray("results")
                    ?: continue

                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val film = item.toFilm() ?: continue

                    if (seenIds.add(film.id)) {
                        films.add(film)
                    }
                }
            }

            films
        }
    }

    suspend fun search(
        apiKey: String,
        query: String
    ): Result<List<Film>> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

            val response = request(
                path = "/search/multi?query=$encodedQuery&include_adult=false",
                apiKey = apiKey
            )

            val results = JSONObject(response)
                .optJSONArray("results")
                ?: return@runCatching emptyList()

            val films = mutableListOf<Film>()

            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val film = item.toFilm() ?: continue

                films.add(film)
            }

            films
        }
    }

    private fun request(
        path: String,
        apiKey: String
    ): String {
        val separator = if (path.contains("?")) "&" else "?"
        val usesBearer = apiKey.length > 40

        val url = if (usesBearer) {
            "$BASE_URL$path"
        } else {
            "$BASE_URL$path${separator}api_key=$apiKey"
        }

        return Http.get(
            url = url,
            bearerToken = if (usesBearer) apiKey else null
        )
    }

    private fun JSONObject.toFilm(): Film? {
        val mediaType = safeString("media_type")

        if (mediaType != "movie" && mediaType != "tv") {
            return null
        }

        val posterPath = safeString("poster_path")

        if (posterPath.isBlank()) {
            return null
        }

        val tmdbId = optInt("id", 0)
        val title = safeString("title").ifBlank {
            safeString("name")
        }

        if (title.isBlank()) {
            return null
        }

        val releaseDate = safeString("release_date").ifBlank {
            safeString("first_air_date")
        }

        val backdropPath = safeString("backdrop_path")
        val rating = optDouble("vote_average", 0.0)

        return Film(
            id = "$mediaType$tmdbId",
            tmdbId = tmdbId,
            name = title,
            year = releaseDate.take(4),
            kind = if (mediaType == "tv") "Series" else "Movie",
            poster = posterUrl(posterPath),
            backdrop = if (backdropPath.isBlank()) {
                ""
            } else {
                backdropUrl(backdropPath)
            },
            overview = safeString("overview"),
            rating = if (rating > 0.0) {
                (Math.round(rating * 10.0) / 10.0).toString()
            } else {
                ""
            },
            provider = fallbackProviders[
                Math.floorMod(tmdbId, fallbackProviders.size)
            ]
        )
    }
}

private object TorBoxRepository {

    private const val BASE_URL = "https://api.torbox.app/v1/api"

    private val videoExtensions = listOf(
        ".mp4",
        ".mkv",
        ".avi",
        ".mov",
        ".m4v",
        ".webm",
        ".ts"
    )

    suspend fun loadLibrary(
        apiKey: String
    ): Result<List<TbFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = Http.get(
                url = "$BASE_URL/torrents/mylist?limit=100&offset=0",
                bearerToken = apiKey
            )

            val data = JSONObject(response)
                .optJSONArray("data")
                ?: return@runCatching emptyList()

            val files = mutableListOf<TbFile>()

            for (torrentIndex in 0 until data.length()) {
                val torrent = data.optJSONObject(torrentIndex) ?: continue
                val torrentId = torrent.optInt("id", -1)
                val torrentFiles = torrent.optJSONArray("files") ?: continue

                for (fileIndex in 0 until torrentFiles.length()) {
                    val file = torrentFiles.optJSONObject(fileIndex) ?: continue
                    val fileName = file.safeString("name")

                    val isVideo = videoExtensions.any { extension ->
                        fileName.lowercase().endsWith(extension)
                    }

                    if (isVideo) {
                        files.add(
                            TbFile(
                                torrentId = torrentId,
                                fileId = file.optInt("fileId", -1),
                                name = fileName.substringAfterLast('/'),
                                size = file.optLong("size", 0L)
                            )
                        )
                    }
                }
            }

            files
        }
    }

    suspend fun requestStreamLink(
        apiKey: String,
        torrentId: Int,
        fileId: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$BASE_URL/torrents/requestdl" +
                "?token=$apiKey" +
                "&torrent_id=$torrentId" +
                "&file_id=$fileId" +
                "&zip_link=false"

            val response = JSONObject(Http.get(url))

            require(response.optBoolean("success", false)) {
                "TorBox did not return a stream link."
            }

            val link = response.safeString("data")

            require(link.isNotBlank()) {
                "TorBox returned an empty stream link."
            }

            link
        }
    }

    suspend fun testConnection(
        apiKey: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = JSONObject(
                Http.get(
                    url = "$BASE_URL/user/me",
                    bearerToken = apiKey
                )
            )

            response.optBoolean("success", false)
        }
    }
}
class AppViewModel(
    private val storage: AppStorage
) : ViewModel() {

    private val _home = MutableStateFlow(
        HomeUiState(
            resume = storage.loadResume(),
            watchlist = storage.loadWatchlist()
        )
    )

    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _library = MutableStateFlow(LibraryUiState())
    val library: StateFlow<LibraryUiState> = _library.asStateFlow()

    private val _settings = MutableStateFlow(
        SettingsUiState(
            torboxKey = storage.loadTorboxKey(),
            tmdbKey = storage.loadTmdbKey()
        )
    )

    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

    private val _detail = MutableStateFlow(DetailUiState())
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    private val _playRequest = MutableStateFlow<PlayRequest?>(null)
    val playRequest: StateFlow<PlayRequest?> = _playRequest.asStateFlow()

    private var catalogJob: Job? = null
    private var searchJob: Job? = null

    fun loadCatalog() {
        val tmdbKey = _settings.value.tmdbKey

        if (tmdbKey.isBlank()) {
            _home.update {
                it.copy(
                    catalog = emptyList(),
                    isLoadingCatalog = false,
                    catalogError = "Add your TMDB API key in Settings to load the catalog."
                )
            }
            return
        }

        catalogJob?.cancel()

        _home.update {
            it.copy(
                isLoadingCatalog = true,
                catalogError = ""
            )
        }

        catalogJob = viewModelScope.launch {
            TmdbRepository.loadTrending(tmdbKey)
                .onSuccess { films ->
                    _home.update {
                        it.copy(
                            catalog = films,
                            isLoadingCatalog = false,
                            catalogError = if (films.isEmpty()) {
                                "No titles were returned by TMDB."
                            } else {
                                ""
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _home.update {
                        it.copy(
                            isLoadingCatalog = false,
                            catalogError = readableError(
                                error = error,
                                fallback = "Could not load the TMDB catalog."
                            )
                        )
                    }
                }
        }
    }

    fun onSearchQueryChange(value: String) {
        _search.update {
            it.copy(
                query = value,
                error = ""
            )
        }

        searchJob?.cancel()

        if (value.isBlank()) {
            _search.update {
                it.copy(
                    results = emptyList(),
                    isSearching = false
                )
            }
            return
        }

        val tmdbKey = _settings.value.tmdbKey

        if (tmdbKey.isBlank()) {
            val localResults = _home.value.catalog.filter { film ->
                film.name.contains(value, ignoreCase = true)
            }

            _search.update {
                it.copy(
                    results = localResults,
                    isSearching = false,
                    error = if (localResults.isEmpty()) {
                        "Add a TMDB API key in Settings to search all titles."
                    } else {
                        ""
                    }
                )
            }
            return
        }

        _search.update {
            it.copy(isSearching = true)
        }

        searchJob = viewModelScope.launch {
            delay(350)

            TmdbRepository.search(
                apiKey = tmdbKey,
                query = value
            )
                .onSuccess { results ->
                    _search.update {
                        it.copy(
                            results = results,
                            isSearching = false,
                            error = if (results.isEmpty()) {
                                "No results found."
                            } else {
                                ""
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _search.update {
                        it.copy(
                            results = emptyList(),
                            isSearching = false,
                            error = readableError(
                                error = error,
                                fallback = "Search failed. Check your connection or TMDB key."
                            )
                        )
                    }
                }
        }
    }

    fun openDetails(film: Film) {
        val resumeItem = _home.value.resume.firstOrNull { resume ->
            resume.name.contains(film.name, ignoreCase = true)
        }

        _detail.value = DetailUiState(
            film = film,
            resumePositionMs = resumeItem?.positionMs ?: 0L,
            resumeDurationMs = resumeItem?.durationMs ?: 0L
        )
    }

    fun closeDetails() {
        _detail.value = DetailUiState()
    }

    fun toggleWatchlist(film: Film) {
        _home.update { current ->
            val updatedWatchlist = current.watchlist.toMutableList()
            val existingIndex = updatedWatchlist.indexOfFirst {
                it.id == film.id
            }

            if (existingIndex >= 0) {
                updatedWatchlist.removeAt(existingIndex)
            } else {
                updatedWatchlist.add(0, film)
            }

            storage.saveWatchlist(updatedWatchlist)

            current.copy(watchlist = updatedWatchlist)
        }

        val isAdded = _home.value.watchlist.any {
            it.id == film.id
        }

        _detail.update {
            it.copy(
                message = if (isAdded) {
                    "Added to My List."
                } else {
                    "Removed from My List."
                }
            )
        }
    }

    fun findTorBoxMatches() {
        val film = _detail.value.film ?: return

        viewModelScope.launch {
            _detail.update {
                it.copy(
                    isLoading = true,
                    matches = emptyList(),
                    message = "Searching your TorBox library..."
                )
            }

            loadLibraryInternal().fold(
                onSuccess = { files ->
                    val normalizedFilmName = normalizeTitle(film.name)

                    val matches = files.filter { file ->
                        normalizedFilmName.isNotBlank() &&
                            normalizeTitle(file.name)
                                .contains(normalizedFilmName)
                    }

                    _detail.update {
                        it.copy(
                            isLoading = false,
                            matches = matches,
                            message = if (matches.isEmpty()) {
                                "No matching files were found in your TorBox library."
                            } else {
                                "Choose a file below."
                            }
                        )
                    }
                },
                onFailure = { error ->
                    _detail.update {
                        it.copy(
                            isLoading = false,
                            message = readableError(
                                error = error,
                                fallback = "Could not load your TorBox library."
                            )
                        )
                    }
                }
            )
        }
    }

    fun playFilm(startPositionMs: Long) {
        val film = _detail.value.film ?: return

        viewModelScope.launch {
            _detail.update {
                it.copy(
                    isLoading = true,
                    matches = emptyList(),
                    message = ""
                )
            }

            if (_settings.value.torboxKey.isBlank()) {
                _detail.update {
                    it.copy(
                        isLoading = false,
                        message = "Add your TorBox API key in Settings."
                    )
                }
                return@launch
            }

            loadLibraryInternal().fold(
                onSuccess = { files ->
                    val normalizedFilmName = normalizeTitle(film.name)

                    val matches = files.filter { file ->
                        normalizedFilmName.isNotBlank() &&
                            normalizeTitle(file.name)
                                .contains(normalizedFilmName)
                    }

                    when {
                        matches.isEmpty() -> {
                            _detail.update {
                                it.copy(
                                    isLoading = false,
                                    message = "This title was not found in your TorBox library."
                                )
                            }
                        }

                        matches.size == 1 -> {
                            startTorBoxPlayback(
                                file = matches.first(),
                                image = film.backdrop.ifBlank {
                                    film.poster
                                },
                                startPositionMs = startPositionMs
                            )
                        }

                        else -> {
                            _detail.update {
                                it.copy(
                                    isLoading = false,
                                    matches = matches,
                                    message = "Choose a file below."
                                )
                            }
                        }
                    }
                },
                onFailure = { error ->
                    _detail.update {
                        it.copy(
                            isLoading = false,
                            message = readableError(
                                error = error,
                                fallback = "Could not load your TorBox library."
                            )
                        )
                    }
                }
            )
        }
    }

    fun playTorBoxFile(
        file: TbFile,
        startPositionMs: Long
    ) {
        val film = _detail.value.film

        startTorBoxPlayback(
            file = file,
            image = film?.backdrop.orEmpty().ifBlank {
                film?.poster.orEmpty()
            },
            startPositionMs = startPositionMs
        )
    }

    private fun startTorBoxPlayback(
        file: TbFile,
        image: String,
        startPositionMs: Long
    ) {
        _detail.update {
            it.copy(isLoading = false)
        }

        _playRequest.value = PlayRequest(
            name = file.name,
            image = image,
            url = "",
            torrentId = file.torrentId,
            fileId = file.fileId,
            startPositionMs = startPositionMs
        )
    }

    fun playDirectLink() {
        val link = _settings.value.directLink.trim()

        val isValidLink = link.startsWith("http://", ignoreCase = true) ||
            link.startsWith("https://", ignoreCase = true)

        if (!isValidLink) {
            _settings.update {
                it.copy(
                    torboxStatus = "Enter a valid http or https stream link."
                )
            }
            return
        }

        _playRequest.value = PlayRequest(
            name = link.substringAfterLast('/')
                .substringBefore('?')
                .ifBlank { "Stream" },
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

    fun saveProgress(
        request: PlayRequest,
        positionMs: Long,
        durationMs: Long
    ) {
        if (positionMs < 3_000L) {
            return
        }

        val isFinished = durationMs > 0L &&
            positionMs > durationMs * 0.95

        _home.update { current ->
            val updatedResume = current.resume.toMutableList()

            updatedResume.removeAll { resume ->
                resume.key == request.key
            }

            if (!isFinished) {
                updatedResume.add(
                    0,
                    Resume(
                        name = request.name,
                        image = request.image,
                        url = request.url,
                        torrentId = request.torrentId,
                        fileId = request.fileId,
                        positionMs = positionMs,
                        durationMs = durationMs
                    )
                )
            }

            while (updatedResume.size > 10) {
                updatedResume.removeAt(updatedResume.lastIndex)
            }

            storage.saveResume(updatedResume)

            current.copy(resume = updatedResume)
        }
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

    fun onTorboxKeyChange(value: String) {
        val trimmed = value.trim()

        _settings.update {
            it.copy(
                torboxKey = trimmed,
                torboxStatus = ""
            )
        }

        storage.saveTorboxKey(trimmed)
    }

    fun onTmdbKeyChange(value: String) {
        val trimmed = value.trim()

        _settings.update {
            it.copy(tmdbKey = trimmed)
        }

        storage.saveTmdbKey(trimmed)
    }

    fun onDirectLinkChange(value: String) {
        _settings.update {
            it.copy(directLink = value)
        }
    }

    fun testTorboxConnection() {
        val apiKey = _settings.value.torboxKey

        if (apiKey.isBlank()) {
            _settings.update {
                it.copy(
                    torboxStatus = "Enter your TorBox API key first."
                )
            }
            return
        }

        _settings.update {
            it.copy(torboxStatus = "Testing TorBox connection...")
        }

        viewModelScope.launch {
            TorBoxRepository.testConnection(apiKey)
                .onSuccess { connected ->
                    _settings.update {
                        it.copy(
                            torboxStatus = if (connected) {
                                "Connected to TorBox."
                            } else {
                                "TorBox rejected this API key."
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _settings.update {
                        it.copy(
                            torboxStatus = readableError(
                                error = error,
                                fallback = "Could not connect to TorBox."
                            )
                        )
                    }
                }
        }
    }

    private suspend fun loadLibraryInternal(): Result<List<TbFile>> {
        val apiKey = _settings.value.torboxKey

        if (apiKey.isBlank()) {
            return Result.failure(
                IllegalStateException(
                    "Add your TorBox API key in Settings."
                )
            )
        }

        _library.update {
            it.copy(
                isLoading = true,
                status = ""
            )
        }

        return TorBoxRepository.loadLibrary(apiKey)
            .onSuccess { files ->
                _library.update {
                    it.copy(
                        files = files,
                        isLoading = false,
                        status = if (files.isEmpty()) {
                            "No video files were found in your TorBox library."
                        } else {
                            ""
                        }
                    )
                }
            }
            .onFailure { error ->
                _library.update {
                    it.copy(
                        isLoading = false,
                        status = readableError(
                            error = error,
                            fallback = "Could not load your TorBox library."
                        )
                    )
                }
            }
    }

    private fun readableError(
        error: Throwable,
        fallback: String
    ): String {
        return when (error) {
            is Http.HttpException -> {
                "$fallback (HTTP ${error.statusCode})"
            }

            is IllegalStateException -> {
                error.message ?: fallback
            }

            else -> fallback
        }
    }
}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.rgb(20, 20, 20)
        window.navigationBarColor = android.graphics.Color.rgb(20, 20, 20)

        setContent {
            val context = LocalContext.current

            val storage = remember {
                AppStorage(
                    context.getSharedPreferences(
                        "cinestream",
                        Context.MODE_PRIVATE
                    )
                )
            }

            val viewModel: AppViewModel = viewModel {
                AppViewModel(storage)
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

    var selectedScreen by remember {
        mutableStateOf("Home")
    }

    var lastBackPressTimeMs by remember {
        mutableStateOf(0L)
    }

    LaunchedEffect(settings.tmdbKey) {
        viewModel.loadCatalog()
    }

    BackHandler(enabled = true) {
        when {
            playRequest != null -> {
                viewModel.closePlayer()
            }

            detail.film != null -> {
                viewModel.closeDetails()
            }

            selectedScreen != "Home" -> {
                selectedScreen = "Home"
            }

            else -> {
                val now = System.currentTimeMillis()

                if (now - lastBackPressTimeMs < 2_000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressTimeMs = now

                    Toast.makeText(
                        context,
                        "Press Back again to exit",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NetBlack)
    ) {
        val activePlayRequest = playRequest
        val activeFilm = detail.film

        when {
            activePlayRequest != null -> {
                PlayerScreen(
                    request = activePlayRequest,
                    torboxKey = settings.torboxKey,
                    onProgress = { position, duration ->
                        viewModel.saveProgress(
                            request = activePlayRequest,
                            positionMs = position,
                            durationMs = duration
                        )
                    }
                )
            }

            activeFilm != null -> {
                DetailsScreen(
                    state = detail,
                    isInWatchlist = home.watchlist.any {
                        it.id == activeFilm.id
                    },
                    onPlay = viewModel::playFilm,
                    onFindFiles = viewModel::findTorBoxMatches,
                    onPickFile = viewModel::playTorBoxFile,
                    onToggleWatchlist = {
                        viewModel.toggleWatchlist(activeFilm)
                    },
                    onBack = viewModel::closeDetails
                )
            }

            else -> {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    TopNavigationBar(
                        currentScreen = selectedScreen,
                        onScreenSelected = { screen ->
                            selectedScreen = screen
                        }
                    )

                    when (selectedScreen) {
                        "Home" -> {
                            HomeScreen(
                                state = home,
                                onOpenFilm = viewModel::openDetails,
                                onResume = viewModel::resumeFromHome
                            )
                        }

                        "Shows" -> {
                            FilteredCatalogScreen(
                                title = "TV Shows",
                                films = home.catalog.filter {
                                    it.kind == "Series"
                                },
                                onOpenFilm = viewModel::openDetails
                            )
                        }

                        "Movies" -> {
                            FilteredCatalogScreen(
                                title = "Movies",
                                films = home.catalog.filter {
                                    it.kind == "Movie"
                                },
                                onOpenFilm = viewModel::openDetails
                            )
                        }

                        "My List" -> {
                            FilteredCatalogScreen(
                                title = "My Watchlist",
                                films = home.watchlist,
                                emptyMessage = "Your watchlist is empty.",
                                onOpenFilm = viewModel::openDetails
                            )
                        }

                        "Search" -> {
                            SearchScreen(
                                state = search,
                                onQueryChange = viewModel::onSearchQueryChange,
                                onOpenFilm = viewModel::openDetails
                            )
                        }

                        "Settings" -> {
                            SettingsScreen(
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
    }
}

@Composable
private fun TopNavigationBar(
    currentScreen: String,
    onScreenSelected: (String) -> Unit
) {
    val screens = listOf(
        "Search" to "⌕",
        "Home" to "Home",
        "Shows" to "Shows",
        "Movies" to "Movies",
        "My List" to "My List",
        "Settings" to "Settings"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(NetBlack)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(Color(0xFF222222))
                .border(
                    width = 1.dp,
                    color = Color(0xFF333333),
                    shape = RoundedCornerShape(50.dp)
                )
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            screens.forEach { screen ->
                NavigationPill(
                    label = screen.second,
                    isSelected = currentScreen == screen.first,
                    onClick = {
                        onScreenSelected(screen.first)
                    }
                )
            }
        }
    }
}

@Composable
private fun NavigationPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember {
        mutableStateOf(false)
    }

    val isActive = isSelected || isFocused

    Box(
        modifier = Modifier
            .tvFocusable(
                isFocused = isFocused,
                onFocusChanged = { focused ->
                    isFocused = focused
                }
            )
            .clip(RoundedCornerShape(50.dp))
            .background(
                if (isActive) NetWhite else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) Color.Black else NetWhite,
            fontSize = if (label == "⌕") 18.sp else 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun Modifier.tvFocusable(
    isFocused: Boolean,
    focusedScale: Float = 1.05f,
    showBorder: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {}
): Modifier = this
    .onFocusChanged { focused ->
        onFocusChanged(focused.isFocused)
    }
    .scale(
        if (isFocused) focusedScale else 1f
    )
    .then(
        if (isFocused && showBorder) {
            Modifier.border(
                width = 3.dp,
                color = NetWhite,
                shape = RoundedCornerShape(6.dp)
            )
        } else {
            Modifier
        }
    )

@Composable
private fun FocusableCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var isFocused by remember {
        mutableStateOf(false)
    }

    Box(
        modifier = modifier
            .tvFocusable(
                isFocused = isFocused,
                onFocusChanged = { focused ->
                    isFocused = focused
                    onFocusChanged(focused)
                }
            )
            .clip(RoundedCornerShape(6.dp))
            .background(NetDarkGray)
            .clickable(onClick = onClick)
    ) {
        content(this, isFocused)
    }
}

@Composable
private fun PosterCard(
    film: Film,
    onClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    FocusableCard(
        modifier = Modifier
            .width(150.dp)
            .height(220.dp),
        onClick = onClick,
        onFocusChanged = onFocusChanged
    ) {
        AsyncImage(
            model = film.poster,
            contentDescription = film.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun ResumeCard(
    resume: Resume,
    onClick: () -> Unit
) {
    val progress = if (resume.durationMs > 0L) {
        (resume.positionMs.toFloat() / resume.durationMs.toFloat())
            .coerceIn(0f, 1f)
    } else {
        0f
    }

    val remainingMinutes = (
        (resume.durationMs - resume.positionMs) / 60_000L
        ).coerceAtLeast(0L).toInt()

    val remainingLabel = when {
        resume.durationMs <= 0L -> "Continue watching"
        remainingMinutes >= 60 -> {
            "${remainingMinutes / 60}h ${remainingMinutes % 60}m left"
        }

        remainingMinutes > 0 -> "$remainingMinutes min left"
        else -> "Almost finished"
    }

    FocusableCard(
        modifier = Modifier
            .width(300.dp)
            .height(190.dp),
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
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0x55000000),
                            Color(0xEE000000)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = resume.name,
                color = NetWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = remainingLabel,
                color = Color(0xFFDDDDDD),
                fontSize = 11.sp,
                maxLines = 1
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF777777))
            ) {
                Box(
                    modifier = Modifier
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
    onOpenFilm: (Film) -> Unit,
    onFilmFocused: (Film) -> Unit = {}
) {
    if (films.isEmpty()) {
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = title,
            color = NetWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                start = 48.dp,
                bottom = 8.dp
            )
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(
                items = films,
                key = { it.id },
                contentType = { "poster" }
            ) { film ->
                PosterCard(
                    film = film,
                    onClick = {
                        onOpenFilm(film)
                    },
                    onFocusChanged = { focused ->
                        if (focused) {
                            onFilmFocused(film)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LoadingState(
    message: String = "Loading..."
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(color = NetRed)

        Text(
            text = message,
            color = NetMuted
        )
    }
}

@Composable
private fun MessageState(
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            color = NetWhite,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ActionPillButton(
    label: String,
    onClick: () -> Unit
) {
    var isFocused by remember {
        mutableStateOf(false)
    }

    Box(
        modifier = Modifier
            .width(320.dp)
            .height(48.dp)
            .tvFocusable(
                isFocused = isFocused,
                focusedScale = 1.03f,
                showBorder = false,
                onFocusChanged = { focused ->
                    isFocused = focused
                }
            )
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isFocused) NetWhite else Color(0xFF303030)
            )
            .border(
                width = 1.dp,
                color = if (isFocused) {
                    NetWhite
                } else {
                    Color(0xFF555555)
                },
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            color = if (isFocused) Color.Black else NetWhite,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@Composable
private fun HomeScreen(
    state: HomeUiState,
    onOpenFilm: (Film) -> Unit,
    onResume: (Resume) -> Unit
) {
    when {
        state.isLoadingCatalog -> {
            LoadingState("Loading today’s catalog...")
        }

        state.catalogError.isNotBlank() && state.catalog.isEmpty() -> {
            MessageState(message = state.catalogError)
        }

        state.catalog.isEmpty() -> {
            MessageState(message = "No titles available yet.")
        }

        else -> {
            val selectedHero = remember(state.catalog) {
                mutableStateOf(state.catalog.first())
            }

            val hero = state.catalog.firstOrNull {
                it.id == selectedHero.value.id
            } ?: state.catalog.first()

            val trending = state.catalog.take(10)

            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item(key = "hero") {
                    HeroBanner(
                        film = hero,
                        onOpen = {
                            onOpenFilm(hero)
                        }
                    )
                }

                if (state.resume.isNotEmpty()) {
                    item(key = "continue-watching") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = "Continue Watching",
                                color = NetWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(
                                    start = 48.dp,
                                    bottom = 8.dp
                                )
                            )

                            LazyRow(
                                contentPadding = PaddingValues(
                                    horizontal = 48.dp
                                ),
                                horizontalArrangement = Arrangement.spacedBy(
                                    14.dp
                                )
                            ) {
                                items(
                                    items = state.resume,
                                    key = { it.key }
                                ) { resume ->
                                    ResumeCard(
                                        resume = resume,
                                        onClick = {
                                            onResume(resume)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "trending") {
                    TrendingShelf(
                        films = trending,
                        onOpenFilm = onOpenFilm,
                        onFilmFocused = { film ->
                            selectedHero.value = film
                        }
                    )
                }

                item(key = "apple") {
                    Shelf(
                        title = "Apple TV+",
                        films = state.catalog.filter {
                            it.provider == "Apple TV+"
                        },
                        onOpenFilm = onOpenFilm,
                        onFilmFocused = { film ->
                            selectedHero.value = film
                        }
                    )
                }

                item(key = "disney") {
                    Shelf(
                        title = "Disney+",
                        films = state.catalog.filter {
                            it.provider == "Disney+"
                        },
                        onOpenFilm = onOpenFilm,
                        onFilmFocused = { film ->
                            selectedHero.value = film
                        }
                    )
                }

                item(key = "max") {
                    Shelf(
                        title = "MAX",
                        films = state.catalog.filter {
                            it.provider == "MAX"
                        },
                        onOpenFilm = onOpenFilm,
                        onFilmFocused = { film ->
                            selectedHero.value = film
                        }
                    )
                }

                item(key = "netflix") {
                    Shelf(
                        title = "Netflix",
                        films = state.catalog.filter {
                            it.provider == "Netflix"
                        },
                        onOpenFilm = onOpenFilm,
                        onFilmFocused = { film ->
                            selectedHero.value = film
                        }
                    )
                }

                item(key = "prime") {
                    Shelf(
                        title = "Prime Video",
                        films = state.catalog.filter {
                            it.provider == "Prime Video"
                        },
                        onOpenFilm = onOpenFilm,
                        onFilmFocused = { film ->
                            selectedHero.value = film
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroBanner(
    film: Film,
    onOpen: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(500.dp)
            .background(NetBlack)
            .clickable(onClick = onOpen)
    ) {
        Crossfade(
            targetState = film.backdrop,
            label = "hero-backdrop"
        ) { backdrop ->
            if (backdrop.isNotBlank()) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = film.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            NetBlack
                        ),
                        startY = 250f
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            NetBlack.copy(alpha = 0.9f),
                            Color.Transparent
                        ),
                        endX = 700f
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = 48.dp,
                    bottom = 40.dp
                )
                .width(650.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "STREAMING ON",
                    color = NetMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Box(
                    modifier = Modifier
                        .background(
                            color = NetRed,
                            shape = RoundedCornerShape(2.dp)
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 2.dp
                        )
                ) {
                    Text(
                        text = film.provider,
                        color = NetWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = film.name,
                color = NetWhite,
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = film.year,
                    color = NetMuted,
                    fontSize = 13.sp
                )

                Text(
                    text = "•",
                    color = NetMuted
                )

                Text(
                    text = film.kind,
                    color = NetMuted,
                    fontSize = 13.sp
                )

                if (film.rating.isNotBlank()) {
                    Text(
                        text = "•",
                        color = NetMuted
                    )

                    Text(
                        text = "★ ${film.rating}",
                        color = NetGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = film.overview,
                color = NetWhite.copy(alpha = 0.9f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TrendingShelf(
    films: List<Film>,
    onOpenFilm: (Film) -> Unit,
    onFilmFocused: (Film) -> Unit
) {
    if (films.isEmpty()) {
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = "Top 10 Trending Today",
            color = NetWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                start = 48.dp,
                bottom = 10.dp
            )
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = films.take(10),
                key = { "trending-${it.id}" },
                contentType = { "trending-card" }
            ) { film ->
                val rank = films.indexOf(film) + 1

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(
                        (-10).dp
                    )
                ) {
                    Text(
                        text = rank.toString(),
                        color = Color(0xFF8A8A8A),
                        fontSize = 84.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .width(62.dp)
                            .align(Alignment.Bottom)
                    )

                    PosterCard(
                        film = film,
                        onClick = {
                            onOpenFilm(film)
                        },
                        onFocusChanged = { focused ->
                            if (focused) {
                                onFilmFocused(film)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilteredCatalogScreen(
    title: String,
    films: List<Film>,
    emptyMessage: String = "No titles available.",
    onOpenFilm: (Film) -> Unit
) {
    if (films.isEmpty()) {
        MessageState(message = emptyMessage)
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp)
    ) {
        item {
            Text(
                text = title,
                color = NetWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(
                    start = 48.dp,
                    bottom = 16.dp
                )
            )
        }

        item {
            Shelf(
                title = "Apple TV+",
                films = films.filter {
                    it.provider == "Apple TV+"
                },
                onOpenFilm = onOpenFilm
            )
        }

        item {
            Shelf(
                title = "Disney+",
                films = films.filter {
                    it.provider == "Disney+"
                },
                onOpenFilm = onOpenFilm
            )
        }

        item {
            Shelf(
                title = "MAX",
                films = films.filter {
                    it.provider == "MAX"
                },
                onOpenFilm = onOpenFilm
            )
        }

        item {
            Shelf(
                title = "Netflix",
                films = films.filter {
                    it.provider == "Netflix"
                },
                onOpenFilm = onOpenFilm
            )
        }

        item {
            Shelf(
                title = "Prime Video",
                films = films.filter {
                    it.provider == "Prime Video"
                },
                onOpenFilm = onOpenFilm
            )
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpenFilm: (Film) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 16.dp)
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = {
                Text("Search titles")
            },
            shape = RoundedCornerShape(6.dp),
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
                .padding(bottom = 16.dp)
        )

        when {
            state.isSearching -> {
                LoadingState("Searching...")
            }

            state.error.isNotBlank() -> {
                MessageState(message = state.error)
            }

            state.results.isEmpty() && state.query.isNotBlank() -> {
                MessageState(message = "No results found.")
            }

            state.results.isEmpty() -> {
                MessageState(
                    message = "Search for movies and TV shows."
                )
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = state.results,
                        key = { it.id }
                    ) { film ->
                        PosterCard(
                            film = film,
                            onClick = {
                                onOpenFilm(film)
                            }
                        )
                    }
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
    onFindFiles: () -> Unit,
    onPickFile: (TbFile, Long) -> Unit,
    onToggleWatchlist: () -> Unit,
    onBack: () -> Unit
) {
    val film = state.film ?: return

    val hasProgress = state.resumePositionMs > 5_000L &&
        state.resumeDurationMs > 0L

    val progress = if (hasProgress) {
        (state.resumePositionMs.toFloat() / state.resumeDurationMs.toFloat())
            .coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = Modifier
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
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            NetBlack,
                            NetBlack.copy(alpha = 0.9f),
                            NetBlack.copy(alpha = 0.4f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = 56.dp,
                    vertical = 48.dp
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Streaming on:",
                    color = NetMuted,
                    fontSize = 14.sp
                )

                Box(
                    modifier = Modifier
                        .background(
                            color = NetRed,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(
                            horizontal = 8.dp,
                            vertical = 3.dp
                        )
                ) {
                    Text(
                        text = film.provider,
                        color = NetWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = film.name,
                color = NetWhite,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (film.rating.isNotBlank()) {
                    Text(
                        text = "★ ${film.rating}",
                        color = NetGreen,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = film.year,
                    color = NetWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = film.kind,
                    color = NetMuted,
                    fontSize = 15.sp
                )
            }

            Text(
                text = film.overview,
                color = NetWhite,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(600.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            PlayButton(
                hasProgress = hasProgress,
                progress = progress,
                isLoading = state.isLoading,
                onClick = {
                    onPlay(state.resumePositionMs)
                }
            )

            ActionPillButton(
                label = if (film.kind == "Series") {
                    "Episodes"
                } else {
                    "Find in TorBox"
                },
                onClick = onFindFiles
            )

            ActionPillButton(
                label = if (isInWatchlist) {
                    "✓ Added to My List"
                } else {
                    "+ Add to My List"
                },
                onClick = onToggleWatchlist
            )

            if (state.message.isNotBlank()) {
                Text(
                    text = state.message,
                    color = NetMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (state.matches.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Select a file from TorBox",
                    color = NetWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                state.matches.forEach { file ->
                    FocusableCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onPickFile(
                                file,
                                state.resumePositionMs
                            )
                        }
                    ) {
                        Text(
                            text = "${file.name} " +
                                "(${formatFileSize(file.size)})",
                            color = NetWhite,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PlayButton(
    hasProgress: Boolean,
    progress: Float,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember {
        mutableStateOf(false)
    }

    Box(
        modifier = Modifier
            .width(320.dp)
            .height(52.dp)
            .tvFocusable(
                isFocused = isFocused,
                focusedScale = 1.04f,
                showBorder = false,
                onFocusChanged = { focused ->
                    isFocused = focused
                }
            )
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isFocused) NetWhite else Color(0xFFE5E5E5)
            )
            .clickable(
                enabled = !isLoading,
                onClick = onClick
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        if (hasProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFB0B0B0))
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(Color(0xFF8C8C8C))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "▶",
                color = Color.Black,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = when {
                    isLoading -> "Loading..."
                    hasProgress -> "Resume"
                    else -> "Play"
                },
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
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
            .padding(
                horizontal = 48.dp,
                vertical = 16.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "App Settings",
            color = NetWhite,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "TorBox API Key",
            color = NetMuted,
            fontSize = 13.sp
        )

        OutlinedTextField(
            value = state.torboxKey,
            onValueChange = onTorboxKeyChange,
            singleLine = true,
            shape = RoundedCornerShape(6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = NetDarkGray,
                unfocusedContainerColor = NetDarkGray,
                focusedBorderColor = NetWhite,
                unfocusedBorderColor = Color(0x33FFFFFF)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        ActionPillButton(
            label = "Test TorBox Connection",
            onClick = onTestTorbox
        )

        if (state.torboxStatus.isNotBlank()) {
            Text(
                text = state.torboxStatus,
                color = NetWhite,
                fontSize = 14.sp
            )
        }

        Text(
            text = "TMDB API Key",
            color = NetMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp)
        )

        OutlinedTextField(
            value = state.tmdbKey,
            onValueChange = onTmdbKeyChange,
            singleLine = true,
            shape = RoundedCornerShape(6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = NetDarkGray,
                unfocusedContainerColor = NetDarkGray,
                focusedBorderColor = NetWhite,
                unfocusedBorderColor = Color(0x33FFFFFF)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Direct Stream Link",
            color = NetMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp)
        )

        OutlinedTextField(
            value = state.directLink,
            onValueChange = onDirectLinkChange,
            singleLine = true,
            shape = RoundedCornerShape(6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = NetDarkGray,
                unfocusedContainerColor = NetDarkGray,
                focusedBorderColor = NetWhite,
                unfocusedBorderColor = Color(0x33FFFFFF)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        ActionPillButton(
            label = "Play Link",
            onClick = onPlayDirectLink
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Made by Jamie",
            color = JamieGold,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        )
    }
}

@Composable
private fun PlayerScreen(
    request: PlayRequest,
    torboxKey: String,
    onProgress: (Long, Long) -> Unit
) {
    val context = LocalContext.current

    var streamUrl by remember(request) {
        mutableStateOf(request.url)
    }

    var errorMessage by remember(request) {
        mutableStateOf("")
    }

    LaunchedEffect(request, torboxKey) {
        if (streamUrl.isBlank() && request.torrentId >= 0) {
            TorBoxRepository.requestStreamLink(
                apiKey = torboxKey,
                torrentId = request.torrentId,
                fileId = request.fileId
            ).fold(
                onSuccess = { link ->
                    streamUrl = link
                },
                onFailure = { error ->
                    errorMessage = error.message
                        ?: "Failed to fetch the stream link."
                }
            )
        }
    }

    if (streamUrl.isBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (errorMessage.isBlank()) {
                CircularProgressIndicator(color = NetRed)
            } else {
                Text(
                    text = errorMessage,
                    color = NetWhite,
                    fontSize = 16.sp
                )
            }
        }

        return
    }

    val exoPlayer = remember(streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))

            if (request.startPositionMs > 5_000L) {
                seekTo(request.startPositionMs)
            }

            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    onProgress(
                        exoPlayer.currentPosition,
                        exoPlayer.duration
                    )
                }
            }
        }

        exoPlayer.addListener(listener)

        onDispose {
            onProgress(
                exoPlayer.currentPosition,
                exoPlayer.duration
            )

            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    player = exoPlayer
                    useController = true
                    isFocusable = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    requestFocus()
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
