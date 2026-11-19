package com.amatsagu.reson.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * Section header that smartly displays the full title (e.g. "Recommended content", "Continue listening")
 * and dynamically shortens to the short title (e.g. "Recommended", "Continue") when horizontal space is constrained.
 */
@Composable
fun AdaptiveSectionHeader(
    fullTitle: String,
    shortTitle: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    ),
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val availableWidthPx = with(density) { maxWidth.toPx() }
        val measuredFullWidthPx = textMeasurer.measure(
            text = fullTitle,
            style = textStyle
        ).size.width

        val displayText = if (measuredFullWidthPx <= availableWidthPx) {
            fullTitle
        } else {
            shortTitle
        }

        Text(
            text = displayText,
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
