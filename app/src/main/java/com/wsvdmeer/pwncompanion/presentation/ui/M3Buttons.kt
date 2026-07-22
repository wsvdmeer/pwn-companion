package com.wsvdmeer.pwncompanion.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * M3-Compliant Button States - Implements Material Design 3 button guidelines.
 * Supports: Default, Loading, Success, Error, Disabled states with proper feedback.
 */

/**
 * Button State - Represents different M3 button states.
 */
enum class M3ButtonState {
    DEFAULT,    // Ready to interact
    LOADING,    // Processing (show spinner)
    SUCCESS,    // Action completed
    ERROR,      // Action failed
    DISABLED    // Not interactive
}

/**
 * Primary M3 Button with state support.
 * Follows Material Design 3 guidelines for button elevation, color, and feedback.
 */
@Composable
fun M3Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: M3ButtonState = M3ButtonState.DEFAULT,
    enabled: Boolean = state == M3ButtonState.DEFAULT,
    icon: @Composable (() -> Unit)? = null,
    fullWidth: Boolean = true
) {
    val buttonModifier = if (fullWidth) {
        modifier
            .fillMaxWidth()
            .height(48.dp)
    } else {
        modifier.height(48.dp)
    }

    Button(
        onClick = onClick,
        modifier = buttonModifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = ButtonDefaults.elevatedButtonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 8.dp,
            disabledElevation = 0.dp
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            when (state) {
                M3ButtonState.LOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Text(
                        "Processing...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                M3ButtonState.SUCCESS -> {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Success",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        "Done!",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                M3ButtonState.ERROR -> {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Error",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        "Failed",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                else -> {
                    icon?.invoke()
                    Text(
                        text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Secondary M3 Button - Less prominent alternative.
 */
@Composable
fun M3SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: M3ButtonState = M3ButtonState.DEFAULT,
    enabled: Boolean = state == M3ButtonState.DEFAULT,
    fullWidth: Boolean = true
) {
    val buttonModifier = if (fullWidth) {
        modifier
            .fillMaxWidth()
            .height(48.dp)
    } else {
        modifier.height(48.dp)
    }

    Button(
        onClick = onClick,
        modifier = buttonModifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

/**
 * Tertiary M3 Button - Least prominent action.
 */
@Composable
fun M3TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: M3ButtonState = M3ButtonState.DEFAULT,
    enabled: Boolean = state == M3ButtonState.DEFAULT,
    fullWidth: Boolean = true
) {
    val buttonModifier = if (fullWidth) {
        modifier
            .fillMaxWidth()
            .height(48.dp)
    } else {
        modifier.height(48.dp)
    }

    Button(
        onClick = onClick,
        modifier = buttonModifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

/**
 * Destructive M3 Button - For negative actions (delete, stop, etc).
 */
@Composable
fun M3DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: M3ButtonState = M3ButtonState.DEFAULT,
    enabled: Boolean = state == M3ButtonState.DEFAULT,
    fullWidth: Boolean = true
) {
    val buttonModifier = if (fullWidth) {
        modifier
            .fillMaxWidth()
            .height(48.dp)
    } else {
        modifier.height(48.dp)
    }

    Button(
        onClick = onClick,
        modifier = buttonModifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

