package com.oamsapps.productsnap.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oamsapps.productsnap.image.Marketplace
import com.oamsapps.productsnap.ui.AccentButton
import com.oamsapps.productsnap.ui.theme.DisplayFont
import com.oamsapps.productsnap.ui.theme.Studio

/** What each choice means for the photos, in the seller's words. null = somewhere else. */
private fun spec(m: Marketplace?) = when (m) {
    Marketplace.EBAY -> "Square, white background, no watermark"
    Marketplace.VINTED -> "4:5 portrait, your real background"
    Marketplace.POSHMARK -> "Square, clean background"
    Marketplace.DEPOP -> "Square, clean background"
    Marketplace.ETSY -> "Landscape 4:3, 2000 px tall"
    null -> "Keep the photo's own shape"
}

private val Choices: List<Marketplace?> =
    listOf(Marketplace.EBAY, Marketplace.VINTED, Marketplace.POSHMARK, Marketplace.DEPOP, Marketplace.ETSY, null)

/** "Where do you sell?", asked once. Every photo is then sized and framed for the first pick. */
@Composable
fun OnboardingScreen(initial: List<Marketplace?>, onDone: (List<Marketplace?>) -> Unit, onSkip: () -> Unit) {
    val picked = remember { mutableStateListOf<Marketplace?>().apply { addAll(initial) } }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onSkip) { Text("Skip", color = Studio.Muted) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Where do you sell?", style = MaterialTheme.typography.displaySmall)
            Text(
                "Every photo comes out sized and framed for it. Pick all that apply; switch any time.",
                style = MaterialTheme.typography.bodyLarge,
                color = Studio.Muted,
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Choices.forEach { m ->
                val on = m in picked
                MarketRow(
                    name = m?.label ?: "Somewhere else",
                    initial = m?.label?.take(1) ?: "+",
                    spec = spec(m),
                    selected = on,
                    onToggle = { if (on) picked.remove(m) else picked.add(m) },
                )
            }
        }
        AccentButton(
            text = "Continue",
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            enabled = picked.isNotEmpty(),
            onClick = { onDone(Choices.filter { it in picked }) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MarketRow(name: String, initial: String, spec: String, selected: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Studio.Card,
        border = BorderStroke(2.dp, if (selected) Studio.Ink else Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(Modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = if (selected) Studio.Ink else Studio.Chip) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        initial,
                        fontFamily = DisplayFont,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (selected) Studio.Paper else Studio.Ink,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(spec, style = MaterialTheme.typography.bodySmall, color = Studio.Muted)
            }
            Surface(
                Modifier.size(28.dp),
                shape = RoundedCornerShape(999.dp),
                color = if (selected) Studio.Accent else Color.Transparent,
                border = if (selected) null else BorderStroke(2.dp, Studio.Line),
            ) {
                if (selected) Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Studio.Ink) }
            }
        }
    }
}
