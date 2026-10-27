package com.example.cinestreamtv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FrameTvApp() }
    }
}

// ---------- Data ----------

data class Title(
    val name: String,
    val genre: String,
    val year: String,
    val desc: String,
    val c1: Color,
    val c2: Color,
    val progress: Float = 0f
)

enum class Tab(val label: String) {
    Home("Home"), Search("Search"), Library("Library"), Settings("Settings")
}

private val sample = listOf(
    Title(
        "Midnight Harbor", "Thriller", "2024",
        "A night-shift harbor master uncovers a smuggling ring hiding in plain sight, and the tide is running out.",
        Color(0xFF5B2A86), Color(0xFFE5566D), 0.62f
    ),
    Title(
        "Solar Drift", "Sci-Fi", "2025",
        "A salvage crew drifting past the sun finds a ship that should have been lost a century ago.",
        Color(0xFF0B3C5D), Color(0xFF2EC4B6), 0.35f
    ),
    Title(
        "The Quiet Hours", "Drama", "2023",
        "Three strangers share one sleepless night in a small town and change each other's lives.",
        Color(0xFF3D2C8D), Color(0xFFB983FF), 0.8f
    ),
    Title(
        "Paper Lanterns", "Fantasy", "2024",
        "When the festival lights go out, a young lantern maker must follow them into the sky.",
        Color(0xFFFF5F6D), Color(0xFFFFC371), 0.15f
    ),
    Title(
        "Glass Houses", "Mystery", "2022",
        "Every window in the street hides a secret, and someone is watching all of them.",
        Color(0xFF0F5E6E), Color(0xFF8FD694), 0.5f
    ),
    Title(
        "Northbound", "Adventure", "2025",
        "Two rivals share one map and one frozen road to the top of the world.",
        Color(0xFF1D3557), Color(0xFF5AA9E6)
    ),
    Title(
        "Velvet Sky", "Romance", "2021",
        "A touring pianist and a night-train conductor keep missing each other across Europe.",
        Color(0xFF7B2FF7), Color(0xFFF107A3)
    ),
    Title(
        "Static", "Horror", "2024",
        "The signal only comes in after midnight, and it knows your name.",
        Color(0xFF1B1B2F), Color(0xFF9D4EDD)
    )
)

// ---------- Glass helpers ----------

fun Modifier.glass(shape: Shape, strong: Boolean): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (strong) 0.30f else 0.13f),
                Color.White.copy(alpha = if (strong) 0.14f else 0.05f)
            )
        )
    )
    .border(
        1.dp,
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (strong) 0.80f else 0.40f),
                Color.White.copy(alpha = 0.05f)
            )
        ),
        shape
    )

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "pill")
    val shape = RoundedCornerShape(50)
    val look = if (primary) {
        Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = if (focused) 1f else 0.86f))
    } else {
        Modifier.glass(shape, focused)
    }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .then(look)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (primary) Color.Black else Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 34.dp, vertical = 15.dp)
        )
    }
}

// ---------- Root ----------

@Composable
fun FrameTvApp() {
    var tab by remember { mutableStateOf(Tab.Home) }
    var hero by remember { mutableStateOf(sample[0]) }

    val c1 by animateColorAsState(hero.c1, tween(800), label = "c1")
    val c2 by animateColorAsState(hero.c2, tween(800), label = "c2")
    val base = Color(0xFF050509)

    Box(
        Modifier
            .fillMaxSize()
            .background(base)
    ) {
        // glow in the colors of whatever is focused
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(c1.copy(alpha = 0.85f), Color.Transparent),
                        center = Offset(1500f, 150f),
                        radius = 1500f
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(c2.copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(200f, 700f),
                        radius = 1300f
                    )
                )
        )
        // darken toward the bottom so rows stay readable
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.40f to Color.Transparent,
                        1f to base.copy(alpha = 0.92f)
                    )
                )
        )

        Column(Modifier.fillMaxSize()) {
            TopNav(tab) { tab = it }
            when (tab) {
                Tab.Home -> HomeScreen(hero) { hero = it }
                Tab.Search -> SearchScreen { hero = it }
                Tab.Library -> LibraryScreen { hero = it }
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}

// ---------- Top nav ----------

@Composable
fun TopNav(current: Tab, onSelect: (Tab) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, start = 64.dp, end = 64.dp)
    ) {
        Row(
            Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .glass(RoundedCornerShape(14.dp), true),
                contentAlignment = Alignment.Center
            ) {
                Text("F", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(14.dp))
            Text("Frame TV", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(
            Modifier
                .align(Alignment.Center)
                .glass(RoundedCornerShape(50), false)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Tab.values().forEach { t ->
                NavTab(t.label, t == current) { onSelect(t) }
            }
        }
    }
}

@Composable
fun NavTab(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(160), label = "tab")
    val shape = RoundedCornerShape(50)
    val bg = when {
        focused -> Color.White.copy(alpha = 0.92f)
        selected -> Color.White.copy(alpha = 0.22f)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
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
            color = if (focused) Color.Black else Color.White.copy(alpha = if (selected) 1f else 0.7f),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp)
        )
    }
}

// ---------- Home ----------

@Composable
fun HomeScreen(hero: Title, onFocus: (Title) -> Unit) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    LazyColumn(
        contentPadding = PaddingValues(top = 10.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .padding(horizontal = 64.dp)
            ) {
                // huge faint letter on the right
                Crossfade(
                    targetState = hero,
                    animationSpec = tween(600),
                    modifier = Modifier.align(Alignment.CenterEnd),
                    label = "letter"
                ) { h ->
                    Text(
                        h.name.first().toString(),
                        color = Color.White.copy(alpha = 0.08f),
                        fontSize = 340.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(end = 40.dp)
                    )
                }

                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .widthIn(max = 760.dp)
                ) {
                    Crossfade(targetState = hero, animationSpec = tween(500), label = "hero") { h ->
                        Column {
                            Text(
                                "FEATURED  ·  ${h.genre.uppercase()}",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                letterSpacing = 3.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                h.name,
                                color = Color.White,
                                fontSize = 66.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(h.year, color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp)
                                Spacer(Modifier.width(14.dp))
                                Box(
                                    Modifier
                                        .glass(RoundedCornerShape(6.dp), false)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("4K", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier
                                        .glass(RoundedCornerShape(6.dp), false)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("HDR", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                h.desc,
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 19.sp,
                                lineHeight = 27.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PillButton("▶  Play", Modifier.focusRequester(playFocus), primary = true)
                        PillButton("+  My List")
                    }
                }
            }
        }

        item { PosterRow("Continue Watching", sample.filter { it.progress > 0f }, wide = true, onFocus = onFocus) }
        item { PosterRow("Trending Now", sample, wide = false, onFocus = onFocus) }
        item { PosterRow("Because You Watched", sample.reversed(), wide = false, onFocus = onFocus) }
    }
}

// ---------- Rows & cards ----------

@Composable
fun PosterRow(title: String, list: List<Title>, wide: Boolean, onFocus: (Title) -> Unit) {
    Column {
        Text(
            title,
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 64.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 64.dp, vertical = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            items(list) { PosterCard(it, wide, onFocus) }
        }
    }
}

@Composable
fun PosterCard(t: Title, wide: Boolean, onFocus: (Title) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.10f else 1f, tween(200), label = "card")
    val shape = RoundedCornerShape(26.dp)
    val w = if (wide) 360.dp else 210.dp
    val h = if (wide) 202.dp else 300.dp

    Column(
        Modifier
            .width(w)
            .graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(h)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus(t)
                }
                .clip(shape)
                .background(Brush.linearGradient(listOf(t.c1, t.c2)))
                .border(
                    if (focused) 3.dp else 1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (focused) 1f else 0.45f),
                            Color.White.copy(alpha = if (focused) 0.55f else 0.05f)
                        )
                    ),
                    shape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            // light bloom in the top-left corner
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                            center = Offset(40f, 40f),
                            radius = 420f
                        )
                    )
            )
            // big faint initial
            Text(
                t.name.first().toString(),
                color = Color.White.copy(alpha = 0.18f),
                fontSize = if (wide) 150.sp else 190.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp)
            )
            // bottom scrim
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f)
                        )
                    )
            )
            if (wide && t.progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.28f))
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
        Spacer(Modifier.height(12.dp))
        Text(
            t.name,
            color = Color.White.copy(alpha = if (focused) 1f else 0.75f),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Text(
            "${t.genre} · ${t.year}",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

// ---------- Search ----------

@Composable
fun SearchScreen(onFocus: (Title) -> Unit) {
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val results = if (query.isBlank()) sample else sample.filter { it.name.contains(query, true) }

    Column(Modifier.padding(top = 30.dp)) {
        Box(
            Modifier
                .padding(horizontal = 64.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(32.dp), focused)
                .padding(horizontal = 32.dp, vertical = 22.dp)
        ) {
            if (query.isEmpty()) {
                Text("Search movies and shows", color = Color.White.copy(alpha = 0.5f), fontSize = 24.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 24.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
            )
        }
        Spacer(Modifier.height(16.dp))
        if (results.isEmpty()) {
            Text(
                "No results",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 20.sp,
                modifier = Modifier.padding(start = 64.dp, top = 20.dp)
            )
        } else {
            PosterRow(
                if (query.isBlank()) "Top Searches" else "Results",
                results,
                wide = false,
                onFocus = onFocus
            )
        }
    }
}

// ---------- Library ----------

@Composable
fun LibraryScreen(onFocus: (Title) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(top = 30.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PosterRow("My List", sample.take(5), wide = false, onFocus = onFocus) }
        item { PosterRow("Watched", sample.takeLast(4), wide = true, onFocus = onFocus) }
    }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("frametv", Context.MODE_PRIVATE) }
    var torbox by remember { mutableStateOf(prefs.getString("torbox", "") ?: "") }
    var rd by remember { mutableStateOf(prefs.getString("realdebrid", "") ?: "") }
    var saved by remember { mutableStateOf(false) }

    Box(
        Modifier
            .padding(horizontal = 64.dp, vertical = 30.dp)
            .widthIn(max = 820.dp)
            .glass(RoundedCornerShape(36.dp), false)
            .padding(40.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Settings", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Text("Connect your service", color = Color.White.copy(alpha = 0.65f), fontSize = 17.sp)

            GlassField("TorBox API key", torbox) { torbox = it; saved = false }
            GlassField("Real-Debrid API key", rd) { rd = it; saved = false }

            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillButton("Save", primary = true) {
                    prefs.edit()
                        .putString("torbox", torbox.trim())
                        .putString("realdebrid", rd.trim())
                        .apply()
                    saved = true
                }
                if (saved) Text("Saved", color = Color(0xFF7CE0A3), fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun GlassField(label: String, value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), focused)
                .padding(horizontal = 24.dp, vertical = 18.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                visualTransformation = if (focused) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
            )
        }
    }
}
