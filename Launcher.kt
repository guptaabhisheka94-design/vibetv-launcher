  package dev.vibetv.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Bumped on every resume so the app list refreshes after installs/disables.
    private val reloadTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeScreen(reloadTick.intValue) }
    }

    override fun onResume() {
        super.onResume()
        reloadTick.intValue = reloadTick.intValue + 1
    }
}

data class TvApp(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    val image: ImageBitmap,
    val isBanner: Boolean,
    val accent: Color,
)

object AppRepository {

    // Your favourite apps come first, in this order (matched against the app name).
    private val favourites = listOf(
        "netflix", "prime video", "youtube", "hotstar", "xstream",
        "apple tv", "vzy", "tata play",
    )

    // Apps that should never show up as tiles.
    private val hidden = setOf(
        "com.android.camera2",
        "com.android.documentsui",
        "com.android.tv",              // Live Channels (reachable via the Inputs shortcut)
        "com.droidlogic.droidlivetv",  // Mi TV live TV / inputs (Inputs shortcut)
    )

    private const val PREFS = "vibetv"

    fun load(context: Context): List<TvApp> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val result = ArrayList<TvApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val info = ri.activityInfo ?: continue
                val pkg = info.packageName
                if (pkg == context.packageName || pkg in hidden) continue
                if (!seen.add(pkg)) continue
                result += toApp(pm, ri)
            }
        }
        return result.sortedWith(
            compareBy<TvApp>({ rank(it.label) }, { it.label.lowercase() })
        )
    }

    private fun rank(label: String): Int {
        val l = label.lowercase()
        val i = favourites.indexOfFirst { l.contains(it) }
        return if (i == -1) 100 else i
    }

    private fun toApp(pm: PackageManager, ri: ResolveInfo): TvApp {
        val info = ri.activityInfo
        val label = ri.loadLabel(pm).toString()
        val banner: Drawable? = info.loadBanner(pm)
        val isBanner = banner != null
        val drawable: Drawable = banner ?: info.loadIcon(pm)
        val bmp: Bitmap = runCatching {
            if (isBanner) drawable.toBitmap(480, 270) else drawable.toBitmap(192, 192)
        }.getOrElse { Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888) }

        return TvApp(
            pkg = info.packageName,
            label = label,
            component = ComponentName(info.packageName, info.name),
            image = bmp.asImageBitmap(),
            isBanner = isBanner,
            accent = accentOf(bmp),
        )
    }

    // Average colour of the artwork (ignoring near-black / near-white), tuned to look good as a backdrop.
    private fun accentOf(bmp: Bitmap): Color {
        val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (x in 0 until 32) {
            for (y in 0 until 32) {
                val p = small.getPixel(x, y)
                if (AColor.alpha(p) < 200) continue
                val rr = AColor.red(p)
                val gg = AColor.green(p)
                val bb = AColor.blue(p)
                if (maxOf(rr, gg, bb) < 40 || minOf(rr, gg, bb) > 225) continue
                r += rr
                g += gg
                b += bb
                n++
            }
        }
        if (n == 0) return Color(0xFF3B4CCA)
        val hsv = FloatArray(3)
        AColor.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.9f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.75f)
        return Color(AColor.HSVToColor(hsv))
    }

    // ---- Recently used (tracked only from this launcher, no special permission needed) ----

    fun recents(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("recents", "")
            .orEmpty()
            .split(",")
            .filter { it.isNotBlank() }

    private fun pushRecent(context: Context, pkg: String) {
        val list = (listOf(pkg) + recents(context).filter { it != pkg }).take(8)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("recents", list.joinToString(","))
            .apply()
    }

    // ---- Actions ----

    fun launch(context: Context, app: TvApp) {
        pushRecent(context, app.pkg)
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = app.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // Opens Live TV, from where the TV inputs (HDMI etc.) can be picked.
    fun openInputs(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in listOf("com.droidlogic.droidlivetv", "com.android.tv")) {
            val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(i) }.isSuccess) return true
            }
        }
        return false
    }
}

// UI scale factor so the layout looks the same on 1080p / 4K TVs with different densities.
private val LocalU = compositionLocalOf { 1f }

@Composable
private fun Int.dpu(): Dp = (this * LocalU.current).dp

@Composable
private fun Int.spu(): TextUnit = (this * LocalU.current).sp

@Composable
fun HomeScreen(reloadTick: Int) {
    val u = (LocalConfiguration.current.screenWidthDp / 1440f).coerceIn(0.75f, 1.8f)
    CompositionLocalProvider(LocalU provides u) {
        HomeContent(reloadTick)
    }
}

@Composable
private fun HomeContent(reloadTick: Int) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<TvApp>>(emptyList()) }
    var recentPkgs by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    val firstTile = remember { FocusRequester() }
    var didInitialFocus by remember { mutableStateOf(false) }

    LaunchedEffect(reloadTick) {
        apps = withContext(Dispatchers.Default) { AppRepository.load(context) }
        recentPkgs = AppRepository.recents(context)
    }

    LaunchedEffect(apps) {
        if (apps.isNotEmpty() && !didInitialFocus) {
            delay(250)
            runCatching { firstTile.requestFocus() }
            didInitialFocus = true
        }
    }

    // Home screen should never "back out".
    BackHandler { }

    val selected = apps.firstOrNull { it.pkg == selectedPkg } ?: apps.firstOrNull()
    val accent by animateColorAsState(
        targetValue = selected?.accent ?: Color(0xFF26304F),
        animationSpec = tween(700),
        label = "accent",
    )
    val recents = recentPkgs.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to accent.copy(alpha = 0.9f),
                    0.6f to Color(0xFF0B0C14),
                    1f to Color(0xFF05060A),
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dpu(), vertical = 28.dpu())
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dpu())) {
                    PillButton("Settings") { AppRepository.openSettings(context) }
                    PillButton("Live TV / Inputs") {
                        if (!AppRepository.openInputs(context)) {
                            Toast.makeText(context, "Inputs app not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                Clock()
            }

            Hero(selected, Modifier.weight(1f).fillMaxWidth())

            if (apps.isEmpty()) {
                Text("Loading apps...", color = Color.White.copy(alpha = 0.7f), fontSize = 18.spu())
            } else {
                AppRow(
                    title = "Apps",
                    apps = apps,
                    tileW = 232,
                    tileH = 130,
                    firstFocus = firstTile,
                    onFocus = { selectedPkg = it.pkg },
                    onOpen = { AppRepository.launch(context, it) },
                )
                if (recents.isNotEmpty()) {
                    AppRow(
                        title = "Recently used",
                        apps = recents,
                        tileW = 168,
                        tileH = 95,
                        firstFocus = null,
                        onFocus = { selectedPkg = it.pkg },
                        onOpen = { AppRepository.launch(context, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(app: TvApp?, modifier: Modifier) {
    Box(modifier) {
        if (app != null) {
            Crossfade(targetState = app, animationSpec = tween(450), label = "hero") { a ->
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(end = 40.dpu())
                    ) {
                        Text(
                            a.label,
                            color = Color.White,
                            fontSize = 52.spu(),
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dpu()))
                        Text(
                            "Press OK to open",
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 18.spu(),
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxHeight(0.9f)
                            .aspectRatio(16f / 9f)
                            .shadow(30.dpu(), RoundedCornerShape(24.dpu()))
                            .clip(RoundedCornerShape(24.dpu()))
                    ) {
                        TileImage(a)
                    }
                }
            }
        }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(15_000)
        }
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            timeFmt.format(now),
            color = Color.White,
            fontSize = 34.spu(),
            fontWeight = FontWeight.Light,
        )
        Text(
            dateFmt.format(now),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 15.spu(),
        )
    }
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "pill")
    Box(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dpu(), vertical = 10.dpu())
    ) {
        Text(
            text,
            color = if (focused) Color.Black else Color.White,
            fontSize = 16.spu(),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AppRow(
    title: String,
    apps: List<TvApp>,
    tileW: Int,
    tileH: Int,
    firstFocus: FocusRequester?,
    onFocus: (TvApp) -> Unit,
    onOpen: (TvApp) -> Unit,
) {
    val w = tileW.dpu()
    val h = tileH.dpu()
    Column {
        Text(
            title,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 18.spu(),
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dpu(), vertical = 18.dpu()),
            horizontalArrangement = Arrangement.spacedBy(22.dpu()),
        ) {
            itemsIndexed(apps, key = { _, a -> a.pkg }) { index, app ->
                AppTile(
                    app = app,
                    width = w,
                    height = h,
                    focusRequester = if (index == 0) firstFocus else null,
                    onFocus = { onFocus(app) },
                    onOpen = { onOpen(app) },
                )
            }
        }
    }
}

@Composable
private fun AppTile(
    app: TvApp,
    width: Dp,
    height: Dp,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onOpen: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.14f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tile",
    )
    val shape = RoundedCornerShape(16.dpu())

    Column(Modifier.width(width)) {
        Box(
            Modifier
                .size(width, height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = if (focused) 26.dp else 0.dp,
                    shape = shape,
                    ambientColor = app.accent,
                    spotColor = app.accent,
                )
                .clip(shape)
                .border(3.dpu(), if (focused) Color.White else Color.Transparent, shape)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .clickable(onClick = onOpen)
        ) {
            TileImage(app)
        }
        Spacer(Modifier.height(14.dpu()))
        Text(
            app.label,
            color = Color.White.copy(alpha = if (focused) 1f else 0.6f),
            fontSize = 14.spu(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TileImage(app: TvApp) {
    if (app.isBanner) {
        Image(
            app.image,
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(app.accent, app.accent.copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                app.image,
                null,
                Modifier
                    .fillMaxHeight(0.6f)
                    .aspectRatio(1f),
            )
        }
    }
}
