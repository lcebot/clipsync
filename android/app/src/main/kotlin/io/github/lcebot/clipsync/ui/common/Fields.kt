package io.github.lcebot.clipsync.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The expressive tonal text field: a filled, fully rounded container a step darker than the row it
 * sits on, and no underline. An underline under a rounded shape reads as a leftover of the older
 * filled style; the container itself says where to type, and an error turns the container and the
 * label red instead.
 */
object TonalField {
    val Shape = RoundedCornerShape(16.dp)

    @Composable
    fun colors(): TextFieldColors {
        val c = MaterialTheme.colorScheme
        return TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceContainerHighest,
            unfocusedContainerColor = c.surfaceContainerHighest,
            disabledContainerColor = c.surfaceContainerHighest.copy(alpha = 0.38f),
            errorContainerColor = c.errorContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedTextColor = c.onSurface,
            unfocusedTextColor = c.onSurface,
            errorTextColor = c.onErrorContainer,
        )
    }
}
