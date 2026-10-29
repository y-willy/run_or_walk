package com.example.run_or_walk

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

/** Shows the supplied artwork once on a fresh activity, independent of API or GPS requests. */
@Composable
fun BrandLaunchGate(showOnLaunch: Boolean, content: @Composable () -> Unit) {
    var launchFinished by rememberSaveable { mutableStateOf(false) }
    val showArtwork = showOnLaunch && !launchFinished

    LaunchedEffect(showArtwork) {
        if (showArtwork) {
            // Show the artwork for the first frame, then allow interaction immediately.
            withFrameNanos { }
            launchFinished = true
        }
    }

    if (showArtwork) BrandLaunchScreen() else content()
}

@Composable
private fun BrandLaunchScreen() {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.brand_launch_background))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        val artworkSize = minOf(maxWidth, (maxHeight - 76.dp).coerceAtLeast(0.dp), 320.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Image(
                painter = painterResource(R.drawable.brand_launch_artwork),
                contentDescription = stringResource(R.string.brand_launch_description),
                modifier = Modifier.size(artworkSize),
                contentScale = ContentScale.Fit
            )
            Text(stringResource(R.string.app_name),style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
        }
    }
}
