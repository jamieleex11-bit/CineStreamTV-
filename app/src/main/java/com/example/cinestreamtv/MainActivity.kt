package com.example.cinestreamtv
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FrameTvApp() }
    }
}

// ---------- Data ----------

data class Title(val name: String, val meta: String, val c1: Color, val c2: Color)

enum class Tab(val label: String) {
    Home("Home"), Search("Search"), Library("Library"), Settings("Settings")
}

private val sample = listOf(
    Title("Midnight Harbor", "2024 · Thriller", Color(0xFF3A1C71), Color(0xFFD76D77)),
    Title("Solar Drift", "2025 · Sci-Fi", Color(0xFF0F2027), Color(0xFF2C7A9B)),
    Title("The Quiet Hours", "2023 · Drama", Color(0xFF42275A), Color(0xFF734B6D)),
    Title("Paper Lanterns", "2024 · Fantasy", Color(0xFFFF512F), Color(0xFFDD2476)),
    Title("Glass Houses", "2022 · Mystery", Color(0xFF134E5E), Color(0xFF71B280)),
    Title("Northbound", "2025 · Adventure", Color(0xFF283E51), Color(0xFF4B79A1)),
    Title("Velvet Sky", "2021 · Romance", Color(0xFF5F2C82), Color(0xFF49A09D)),
    Title("Static", "2024 · Horror", Color(0xFF232526), Color(0xFF8E2DE2))
)

// ---------- Glass helpers ----------

fun Modifier.glass(shape: Shape, strong: Boolean): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (strong) 0.30f else 0.14f),
                Color.White.copy(alpha = if (strong) 0.14f else 0.05f)
            )
        )
    )
    .border(
        1.dp,
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (strong) 0.80f else 0.45f),
                Color.White.copy(alpha = 0.06f)
            )
        ),
        shape
    )

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    selected: Boolean = false,
    focusScale: Float = 1.08f,
    onClick: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(180), label = "scale")
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .glass(shape, focused || selected)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        content = content
    )
}

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        focusScale = 1.06f,
        onClick = onClick
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 14.dp)
        )
    }
}

// ---------- Root ----------

@Composable
fun FrameTvApp() {
    var tab by remember { mutableStateOf(Tab.Home) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07070D))
    ) {
        // soft colored glow blobs behind the glass
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF5B3FD6).copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(300f, 200f),
                        radius = 1100f
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF1FA2D8).copy(alpha = 0.40f), Color.Transparent),
                        center = Offset(1700f, 900f),
                        radius = 1200f
                    )
                )
        )

        Column(Modifier.fillMaxSize()) {
            TopNav(tab) { tab = it }
            when (tab) {
                Tab.Home -> HomeScreen()
                Tab.Search -> SearchScreen()
                Tab.Library -> LibraryScreen()
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}

@Composable
fun TopNav(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, start = 56.dp, end = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Frame TV", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .glass(RoundedCornerShape(50), false)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Tab.values().forEach { t ->
                GlassSurface(
                    shape = RoundedCornerShape(50),
                    selected = t == current,
                    focusScale = 1.05f,
                    onClick = { onSelect(t) }
                ) {
                    Text(
                        t.label,
                        color = Color.White.copy(alpha = if (t == current) 1f else 0.75f),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(110.dp))
    }
}

// ---------- Home ----------

@Composable
fun HomeScreen() {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    LazyColumn(
        contentPadding = PaddingValues(top = 24.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp)
    ) {
        item {
            val hero = sample[0]
            Box(
                Modifier
                    .padding(horizontal = 56.dp)
                    .fillMaxWidth()
                    .height(340.dp)
                    .clip(RoundedCornerShape(36.dp))
                    .background(Brush.linearGradient(listOf(hero.c1, hero.c2)))
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(0.6f), Color.White.copy(0.05f))),
                        RoundedCornerShape(36.dp)
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Black.copy(0.55f), Color.Transparent)
                            )
                        )
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(40.dp)
                ) {
                    Text("FEATURED", color = Color.White.copy(0.7f), fontSize = 14.sp, letterSpacing = 3.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(hero.name, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Text(hero.meta, color = Color.White.copy(0.8f), fontSize = 18.sp)
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        GlassButton("▶  Play", Modifier.focusRequester(playFocus))
                        GlassButton("+  My List")
                    }
                }
            }
        }
        item { PosterRow("Continue Watching", sample.shuffled()) }
        item { PosterRow("Trending Now", sample) }
        item { PosterRow("Because You Watched", sample.reversed()) }
    }
}

@Composable
fun PosterRow(title: String, list: List<Title>) {
    Column {
        Text(
            title,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 56.dp, bottom = 14.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            items(list) { PosterCard(it) }
        }
    }
}

@Composable
fun PosterCard(t: Title) {
    GlassSurface(
        modifier = Modifier
            .width(200.dp)
            .height(290.dp),
        shape = RoundedCornerShape(26.dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(t.c1, t.c2)))
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(0.75f)),
                        startY = 300f
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(t.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(t.meta, color = Color.White.copy(0.7f), fontSize = 13.sp)
        }
    }
}

// ---------- Search ----------

@Composable
fun SearchScreen() {
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val results = if (query.isBlank()) sample else sample.filter { it.name.contains(query, true) }

    Column(Modifier.padding(horizontal = 56.dp, vertical = 24.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(30.dp), focused)
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            if (query.isEmpty()) {
                Text("Search movies and shows", color = Color.White.copy(0.5f), fontSize = 22.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            items(results) { PosterCard(it) }
        }
    }
}

// ---------- Library ----------

@Composable
fun LibraryScreen() {
    LazyColumn(
        contentPadding = PaddingValues(top = 24.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp)
    ) {
        item { PosterRow("My List", sample.take(5)) }
        item { PosterRow("Watched", sample.takeLast(4)) }
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

    Column(
        Modifier
            .padding(horizontal = 56.dp, vertical = 24.dp)
            .widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Settings", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Connect your service", color = Color.White.copy(0.65f), fontSize = 16.sp)

        GlassField("TorBox API key", torbox) { torbox = it; saved = false }
        GlassField("Real-Debrid API key", rd) { rd = it; saved = false }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassButton("Save") {
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

@Composable
fun GlassField(label: String, value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Color.White.copy(0.7f), fontSize = 15.sp)
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
