package com.amatsagu.reson.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Custom clean progress bar that removes the weird Material 3 thumb dot at the end of the bar.
 * Uses a smooth clipped background container with no dot or indicator artifact.
 */
@Composable
fun ResonProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    height: Dp = 4.dp
) {
    val cornerRadius = height / 2
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(trackColor)
    ) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        if (clampedProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = clampedProgress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(color)
            )
        }
    }
}
