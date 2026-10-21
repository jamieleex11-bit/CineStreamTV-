package com.example.cinestreamtv

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.activity.compose.setContent
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ---------- Theme Colors ----------
private val NetBlack = Color(0xFF141414)
private val NetDarkGray = Color(0xFF1F1F1F)
private val NetRed = Color(0xFFE50914)
private val NetWhite = Color(0xFFFFFFFF)
private val NetMuted = Color(0xFF8C8C8C)
private val JamieGold = Color(0xFFFFD166)

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

data class Resume(
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
    val name: String,
    val image: String,
    val url: String,
    val torrentId: Int,
    val fileId: Int,
    val start: Long
)

private val Resume.key: String get() = if (torrentId >= 0) "tb\$torrentId:\$fileId" else url
private val PlayReq.key: String get() = if (torrentId >= 0) "tb\$torrentId:\$fileId" else url

private fun poster(path: String) = "https://image.tmdb.org/t/p/w500\$path"

private val demoFilms = listOf(
    Film("d1", 101, "Ted Lasso", "2023", "Series", poster("/kXJQv9GBnYJ0AvkWMe5JGPUlOW5.jpg"),
        "https://image.tmdb.org/t/p/w1280/56v2KjBlU4XaOv9rVYEQypROD7P.jpg",
        "An American football coach is hired to manage a British soccer team.", "8.6", "Apple TV+"),
    Film("d2", 102, "Succession", "2023", "Series", poster("/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg"),
        "https://image.tmdb.org/t/p/w1280/4HodYYKEIsGOdhrGi2Ud6XGjBn1.jpg",
        "The Roy family controls the biggest media and entertainment company.", "8.8", "MAX"),
    Film("d3", 103, "The Mandalorian", "2023", "Series", poster("/z4nriHz7ioQ0gX9Kj5kX5kX5kX5.jpg"),
        "https://image.tmdb.org/t/p/w1280/xOMo8BRK7PfcJv9JCnx7s5hj0PX.jpg",
        "A lone bounty hunter in the outer reaches of the galaxy.", "8.7", "Disney+"),
    Film("d4", 104, "Stranger Things", "2022", "Series", poster("/b0PlSFdDwbyK0cf5RxwDpaOJQvQ.jpg"),
        "https://image.tmdb.org/t/p/w1280/rAiYTfKGqDCRIIqo664sY9XZIvQ.jpg",
        "When a young boy vanishes, a small town uncovers a mystery.", "8.7", "Netflix")
)

// ---------- Helpers ----------
private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

private val SXE = Regex("""[sS](\d{1,2})[\s._-]?[eE](\d{1,3})""")
private fun parseSeasonEpisode(name: String): Pair<Int, Int>? {
    val m = SXE.find(name) ?: return null
    val s = m.groupValues[1].toIntOrNull() ?: return null
    val e = m.groupValues[2].toIntOrNull() ?: return null
    return s to e
}

private fun fmtSize(b: Long): String {
    val gb = b / 1073741824.0
    return if (gb >= 1) "${Math.round(gb * 10) / 10.0} GB" else "${b / 1048576} MB"
}

private fun loadResume(p: SharedPreferences): List<Resume> = try {
    val arr = JSONArray(p.getString("resume", "[]"))
    (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        Resume(o.str("name"), o.str("image"), o.str("url"),
            o.optInt("tid", -1), o.optInt("fid", -1), o.optLong("pos"), o.optLong("dur"))
    }
} catch (e: Exception) { emptyList() }

private fun saveResume(p: SharedPreferences, list: List<Resume>) {
    val arr = JSONArray()
    list.forEach {
        arr.put(
            JSONObject().put("name", it.name).put("image", it.image).put("url", it.url)
                .put("tid", it.torrentId).put("fid", it.fileId).put("pos", it.pos).put("dur", it.dur)
        )
    }
    p.edit().putString("resume", arr.toString()).apply()
}

private fun httpGet(urlStr: String, bearer: String? = null): String {
    val c = URL(urlStr).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 20000
    c.setRequestProperty("Accept", "application/json")
    if (bearer != null) c.setRequestProperty("Authorization", "Bearer \$bearer")
    try {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299
