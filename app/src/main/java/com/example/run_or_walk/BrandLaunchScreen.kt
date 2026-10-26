package com.example.run_or_walk

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.brand_launch_background))
    ) {
        Image(
            painter = painterResource(R.drawable.brand_launch_artwork),
            contentDescription = stringResource(R.string.brand_launch_description),
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentScale = ContentScale.Fit
        )
    }
}
