package com.oamsapps.productsnap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.oamsapps.productsnap.BuildConfig
import com.oamsapps.productsnap.ui.theme.Studio

/** The one main action on a screen: tangerine, ink text. */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Studio.Accent,
            contentColor = Studio.OnAccent,
            disabledContainerColor = Studio.Chip,
            disabledContentColor = Studio.Muted,
        ),
    ) { ButtonContent(text, icon) }
}

/** Second action next to [AccentButton]: ink, paper text. */
@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Studio.Ink, contentColor = Studio.Paper),
    ) { ButtonContent(text, icon) }
}

/** Quiet action on paper: white with a hairline. */
@Composable
fun PaperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Studio.Ink,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Studio.Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Studio.Card, contentColor = Studio.Ink),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = iconTint)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}

/** Square icon button on the dark stage. */
@Composable
fun StageIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    SquareIconButton(
        icon, description, onClick, enabled,
        container = Studio.StageRaised,
        content = if (enabled) Studio.StageText else Studio.StageMuted.copy(alpha = 0.5f),
    )
}

/** Square icon button on paper. */
@Composable
fun PaperIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    SquareIconButton(icon, description, onClick, enabled, container = Studio.Card, content = if (enabled) Studio.Ink else Studio.Muted)
}

@Composable
private fun SquareIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
    container: Color,
    content: Color,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(20.dp)) }
    }
}

/** Small status pill on the stage. */
@Composable
fun StagePill(text: String, dot: Color? = null) {
    Surface(shape = RoundedCornerShape(999.dp), color = Studio.StageRaised, contentColor = Color(0xFFCFCBC2)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dot != null) Surface(Modifier.size(7.dp), shape = RoundedCornerShape(999.dp), color = dot) {}
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Upper-case section label on paper. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Studio.Muted,
        modifier = modifier,
    )
}

/** The build ID, small, so screenshots name their build. */
@Composable
fun BuildTag(color: Color = Studio.Muted, modifier: Modifier = Modifier) {
    Text("Build ${BuildConfig.BUILD_ID}", style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}
