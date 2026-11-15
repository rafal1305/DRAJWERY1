package pl.example.trasa

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Czcionka marki: Big Shoulders (SIL OFL) – wąska, „drogowa”, jak napisy na tablicach i naczepach. */
val BrandFont = FontFamily(Font(R.font.bigshoulders_bold, FontWeight.Bold), Font(R.font.bigshoulders_regular, FontWeight.Normal))

private val Scheme = darkColorScheme(
    primary = Color(0xFFFFC400), onPrimary = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF3D3200), onPrimaryContainer = Color(0xFFFFE082),
    secondary = Color(0xFFFF8F00), onSecondary = Color(0xFF1A1A1A),
    background = Color(0xFF151517), onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF212125), onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF2E2E33), onSurfaceVariant = Color(0xFFD6D6DB),
    error = Color(0xFFFF7B6E), outline = Color(0xFF9A9AA0),
)

@Composable
fun DrajweryTheme(content: @Composable () -> Unit) {
    val t = Typography()
    fun brand(s: androidx.compose.ui.text.TextStyle, size: TextUnit) =
        s.copy(fontFamily = BrandFont, fontWeight = FontWeight.Bold, fontSize = size, letterSpacing = 0.6.sp)
    val typo = t.copy(
        headlineLarge = brand(t.headlineLarge, 44.sp), headlineMedium = brand(t.headlineMedium, 38.sp),
        headlineSmall = brand(t.headlineSmall, 32.sp), titleLarge = brand(t.titleLarge, 28.sp),
        titleMedium = brand(t.titleMedium, 22.sp), titleSmall = brand(t.titleSmall, 19.sp),
    )
    MaterialTheme(colorScheme = Scheme, typography = typo, content = content)
}

/** Karta lekko przezroczysta – tło z asfaltem prześwituje, a tekst pozostaje czytelny. */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f)), content = content)

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))

@Composable
fun BrandTitle(size: TextUnit = 30.sp, modifier: Modifier = Modifier) =
    Text("DRAJWERY", modifier, color = MaterialTheme.colorScheme.primary, fontSize = size, fontWeight = FontWeight.Bold,
        fontFamily = BrandFont, letterSpacing = 1.sp)

/** Ślad pary kół: łagodny łuk przez cały ekran (x zależy od położenia w pionie). */
private fun trackPath(w: Float, h: Float, offX: Float, amp: Float, phase: Float, tilt: Float, freq: Float): Path {
    val p = Path(); val steps = 120
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val y = -0.1f * h + t * 1.2f * h
        val x = w * (0.5f + tilt * (t - 0.5f)) + amp * w * sin(2.0 * PI * freq * t + phase).toFloat() + offX
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    return p
}

/** Tło aplikacji: ciemny asfalt z ziarnem, ślady opon (bieżnik) i żółta przerywana linia przy krawędzi. Rysowane raz na rozmiar ekranu. */
@Composable
fun AsphaltBackground(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().drawWithCache {
        val w = size.width; val h = size.height
        val rnd = Random(42)
        val n = ((w * h) / 1100f).toInt().coerceIn(600, 5000)
        fun pts(k: Int) = List(k) { Offset(rnd.nextFloat() * w, rnd.nextFloat() * h) }
        val dark = pts(n); val light = pts(n); val coarse = pts(n / 6)
        val gap = w * 0.19f; val tw = w * 0.052f
        val tracks = listOf(
            // główna para kół
            Triple(trackPath(w, h, -gap / 2, 0.17f, 0.9f, 0.10f, 0.55f), tw, 1.0f),
            Triple(trackPath(w, h, gap / 2, 0.17f, 0.9f, 0.10f, 0.55f), tw, 1.0f),
            // druga, przecinająca się para – słabsza
            Triple(trackPath(w, h, w * 0.12f - gap * 0.42f, 0.12f, 2.2f, -0.45f, 0.42f), tw * 0.8f, 0.6f),
            Triple(trackPath(w, h, w * 0.12f + gap * 0.42f, 0.12f, 2.2f, -0.45f, 0.42f), tw * 0.8f, 0.6f),
        )
        val tread = PathEffect.dashPathEffect(floatArrayOf(w * 0.0065f, w * 0.0085f), 0f)
        val lane = PathEffect.dashPathEffect(floatArrayOf(60f, 50f), 0f)
        val lanePath = Path().apply { moveTo(w * 0.048f, -40f); lineTo(w * 0.048f, h + 40f) }
        onDrawBehind {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF1B1B1E), Color(0xFF2B2B2F), Color(0xFF1E1E21))))
            drawPoints(dark, PointMode.Points, Color.Black.copy(alpha = 0.35f), 2.5f, StrokeCap.Round)
            drawPoints(light, PointMode.Points, Color(0xFF9A9AA0).copy(alpha = 0.22f), 2.2f, StrokeCap.Round)
            drawPoints(coarse, PointMode.Points, Color(0xFFB0B0B5).copy(alpha = 0.18f), 4f, StrokeCap.Round)
            tracks.forEach { (path, width, a) ->
                drawPath(path, Color.Black.copy(alpha = 0.30f * a), style = Stroke(width * 1.5f, cap = StrokeCap.Round))      // miękki pas gumy
                drawPath(path, Color.Black.copy(alpha = 0.66f * a), style = Stroke(width, pathEffect = tread))                  // bloki bieżnika
            }
            drawPath(lanePath, Color(0xFFF2C200).copy(alpha = 0.35f), style = Stroke(10f, pathEffect = lane))                   // linia przy krawędzi
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                center = Offset(w / 2, h / 2), radius = max(w, h) * 0.75f))                                                     // winieta
            drawRect(Color.Black.copy(alpha = 0.25f))                                                                           // przyciemnienie pod tekst
        }
    })
}

/** Ekran startowy: nazwa aplikacji i pasek postępu ładowania na dole. */
@Composable
fun SplashScreen(userName: String, onDone: () -> Unit) {
    var target by remember { mutableStateOf(0f) }
    val p by animateFloatAsState(target, tween(2200, easing = LinearEasing), label = "ladowanie")
    LaunchedEffect(Unit) { target = 1f; delay(2400); onDone() }
    val label = when { p < 0.35f -> "Ładowanie ustawień…"; p < 0.7f -> "Wczytywanie zapisanych tras…"; p < 0.95f -> "Przygotowanie map…"; else -> "Gotowe" }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandTitle(size = 84.sp)
            Text("PLANER TRAS DLA KIEROWCÓW", color = Color(0xFFF0F0F0), fontFamily = BrandFont, fontSize = 20.sp, letterSpacing = 3.sp)
            if (userName.isNotBlank()) Text("Witaj, $userName", Modifier.padding(top = 16.dp), color = Color(0xFFD6D6DB), fontSize = 16.sp)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = Color(0xFFE6E6E6), fontSize = 14.sp)
            LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary, trackColor = Color.White.copy(alpha = 0.25f))
        }
    }
}
