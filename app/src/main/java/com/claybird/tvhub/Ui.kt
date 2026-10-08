package com.claybird.tvhub

import androidx.compose.foundation.background
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
