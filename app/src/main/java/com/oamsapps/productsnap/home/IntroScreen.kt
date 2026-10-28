package com.oamsapps.productsnap.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oamsapps.productsnap.R
import com.oamsapps.productsnap.ui.AccentButton
import com.oamsapps.productsnap.ui.theme.Studio
import kotlinx.coroutines.launch

private const val PAGES = 3

/**
 * Welcome walkthrough, shown once before "Where do you sell?" and again from Home ("How it works").
 * Says what the app is for, how a photo goes from camera to listing, and what makes it different.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { PAGES })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES - 1

    Column(Modifier.fillMaxSize().background(Studio.Paper).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = onDone) { Text("Skip", color = Studio.Muted) }
            else Spacer(Modifier.height(48.dp))
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            when (page) {
                0 -> PurposePage()
                1 -> HowPage()
                else -> WhyPage()
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.semantics { contentDescription = "Page ${pager.currentPage + 1} of $PAGES" },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(PAGES) { i ->
                    val on = i == pager.currentPage
                    Box(
                        Modifier
                            .height(8.dp)
                            .width(if (on) 24.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (on) Studio.Ink else Studio.Line)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            AccentButton(
                text = if (last) "Get started" else "Next",
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            )
        }
    }
}

@Composable
private fun PageText(title: String, body: String) {
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Studio.Ink)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Studio.Muted)
    }
}

/** 1. What it's for: a real before and after. */
@Composable
private fun PurposePage() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Studio.Stage),
            contentAlignment = Alignment.Center,
        ) {
            val cardH = (maxHeight * 0.72f).coerceAtMost(320.dp)
            val cardW = cardH * 0.78f
            Box(Modifier.size(cardW * 1.75f, cardH * 1.1f)) {
                PhotoCard(R.drawable.intro_before, "Before", Studio.StageRaised, Studio.StageText, cardW, cardH,
                    Modifier.align(Alignment.CenterStart).rotate(-5f))
                PhotoCard(R.drawable.intro_after, "After", Studio.Accent, Studio.Ink, cardW, cardH,
                    Modifier.align(Alignment.CenterEnd).offset(y = (-8).dp).rotate(3f))
            }
        }
        PageText(
            "Clean listing photos from your phone",
            "Shoot your item anywhere, even on the floor. ProductSnap removes the clutter, fixes the light and sizes the photo for where you sell.",
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun PhotoCard(
    res: Int,
    label: String,
    tagBg: Color,
    tagFg: Color,
    w: androidx.compose.ui.unit.Dp,
    h: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    Box(modifier.size(w, h).clip(RoundedCornerShape(18.dp)).background(Color.White)) {
        Image(painterResource(res), contentDescription = "$label photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = tagBg,
            contentColor = tagFg,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

/** 2. How to use it, in the order the app asks. */
@Composable
private fun HowPage() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Step(1, Icons.Default.PhotoCamera, "Pick a photo", "Tap One photo on Home, or Batch to do up to 30 at once.")
            Step(2, Icons.Default.AutoFixHigh, "Clean it up", "Remove the background, then use the Light, Background and Crop tabs if you want to adjust.")
            Step(3, Icons.Default.Share, "Save or share", "Save to your gallery, or share straight to your eBay, Vinted or Depop app.")
        }
        PageText("Three steps per photo", "Everything happens on one screen. After an edit, hold Compare to see the original.")
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Step(n: Int, icon: ImageVector, title: String, body: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = Studio.Card, border = BorderStroke(1.dp, Studio.Line), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Studio.Ink), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Studio.Paper, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("$n. $title", style = MaterialTheme.typography.titleMedium, color = Studio.Ink)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Studio.Muted)
            }
        }
    }
}

/** 3. What sets it apart. */
@Composable
private fun WhyPage() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Feature(Icons.Default.Adjust, "Honest about condition", "Tap stains or tears to circle them. Each one is also saved as a close-up photo.")
            Feature(Icons.Default.PhotoLibrary, "Many photos at once", "Up to 30 photos with the same look. Photos that need a second look are flagged.")
            Feature(Icons.Default.Lock, "Private", "Every edit happens on your phone. Your photos are never uploaded.")
        }
        PageText("Made for resellers", "Pick where you sell and every photo comes out the right shape. Change it anytime from Home, where the bulb has photo tips.")
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Studio.Accent), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Studio.Ink, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Studio.Ink)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Studio.Muted)
        }
    }
}
