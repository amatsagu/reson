package com.amatsagu.reson.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Linear progress bar with rounded ends and no thumb dot indicator.
 * Optimized with drawWithCache for 120 FPS hardware-accelerated rendering.
 */
@Composable
fun ResonProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    height: Dp = 4.dp,
) {
    Spacer(
        modifier = modifier
            .height(height)
            .drawWithCache {
                val clamped = progress.coerceIn(0f, 1f)
                val cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                onDrawBehind {
                    drawRoundRect(
                        color = trackColor,
                        size = size,
                        cornerRadius = cornerRadius
                    )
                    if (clamped > 0f) {
                        drawRoundRect(
                            color = color,
                            size = Size(size.width * clamped, size.height),
                            cornerRadius = cornerRadius
                        )
                    }
                }
            }
    )
}
