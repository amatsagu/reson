package com.amatsagu.reson.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amatsagu.reson.R

private val CottonCandyPink = Color(0xFFF472B6)
private val SkyCyan = Color(0xFF38BDF8)

/**
 * Startup loading overlay for initializing yt-dlp and checking engine updates.
 * Features an animated Cotton Candy Puffy Bubble logo with ambient glow and breathing motion.
 * Provides a prominent bypass button to continue offline immediately.
 */
@Composable
fun AppLoadingOverlay(
    visible: Boolean,
    statusText: String,
    onBypass: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "StartupLogoAnimation")

        // Gentle floating vertical offset
        val floatOffsetY by infiniteTransition.animateFloat(
            initialValue = -5f,
            targetValue = 5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "FloatOffset"
        )

        // Subtle breathing scale pulse
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "PulseScale"
        )

        // Soft halo glow intensity breathing
        val glowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "GlowAlpha"
        )

        // Smooth rotation for the ambient gradient ring
        val ringRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "RingRotation"
        )

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Interactive Animated Logo Showcase
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .offset { androidx.compose.ui.unit.IntOffset(0, floatOffsetY.dp.roundToPx()) }
                        .scale(pulseScale),
                    contentAlignment = Alignment.Center
                ) {
                    // 1. Ambient Radial Glow Aura
                    Box(
                        modifier = Modifier
                            .size(170.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        CottonCandyPink.copy(alpha = glowAlpha * 0.45f),
                                        SkyCyan.copy(alpha = glowAlpha * 0.30f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )

                    // 2. Rotating Decorative Accent Ring
                    Box(
                        modifier = Modifier
                            .size(142.dp)
                            .rotate(ringRotation)
                            .background(
                                brush = Brush.sweepGradient(
                                    listOf(
                                        CottonCandyPink.copy(alpha = 0.7f),
                                        Color.Transparent,
                                        SkyCyan.copy(alpha = 0.7f),
                                        Color.Transparent,
                                        CottonCandyPink.copy(alpha = 0.7f)
                                    )
                                ),
                                shape = CircleShape
                            )
                    )

                    // 3. Central Rounded Badge
                    Surface(
                        modifier = Modifier.size(126.dp),
                        shape = RoundedCornerShape(32.dp),
                        color = Color(0xFF0F1422),
                        border = BorderStroke(
                            1.5.dp,
                            Brush.linearGradient(
                                listOf(
                                    CottonCandyPink.copy(alpha = 0.7f),
                                    SkyCyan.copy(alpha = 0.7f)
                                )
                            )
                        ),
                        shadowElevation = 12.dp
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_reson_logo),
                                contentDescription = stringResource(R.string.app_name),
                                modifier = Modifier.size(92.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // App Brand Name with Cotton Candy gradient accent
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = 1.5.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Status feedback (pure text with subtle indicator, no button background/border)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = SkyCyan,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = statusText,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(44.dp))

                // Bypass / Continue offline button
                OutlinedButton(
                    onClick = onBypass,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(text = stringResource(R.string.action_skip_offline), fontSize = 13.sp)
                    Spacer(modifier = Modifier.size(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.action_skip),
                        modifier = Modifier.size(16.dp),
                        tint = SkyCyan
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E1324)
@Composable
private fun AppLoadingOverlayPreview() {
    MaterialTheme {
        AppLoadingOverlay(
            visible = true,
            statusText = "Checking for engine updates…",
            onBypass = {}
        )
    }
}
