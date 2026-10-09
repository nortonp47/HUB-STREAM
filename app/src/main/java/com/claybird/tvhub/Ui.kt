package com.claybird.tvhub

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val HubGreen = Color(0xFF3DDC84)
val HubBackground = Color(0xFF10141C)
val HubSurface = Color(0xFF2A2F3A)
val HubMuted = Color(0xFFB0B8C8)
val HubRed = Color(0xFFE5484D)

@Composable
fun HubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HubGreen,
            background = HubBackground,
            surface = HubBackground,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}

/**
 * A button that is clearly highlighted when the remote's D-pad focuses it.
 * Pressing the center/OK button on the remote triggers [onClick].
 */
@Composable
fun FocusButton(
    label: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    textSize: TextUnit = 20.sp,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) HubGreen else HubSurface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused) Color.Black else Color.White,
            fontSize = textSize,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * A microphone icon button, drawn in code. Highlighted with a white border when
 * the remote's D-pad focuses it, and red while the app is listening.
 */
@Composable
fun MicButton(
    modifier: Modifier = Modifier,
    listening: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val background = when {
        listening -> HubRed
        focused -> HubGreen
        else -> HubSurface
    }
    val iconColor = if (focused && !listening) Color.Black else Color.White
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(background)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(32.dp)) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.09f
            val capW = w * 0.34f
            val capH = h * 0.52f
            // Microphone capsule
            drawRoundRect(
                color = iconColor,
                topLeft = Offset((w - capW) / 2f, h * 0.04f),
                size = Size(capW, capH),
                cornerRadius = CornerRadius(capW / 2f, capW / 2f),
            )
            // U-shaped holder around the capsule
            val arcW = w * 0.62f
            drawArc(
                color = iconColor,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset((w - arcW) / 2f, h * 0.28f),
                size = Size(arcW, h * 0.46f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Stem and base
            drawLine(
                color = iconColor,
                start = Offset(w / 2f, h * 0.74f),
                end = Offset(w / 2f, h * 0.92f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = iconColor,
                start = Offset(w * 0.32f, h * 0.94f),
                end = Offset(w * 0.68f, h * 0.94f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
