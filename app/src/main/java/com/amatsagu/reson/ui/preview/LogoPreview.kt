package com.amatsagu.reson.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class BubbleTheme(
    val id: String,
    val title: String,
    val subtitle: String,
    val bgColors: List<Color>,
    val bubbleColors: List<Color>,
    val highlightColor: Color = Color.White.copy(alpha = 0.55f),
    val accentColor: Color,
)

val PuffyThemes = listOf(
    BubbleTheme(
        id = "pastel_lilac",
        title = "1. Original Pastel Lilac",
        subtitle = "Soft whimsical lavender flowing into dreamy periwinkle.",
        bgColors = listOf(Color(0xFF161226), Color(0xFF090712)),
        bubbleColors = listOf(Color(0xFFD8B4FE), Color(0xFF818CF8)),
        highlightColor = Color.White.copy(alpha = 0.55f),
        accentColor = Color(0xFFC084FC),
    ),
    BubbleTheme(
        id = "sunset_lofi",
        title = "2. Sunset Lo-Fi (Peach & Coral)",
        subtitle = "Warm glowing peach melting into radiant coral pink.",
        bgColors = listOf(Color(0xFF1E0E18), Color(0xFF0D060B)),
        bubbleColors = listOf(Color(0xFFFFB088), Color(0xFFFF6584)),
        highlightColor = Color(0xFFFFF2EB).copy(alpha = 0.65f),
        accentColor = Color(0xFFFF6584),
    ),
    BubbleTheme(
        id = "electric_mint",
        title = "3. Electric Aurora (Mint & Cyan)",
        subtitle = "Ultra-fresh luminous mint flowing into vibrant electric cyan.",
        bgColors = listOf(Color(0xFF07191C), Color(0xFF030C0E)),
        bubbleColors = listOf(Color(0xFF6EE7B7), Color(0xFF06B6D4)),
        highlightColor = Color(0xFFECFDF5).copy(alpha = 0.70f),
        accentColor = Color(0xFF34D399),
    ),
    BubbleTheme(
        id = "cyber_violet",
        title = "4. Cyber Neon (Magenta & Violet)",
        subtitle = "Vibrant synthwave magenta blending into deep rich purple.",
        bgColors = listOf(Color(0xFF150D24), Color(0xFF090512)),
        bubbleColors = listOf(Color(0xFFF472B6), Color(0xFFA855F7)),
        highlightColor = Color(0xFFFAF5FF).copy(alpha = 0.70f),
        accentColor = Color(0xFFA855F7),
    ),
    BubbleTheme(
        id = "matcha_latte",
        title = "5. Matcha Pistachio (Sage & Forest)",
        subtitle = "Soothing organic matcha green with creamy pistachio highlights.",
        bgColors = listOf(Color(0xFF101C14), Color(0xFF070E0A)),
        bubbleColors = listOf(Color(0xFFA7F3D0), Color(0xFF34D399)),
        highlightColor = Color(0xFFF0FDF4).copy(alpha = 0.65f),
        accentColor = Color(0xFF4ADE80),
    ),
    BubbleTheme(
        id = "cotton_candy",
        title = "6. Cotton Candy (Bubblegum & Sky)",
        subtitle = "Playful Y2K bubblegum pink and breezy baby blue gradient.",
        bgColors = listOf(Color(0xFF0E1324), Color(0xFF060912)),
        bubbleColors = listOf(Color(0xFFF472B6), Color(0xFF38BDF8)),
        highlightColor = Color.White.copy(alpha = 0.65f),
        accentColor = Color(0xFF38BDF8),
    ),
    BubbleTheme(
        id = "solar_amber",
        title = "7. Solar Tangerine (Marigold & Amber)",
        subtitle = "Cheerful marigold gold transitioning into energetic warm orange.",
        bgColors = listOf(Color(0xFF1A1208), Color(0xFF0D0803)),
        bubbleColors = listOf(Color(0xFFFDE047), Color(0xFFFB923C)),
        highlightColor = Color(0xFFFEFCE8).copy(alpha = 0.70f),
        accentColor = Color(0xFFFBBF24),
    ),
)

/**
 * Puffy Bubble 'R' Color Variants Showcase
 */
@Preview(name = "Puffy Bubble Color Variants Showcase", showBackground = true, widthDp = 420, heightDp = 2250)
@Composable
fun PuffyBubblePaletteShowcasePreview() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0D1117),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Puffy Bubble 'R' Color Variants",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Explore 7 distinct color personalities for the Puffy Bubble mark",
                color = Color(0xFF8B949E),
                fontSize = 12.sp,
            )

            PuffyThemes.forEach { theme ->
                BubbleVariantCard(theme = theme)
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun BubbleVariantCard(theme: BubbleTheme) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = theme.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = theme.subtitle,
                color = Color(0xFF8B949E),
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Color Launcher
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PuffyBubbleRIcon(theme = theme, isMonochrome = false)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "App Launcher",
                        color = theme.accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // Black & White / Themed
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF21262D))
                            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PuffyBubbleRIcon(theme = theme, isMonochrome = true)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "B&W / Themed",
                        color = Color(0xFFC9D1D9),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

// =============================================================
// Individual Previews for High-Res Extraction
// =============================================================
@Preview(name = "1. Pastel Lilac - Color", widthDp = 108, heightDp = 108)
@Composable
fun PreviewPastelLilac() {
    PuffyBubbleRIcon(theme = PuffyThemes[0], isMonochrome = false)
}

@Preview(name = "2. Sunset Lo-Fi - Color", widthDp = 108, heightDp = 108)
@Composable
fun PreviewSunsetLofi() {
    PuffyBubbleRIcon(theme = PuffyThemes[1], isMonochrome = false)
}

@Preview(name = "3. Electric Aurora - Color", widthDp = 108, heightDp = 108)
@Composable
fun PreviewElectricAurora() {
    PuffyBubbleRIcon(theme = PuffyThemes[2], isMonochrome = false)
}

@Preview(name = "4. Cyber Neon - Color", widthDp = 108, heightDp = 108)
@Composable
fun PreviewCyberNeon() {
    PuffyBubbleRIcon(theme = PuffyThemes[3], isMonochrome = false)
}

@Preview(name = "5. Matcha Pistachio - Color", widthDp = 108, heightDp = 108)
@Composable
fun PreviewMatchaPistachio() {
    PuffyBubbleRIcon(theme = PuffyThemes[4], isMonochrome = false)
}

@Preview(name = "6. Cotton Candy - Color", widthDp = 108, heightDp = 108)
@Composable
fun PreviewCottonCandy() {
    PuffyBubbleRIcon(theme = PuffyThemes[5], isMonochrome = false)
}

@Preview(name = "7. Solar Tangerine - Color", widthDp = 108, heightDp = 108)
@Composable
fun PreviewSolarTangerine() {
    PuffyBubbleRIcon(theme = PuffyThemes[6], isMonochrome = false)
}

@Preview(name = "Monochrome / Themed Icon", widthDp = 108, heightDp = 108)
@Composable
fun PreviewMonochrome() {
    PuffyBubbleRIcon(theme = PuffyThemes[0], isMonochrome = true)
}

// =============================================================
// Core Puffy Bubble 'R' Component
// =============================================================
@Composable
fun PuffyBubbleRIcon(
    theme: BubbleTheme,
    modifier: Modifier = Modifier,
    isMonochrome: Boolean = false,
) {
    Canvas(modifier = modifier.size(108.dp)) {
        val w = size.width
        val h = size.height

        val bgBrush = if (isMonochrome) {
            Brush.verticalGradient(listOf(Color(0xFF21262D), Color(0xFF21262D)))
        } else {
            Brush.verticalGradient(theme.bgColors)
        }
        drawRect(brush = bgBrush)

        val bubbleBrush = if (isMonochrome) {
            Brush.verticalGradient(listOf(Color.White, Color.White))
        } else {
            Brush.linearGradient(
                colors = theme.bubbleColors,
                start = Offset(w * 0.2f, h * 0.15f),
                end = Offset(w * 0.8f, h * 0.85f),
            )
        }

        withTransform({
            rotate(4f, Offset(w * 0.5f, h * 0.5f))
        }) {
            // Chubby Bubble R Silhouette
            val outerBubble = Path().apply {
                // Top-left round corner
                moveTo(w * 0.28f, h * 0.18f)
                cubicTo(w * 0.38f, h * 0.14f, w * 0.68f, h * 0.14f, w * 0.78f, h * 0.26f)
                // Round right bowl
                cubicTo(w * 0.88f, h * 0.38f, w * 0.84f, h * 0.54f, w * 0.68f, h * 0.58f)
                // Waist & bouncy leg
                cubicTo(w * 0.74f, h * 0.64f, w * 0.82f, h * 0.72f, w * 0.80f, h * 0.82f)
                cubicTo(w * 0.78f, h * 0.88f, w * 0.68f, h * 0.88f, w * 0.60f, h * 0.80f)
                // Inward curve of leg
                cubicTo(w * 0.52f, h * 0.72f, w * 0.46f, h * 0.62f, w * 0.42f, h * 0.58f)
                lineTo(w * 0.42f, h * 0.76f)
                // Rounded bottom-left foot
                cubicTo(w * 0.42f, h * 0.86f, w * 0.22f, h * 0.86f, w * 0.22f, h * 0.76f)
                // Up vertical stem
                lineTo(w * 0.22f, h * 0.28f)
                cubicTo(w * 0.22f, h * 0.20f, w * 0.24f, h * 0.18f, w * 0.28f, h * 0.18f)
                close()
            }

            // Organic pebble-shaped counter hole
            val pebbleCounter = Path().apply {
                moveTo(w * 0.42f, h * 0.32f)
                cubicTo(w * 0.50f, h * 0.28f, w * 0.62f, h * 0.30f, w * 0.64f, h * 0.38f)
                cubicTo(w * 0.66f, h * 0.46f, w * 0.54f, h * 0.48f, w * 0.44f, h * 0.46f)
                cubicTo(w * 0.40f, h * 0.44f, w * 0.40f, h * 0.36f, w * 0.42f, h * 0.32f)
                close()
            }

            val fullBubble = Path().apply {
                fillType = PathFillType.EvenOdd
                addPath(outerBubble)
                addPath(pebbleCounter)
            }

            drawPath(fullBubble, brush = bubbleBrush)

            // Glossy inflatable shine highlights
            if (!isMonochrome) {
                // Top curve gloss
                val glossStroke = Stroke(width = w * 0.035f, cap = StrokeCap.Round)
                val topGloss = Path().apply {
                    moveTo(w * 0.32f, h * 0.20f)
                    cubicTo(w * 0.42f, h * 0.17f, w * 0.62f, h * 0.17f, w * 0.72f, h * 0.24f)
                }
                drawPath(topGloss, color = theme.highlightColor, style = glossStroke)

                // Stem vertical gloss
                drawLine(
                    color = theme.highlightColor.copy(alpha = theme.highlightColor.alpha * 0.8f),
                    start = Offset(w * 0.26f, h * 0.36f),
                    end = Offset(w * 0.26f, h * 0.68f),
                    strokeWidth = w * 0.03f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
