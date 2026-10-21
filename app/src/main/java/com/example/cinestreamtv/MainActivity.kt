package com.example.cinestreamtv

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Ink = Color(0xFF08090D)
private val Panel = Color(0xFF171920)
private val Muted = Color(0xFFB6B8C2)
private val Red = Color(0xFFE50914)

data class Film(
    val title: String,
    val subtitle: String,
    val image: String,
    val overview: String,
    val category: String
)

private val demoFilms = listOf(
    Film("Interstellar", "2014  •  Sci-Fi", "https://image.tmdb.org/t/p/w780/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg",
        "A team travels beyond this galaxy to discover whether mankind has a future among the stars.", "Trending Movies"),
    Film("Dune: Part Two", "2024  •  Sci-Fi", "https://image.tmdb.org/t/p/w780/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg",
        "Paul Atreides unites with Chani and the Fremen while seeking revenge.", "Trending Movies"),
    Film("The Batman", "2022  •  Crime", "https://image.tmdb.org/t/p/w780/74xTEgt7R36Fpooo50r9T25onhq.jpg",
        "A detective uncovers corruption in Gotham City.", "Popular Movies"),
    Film("Oppenheimer", "2023  •  Drama", "https://image.tmdb.org/t/p/w780/ptpr0kGAckfQkJeJIt8st5dglvd.jpg",
        "The story of the physicist whose work changed the world.", "Popular Movies"),
    Film("Breaking Bad", "Series  •  Crime", "https://image.tmdb.org/t/p/w780/ztkUQFLlC19CCMYHW9o1zWhJRNq.jpg",
        "A chemistry teacher enters the world of crime.", "Popular Series"),
    Film("Dark", "Series  •  Mystery", "https://image.tmdb.org/t/p/w780/apbrbWs8M9lyOpJYYy0UXjN5J0E.jpg",
        "A disappearance exposes the secrets of a small German town.", "Popular Series"),
    Film("Inception", "2010  •  Sci-Fi", "https://image.tmdb.org/t/p/w780/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg",
        "A skilled thief enters dreams to steal secrets.", "Popular Movies")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 9, 13)
        window.navigationBarColor = android.graphics.Color.rgb(8, 9, 13)
        setContent { CineStreamApp() }
    }
}

@Composable
fun CineStreamApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cinestream", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("torbox_token", "") ?: "") }
    var screen by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf(demoFilms.first()) }
    var query by remember { mutableStateOf("") }
    var directUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Demo catalogue loaded. Add your own TorBox token in Settings.") }
    var torrentJson by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val myList = remember { mutableStateListOf<String>() }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink, surface = Panel, primary = Red,
            onBackground = Color.White, onSurface = Color.White
        )
    ) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            Column(Modifier.fillMaxSize()) {
                TopBar(screen, onNavigate = { screen = it }, onSearch = { screen = "Search" })
                when (screen) {
                    "Home" -> HomeScreen(
                        films = demoFilms, myList = myList,
                        onFilm = { selected = it; screen = "Details" },
                        onPlayUrl = { screen = "Player" }
                    )
                    "Movies" -> CatalogueScreen("Movies", demoFilms.filter { it.category.contains("Movies") }, onFilm = { selected = it; screen = "Details" })
                    "Shows" -> CatalogueScreen("Shows", demoFilms.filter { it.category.contains("Series") }, onFilm = { selected = it; screen = "Details" })
                    "My List" -> CatalogueScreen("My List", demoFilms.filter { myList.contains(it.title) }, onFilm = { selected = it; screen = "Details" })
                    "Search" -> SearchScreen(query, { query = it }, demoFilms.filter { it.title.contains(query, true) }, onFilm = { selected = it; screen = "Details" })
                    "Details" -> DetailsScreen(selected, myList.contains(selected.title),
                        onToggleList = { if (myList.contains(selected.title)) myList.remove(selected.title) else myList.add(selected.title) },
                        onPlay = { screen = "Player" })
                    "Player" -> PlayerScreen(
                        directUrl = directUrl, onUrl = { directUrl = it },
                        onStatus = { status = it }, status = status
                    )
                    "Settings" -> SettingsScreen(
                        token = token, onToken = { token = it; prefs.edit().putString("torbox_token", it).apply() },
                        status = status, torrentJson = torrentJson, loading = isLoading,
                        onTest = {
                            scope.launch {
                                isLoading = true
                                val result = TorBoxApi.testToken(token)
                                status = result.first
                                torrentJson = result.second
                                isLoading = false
                            }
                        },
                        onRefresh = {
                            scope.launch {
                                isLoading = true
                                val result = TorBoxApi.getTorrents(token)
                                status = result.first
                                torrentJson = result.second
                                isLoading = false
                            }
                        }
                    )
                    else -> HomeScreen(demoFilms, myList, { selected = it; screen = "Details" }, { screen = "Player" })
                }
            }
        }
    }
}

@Composable
private fun TopBar(current: String, onNavigate: (String) -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink).padding(horizontal = 42.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        Text("CINE", color = Red, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("STREAM", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(18.dp))
        listOf("Home", "Shows", "Movies", "My List").forEach { item ->
            Text(
                item,
                color = if (current == item) Color.White else Muted,
                fontWeight = if (current == item) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onNavigate(item) }
                    .focusable().padding(horizontal = 7.dp, vertical = 8.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Text("⌕  Search", color = Color.White, modifier = Modifier.clickable { onSearch() }.focusable().padding(8.dp))
        Text("⚙", color = Muted, modifier = Modifier.clickable { onNavigate("Settings") }.focusable().padding(8.dp))
    }
}

@Composable
private fun HomeScreen(
    films: List<Film>,
    myList: List<String>,
    onFilm: (Film) -> Unit,
    onPlayUrl: () -> Unit
) {
    val hero = films.first()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(350.dp)) {
            AsyncImage(model = hero.image, contentDescription = hero.title, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Ink.copy(alpha = .98f), Ink.copy(alpha = .78f), Color.Transparent))
            ))
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .2f), Ink))
            ))
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 48.dp, end = 32.dp).widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("FEATURED TONIGHT", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(hero.title, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
                Text(hero.subtitle, color = Muted, fontSize = 16.sp)
                Text(hero.overview, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("▶  Play") { onPlayUrl() }
                    OutlinedButton(onClick = { onFilm(hero) }, border = BorderStroke(1.dp, Color.LightGray)) {
                        Text("More Info", color = Color.White)
                    }
                }
            }
        }
        val groups = listOf(
            "Trending Now" to films,
            "Popular Movies" to films.filter { it.category.contains("Movies") },
            "Popular Series" to films.filter { it.category.contains("Series") },
            "My List" to films.filter { myList.contains(it.title) }
        )
        groups.forEach { (title, items) ->
            if (items.isNotEmpty()) PosterRow(title, items, onFilm)
        }
        Spacer(Modifier.height(34.dp))
    }
}

@Composable
private fun PosterRow(title: String, films: List<Film>, onFilm: (Film) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 42.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 42.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(films) { film ->
                var focused by remember { mutableStateOf(false) }
                Column(Modifier.width(155.dp).onFocusChanged { focused = it.isFocused }
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (focused) Color(0xFF282B34) else Color.Transparent)
                    .clickable { onFilm(film) }.focusable().padding(4.dp)) {
                    AsyncImage(model = film.image, contentDescription = film.title,
                        modifier = Modifier.fillMaxWidth().height(218.dp).clip(RoundedCornerShape(8.dp)))
                    Text(film.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp, start = 2.dp, bottom = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun CatalogueScreen(title: String, films: List<Film>, onFilm: (Film) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp)) {
        Text(title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 42.dp, vertical = 14.dp))
        PosterRow("Browse", films, onFilm)
    }
}

@Composable
private fun SearchScreen(query: String, onQuery: (String) -> Unit, films: List<Film>, onFilm: (Film) -> Unit) {
    Column(Modifier.fillMaxSize().padding(42.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Search", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("Movie or series title") }, singleLine = true)
        PosterRow("Results", films, onFilm)
    }
}

@Composable
private fun DetailsScreen(film: Film, inList: Boolean, onToggleList: () -> Unit, onPlay: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(42.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        AsyncImage(model = film.image, contentDescription = film.title,
            modifier = Modifier.width(235.dp).fillMaxHeight(.86f).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(film.title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
            Text(film.subtitle, color = Muted, fontSize = 17.sp)
            Text(film.overview, color = Color.White, fontSize = 19.sp, lineHeight = 28.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("▶  Play") { onPlay() }
                OutlinedButton(onClick = onToggleList) { Text(if (inList) "✓ In My List" else "＋ My List") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    token: String, onToken: (String) -> Unit, status: String, torrentJson: String,
    loading: Boolean, onTest: () -> Unit, onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(42.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("TorBox integration", color = Red, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Paste your TorBox API key. It is stored locally on this device for this prototype.", color = Muted, fontSize = 15.sp)
        OutlinedTextField(value = token, onValueChange = onToken, label = { Text("TorBox API key") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onTest, enabled = !loading && token.isNotBlank()) { Text("Test connection") }
            OutlinedButton(onClick = onRefresh, enabled = !loading && token.isNotBlank()) { Text("Load my torrents") }
        }
        if (loading) CircularProgressIndicator(color = Red)
        Text(status, color = Color.White, fontSize = 15.sp)
        if (torrentJson.isNotBlank()) {
            Text("API response preview", color = Muted, fontSize = 14.sp)
            Text(torrentJson.take(2200), color = Color.LightGray, fontSize = 12.sp)
        }
        Text("Playback: this prototype plays direct media URLs entered on the Player screen. TorBox torrent-file selection and adaptive stream handling are the next integration step.", color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun PlayerScreen(directUrl: String, onUrl: (String) -> Unit, onStatus: (String) -> Unit, status: String) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Player", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Prototype playback accepts a direct, authorised media URL (for example an MP4 or supported HLS stream).", color = Muted, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = directUrl, onValueChange = onUrl, label = { Text("Direct media URL") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = {
                if (directUrl.startsWith("https://") || directUrl.startsWith("http://")) {
                    try {
                        player?.release()
                        player = ExoPlayer.Builder(context).build().also {
                            it.setMediaItem(MediaItem.fromUri(Uri.parse(directUrl)))
                            it.prepare()
                            it.playWhenReady = true
                        }
                        playing = true
                        onStatus("Playback started. If video does not appear, check the URL and format.")
                    } catch (e: Exception) { onStatus("Could not start playback: ${e.message}") }
                } else onStatus("Enter a valid http:// or https:// media URL.")
            }) { Text("Play URL") }
        }
        if (playing && player != null) {
            AndroidView(factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = true
                }
            }, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Panel), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("▶", color = Red, fontSize = 48.sp)
                    
